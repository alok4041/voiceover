package com.squadvoice.app

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ParticipantUi(
    val identity: String,
    val name: String,
    val isLocal: Boolean,
    val isSpeaking: Boolean,
)

data class VoiceSessionState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val micEnabled: Boolean = false,
    val speakerOutput: Boolean = false,
    val participants: List<ParticipantUi> = emptyList(),
    val errorMessage: String? = null,
)

/** Keeps the LiveKit room alive independently of the screen while the foreground service runs. */
object VoiceSessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = mutableState.asStateFlow()

    private var room: Room? = null
    private var eventsJob: Job? = null
    private var localName: String = ""
    private var localIdentity: String = ""
    private var activeSpeakerIds: Set<String> = emptySet()

    fun connect(context: Context, credentials: RoomCredentials, participantName: String) {
        scope.launch {
            disconnectNow()
            mutableState.value = VoiceSessionState(isConnecting = true)
            localName = participantName

            val newRoom = LiveKit.create(context.applicationContext)
            room = newRoom
            eventsJob = scope.launch {
                newRoom.events.collect { event ->
                    when (event) {
                        is RoomEvent.ActiveSpeakersChanged -> {
                            activeSpeakerIds = event.speakers.map { it.identity.value }.toSet()
                            publishParticipants(newRoom)
                        }

                        is RoomEvent.ParticipantConnected,
                        is RoomEvent.ParticipantDisconnected,
                        is RoomEvent.ParticipantNameChanged -> publishParticipants(newRoom)

                        else -> Unit
                    }
                }
            }

            try {
                newRoom.connect(credentials.serverUrl, credentials.participantToken)
                localIdentity = newRoom.localParticipant.identity.value
                newRoom.localParticipant.setMicrophoneEnabled(true)
                mutableState.update {
                    it.copy(isConnecting = false, isConnected = true, micEnabled = true, errorMessage = null)
                }
                publishParticipants(newRoom)
            } catch (error: Throwable) {
                mutableState.value = VoiceSessionState(
                    errorMessage = error.message?.takeIf(String::isNotBlank)
                        ?: "Couldn't connect. Check the server and try again.",
                )
                eventsJob?.cancel()
                eventsJob = null
                room = null
                runCatching { newRoom.disconnect() }
            }
        }
    }

    fun toggleMicrophone() = setMicrophoneEnabled(!mutableState.value.micEnabled)

    fun setMicrophoneEnabled(enabled: Boolean) {
        scope.launch {
            val currentRoom = room ?: return@launch
            runCatching { currentRoom.localParticipant.setMicrophoneEnabled(enabled) }
                .onSuccess {
                    mutableState.update { it.copy(micEnabled = enabled, errorMessage = null) }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(errorMessage = error.message ?: "Couldn't change microphone state.")
                    }
                }
        }
    }

    /** Force speaker output, or return audio routing to the SDK's automatic headset-first choice. */
    fun setSpeakerOutput(enabled: Boolean) {
        val audioSwitch = room?.audioSwitchHandler
        if (audioSwitch == null) {
            mutableState.update { it.copy(errorMessage = "Audio routing is not ready yet.") }
            return
        }

        if (enabled) {
            val speaker = audioSwitch.availableAudioDevices.firstOrNull {
                it.javaClass.simpleName.contains("Speaker", ignoreCase = true)
            }
            if (speaker == null) {
                mutableState.update { it.copy(errorMessage = "Speaker output is not available right now.") }
                return
            }
            audioSwitch.selectDevice(speaker)
        } else {
            audioSwitch.selectDevice(null)
        }

        mutableState.update { it.copy(speakerOutput = enabled, errorMessage = null) }
    }

    fun endSession() {
        scope.launch { disconnectNow() }
    }

    private suspend fun disconnectNow() {
        eventsJob?.cancel()
        eventsJob = null
        val oldRoom = room
        room = null
        activeSpeakerIds = emptySet()
        localIdentity = ""
        runCatching { oldRoom?.disconnect() }
        mutableState.value = VoiceSessionState()
    }

    private fun publishParticipants(currentRoom: Room) {
        val local = currentRoom.localParticipant
        localIdentity = local.identity.value
        val currentParticipants = buildList {
            add(
                ParticipantUi(
                    identity = localIdentity,
                    name = localName,
                    isLocal = true,
                    isSpeaking = localIdentity in activeSpeakerIds,
                ),
            )
            currentRoom.remoteParticipants.values.forEach { participant ->
                add(participant.toUi(activeSpeakerIds))
            }
        }
        mutableState.update { it.copy(participants = currentParticipants) }
    }

    private fun Participant.toUi(speakers: Set<String>) = ParticipantUi(
        identity = identity.value,
        name = name?.takeIf(String::isNotBlank) ?: identity.value,
        isLocal = false,
        isSpeaking = identity.value in speakers,
    )
}
