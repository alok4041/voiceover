package com.squadvoice.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.audio.krisp.KrispAudioProcessor
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val microphoneEnabled: Boolean,
    val volumePercent: Int = 100,
)

data class VoiceSessionState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val micEnabled: Boolean = false,
    val participants: List<ParticipantUi> = emptyList(),
    val errorMessage: String? = null,
    val noiseCancellationActive: Boolean = false,
    val noiseCancellationMessage: String? = null,
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
    private val participantVolumePercent = mutableMapOf<String, Int>()
    private val noiseProcessorMutex = Mutex()
    private val noiseSettingsMutex = Mutex()
    private var krispProcessor: KrispAudioProcessor? = null

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
                            publishParticipants(newRoom)
                        }

                        is RoomEvent.Disconnected -> {
                            mutableState.update {
                                it.copy(
                                    isConnecting = false,
                                    isConnected = false,
                                    micEnabled = false,
                                    participants = emptyList(),
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

                        is RoomEvent.TrackMuted -> {
                            if (event.publication.kind == io.livekit.android.room.track.Track.Kind.AUDIO) {
                                publishParticipants(newRoom)
                            }
                        }

                        is RoomEvent.TrackUnmuted -> {
                            if (event.publication.kind == io.livekit.android.room.track.Track.Kind.AUDIO) {
                                publishParticipants(newRoom)
                            }
                        }

                        is RoomEvent.TrackSubscribed -> {
                            val identity = event.participant.identity?.value
                            val audioTrack = event.track as? RemoteAudioTrack
                            if (identity != null && audioTrack != null) {
                                val percent = participantVolumePercent[identity] ?: DEFAULT_REMOTE_VOLUME_PERCENT
                                audioTrack.setVolume(percent / 100.0)
                            }
                        }

                        else -> Unit
                    }
                }
            }

            try {
                newRoom.connect(credentials.serverUrl, credentials.participantToken)
                var noiseCancellationMessage: String? = null
                var appliedNoiseMode = MicNoiseMode.NORMAL
                noiseSettingsMutex.withLock {
                    val requestedNoiseMode = MicNoiseMode.fromPreferences(
                        context.getSharedPreferences("squadvoice", Context.MODE_PRIVATE),
                    )
                    runCatching { applyMicNoiseMode(context, newRoom, requestedNoiseMode) }
                        .onSuccess { appliedNoiseMode = requestedNoiseMode }
                        .onFailure { error ->
                            noiseCancellationMessage = noiseCancellationUnavailableMessage(requestedNoiseMode, error)
                            runCatching { applyMicNoiseMode(context, newRoom, MicNoiseMode.NORMAL) }
                        }
                }
                localIdentity = newRoom.localParticipant.identity?.value.orEmpty()
                val microphoneStarted = newRoom.localParticipant.setMicrophoneEnabled(true)
                if (!microphoneStarted) {
                    throw IllegalStateException("Microphone could not start. Check app microphone access and try again.")
                }
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        micEnabled = true,
                        errorMessage = null,
                        noiseCancellationActive = appliedNoiseMode != MicNoiseMode.NORMAL,
                        noiseCancellationMessage = noiseCancellationMessage,
                    )
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
                        publishParticipants(currentRoom)
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

    /** Applies the selected filter to this device's outgoing microphone without reconnecting the room. */
    internal fun setMicNoiseMode(context: Context, mode: MicNoiseMode) {
        scope.launch {
            noiseSettingsMutex.withLock {
                val currentRoom = room
                if (currentRoom == null) {
                    mutableState.update {
                        it.copy(noiseCancellationActive = false, noiseCancellationMessage = null)
                    }
                    return@withLock
                }

                runCatching { applyMicNoiseMode(context, currentRoom, mode) }
                    .onSuccess {
                        mutableState.update {
                            it.copy(
                                noiseCancellationActive = mode != MicNoiseMode.NORMAL,
                                noiseCancellationMessage = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        mutableState.update {
                            it.copy(
                                noiseCancellationActive = false,
                                noiseCancellationMessage = noiseCancellationUnavailableMessage(mode, error),
                            )
                        }
                        runCatching { applyMicNoiseMode(context, currentRoom, MicNoiseMode.NORMAL) }
                    }
            }
        }
    }

    private suspend fun applyMicNoiseMode(context: Context, currentRoom: Room, mode: MicNoiseMode) {
        val controller = currentRoom.audioProcessingController
        when (mode) {
            MicNoiseMode.NORMAL -> {
                controller.bypassCapturePostProcessing = true
                controller.capturePostProcessor = null
            }

            MicNoiseMode.KRISP -> {
                val processor = noiseProcessorMutex.withLock {
                    krispProcessor ?: withContext(Dispatchers.IO) {
                        KrispAudioProcessor.getInstance(context.applicationContext).also { candidate ->
                            check(candidate.init()) { "Krisp could not initialize on this device." }
                        }
                    }.also { krispProcessor = it }
                }
                controller.capturePostProcessor = processor
                controller.bypassCapturePostProcessing = false
            }
        }
    }

    private fun noiseCancellationUnavailableMessage(mode: MicNoiseMode, error: Throwable): String {
        val detail = error.message?.takeIf(String::isNotBlank)
        return if (detail == null) {
            "${mode.title} couldn't start. Normal microphone processing is still on."
        } else {
            "${mode.title} couldn't start. Normal microphone processing is still on. $detail"
        }
    }

    fun setParticipantVolume(identity: String, volumePercent: Int) {
        val percent = volumePercent.coerceIn(MIN_REMOTE_VOLUME_PERCENT, MAX_REMOTE_VOLUME_PERCENT)
        participantVolumePercent[identity] = percent
        val gain = percent / 100.0
        room?.remoteParticipants?.values
            ?.firstOrNull { it.identity?.value == identity }
            ?.let { participant -> applyParticipantVolume(participant, gain) }
        mutableState.update { current ->
            current.copy(
                participants = current.participants.map { participant ->
                    if (participant.identity == identity) participant.copy(volumePercent = percent) else participant
                },
            )
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
        participantVolumePercent.clear()
        runCatching { oldRoom?.disconnect() }
        mutableState.value = VoiceSessionState()
    }

    private fun applyParticipantVolume(participant: RemoteParticipant, gain: Double) {
        participant.audioTrackPublications.forEach { (_, track) ->
            if (track is RemoteAudioTrack) track.setVolume(gain)
        }
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
                        microphoneEnabled = local.isMicrophoneEnabled,
                    ),
                )
            }
            currentRoom.remoteParticipants.values.forEach { participant ->
                participant.toUi(activeSpeakerIds)?.let(::add)
            }
        }
        // Keep the room-level mic control and local participant badge on the
        // same source of truth. Reconnect and track events can arrive in either
        // order, so updating only the participant list can leave stale UI state.
        mutableState.update {
            it.copy(
                micEnabled = local.isMicrophoneEnabled,
                participants = currentParticipants,
            )
        }
    }

    private fun Participant.toUi(speakers: Set<String>): ParticipantUi? {
        val participantIdentity = identity?.value ?: return null
        return ParticipantUi(
            identity = participantIdentity,
            name = name?.takeIf(String::isNotBlank) ?: participantIdentity,
            isLocal = false,
            isSpeaking = participantIdentity in speakers,
            microphoneEnabled = isMicrophoneEnabled,
            volumePercent = participantVolumePercent[participantIdentity] ?: DEFAULT_REMOTE_VOLUME_PERCENT,
        )
    }

    private const val DEFAULT_REMOTE_VOLUME_PERCENT = 100
    private const val MIN_REMOTE_VOLUME_PERCENT = 0
    private const val MAX_REMOTE_VOLUME_PERCENT = 200
}
