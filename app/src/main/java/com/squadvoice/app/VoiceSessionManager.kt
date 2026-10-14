package com.squadvoice.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
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

            // Play received SquadVoice speech through the same media volume as the game.
            // Do not request call audio focus or force call routing while game audio is active.
            val gameMixAudio = AudioType.CustomAudioType(
                audioMode = AudioManager.MODE_NORMAL,
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                audioStreamType = AudioManager.STREAM_MUSIC,
            )
            val newRoom = LiveKit.create(
                appContext = context.applicationContext,
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(
                        audioOutputType = gameMixAudio,
                        audioHandler = NoAudioHandler(),
                    ),
                ),
            )
            room = newRoom
            eventsJob = scope.launch {
                newRoom.events.events.collect { event ->
                    when (event) {
                        is RoomEvent.Reconnecting -> {
                            mutableState.update {
                                it.copy(isConnecting = true, isConnected = false, errorMessage = "Reconnecting to the room…")
                            }
                        }

                        is RoomEvent.Reconnected -> {
                            mutableState.update {
                                it.copy(
                                    isConnecting = false,
                                    isConnected = true,
                                    micEnabled = newRoom.localParticipant.isMicrophoneEnabled,
                                    errorMessage = null,
                                )
                            }
                        }

                        is RoomEvent.Disconnected -> {
                            mutableState.update {
                                it.copy(
                                    isConnecting = false,
                                    isConnected = false,
                                    micEnabled = false,
                                    errorMessage = event.error?.message ?: "Disconnected from the voice room.",
                                )
                            }
                        }

                        is RoomEvent.FailedToConnect -> {
                            mutableState.update {
                                it.copy(
                                    isConnecting = false,
                                    isConnected = false,
                                    micEnabled = false,
                                    errorMessage = event.error.message ?: "Couldn't connect to the voice room.",
                                )
                            }
                        }

                        is RoomEvent.ActiveSpeakersChanged -> {
                            activeSpeakerIds = event.speakers.mapNotNull { it.identity?.value }.toSet()
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
                localIdentity = newRoom.localParticipant.identity?.value.orEmpty()
                val microphoneStarted = newRoom.localParticipant.setMicrophoneEnabled(true)
                if (!microphoneStarted) {
                    throw IllegalStateException("Microphone could not start. Check app microphone access and try again.")
                }
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
                .onSuccess { changed ->
                    if (changed) {
                        mutableState.update { it.copy(micEnabled = enabled, errorMessage = null) }
                    } else {
                        mutableState.update { it.copy(errorMessage = "Microphone state did not change. Check microphone access.") }
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(errorMessage = error.message ?: "Couldn't change microphone state.")
                    }
                }
        }
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
        localIdentity = local.identity?.value.orEmpty()
        val currentParticipants = buildList {
            if (localIdentity.isNotEmpty()) {
                add(
                    ParticipantUi(
                        identity = localIdentity,
                        name = localName,
                        isLocal = true,
                        isSpeaking = localIdentity in activeSpeakerIds,
                    ),
                )
            }
            currentRoom.remoteParticipants.values.forEach { participant ->
                participant.toUi(activeSpeakerIds)?.let(::add)
            }
        }
        mutableState.update { it.copy(participants = currentParticipants) }
    }

    private fun Participant.toUi(speakers: Set<String>): ParticipantUi? {
        val participantIdentity = identity?.value ?: return null
        return ParticipantUi(
            identity = participantIdentity,
            name = name?.takeIf(String::isNotBlank) ?: participantIdentity,
            isLocal = false,
            isSpeaking = participantIdentity in speakers,
        )
    }
}
