package com.squadvoice.app.game

import android.content.res.Configuration
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.runtime.withFrameNanos
import com.squadvoice.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class GameControlState(
    val move: Float = 0f,
    val steer: Float = 0f,
    val bracing: Boolean = false,
    val jumpSequence: Long = 0,
)

@Composable
internal fun RopeboundGameScreen(
    roomCode: String,
    participantToken: String,
    microphoneEnabled: Boolean,
    onToggleMicrophone: () -> Unit,
    onBack: () -> Unit,
    previewMode: Boolean = false,
) {
    val api = remember { RopeboundApi() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val hudPreferences = remember(context) { context.getSharedPreferences("ropebound_hud", android.content.Context.MODE_PRIVATE) }
    val gameAudio = remember(context) { RopeboundGameAudio(context) }
    var soundEnabled by remember(gameAudio) { mutableStateOf(gameAudio.isEnabled()) }
    var ambienceVolume by remember(gameAudio) { mutableStateOf(gameAudio.ambienceVolume()) }
    var sfxVolume by remember(gameAudio) { mutableStateOf(gameAudio.sfxVolume()) }
    var gameSettingsOpen by remember { mutableStateOf(false) }
    var hudOpacity by remember { mutableStateOf(hudPreferences.getFloat("opacity", .88f).coerceIn(.35f, 1f)) }
    var hudEditMode by remember { mutableStateOf(false) }
    var joystickPosition by remember {
        mutableStateOf(Offset(hudPreferences.getFloat("stick_x", .025f), hudPreferences.getFloat("stick_y", .67f)))
    }
    var jumpPosition by remember {
        mutableStateOf(Offset(hudPreferences.getFloat("jump_x", .78f), hudPreferences.getFloat("jump_y", .78f)))
    }
    var bracePosition by remember {
        mutableStateOf(Offset(hudPreferences.getFloat("brace_x", .89f), hudPreferences.getFloat("brace_y", .78f)))
    }
    var hasInitializedGameEffect by remember(roomCode) { mutableStateOf(false) }
    var observedGameEffectId by remember(roomCode) { mutableStateOf<Long?>(null) }
    var hasInitializedNotice by remember(roomCode) { mutableStateOf(false) }
    var hasObservedGamePhase by remember(roomCode) { mutableStateOf(false) }
    var hasInitializedCheckpoints by remember(roomCode) { mutableStateOf(false) }
    var observedCheckpointA by remember(roomCode) { mutableStateOf<Float?>(null) }
    var observedCheckpointB by remember(roomCode) { mutableStateOf<Float?>(null) }
    var ropeTensionWasHigh by remember(roomCode) { mutableStateOf(false) }
    val identity = remember(participantToken, previewMode) {
        if (previewMode) "preview1" else participantIdentity(participantToken)
    }
    var snapshot by remember(roomCode, previewMode) {
        mutableStateOf(if (previewMode) localPreviewLobbySnapshot() else null)
    }
    val measuredPingMs = remember(roomCode, participantToken) { AtomicReference<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectAttempt by remember(roomCode) { mutableStateOf(0) }
    val controls = remember(roomCode, participantToken) { MutableStateFlow(GameControlState()) }
    val inputTrigger = remember(roomCode, participantToken) { Channel<Unit>(Channel.CONFLATED) }

    DisposableEffect(gameAudio) {
        onDispose { gameAudio.release() }
    }

    LaunchedEffect(hudOpacity, joystickPosition, jumpPosition, bracePosition) {
        hudPreferences.edit()
            .putFloat("opacity", hudOpacity)
            .putFloat("stick_x", joystickPosition.x)
            .putFloat("stick_y", joystickPosition.y)
            .putFloat("jump_x", jumpPosition.x)
            .putFloat("jump_y", jumpPosition.y)
            .putFloat("brace_x", bracePosition.x)
            .putFloat("brace_y", bracePosition.y)
            .apply()
    }

    LaunchedEffect(snapshot?.phase, soundEnabled) {
        if ((snapshot?.phase == "lobby" || snapshot?.phase == "race") && soundEnabled) gameAudio.startAmbience()
        else gameAudio.stopAmbience()
    }

    LaunchedEffect(snapshot?.phase) {
        val phase = snapshot?.phase ?: return@LaunchedEffect
        if (!hasObservedGamePhase) {
            hasObservedGamePhase = true
            return@LaunchedEffect
        }
        when {
            phase == "race" -> gameAudio.play(RopeboundGameAudio.Cue.MATCH_START)
            phase == "results" && snapshot?.winner != null -> gameAudio.play(RopeboundGameAudio.Cue.VICTORY)
        }
    }

    LaunchedEffect(snapshot?.checkpointA, snapshot?.checkpointB, snapshot?.phase) {
        val current = snapshot ?: return@LaunchedEffect
        val previousA = observedCheckpointA
        val previousB = observedCheckpointB
        if (!hasInitializedCheckpoints) {
            hasInitializedCheckpoints = true
        } else if (
            current.phase == "race" &&
            ((previousA != null && current.checkpointA > previousA + .005f) ||
                (previousB != null && current.checkpointB > previousB + .005f))
        ) {
            gameAudio.play(RopeboundGameAudio.Cue.CHECKPOINT)
        }
        observedCheckpointA = current.checkpointA
        observedCheckpointB = current.checkpointB
    }

    LaunchedEffect(snapshot?.effect?.id) {
        val effect = snapshot?.effect ?: return@LaunchedEffect
        if (!hasInitializedGameEffect) {
            hasInitializedGameEffect = true
            observedGameEffectId = effect.id
            return@LaunchedEffect
        }
        if (effect.id == observedGameEffectId) return@LaunchedEffect
        observedGameEffectId = effect.id
        when (effect.type) {
            "water-splash" -> gameAudio.play(RopeboundGameAudio.Cue.WATER)
            "bridge-break" -> gameAudio.play(RopeboundGameAudio.Cue.BRIDGE_BREAK)
            "lava-burst" -> gameAudio.play(RopeboundGameAudio.Cue.LAVA)
            "team-surge" -> gameAudio.play(RopeboundGameAudio.Cue.SQUAD_SURGE)
            "team-respawn" -> gameAudio.play(RopeboundGameAudio.Cue.CHECKPOINT)
        }
    }

    LaunchedEffect(snapshot?.notice, snapshot?.phase) {
        val current = snapshot ?: return@LaunchedEffect
        if (!hasInitializedNotice) {
            hasInitializedNotice = true
            return@LaunchedEffect
        }
        if (current.phase == "race" && current.notice.contains("bumped the stone", ignoreCase = true)) {
            gameAudio.play(RopeboundGameAudio.Cue.BOULDER)
        }
    }

    var localJumpStartedAt by remember(roomCode, participantToken) { mutableLongStateOf(0L) }

    LaunchedEffect(roomCode, participantToken, reconnectAttempt) {
        if (previewMode) return@LaunchedEffect
        while (true) {
            try {
                snapshot = api.join(roomCode, participantToken)
                error = null
                api.events(roomCode, participantToken).collect { event ->
                    when (event) {
                        is RopeboundRealtimeEvent.State -> {
                            val incoming = event.snapshot
                            val currentLocal = snapshot
                            // Reconcile incoming authoritative server snapshot with local 60 FPS Client-Side Prediction
                            // so ~80-100ms network RTT never pulls the local player backward or cancels an active jump.
                            snapshot = if (currentLocal != null && incoming.phase == "race" && !incoming.viewerSpectating) {
                                val now = SystemClock.elapsedRealtime()
                                val localJumpAge = if (localJumpStartedAt == 0L) Float.POSITIVE_INFINITY else (now - localJumpStartedAt) / 1_000f
                                val inLocalJump = localJumpAge in 0f..1.25f
                                val activeInput = controls.value
                                val isMovingLocally = abs(activeInput.move) > 0.06f || abs(activeInput.steer) > 0.06f || inLocalJump
                                fun reconcileTeam(serverTeam: RopeboundTeam, localTeam: RopeboundTeam): RopeboundTeam {
                                    val predPlayer = localTeam.players.firstOrNull { it.id == identity } ?: return serverTeam
                                    val reconciledPlayers = serverTeam.players.map { srvPlayer ->
                                        if (srvPlayer.id != identity) srvPlayer else {
                                            val progressDiff = srvPlayer.progress - predPlayer.progress
                                            val latDiff = srvPlayer.lateralOffset - predPlayer.lateralOffset
                                            // Hard snap only if server triggered a checkpoint respawn / rescue (> 4.5m jump)
                                            val hardTeleport = abs(progressDiff) > 0.045f
                                            val blendedProgress = when {
                                                hardTeleport -> srvPlayer.progress
                                                isMovingLocally -> (predPlayer.progress + progressDiff * 0.22f).coerceIn(0f, 1f)
                                                else -> (predPlayer.progress + progressDiff * 0.45f).coerceIn(0f, 1f)
                                            }
                                            val blendedLat = if (hardTeleport) srvPlayer.lateralOffset else {
                                                (predPlayer.lateralOffset + latDiff * 0.28f).coerceIn(-1.25f, 1.25f)
                                            }
                                            val localJumpH = if (inLocalJump && !hardTeleport) {
                                                (16f * localJumpAge - 12.5f * localJumpAge * localJumpAge).coerceAtLeast(0f)
                                            } else srvPlayer.height
                                            srvPlayer.copy(
                                                progress = blendedProgress,
                                                lateralOffset = blendedLat,
                                                height = maxOf(localJumpH, srvPlayer.height),
                                                grounded = if (inLocalJump && !hardTeleport) false else srvPlayer.grounded,
                                                jumping = if (inLocalJump && !hardTeleport) true else srvPlayer.jumping,
                                                bracing = activeInput.bracing,
                                            )
                                        }
                                    }
                                    return serverTeam.copy(players = reconciledPlayers)
                                }
                                if (incoming.viewerTeam == "B") {
                                    incoming.copy(teamB = reconcileTeam(incoming.teamB, currentLocal.teamB))
                                } else {
                                    incoming.copy(teamA = reconcileTeam(incoming.teamA, currentLocal.teamA))
                                }
                            } else {
                                incoming
                            }
                            error = null
                        }
                        is RopeboundRealtimeEvent.Ping -> {
                            measuredPingMs.updateAndGet { previous ->
                                previous?.let { (it * .75f + event.roundTripMs * .25f).roundToInt() }
                                    ?: event.roundTripMs
                            }
                        }
                        is RopeboundRealtimeEvent.ServerError -> error = event.message
                    }
                }
                error = "The game update connection closed. Reconnecting…"
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Couldn't connect to the Ropebound lobby."
            }
            delay(1_500)
        }
    }

    LaunchedEffect(roomCode, participantToken, snapshot?.phase) {
        if (previewMode) return@LaunchedEffect
        if (snapshot?.phase != "lobby" && snapshot?.phase != "race") return@LaunchedEffect
        while (true) {
            val sent = api.heartbeat(measuredPingMs.get())
            if (!sent) {
                runCatching { api.heartbeat(roomCode, participantToken, measuredPingMs.get()) }
            }
            delay(1_000)
        }
    }

    fun action(block: suspend () -> RopeboundSnapshot) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                snapshot = block()
                error = null
            } catch (failure: Throwable) {
                error = failure.message ?: "That game action couldn't be completed."
            } finally {
                busy = false
            }
        }
    }

    fun setStick(move: Float, steer: Float) {
        controls.update { it.copy(move = move.coerceIn(-1f, 1f), steer = steer.coerceIn(-1f, 1f)) }
        inputTrigger.trySend(Unit)
        if (abs(controls.value.move) > .12f || abs(controls.value.steer) > .12f) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
    }

    fun setBrace(pressed: Boolean) {
        controls.update { it.copy(bracing = pressed) }
        inputTrigger.trySend(Unit)
    }

    fun openGameSettings() {
        controls.value = GameControlState()
        inputTrigger.trySend(Unit)
        gameAudio.stopFootsteps()
        gameSettingsOpen = true
    }

    var jumpRequestBlockedUntil by remember(roomCode, participantToken) { mutableLongStateOf(0L) }
    fun jump() {
        val current = snapshot ?: return
        if (current.phase != "race" || current.viewerSpectating) return
        val ownTeam = if (current.viewerTeam == "A") current.teamA else current.teamB
        val ownPlayer = ownTeam.players.firstOrNull { it.id == identity } ?: return
        val now = SystemClock.elapsedRealtime()
        if (!ownPlayer.grounded || now < jumpRequestBlockedUntil) return
        // Start local jump prediction on Frame 0 (0ms delay) and block double-taps during the ~1.25s jump arc.
        jumpRequestBlockedUntil = now + 1_250L
        localJumpStartedAt = now
        controls.update { it.copy(jumpSequence = it.jumpSequence + 1) }
        inputTrigger.trySend(Unit)
        gameAudio.play(RopeboundGameAudio.Cue.JUMP)
    }

    LaunchedEffect(snapshot?.phase, snapshot?.viewerSpectating) {
        if (snapshot?.phase != "race" || snapshot?.viewerSpectating == true) {
            controls.value = GameControlState()
            inputTrigger.trySend(Unit)
            gameAudio.stopFootsteps()
        }
    }

    // 60 FPS Client-Side Prediction loop for Live Multiplayer: advances local player immediately on input
    // so movement, steering, and jumping respond in 0ms regardless of server ping.
    LaunchedEffect(roomCode, participantToken, previewMode, snapshot?.phase, snapshot?.viewerSpectating) {
        if (previewMode || snapshot?.phase != "race" || snapshot?.viewerSpectating == true) return@LaunchedEffect
        var prevAt = SystemClock.elapsedRealtime()
        var predForwardVel = 0f
        var predLateralVel = 0f
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - prevAt) / 1_000f).coerceIn(0.005f, 0.04f)
            prevAt = now
            val input = controls.value
            val jumpAge = if (localJumpStartedAt == 0L) Float.POSITIVE_INFINITY else (now - localJumpStartedAt) / 1_000f
            val jumping = jumpAge in 0f..1.28f
            val jumpH = if (jumping) (16f * jumpAge - 12.5f * jumpAge * jumpAge).coerceAtLeast(0f) else 0f

            val currentSnap = snapshot
            if (currentSnap != null && currentSnap.phase == "race") {
                val team = if (currentSnap.viewerTeam == "B") currentSnap.teamB else currentSnap.teamA
                val surgeMult = if (team.surgeActive && input.move > 0.9f) 1.2f else 1f
                val accelScale = (if (input.bracing) 0.55f else 1f) * surgeMult
                predForwardVel += input.move * 30f * accelScale * dt
                if (abs(input.move) < 0.04f) predForwardVel *= (1f - 8f * dt).coerceAtLeast(0f)
                val maxSpeed = 12f * (if (team.surgeActive) 1.2f else 1f)
                predForwardVel = predForwardVel.coerceIn(-12f * 0.45f, maxSpeed)

                predLateralVel += input.steer * 20f * dt
                if (abs(input.steer) < 0.04f) predLateralVel *= (1f - 9f * dt).coerceAtLeast(0f)
                predLateralVel = predLateralVel.coerceIn(-2.6f, 2.6f)

                val pIndex = team.players.indexOfFirst { it.id == identity }
                if (pIndex >= 0) {
                    val p = team.players[pIndex]
                    var nextMeters = (p.progress * 100f + predForwardVel * dt).coerceIn(0f, 100f)
                    // Local rope constraint prediction so local player stays within rope limit of adjacent teammates
                    val effectiveGap = if (jumping) 9.7f else 7.5f
                    if (pIndex > 0) {
                        val leaderMeters = team.players[pIndex - 1].progress * 100f
                        nextMeters = nextMeters.coerceIn(leaderMeters - effectiveGap, leaderMeters - 0.4f)
                    }
                    if (pIndex < team.players.lastIndex) {
                        val trailerMeters = team.players[pIndex + 1].progress * 100f
                        nextMeters = nextMeters.coerceAtMost(trailerMeters + effectiveGap)
                    }
                    val boulder = currentSnap.obstacles.firstOrNull { it.id == "boulder" }
                    if (boulder != null && nextMeters in boulder.start..boulder.end && jumpH < 2.4f && p.height < 2.4f) {
                        nextMeters = boulder.start - 1.5f
                        predForwardVel = 0f
                    }
                    val nextLat = (p.lateralOffset + predLateralVel * dt).coerceIn(-1.25f, 1.25f)
                    val updatedPlayers = team.players.toMutableList()
                    updatedPlayers[pIndex] = p.copy(
                        progress = (nextMeters / 100f).coerceIn(0f, 1f),
                        lateralOffset = nextLat,
                        height = if (jumping) jumpH else p.height,
                        velocity = kotlin.math.hypot(predForwardVel, predLateralVel),
                        grounded = !jumping && p.grounded,
                        jumping = jumping || p.jumping,
                        bracing = input.bracing,
                    )
                    val updatedTeam = team.copy(players = updatedPlayers)
                    snapshot = if (currentSnap.viewerTeam == "B") currentSnap.copy(teamB = updatedTeam)
                    else currentSnap.copy(teamA = updatedTeam)
                }
            }
            delay(16)
        }
    }

    LaunchedEffect(roomCode, participantToken, snapshot?.phase, snapshot?.viewerSpectating) {
        if (previewMode) return@LaunchedEffect
        if (snapshot?.phase != "race" || snapshot?.viewerSpectating == true) return@LaunchedEffect

        var sequence = System.currentTimeMillis() * 1_000L
        var lastMove: Float? = null
        var lastSteer: Float? = null
        var lastBrace: Boolean? = null
        var lastJump: Boolean? = null
        var sentJumpSequence = controls.value.jumpSequence
        var jumpPulseUntil = 0L
        var lastSentAt = 0L

        while (true) {
            val current = controls.value
            val now = SystemClock.elapsedRealtime()
            if (current.jumpSequence > sentJumpSequence) jumpPulseUntil = maxOf(jumpPulseUntil, now + 150L)
            val jumpActive = now < jumpPulseUntil
            if (abs(current.move) > .12f || abs(current.steer) > .12f) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
            val active = abs(current.move) > .04f || abs(current.steer) > .04f || current.bracing || jumpActive
            val needsSend = active || current.move != lastMove || current.steer != lastSteer || current.bracing != lastBrace || jumpActive != lastJump
            // Urgent state transitions (Jump, Brace, Start/Stop/Reverse Move) bypass periodic throttling for 0ms dispatch!
            val urgentEdge = jumpActive != lastJump ||
                current.bracing != lastBrace ||
                (lastMove != null && (abs(current.move) > 0.05f) != (abs(lastMove!!) > 0.05f)) ||
                (lastMove != null && current.move * lastMove!! < 0f)

            if (needsSend) {
                val sinceLastSend = now - lastSentAt
                if (!urgentEdge && lastSentAt != 0L && sinceLastSend < 25L) {
                    delay(25L - sinceLastSend)
                    continue
                }
                try {
                    val sent = api.input(
                        seq = ++sequence,
                        move = current.move,
                        steer = current.steer,
                        jump = jumpActive,
                        brace = current.bracing,
                    )
                    if (!sent) throw IllegalStateException("Reconnecting to the live game server…")
                    lastMove = current.move
                    lastSteer = current.steer
                    lastBrace = current.bracing
                    lastJump = jumpActive
                    if (jumpActive) sentJumpSequence = current.jumpSequence
                    lastSentAt = SystemClock.elapsedRealtime()
                    error = null
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "Game controls could not reach the server."
                    delay(250)
                }
            }
            val waitTimeout = if (active) 25L else 120L
            withTimeoutOrNull(waitTimeout) {
                inputTrigger.receive()
            }
        }
    }

    LaunchedEffect(previewMode, snapshot?.phase) {
        if (!previewMode || snapshot?.phase != "race") return@LaunchedEffect
        var lastJumpSequence = controls.value.jumpSequence
        var jumpStartedAt = 0L
        var previousFrameAt = SystemClock.elapsedRealtime()
        var forwardVelocity = 0f
        var lateralVelocity = 0f
        val botJumpStartedAt = LongArray(4)
        var bridgeEnteredAt = 0L
        var lavaEnteredAt = 0L
        while (true) {
            val input = controls.value
            val now = SystemClock.elapsedRealtime()
            val deltaSeconds = ((now - previousFrameAt) / 1_000f).coerceIn(0f, .05f)
            previousFrameAt = now
            if (input.jumpSequence > lastJumpSequence) {
                lastJumpSequence = input.jumpSequence
                jumpStartedAt = now
            }
            val jumpAgeSeconds = if (jumpStartedAt == 0L) Float.POSITIVE_INFINITY else (now - jumpStartedAt) / 1_000f
            val jumping = jumpAgeSeconds in 0f..1.28f
            val jumpHeight = if (jumping) (16f * jumpAgeSeconds - 12.5f * jumpAgeSeconds * jumpAgeSeconds).coerceAtLeast(0f) else 0f
            val accelerationScale = if (input.bracing) .55f else 1f
            forwardVelocity += input.move * 30f * accelerationScale * deltaSeconds
            if (abs(input.move) < .04f) forwardVelocity *= (1f - 8f * deltaSeconds).coerceAtLeast(0f)
            forwardVelocity = forwardVelocity.coerceIn(-5.4f, 12f)
            lateralVelocity += input.steer * 20f * deltaSeconds
            if (abs(input.steer) < .04f) lateralVelocity *= (1f - 9f * deltaSeconds).coerceAtLeast(0f)
            lateralVelocity = lateralVelocity.coerceIn(-2.6f, 2.6f)
            snapshot = snapshot?.let { current ->
                var updatedCheckpoint = current.checkpointA
                var updatedNotice = current.notice
                fun advance(team: RopeboundTeam): RopeboundTeam {
                    val timeSec = now / 1_000f
                    val localCurrent = team.players.firstOrNull { it.id == identity }
                    var rawMeters = ((localCurrent?.progress ?: 0.448f) * 100f) + forwardVelocity * deltaSeconds
                    // Authoritative-matching obstacle & gap physics in preview mode:
                    val windObs = current.obstacles.firstOrNull { it.id == "wind-tunnel" }
                    if (windObs != null && rawMeters in windObs.start..windObs.end && !input.bracing) {
                        forwardVelocity = (forwardVelocity - 7f * deltaSeconds).coerceAtLeast(-5.4f)
                        rawMeters = ((localCurrent?.progress ?: 0.448f) * 100f) + forwardVelocity * deltaSeconds
                    }
                    val boulderObs = current.obstacles.firstOrNull { it.id == "boulder" }
                    if (boulderObs != null && rawMeters in boulderObs.start..boulderObs.end && jumpHeight < 2.4f) {
                        rawMeters = boulderObs.start - 1.4f
                        forwardVelocity = -1.5f
                        updatedNotice = "Bumped the boulder! Tap Jump before reaching it."
                        gameAudio.play(RopeboundGameAudio.Cue.BOULDER)
                    }
                    val pitObs = current.obstacles.firstOrNull { it.id == "pit" }
                    val riverGapObs = current.obstacles.firstOrNull { it.id == "river-gap" }
                    val lavaObs = current.obstacles.firstOrNull { it.id == "lava" }
                    val bridgeObs = current.obstacles.firstOrNull { it.id == "river-bridge" }

                    val fellInPit = pitObs != null && rawMeters in (pitObs.start + 0.4f)..(pitObs.end - 0.3f) && !jumping
                    val fellInRiver = riverGapObs != null && rawMeters in (riverGapObs.start + 0.4f)..(riverGapObs.end - 0.3f) && !jumping
                    if (bridgeObs != null && rawMeters in bridgeObs.start..bridgeObs.end && abs(forwardVelocity) < 0.8f) {
                        if (bridgeEnteredAt == 0L) bridgeEnteredAt = now
                    } else {
                        bridgeEnteredAt = 0L
                    }
                    val bridgeBroke = bridgeEnteredAt != 0L && (now - bridgeEnteredAt) >= 850L

                    if (lavaObs != null && rawMeters in (lavaObs.start + 0.3f)..(lavaObs.end - 0.3f) && !jumping) {
                        if (lavaEnteredAt == 0L) lavaEnteredAt = now
                    } else {
                        lavaEnteredAt = 0L
                    }
                    val burnedInLava = lavaEnteredAt != 0L && (now - lavaEnteredAt) >= 380L

                    if (fellInPit || fellInRiver || bridgeBroke || burnedInLava) {
                        val respawnMeters = updatedCheckpoint.coerceIn(8f, 80f)
                        forwardVelocity = 0f
                        lateralVelocity = 0f
                        bridgeEnteredAt = 0L
                        lavaEnteredAt = 0L
                        when {
                            fellInRiver -> gameAudio.play(RopeboundGameAudio.Cue.WATER)
                            bridgeBroke -> gameAudio.play(RopeboundGameAudio.Cue.BRIDGE_BREAK)
                            burnedInLava -> gameAudio.play(RopeboundGameAudio.Cue.LAVA)
                            else -> gameAudio.play(RopeboundGameAudio.Cue.CHECKPOINT)
                        }
                        updatedNotice = "Hazard recovery · regrouped at checkpoint ${respawnMeters.toInt()}m"
                        val resetPlayers = team.players.mapIndexed { idx, p ->
                            p.copy(
                                progress = ((respawnMeters - idx * 1.1f) / 100f).coerceIn(0.02f, 0.98f),
                                lateralOffset = 0f,
                                height = 0f,
                                velocity = 0f,
                                grounded = true,
                                jumping = false,
                            )
                        }
                        return team.copy(
                            players = resetPlayers,
                            progress = resetPlayers.map { it.progress }.average().toFloat(),
                            ropeTension = 0.18f,
                        )
                    }

                    var leadProgress = (rawMeters / 100f).coerceIn(0.02f, .99f)
                    val updated = team.players.mapIndexed { idx, player ->
                        if (player.id == identity) {
                            player.copy(
                                progress = leadProgress,
                                lateralOffset = (player.lateralOffset + lateralVelocity * deltaSeconds).coerceIn(-1.25f, 1.25f),
                                height = jumpHeight,
                                velocity = kotlin.math.hypot(forwardVelocity, lateralVelocity),
                                grounded = !jumping,
                                jumping = jumping,
                                bracing = input.bracing,
                            )
                        } else {
                            // Companion 3D squadmates follow the rope chain, weave naturally, and leap hazards
                            val targetP = (leadProgress - idx * 0.011f).coerceIn(0.01f, 0.98f)
                            val diffP = targetP - player.progress
                            val botVel = (diffP * 100f / deltaSeconds.coerceAtLeast(0.016f)).coerceIn(-5.2f, 11.8f)
                            val nextP = (player.progress + diffP * 0.24f).coerceIn(0.01f, 0.98f)
                            val distanceMeters = nextP * 100f
                            val nearJumpHazard = current.obstacles.any { obs ->
                                obs.action == "jump" && distanceMeters in (obs.start - 1.2f)..(obs.end - 0.4f)
                            }
                            val botJumpAge = if (botJumpStartedAt[idx % 4] == 0L) Float.POSITIVE_INFINITY else (now - botJumpStartedAt[idx % 4]) / 1_000f
                            if (nearJumpHazard && botJumpAge > 1.35f && abs(botVel) > 0.4f) {
                                botJumpStartedAt[idx % 4] = now
                            }
                            val activeBotJumpAge = if (botJumpStartedAt[idx % 4] == 0L) Float.POSITIVE_INFINITY else (now - botJumpStartedAt[idx % 4]) / 1_000f
                            val botJumping = activeBotJumpAge in 0f..1.28f
                            val botHeight = if (botJumping) (16f * activeBotJumpAge - 12.5f * activeBotJumpAge * activeBotJumpAge).coerceAtLeast(0f) else 0f
                            val targetLat = (sin(timeSec * 1.6f + idx * 1.3f) * 0.28f).coerceIn(-1.1f, 1.1f)
                            val nextLat = player.lateralOffset + (targetLat - player.lateralOffset) * 0.14f
                            player.copy(
                                progress = nextP,
                                lateralOffset = nextLat,
                                height = botHeight,
                                velocity = abs(botVel),
                                grounded = !botJumping,
                                jumping = botJumping,
                                bracing = input.bracing,
                            )
                        }
                    }
                    val slowestMeters = (updated.minOfOrNull { it.progress } ?: 0f) * 100f
                    val nextCp = listOf(8f, 19f, 30f, 40f, 50f, 64f, 80f).lastOrNull { slowestMeters >= it } ?: 8f
                    if (nextCp > updatedCheckpoint) {
                        updatedCheckpoint = nextCp
                        updatedNotice = "Checkpoint ${nextCp.toInt()}m reached!"
                    }
                    val maxGap = updated.zipWithNext().maxOfOrNull { (a, b) -> abs(a.progress - b.progress) * 100f } ?: 1.2f
                    val tension = (maxGap / 7.5f).coerceIn(0.12f, 0.92f)
                    val surgeReady = forwardVelocity > 6.5f && !input.bracing
                    val nextSurgeCharge = if (surgeReady) (team.surgeCharge + deltaSeconds * 0.45f).coerceAtMost(1f) else (team.surgeCharge - deltaSeconds * 0.25f).coerceAtLeast(0.15f)
                    return team.copy(
                        players = updated,
                        progress = updated.map { it.progress }.average().toFloat(),
                        ropeTension = tension,
                        surgeCharge = nextSurgeCharge,
                        surgeActive = nextSurgeCharge >= 0.95f,
                    )
                }
                val nextTeam = if (current.viewerTeam == "B") advance(current.teamB) else advance(current.teamA)
                if (current.viewerTeam == "B") current.copy(teamB = nextTeam, checkpointB = updatedCheckpoint, notice = updatedNotice)
                else current.copy(teamA = nextTeam, checkpointA = updatedCheckpoint, notice = updatedNotice)
            }
            if (abs(input.move) > .12f || abs(input.steer) > .12f) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
            delay(33)
        }
    }

    fun exitGame() {
        // The parent owns the room-leave request. Returning immediately lets
        // its landscape-to-portrait crossfade begin without waiting on network.
        gameAudio.stopAmbience()
        onBack()
    }

    fun updateLocalPreview(transform: (RopeboundSnapshot) -> RopeboundSnapshot) {
        if (previewMode) snapshot = snapshot?.let(transform)
    }

    val state = snapshot
    val ownTeam = when (state?.viewerTeam) {
        "A" -> state.teamA
        "B" -> state.teamB
        else -> null
    }
    val ownPlayer = ownTeam?.players?.firstOrNull { it.id == identity }
    val currentControls = controls.value

    LaunchedEffect(snapshot?.phase, ownTeam?.ropeTension) {
        val isHigh = snapshot?.phase == "race" && (ownTeam?.ropeTension ?: 0f) >= .78f
        if (isHigh && !ropeTensionWasHigh) gameAudio.play(RopeboundGameAudio.Cue.ROPE_TENSION)
        ropeTensionWasHigh = isHigh
    }

    Surface(color = Color(0xFF101820)) {
        Crossfade(
            targetState = state?.phase,
            animationSpec = tween(durationMillis = 480),
            label = "lobby-playground-transition",
        ) { displayedPhase ->
        if (displayedPhase == "race" && state != null) {
            RaceContent(
                modifier = Modifier.fillMaxSize(),
                state = state,
                error = error,
                viewerIdentity = identity,
                microphoneEnabled = microphoneEnabled,
                onDismissError = { error = null },
                onToggleMicrophone = onToggleMicrophone,
                onOpenGameSettings = ::openGameSettings,
                onExit = ::exitGame,
                viewerSpectating = state.viewerSpectating,
                localMoveInput = currentControls.move,
                localSteerInput = currentControls.steer,
                onStick = ::setStick,
                hudOpacity = hudOpacity,
                hudEditMode = hudEditMode,
                joystickPosition = joystickPosition,
                jumpPosition = jumpPosition,
                bracePosition = bracePosition,
                onJoystickPositionChange = { joystickPosition = it },
                onJumpPositionChange = { jumpPosition = it },
                onBracePositionChange = { bracePosition = it },
                onResetHudPositions = {
                    hudOpacity = .88f
                    joystickPosition = Offset(.025f, .67f)
                    jumpPosition = Offset(.78f, .78f)
                    bracePosition = Offset(.89f, .78f)
                },
                onExitHudEdit = { hudEditMode = false },
                onJump = ::jump,
                onBrace = ::setBrace,
                previewMode = previewMode,
            )
        } else if (displayedPhase == "lobby" && state != null) {
            val lobbyStageTeam = state.teamA.copy(
                count = state.teamA.count + state.teamB.count,
                players = state.teamA.players + state.teamB.players,
            )
            Box(Modifier.fillMaxSize()) {
                Ropebound3DScene(
                    team = lobbyStageTeam,
                    viewerIdentity = identity,
                    obstacles = state.obstacles,
                    cameraBlend = .42f,
                    showPlayerNameplates = false,
                    modifier = Modifier.fillMaxSize(),
                    onReady = {},
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xAA0D1722),
                                Color(0x82111C28),
                                Color(0xD90A141B),
                            ),
                        ),
                    ),
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    LobbyHeader(
                        microphoneEnabled = microphoneEnabled,
                        busy = busy,
                        isHost = state.isHost,
                        freeTeamSwitching = state.freeTeamSwitching,
                        onOpenSettings = ::openGameSettings,
                        onToggleMicrophone = onToggleMicrophone,
                        onSetFreeTeamSwitching = { enabled ->
                            if (previewMode) updateLocalPreview { it.copy(freeTeamSwitching = enabled) }
                            else action { api.setFreeTeamSwitching(roomCode, participantToken, enabled) }
                        },
                        onExit = ::exitGame,
                    )
                    LobbyContent(
                        modifier = Modifier.weight(1f),
                        state = state,
                        busy = busy,
                        error = error,
                        ready = ownPlayer?.ready ?: false,
                        viewerIdentity = identity,
                        onChooseTeam = { team ->
                            if (previewMode) updateLocalPreview { movePreviewPlayer(it, identity, team) }
                            else action { api.chooseTeam(roomCode, participantToken, team) }
                        },
                        onSwapPlayers = { playerId, otherPlayerId ->
                            if (previewMode) updateLocalPreview { swapPreviewPlayers(it, playerId, otherPlayerId) }
                            else action { api.swapPlayers(roomCode, participantToken, playerId, otherPlayerId) }
                        },
                        onReady = { ready ->
                            if (previewMode) updateLocalPreview { setPreviewReady(it, identity, ready) }
                            else action { api.setReady(roomCode, participantToken, ready) }
                        },
                        onStart = {
                            if (previewMode) updateLocalPreview { localPreviewSnapshot() }
                            else action { api.start(roomCode, participantToken) }
                        },
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ROPEBOUND · 3D", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(if (state?.phase == "results") "Race results" else "Game lobby", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    GameSettingsButton(onClick = ::openGameSettings)
                    OutlinedButton(onClick = onToggleMicrophone) { Text(if (microphoneEnabled) "Mic on" else "Mic muted") }
                    OutlinedButton(onClick = ::exitGame, enabled = !busy) { Text("Back to voice") }
                }

                when {
                    state == null -> LoadingLobby(error) { reconnectAttempt += 1 }
                    else -> ResultContent(
                        state = state,
                        busy = busy,
                        error = error,
                        onRematch = { action { api.rematch(roomCode, participantToken) } },
                        onExit = ::exitGame,
                    )
                }
                Text(
                    "Your SquadVoice voice room remains connected while you play.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
        if (gameSettingsOpen) {
            GameSettingsOverlay(
                soundEnabled = soundEnabled,
                ambienceVolume = ambienceVolume,
                sfxVolume = sfxVolume,
                hudOpacity = hudOpacity,
                hudEditMode = hudEditMode,
                onSoundEnabledChange = { soundEnabled = gameAudio.setEnabled(it) },
                onAmbienceVolumeChange = {
                    ambienceVolume = it
                    gameAudio.setAmbienceVolume(it)
                },
                onSfxVolumeChange = {
                    sfxVolume = it
                    gameAudio.setSfxVolume(it)
                },
                onHudOpacityChange = { hudOpacity = it.coerceIn(.35f, 1f) },
                onHudEditModeChange = { hudEditMode = it },
                onResetHud = {
                    hudOpacity = .88f
                    joystickPosition = Offset(.025f, .67f)
                    jumpPosition = Offset(.78f, .78f)
                    bracePosition = Offset(.89f, .78f)
                },
                onReset = {
                    ambienceVolume = 0.08f
                    sfxVolume = 0.48f
                    gameAudio.setAmbienceVolume(ambienceVolume)
                    gameAudio.setSfxVolume(sfxVolume)
                },
                onClose = { gameSettingsOpen = false },
            )
        }
    }
}

private fun localPreviewSnapshot(): RopeboundSnapshot {
    val names = listOf("Alex", "Jordan", "Sam", "Morgan")
    val players = names.mapIndexed { index, name ->
        RopeboundPlayer(
            id = "preview${index + 1}",
            name = name,
            ready = true,
            progress = .448f - index * .011f,
            lateralOffset = listOf(-.18f, .16f, -.14f, .20f)[index],
            height = 0f,
            velocity = 1.4f,
            grounded = true,
            jumping = false,
            bracing = false,
            host = index == 0,
            pingMs = listOf(32, 45, 38, 52)[index],
        )
    }
    val ownTeam = RopeboundTeam(
        count = players.size,
        progress = .445f,
        ropeTension = .24f,
        surgeCharge = .42f,
        surgeActive = false,
        surgeCount = 0,
        rescues = 3,
        players = players,
        spectators = emptyList(),
    )
    val rivals = ownTeam.copy(
        progress = .39f,
        players = emptyList(),
    )
    return RopeboundSnapshot(
        phase = "race",
        teamA = ownTeam,
        teamB = rivals,
        viewerTeam = "A",
        viewerSpectating = false,
        isHost = true,
        freeTeamSwitching = false,
        checkpointA = 40f,
        checkpointB = 30f,
        winner = null,
        notice = "Local 3D preview · move with joystick & drag screen to orbit 360°",
        hazard = null,
        effect = null,
        obstacles = listOf(
            RopeboundObstacle("pit", "Spike pit", "jump", "Jump across the spike pit", 23f, 27.5f),
            RopeboundObstacle("boulder", "Path boulder", "jump", "Jump over the stone boulder", 36f, 38.5f),
            RopeboundObstacle("river-bridge", "Rope bridge", "balance", "Cross the moving bridge", 43f, 49f),
            RopeboundObstacle("river-gap", "River gap", "jump", "Leap over the river gap", 56f, 61f),
            RopeboundObstacle("wind-tunnel", "Wind tunnel", "brace", "Brace against the gusts", 67f, 73.5f),
            RopeboundObstacle("lava", "Fire pit", "jump", "Jump past the lava", 84f, 90.5f),
        ),
        durationSeconds = 0,
        surgesA = 0,
        surgesB = 0,
    )
}

private fun localPreviewLobbySnapshot(): RopeboundSnapshot {
    val race = localPreviewSnapshot()
    val demoPlayers = race.teamA.players
    val teamAPlayers = demoPlayers.take(2)
    val teamBPlayers = demoPlayers.drop(2)
    return race.copy(
        phase = "lobby",
        teamA = race.teamA.copy(count = teamAPlayers.size, players = teamAPlayers),
        teamB = race.teamB.copy(count = teamBPlayers.size, players = teamBPlayers),
        viewerTeam = "A",
        freeTeamSwitching = true,
        notice = "Local preview · team changes are simulated",
    )
}

private fun movePreviewPlayer(state: RopeboundSnapshot, playerId: String, targetTeam: String): RopeboundSnapshot {
    if (targetTeam != "A" && targetTeam != "B") return state
    val inA = state.teamA.players.firstOrNull { it.id == playerId }
    val inB = state.teamB.players.firstOrNull { it.id == playerId }
    if (inA != null && targetTeam == "B") return state.copy(
        teamA = state.teamA.copy(count = (state.teamA.count - 1).coerceAtLeast(0), players = state.teamA.players - inA),
        teamB = state.teamB.copy(count = state.teamB.count + 1, players = state.teamB.players + inA),
        viewerTeam = targetTeam,
    )
    if (inB != null && targetTeam == "A") return state.copy(
        teamB = state.teamB.copy(count = (state.teamB.count - 1).coerceAtLeast(0), players = state.teamB.players - inB),
        teamA = state.teamA.copy(count = state.teamA.count + 1, players = state.teamA.players + inB),
        viewerTeam = targetTeam,
    )
    return state
}

private fun swapPreviewPlayers(state: RopeboundSnapshot, playerId: String, otherPlayerId: String): RopeboundSnapshot {
    val playerA = state.teamA.players.firstOrNull { it.id == playerId }
    val playerB = state.teamB.players.firstOrNull { it.id == playerId }
    val otherA = state.teamA.players.firstOrNull { it.id == otherPlayerId }
    val otherB = state.teamB.players.firstOrNull { it.id == otherPlayerId }
    if (playerA != null && otherB != null) return state.copy(
        teamA = state.teamA.copy(players = state.teamA.players.map { if (it.id == playerId) otherB else it }),
        teamB = state.teamB.copy(players = state.teamB.players.map { if (it.id == otherPlayerId) playerA else it }),
    )
    if (playerB != null && otherA != null) return state.copy(
        teamB = state.teamB.copy(players = state.teamB.players.map { if (it.id == playerId) otherA else it }),
        teamA = state.teamA.copy(players = state.teamA.players.map { if (it.id == otherPlayerId) playerB else it }),
    )
    return state
}

private fun setPreviewReady(state: RopeboundSnapshot, playerId: String, ready: Boolean): RopeboundSnapshot = state.copy(
    teamA = state.teamA.copy(players = state.teamA.players.map { if (it.id == playerId) it.copy(ready = ready) else it }),
    teamB = state.teamB.copy(players = state.teamB.players.map { if (it.id == playerId) it.copy(ready = ready) else it }),
)

@Composable
private fun LoadingLobby(error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (error == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Joining the game lobby…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Couldn't open the game lobby", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun LobbyHeader(
    microphoneEnabled: Boolean,
    busy: Boolean,
    isHost: Boolean,
    freeTeamSwitching: Boolean,
    onOpenSettings: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onSetFreeTeamSwitching: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val mint = Color(0xFF59E8D0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color(0xD91A2835), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x6648B9C8), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ROPEBOUND · 3D", color = mint, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = .5.sp)
            Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = .24f)))
            Text("Game lobby", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xAA15232E),
            border = BorderStroke(1.dp, Color(0x775A7180)),
        ) {
            Row(
                modifier = Modifier.height(34.dp).padding(start = 9.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Free switch",
                    color = if (freeTeamSwitching) mint else Color(0xFFD0DEE6),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (isHost) {
                    Switch(
                        checked = freeTeamSwitching,
                        onCheckedChange = onSetFreeTeamSwitching,
                        enabled = !busy,
                        modifier = Modifier.graphicsLayer { scaleX = .58f; scaleY = .58f },
                    )
                } else {
                    Text(
                        if (freeTeamSwitching) "ON" else "HOST",
                        color = if (freeTeamSwitching) mint else Color(0xFFB9C7D0),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onToggleMicrophone,
            modifier = Modifier.height(34.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xAA15232E), contentColor = Color.White),
            border = BorderStroke(1.dp, Color(0x775A7180)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (microphoneEnabled) "Mic on" else "Mic off", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(
                        if (microphoneEnabled) Color(0xFF58F0A5) else Color(0xFF87939C),
                    ),
                )
            }
        }
        GameSettingsButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp))
        OutlinedButton(
            onClick = onExit,
            enabled = !busy,
            modifier = Modifier.height(34.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xAA15232E), contentColor = Color.White),
            border = BorderStroke(1.dp, Color(0x775A7180)),
        ) {
            Text("Back to voice", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LobbyContent(
    modifier: Modifier = Modifier,
    state: RopeboundSnapshot,
    busy: Boolean,
    error: String?,
    ready: Boolean,
    viewerIdentity: String,
    onChooseTeam: (String) -> Unit,
    onSwapPlayers: (String, String) -> Unit,
    onReady: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    var swapSelection by remember { mutableStateOf<Pair<String, RopeboundPlayer>?>(null) }
    val mint = Color(0xFF59E8D0)
    val teamsReady = state.teamA.count >= 2 && state.teamB.count >= 2
    val everyoneReady = teamsReady && state.teamA.players.all { it.ready } && state.teamB.players.all { it.ready }
    val currentTeamCount = when (state.viewerTeam) {
        "A" -> state.teamA.count
        "B" -> state.teamB.count
        else -> 0
    }
    val switchingAllowed = state.isHost || state.freeTeamSwitching

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "At least two runners on each team · Swap players to keep both teams balanced.",
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFD0DEE6),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(180.dp)) {
            TeamCard(
                title = "TEAM A",
                teamId = "A",
                team = state.teamA,
                selected = state.viewerTeam == "A",
                busy = busy,
                viewerIdentity = viewerIdentity,
                viewerTeam = state.viewerTeam,
                isHost = state.isHost,
                freeTeamSwitching = state.freeTeamSwitching,
                canJoin = !busy && (state.viewerTeam == null || (state.viewerTeam == "B" && (currentTeamCount > 2 || state.isHost) && switchingAllowed)) &&
                    state.teamA.count < 5,
                onChooseTeam = onChooseTeam,
                onRequestSwap = { player -> swapSelection = "A" to player },
                modifier = Modifier.weight(1f),
            )
            TeamCard(
                title = "TEAM B",
                teamId = "B",
                team = state.teamB,
                selected = state.viewerTeam == "B",
                busy = busy,
                viewerIdentity = viewerIdentity,
                viewerTeam = state.viewerTeam,
                isHost = state.isHost,
                freeTeamSwitching = state.freeTeamSwitching,
                canJoin = !busy && (state.viewerTeam == null || (state.viewerTeam == "A" && (currentTeamCount > 2 || state.isHost) && switchingAllowed)) &&
                    state.teamB.count < 5,
                onChooseTeam = onChooseTeam,
                onRequestSwap = { player -> swapSelection = "B" to player },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.notice.isNotBlank() || error != null) {
            Text(
                error ?: state.notice,
                modifier = Modifier.fillMaxWidth(),
                color = if (error != null) Color(0xFFFFB4B4) else Color(0xFFD0DEE6),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onReady(!ready) },
                modifier = Modifier.weight(1f).height(42.dp),
                enabled = !busy && state.viewerTeam != null,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xC619302F),
                    contentColor = Color(0xFF70F0B7),
                    disabledContentColor = Color(0xFF78958B),
                ),
                border = BorderStroke(1.dp, Color(0xAA5ADFAE)),
            ) {
                Text(if (ready) "✓ Ready · tap to undo" else "✓ Ready up", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f).height(42.dp),
                enabled = !busy && state.isHost && everyoneReady,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5BEBC0),
                    contentColor = Color(0xFF06211E),
                    disabledContainerColor = Color(0xFF35544F),
                    disabledContentColor = Color(0xFFA6C0BA),
                ),
            ) {
                Text(
                    when {
                        !state.isHost -> "Host starts race"
                        !teamsReady -> "Need 2 per team"
                        !everyoneReady -> "Waiting for ready"
                        else -> "Start race"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    swapSelection?.let { (sourceTeam, sourcePlayer) ->
        val targetTeamId = if (sourceTeam == "A") "B" else "A"
        val targetTeam = if (targetTeamId == "A") state.teamA else state.teamB
        AlertDialog(
            onDismissRequest = { swapSelection = null },
            title = { Text("Swap teams") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choose a player from Team $targetTeamId. Both teams will keep the same number of players.")
                    targetTeam.players.filter { it.id != sourcePlayer.id }.forEach { player ->
                        TextButton(
                            onClick = {
                                onSwapPlayers(sourcePlayer.id, player.id)
                                swapSelection = null
                            },
                            enabled = !busy,
                        ) {
                            Text("${sourcePlayer.name}  ↔  ${player.name}")
                        }
                    }
                    if (targetTeam.players.none { it.id != sourcePlayer.id }) {
                        Text("No connected player is available on the other team.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { swapSelection = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun TeamCard(
    title: String,
    teamId: String,
    team: RopeboundTeam,
    selected: Boolean,
    busy: Boolean,
    viewerIdentity: String,
    viewerTeam: String?,
    isHost: Boolean,
    freeTeamSwitching: Boolean,
    canJoin: Boolean,
    onChooseTeam: (String) -> Unit,
    onRequestSwap: (RopeboundPlayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val teamAccent = if (teamId == "A") Color(0xFF59E8D0) else Color(0xFFB1C1CE)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color(0xE3223A47) else Color(0xD8192934),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) teamAccent else Color(0x77788A98),
        ),
        shadowElevation = if (selected) 10.dp else 4.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(title, color = teamAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(" · ${team.count}/5", color = Color(0xFFD2DEE5), fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(teamAccent.copy(alpha = if (selected) .52f else .22f)))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (team.players.isEmpty() && team.spectators.isEmpty()) {
                    Text(
                        "Waiting for runners…",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        color = Color(0xFFBCC9D1),
                        fontSize = 9.sp,
                    )
                }
                team.players.forEach { player ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(27.dp)
                            .background(Color(0x99202C37), RoundedCornerShape(9.dp))
                            .border(1.dp, Color(0x557A8A98), RoundedCornerShape(9.dp))
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val statusIcon = when {
                            !player.online -> "◌"
                            player.ready -> "✓"
                            else -> "○"
                        }
                        Text(
                            statusIcon,
                            color = if (player.online) Color(0xFF70F0B7) else Color(0xFFFFD166),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val statusSuffix = when {
                            !player.online -> " · connecting…"
                            player.host -> " · host"
                            else -> ""
                        }
                        Text(
                            "${player.name}$statusSuffix",
                            modifier = Modifier.weight(1f),
                            color = if (player.online) Color.White else Color.White.copy(alpha = .58f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PingBadge(player.pingMs)
                        if (isHost || (freeTeamSwitching && viewerTeam == teamId && player.id == viewerIdentity)) {
                            Box(
                                modifier = Modifier
                                    .height(23.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.linearGradient(listOf(Color(0xFF9B64E8), Color(0xFF39C8D8))))
                                    .clickable(enabled = !busy && player.online) { onRequestSwap(player) }
                                    .padding(horizontal = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Swap", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                team.spectators.forEach { spectator ->
                    Text(
                        "◦ $spectator · spectator",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 8.sp,
                        color = Color(0xFFB9C7D0),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                onClick = { onChooseTeam(teamId) },
                enabled = canJoin && !selected && team.count < 5,
                modifier = Modifier.fillMaxWidth().height(27.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x55202E39),
                    contentColor = Color.White,
                    disabledContentColor = if (selected) teamAccent else Color(0xFFAAB9C2),
                ),
                border = BorderStroke(1.dp, Color(0x777E929E)),
            ) {
                Text(
                    when {
                        selected -> "Your team"
                        team.count >= 5 -> "Team full"
                        canJoin -> "Join team"
                        !isHost && !freeTeamSwitching -> "Ask host"
                        else -> "Need 3 on your team"
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PingBadge(pingMs: Int?) {
    val color = when {
        pingMs == null -> Color(0xFFB7CACE)
        pingMs < 150 -> Color(0xFF71EBA2)
        pingMs < 300 -> Color(0xFFFFD166)
        else -> Color(0xFFFF8585)
    }
    Surface(color = color.copy(alpha = .2f), shape = RoundedCornerShape(50)) {
        Text(
            pingMs?.let { "$it ms" } ?: "PING —",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RaceContent(
    modifier: Modifier = Modifier,
    state: RopeboundSnapshot,
    error: String?,
    viewerIdentity: String,
    microphoneEnabled: Boolean,
    onDismissError: () -> Unit,
    viewerSpectating: Boolean,
    localMoveInput: Float = 0f,
    localSteerInput: Float = 0f,
    onToggleMicrophone: () -> Unit,
    onOpenGameSettings: () -> Unit,
    onExit: () -> Unit,
    onStick: (Float, Float) -> Unit,
    onJump: () -> Unit,
    onBrace: (Boolean) -> Unit,
    hudOpacity: Float,
    hudEditMode: Boolean,
    joystickPosition: Offset,
    jumpPosition: Offset,
    bracePosition: Offset = Offset(.89f, .78f),
    onJoystickPositionChange: (Offset) -> Unit,
    onJumpPositionChange: (Offset) -> Unit,
    onBracePositionChange: (Offset) -> Unit = {},
    onResetHudPositions: () -> Unit = {},
    onExitHudEdit: () -> Unit,
    previewMode: Boolean,
) {
    val ownTeam = if (state.viewerTeam == "A") state.teamA else state.teamB
    val otherTeam = if (state.viewerTeam == "A") state.teamB else state.teamA
    val latestJumpPosition by rememberUpdatedState(jumpPosition)
    val latestBracePosition by rememberUpdatedState(bracePosition)
    var playgroundReady by remember { mutableStateOf(false) }
    var minimumLoadingTimeComplete by remember { mutableStateOf(false) }
    var splashVisible by remember { mutableStateOf(true) }
    var gameUiVisible by remember { mutableStateOf(false) }
    var cameraMode by remember { mutableStateOf(0) } // 0 = Follow, 1 = 3D Hero Orbit, 2 = Top-Down
    var orbitYawOffset by remember { mutableStateOf(0f) }
    var orbitPitchOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        delay(1_900)
        minimumLoadingTimeComplete = true
    }
    LaunchedEffect(playgroundReady, minimumLoadingTimeComplete) {
        if (playgroundReady && minimumLoadingTimeComplete) {
            delay(420)
            splashVisible = false
            delay(420)
            gameUiVisible = true
        }
    }
    val cameraBlend by animateFloatAsState(
        targetValue = if (!gameUiVisible) .48f else if (cameraMode == 2) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "camera-view-transition",
    )
    val baseYawTarget = when (cameraMode) {
        1 -> 0.68f
        else -> 0f
    }
    val basePitchTarget = when (cameraMode) {
        1 -> 0.14f
        else -> 0.04f
    }
    val animatedBaseYaw by animateFloatAsState(
        targetValue = baseYawTarget,
        animationSpec = tween(durationMillis = 550),
        label = "camera-yaw-transition",
    )
    val animatedBasePitch by animateFloatAsState(
        targetValue = basePitchTarget,
        animationSpec = tween(durationMillis = 550),
        label = "camera-pitch-transition",
    )
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val loadingAnimation = rememberInfiniteTransition(label = "playground-loading")
    val logoPulse by loadingAnimation.animateFloat(
        initialValue = .96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "loading-logo-pulse",
    )
    val runnerTravel by loadingAnimation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500)),
        label = "loading-rope-travel",
    )

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0D202A))) {
        Ropebound3DScene(
            team = ownTeam,
            viewerIdentity = viewerIdentity,
            obstacles = state.obstacles,
            cameraBlend = cameraBlend,
            cameraOrbitYaw = animatedBaseYaw + orbitYawOffset,
            cameraOrbitPitch = (animatedBasePitch + orbitPitchOffset).coerceIn(-0.25f, 0.58f),
            localMoveInput = localMoveInput,
            localSteerInput = localSteerInput,
            onCameraOrbitDrag = { dyaw, dpitch ->
                orbitYawOffset = (orbitYawOffset + dyaw) % (2f * PI.toFloat())
                orbitPitchOffset = (orbitPitchOffset + dpitch).coerceIn(-0.32f, 0.48f)
            },
            showPlayerNameplates = gameUiVisible,
            modifier = Modifier.fillMaxSize(),
            onReady = { playgroundReady = true },
        )

        if (gameUiVisible) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                .graphicsLayer { alpha = hudOpacity },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier
                    .background(Color(0xE6102630), RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0xFF20C7D3).copy(alpha = .65f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaceMarker("TEAM", ownTeam.progress, valueColor = Color(0xFF18D9EA))
                HudStatDivider()
                RaceMarker("RIVALS", otherTeam.progress, valueColor = Color(0xFFFF6688))
                HudStatDivider()
                RaceMarker("TENSION", ownTeam.ropeTension, valueColor = Color(0xFFFFD34E), isTension = true)
            }
            Spacer(Modifier.weight(1f))
            HudActionButton(GameHudIcon.Follow, null, selected = cameraMode == 0 && abs(orbitYawOffset) < 0.05f, modifier = Modifier.size(38.dp), description = "Follow camera") {
                cameraMode = 0
                orbitYawOffset = 0f
                orbitPitchOffset = 0f
            }
            HudActionButton(GameHudIcon.Orbit3D, "3D", selected = cameraMode == 1 || abs(orbitYawOffset) >= 0.05f, modifier = Modifier.height(38.dp), description = "3D Hero Orbit camera") {
                cameraMode = if (cameraMode == 1) 0 else 1
                orbitYawOffset = 0f
                orbitPitchOffset = 0f
            }
            HudActionButton(GameHudIcon.TopView, null, selected = cameraMode == 2, modifier = Modifier.size(38.dp), description = "Top camera") {
                cameraMode = 2
                orbitYawOffset = 0f
                orbitPitchOffset = 0f
            }
            HudActionButton(GameHudIcon.Settings, null, modifier = Modifier.size(38.dp), description = "Game settings", onClick = onOpenGameSettings)
            HudActionButton(
                icon = GameHudIcon.Microphone,
                label = null,
                modifier = Modifier.size(38.dp),
                description = if (microphoneEnabled) "Mute microphone" else "Unmute microphone",
                onClick = onToggleMicrophone,
            )
            HudActionButton(
                icon = GameHudIcon.Voice,
                label = null,
                modifier = Modifier.size(38.dp),
                description = "Back to voice room",
                onClick = onExit,
            )
        }

        if (previewMode && gameUiVisible) {
            Text(
                "3D PREVIEW · MOVE STICK TO TURN/RUN · DRAG SCREEN TO ORBIT 360°",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 53.dp)
                    .background(Color(0xB9102630), RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                color = Color(0xFF9BE9DE),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .8.sp,
            )
        }

        error?.let {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
                    .background(Color(0xDD451C24), RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    it,
                    color = Color(0xFFFFC5C5),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDismissError, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("×", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        if (viewerSpectating) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(.68f).padding(bottom = 18.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xE8102028),
                border = BorderStroke(1.dp, Color(0xFFFFD166).copy(alpha = .8f)),
            ) {
                Text(
                    "SPECTATING · Your squad continues. You can play next round.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Compact controls retain 56dp touch targets while taking less screen space.
            val bottomPadding = if (isLandscape) 10.dp else 8.dp
            val density = LocalDensity.current
            val stickSize = 112.dp
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenWidthPx = with(density) { maxWidth.toPx() }
                val screenHeightPx = with(density) { maxHeight.toPx() }
                VirtualJoystick(
                    move = 0f,
                    steer = 0f,
                    position = joystickPosition,
                    opacity = hudOpacity,
                    editing = hudEditMode,
                    maxWidthPx = screenWidthPx,
                    maxHeightPx = screenHeightPx,
                    controlSize = stickSize,
                    modifier = Modifier.offset {
                        val stickPx = with(density) { stickSize.toPx() }
                        IntOffset(
                            (screenWidthPx * joystickPosition.x).roundToInt().coerceIn(0, (screenWidthPx - stickPx).roundToInt().coerceAtLeast(0)),
                            (screenHeightPx * joystickPosition.y).roundToInt().coerceIn(0, (screenHeightPx - stickPx).roundToInt().coerceAtLeast(0)),
                        )
                    },
                    onPositionChange = onJoystickPositionChange,
                    onInput = onStick,
                )
                GameActionButton(
                    onClick = { if (!hudEditMode) onJump() },
                    description = if (hudEditMode) "Drag to move jump control" else "Jump",
                    icon = GameControlIcon.Jump,
                    modifier = Modifier.offset {
                        val buttonPx = with(density) { 56.dp.toPx() }
                        IntOffset(
                            (screenWidthPx * jumpPosition.x).roundToInt().coerceIn(0, (screenWidthPx - buttonPx).roundToInt().coerceAtLeast(0)),
                            (screenHeightPx * jumpPosition.y).roundToInt().coerceIn(0, (screenHeightPx - buttonPx).roundToInt().coerceAtLeast(0)),
                        )
                    }.size(56.dp)
                        .graphicsLayer { alpha = hudOpacity }
                        .pointerInput(hudEditMode, screenWidthPx, screenHeightPx) {
                            if (hudEditMode) {
                                var dragPosition = latestJumpPosition
                                detectDragGestures(onDragStart = { dragPosition = latestJumpPosition }) { change, drag ->
                                    change.consume()
                                    val sizePx = with(density) { 56.dp.toPx() }
                                    dragPosition = Offset(
                                        (dragPosition.x + drag.x / screenWidthPx).coerceIn(0f, (screenWidthPx - sizePx) / screenWidthPx),
                                        (dragPosition.y + drag.y / screenHeightPx).coerceIn(0f, (screenHeightPx - sizePx) / screenHeightPx),
                                    )
                                    onJumpPositionChange(dragPosition)
                                }
                            }
                        },
                    highlighted = true,
                )
                HoldBraceButton(
                    onBrace = { if (!hudEditMode) onBrace(it) else onBrace(false) },
                    modifier = Modifier.offset {
                        val buttonPx = with(density) { 56.dp.toPx() }
                        IntOffset(
                            (screenWidthPx * bracePosition.x).roundToInt().coerceIn(0, (screenWidthPx - buttonPx).roundToInt().coerceAtLeast(0)),
                            (screenHeightPx * bracePosition.y).roundToInt().coerceIn(0, (screenHeightPx - buttonPx).roundToInt().coerceAtLeast(0)),
                        )
                    }.size(56.dp)
                        .graphicsLayer { alpha = hudOpacity }
                        .pointerInput(hudEditMode, screenWidthPx, screenHeightPx) {
                            if (hudEditMode) {
                                var dragPosition = latestBracePosition
                                detectDragGestures(onDragStart = { dragPosition = latestBracePosition }) { change, drag ->
                                    change.consume()
                                    val sizePx = with(density) { 56.dp.toPx() }
                                    dragPosition = Offset(
                                        (dragPosition.x + drag.x / screenWidthPx).coerceIn(0f, (screenWidthPx - sizePx) / screenWidthPx),
                                        (dragPosition.y + drag.y / screenHeightPx).coerceIn(0f, (screenHeightPx - sizePx) / screenHeightPx),
                                    )
                                    onBracePositionChange(dragPosition)
                                }
                            }
                        },
                )
                if (hudEditMode) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        shape = RoundedCornerShape(50),
                        color = Color(0xEE102630),
                        border = BorderStroke(1.2.dp, Color(0xFF66E0CB)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("HUD EDIT · Drag Stick, Jump & Brace", color = Color(0xFF9BE9DE), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "RESET",
                                modifier = Modifier.clickable(onClick = onResetHudPositions).padding(horizontal = 4.dp, vertical = 2.dp),
                                color = Color(0xFFFFD166),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "DONE",
                                modifier = Modifier.clickable(onClick = onExitHudEdit).padding(horizontal = 4.dp, vertical = 2.dp),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
            }
        }
        }

        AnimatedVisibility(
            visible = splashVisible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(420)),
        ) {
            MatchLoadingOverlay(
                teamSize = ownTeam.players.size,
                worldReady = playgroundReady,
                logoPulse = logoPulse,
                runnerTravel = runnerTravel,
            )
        }
    }
}

@Composable
private fun GameSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC10212A))
            .border(1.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics {
            contentDescription = "Game audio settings"
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = Color.White
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 1.8.dp.toPx()
            drawCircle(color, 5.dp.toPx(), Offset(cx, cy), style = Stroke(stroke))
            drawCircle(color, 1.4.dp.toPx(), Offset(cx, cy))
            repeat(8) { index ->
                val angle = index * PI / 4
                drawLine(
                    color,
                    Offset(cx + cos(angle).toFloat() * 6.dp.toPx(), cy + sin(angle).toFloat() * 6.dp.toPx()),
                    Offset(cx + cos(angle).toFloat() * 8.dp.toPx(), cy + sin(angle).toFloat() * 8.dp.toPx()),
                    stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun VirtualJoystick(
    move: Float,
    steer: Float,
    position: Offset,
    opacity: Float,
    editing: Boolean,
    maxWidthPx: Float,
    maxHeightPx: Float,
    controlSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onPositionChange: (Offset) -> Unit,
    onInput: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val latestPosition by rememberUpdatedState(position)
    var activeMove by remember { mutableStateOf(move) }
    var activeSteer by remember { mutableStateOf(steer) }
    val sizePx = with(density) { controlSize.toPx() }

    Canvas(
        modifier = modifier.size(controlSize)
            .graphicsLayer { alpha = opacity }
            .semantics { contentDescription = if (editing) "Drag to move joystick" else "Four direction movement joystick" }
            .pointerInput(editing, maxWidthPx, maxHeightPx, sizePx) {
                fun updateAxes(position: Offset) {
                    val center = sizePx / 2f
                    val travel = sizePx * .28f
                    val rawX = (position.x - center) / travel
                    val rawY = (position.y - center) / travel
                    val rawMagnitude = kotlin.math.hypot(rawX, rawY)
                    val deadZone = .12f
                    val inputScale = if (rawMagnitude <= deadZone || rawMagnitude == 0f) {
                        0f
                    } else {
                        ((rawMagnitude.coerceAtMost(1f) - deadZone) / (1f - deadZone)) / rawMagnitude
                    }
                    val horizontal = (rawX * inputScale).coerceIn(-1f, 1f)
                    val vertical = (rawY * inputScale).coerceIn(-1f, 1f)
                    activeMove = -vertical
                    activeSteer = horizontal
                    onInput(-vertical, horizontal)
                }
                if (editing) {
                    var dragPosition = latestPosition
                    detectDragGestures(onDragStart = { dragPosition = latestPosition }) { change, drag ->
                        change.consume()
                        val maxX = ((maxWidthPx - sizePx) / maxWidthPx).coerceAtLeast(0f)
                        val maxY = ((maxHeightPx - sizePx) / maxHeightPx).coerceAtLeast(0f)
                        dragPosition = Offset(
                            (dragPosition.x + drag.x / maxWidthPx).coerceIn(0f, maxX),
                            (dragPosition.y + drag.y / maxHeightPx).coerceIn(0f, maxY),
                        )
                        onPositionChange(dragPosition)
                    }
                } else {
                    // Instant zero-slop touch handling so tapping and holding any of the 4 direction arrows
                    // or sliding the thumbstick responds on the very first touch frame.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        updateAxes(down.position)
                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull { it.pressed }
                            if (change == null || !change.pressed) {
                                activeMove = 0f
                                activeSteer = 0f
                                onInput(0f, 0f)
                                break
                            }
                            pointerId = change.id
                            change.consume()
                            updateAxes(change.position)
                        }
                    }
                }
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .47f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xF11B3038), Color(0xD90B171D)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        drawCircle(
            if (editing) Color(0xFF48F0D8) else Color(0xFFE2F4F4).copy(alpha = .78f),
            radius,
            center,
            style = Stroke(if (editing) 2.6.dp.toPx() else 2.dp.toPx()),
        )
        drawCircle(Color(0xFF39D9D5).copy(alpha = .46f), radius * .72f, center, style = Stroke(1.dp.toPx()))

        fun arrow(dx: Float, dy: Float) {
            val point = Offset(center.x + dx * radius * .48f, center.y + dy * radius * .48f)
            val ux = dx
            val uy = dy
            val px = -dy
            val py = dx
            val tip = Offset(point.x + ux * radius * .105f, point.y + uy * radius * .105f)
            val tail = Offset(point.x - ux * radius * .105f, point.y - uy * radius * .105f)
            val ink = Color.White.copy(alpha = .78f)
            drawLine(ink, tail, tip, 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - ux * radius * .08f + px * radius * .065f, tip.y - uy * radius * .08f + py * radius * .065f), 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - ux * radius * .08f - px * radius * .065f, tip.y - uy * radius * .08f - py * radius * .065f), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        arrow(0f, -1f)
        arrow(0f, 1f)
        arrow(-1f, 0f)
        arrow(1f, 0f)

        val knobTravel = radius * .66f
        val knob = Offset(
            center.x + activeSteer * knobTravel,
            center.y - activeMove * knobTravel,
        )
        drawCircle(Color.Black.copy(alpha = .28f), radius * .30f, Offset(knob.x + 1.dp.toPx(), knob.y + 2.dp.toPx()))
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFC8E2E5), Color(0xFF677E87))), radius * .28f, knob)
        drawCircle(Color.White.copy(alpha = .5f), radius * .28f, knob, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = .24f), radius * .10f, Offset(knob.x - radius * .08f, knob.y - radius * .08f))
    }
}

private enum class GameHudIcon { Follow, Orbit3D, TopView, Settings, Microphone, Voice }

@Composable
private fun HudActionButton(
    icon: GameHudIcon,
    label: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    description: String = label ?: "Game action",
    onClick: () -> Unit,
) {
    val accent = Color(0xFF16D9E7)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xE51B4C58) else Color(0xE6102630),
            contentColor = Color.White,
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) .9f else .52f)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val c = accent
                val cx = size.width / 2f
                val cy = size.height / 2f
                val stroke = 2.dp.toPx()
                when (icon) {
                    GameHudIcon.Follow -> {
                        drawCircle(c, 2.6.dp.toPx(), Offset(cx, cy - 4.dp.toPx()))
                        drawCircle(c, 2.dp.toPx(), Offset(cx - 5.dp.toPx(), cy - 2.dp.toPx()))
                        drawCircle(c, 2.dp.toPx(), Offset(cx + 5.dp.toPx(), cy - 2.dp.toPx()))
                        drawRoundRect(c, Offset(cx - 6.dp.toPx(), cy + 1.dp.toPx()), Size(12.dp.toPx(), 5.dp.toPx()), CornerRadius(3.dp.toPx()))
                        drawRoundRect(c, Offset(cx - 9.dp.toPx(), cy + 2.dp.toPx()), Size(5.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                        drawRoundRect(c, Offset(cx + 4.dp.toPx(), cy + 2.dp.toPx()), Size(5.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                    }
                    GameHudIcon.Orbit3D -> {
                        // 3D Cube / Orbit icon
                        val r = 6.dp.toPx()
                        drawCircle(c, r, Offset(cx, cy), style = Stroke(stroke))
                        drawArc(c, 0f, 360f, false, Offset(cx - r, cy - r * .45f), Size(r * 2f, r * .9f), style = Stroke(stroke * .85f))
                        drawCircle(c, 1.8.dp.toPx(), Offset(cx, cy))
                    }
                    GameHudIcon.TopView -> {
                        drawRoundRect(c, Offset(cx - 8.dp.toPx(), cy - 5.dp.toPx()), Size(16.dp.toPx(), 11.dp.toPx()), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                        drawCircle(c, 2.5.dp.toPx(), Offset(cx, cy + .5f.dp.toPx()), style = Stroke(stroke))
                        drawLine(c, Offset(cx - 4.dp.toPx(), cy - 7.dp.toPx()), Offset(cx + 4.dp.toPx(), cy - 7.dp.toPx()), stroke, cap = StrokeCap.Round)
                    }
                    GameHudIcon.Settings -> {
                        drawCircle(c, 5.dp.toPx(), Offset(cx, cy), style = Stroke(stroke))
                        drawCircle(c, 1.5.dp.toPx(), Offset(cx, cy))
                        repeat(8) { i ->
                            val angle = i * PI / 4
                            val inner = 6.dp.toPx()
                            val outer = 8.dp.toPx()
                            drawLine(
                                c,
                                Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                                Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                                stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    GameHudIcon.Microphone -> {
                        drawRoundRect(c, Offset(cx - 3.dp.toPx(), cy - 8.dp.toPx()), Size(6.dp.toPx(), 11.dp.toPx()), CornerRadius(3.dp.toPx()))
                        drawArc(c, 180f, 180f, false, Offset(cx - 6.dp.toPx(), cy - 4.dp.toPx()), Size(12.dp.toPx(), 10.dp.toPx()), style = Stroke(stroke))
                        drawLine(c, Offset(cx, cy + 6.dp.toPx()), Offset(cx, cy + 8.dp.toPx()), stroke, cap = StrokeCap.Round)
                        drawLine(c, Offset(cx - 3.dp.toPx(), cy + 8.dp.toPx()), Offset(cx + 3.dp.toPx(), cy + 8.dp.toPx()), stroke, cap = StrokeCap.Round)
                    }
                    GameHudIcon.Voice -> {
                        val heights = listOf(4f, 8f, 12f, 6f, 10f, 5f)
                        heights.forEachIndexed { index, height ->
                            val x = cx + (index - 2.5f) * 2.6.dp.toPx()
                            drawLine(c, Offset(x, cy - height.dp.toPx() / 2f), Offset(x, cy + height.dp.toPx() / 2f), stroke, cap = StrokeCap.Round)
                        }
                    }
                }
            }
            if (label != null) {
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GameSettingsOverlay(
    soundEnabled: Boolean,
    ambienceVolume: Float,
    sfxVolume: Float,
    hudOpacity: Float,
    hudEditMode: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onAmbienceVolumeChange: (Float) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
    onHudOpacityChange: (Float) -> Unit,
    onHudEditModeChange: (Boolean) -> Unit,
    onResetHud: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xB807151C)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.88f),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xF0182C35),
            border = BorderStroke(1.dp, Color(0xFF66E0CB).copy(alpha = .75f)),
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ROPEBOUND · IN GAME", color = Color(0xFF66E0CB), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("Game settings", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.height(40.dp).semantics { contentDescription = "Close game settings" },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .55f)),
                    ) { Text("×", fontSize = 21.sp) }
                }

                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    val centerY = size.height / 2f
                    drawLine(Color(0xFF66E0CB).copy(alpha = .55f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.dp.toPx())
                    val spacing = size.width / 5f
                    for (index in 1..4) drawCircle(Color(0xFF9DE4E8), 3.dp.toPx(), Offset(spacing * index, centerY))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Game audio", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Soft game music under voice chat, plus effects", color = Color(0xFFB7CACE), fontSize = 12.sp)
                    }
                    Switch(checked = soundEnabled, onCheckedChange = onSoundEnabledChange)
                }
                Text("Background music · ${(ambienceVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = ambienceVolume,
                    onValueChange = onAmbienceVolumeChange,
                    enabled = soundEnabled,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF66E0CB), activeTrackColor = Color(0xFF66E0CB)),
                )
                Text("Gameplay effects · ${(sfxVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = sfxVolume,
                    onValueChange = onSfxVolumeChange,
                    enabled = soundEnabled,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF66E0CB), activeTrackColor = Color(0xFF66E0CB)),
                )
                Text("HUD transparency · ${(hudOpacity * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = hudOpacity,
                    onValueChange = onHudOpacityChange,
                    valueRange = .35f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF66E0CB), activeTrackColor = Color(0xFF66E0CB)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize HUD", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Move the joystick and jump icon after closing settings", color = Color(0xFFB7CACE), fontSize = 11.sp)
                    }
                    Switch(checked = hudEditMode, onCheckedChange = onHudEditModeChange)
                }
                Text(
                    "Voice chat stays connected. Your controls are released while settings are open.",
                    color = Color(0xFFB7CACE),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onReset) { Text("Reset audio") }
                        OutlinedButton(onClick = onResetHud) { Text("Reset HUD") }
                    }
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66E0CB), contentColor = Color(0xFF082521)),
                    ) { Text("OK · Resume", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun MatchLoadingOverlay(teamSize: Int, worldReady: Boolean, logoPulse: Float, runnerTravel: Float) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val trackProgress = if (runnerTravel < .78f) .72f + runnerTravel * .22f else .89f + (runnerTravel - .78f) * .35f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0xB307151D),
                        .30f to Color(0x2607151D),
                        .62f to Color(0x1607151D),
                        1f to Color(0xD907151D),
                    ),
                ),
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "SQUADVOICE",
                    color = Color.White.copy(alpha = .78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    "ROPEBOUND",
                    modifier = Modifier.graphicsLayer { scaleX = logoPulse; scaleY = logoPulse },
                    color = Color(0xFF7BF1DC),
                    fontSize = if (isLandscape) 28.sp else 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.5.sp,
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(if (isLandscape) .56f else .94f),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xD9122730),
                border = BorderStroke(1.dp, Color(0xFF73E6D7).copy(alpha = .52f)),
                shadowElevation = 14.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("LOADING PLAYGROUND", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                    Text(
                        if (worldReady) "Course ready · joining with $teamSize runners…"
                        else "Syncing your squad and setting the bridge…",
                        color = Color(0xFFC2D8DA),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                        val barHeight = size.height * .48f
                        val top = (size.height - barHeight) / 2f
                        drawRoundRect(Color.White.copy(alpha = .18f), Offset.Zero, size, CornerRadius(size.height / 2f))
                        drawRoundRect(
                            Color(0xFF59E1CB),
                            Offset.Zero,
                            Size(size.width * trackProgress, barHeight),
                            CornerRadius(barHeight / 2f),
                        )
                        val headX = size.width * trackProgress
                        drawCircle(Color.White.copy(alpha = .92f), barHeight * .52f, Offset(headX.coerceAtMost(size.width - barHeight), size.height / 2f))
                        val runnerColors = listOf(Color(0xFFB9A3FF), Color(0xFFFF927F), Color(0xFF62E2C9), Color(0xFFFFCB67))
                        runnerColors.forEachIndexed { index, color ->
                            val x = size.width * ((runnerTravel + index * .19f) % 1f)
                            if (x < headX) drawCircle(color, barHeight * .28f, Offset(x, size.height / 2f))
                        }
                    }
                    Text(
                        "Stay together to keep the rope steady.",
                        color = Color(0xFF83E9D8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                if (worldReady) "YOUR TEAM IS READY" else "YOUR TEAM IS GETTING READY",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = Color.White.copy(alpha = .72f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

@Composable
private fun HoldMoveButton(
    direction: Int,
    topDownCamera: Boolean,
    modifier: Modifier = Modifier,
    onMove: (Int, Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val icon = when {
        topDownCamera && direction < 0 -> GameControlIcon.Down
        topDownCamera -> GameControlIcon.Up
        direction < 0 -> GameControlIcon.Left
        else -> GameControlIcon.Right
    }

    GameControlButton(
        icon = icon,
        description = if (direction < 0) "Move backward" else "Move forward",
        modifier = modifier,
        pressed = pressed,
        interactionSource = interactionSource,
        onClick = {},
        onPressedChange = { onMove(direction, it) },
    )
}

@Composable
private fun HoldBraceButton(onBrace: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { onBrace(pressed) }
    GameControlButton(
        icon = GameControlIcon.Brace,
        description = "Brace",
        modifier = modifier,
        pressed = pressed,
        interactionSource = interactionSource,
    )
}

private enum class GameControlIcon { Left, Right, Up, Down, Jump, Brace }

@Composable
private fun GameActionButton(
    onClick: () -> Unit,
    description: String,
    icon: GameControlIcon,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    GameControlButton(
        icon = icon,
        description = description,
        modifier = modifier,
        pressed = false,
        highlighted = highlighted,
        onClick = onClick,
    )
}

@Composable
private fun GameControlButton(
    icon: GameControlIcon,
    description: String,
    modifier: Modifier = Modifier,
    pressed: Boolean,
    highlighted: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit = {},
    onPressedChange: ((Boolean) -> Unit)? = null,
) {
    val active = pressed || highlighted
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = description
        },
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) Color(0xFF66E0CB) else Color(0xE6102028),
            contentColor = if (active) Color(0xFF082521) else Color.White,
        ),
        border = BorderStroke(1.5.dp, if (active) Color.White.copy(alpha = .92f) else Color.White.copy(alpha = .72f)),
    ) {
        Canvas(Modifier.size(25.dp)) {
            val ink = if (active) Color(0xFF082521) else Color.White
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 2.7.dp.toPx()
            when (icon) {
                GameControlIcon.Left, GameControlIcon.Right, GameControlIcon.Up, GameControlIcon.Down -> {
                    val unit = when (icon) {
                        GameControlIcon.Left -> Offset(-1f, 0f)
                        GameControlIcon.Right -> Offset(1f, 0f)
                        GameControlIcon.Up -> Offset(0f, -1f)
                        else -> Offset(0f, 1f)
                    }
                    val perpendicular = Offset(-unit.y, unit.x)
                    val arrowLength = 7.dp.toPx()
                    val headLength = 4.dp.toPx()
                    val headWidth = 4.dp.toPx()
                    val start = Offset(cx - unit.x * arrowLength, cy - unit.y * arrowLength)
                    val tip = Offset(cx + unit.x * arrowLength, cy + unit.y * arrowLength)
                    drawLine(ink, start, tip, stroke, cap = StrokeCap.Round)
                    drawLine(
                        ink,
                        tip,
                        Offset(tip.x - unit.x * headLength + perpendicular.x * headWidth, tip.y - unit.y * headLength + perpendicular.y * headWidth),
                        stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        ink,
                        tip,
                        Offset(tip.x - unit.x * headLength - perpendicular.x * headWidth, tip.y - unit.y * headLength - perpendicular.y * headWidth),
                        stroke,
                        cap = StrokeCap.Round,
                    )
                }
                GameControlIcon.Jump -> {
                    drawLine(ink, Offset(cx, cy + 8.dp.toPx()), Offset(cx, cy - 7.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - 7.dp.toPx()), Offset(cx - 6.dp.toPx(), cy - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - 7.dp.toPx()), Offset(cx + 6.dp.toPx(), cy - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx - 9.dp.toPx(), cy + 9.dp.toPx()), Offset(cx + 9.dp.toPx(), cy + 9.dp.toPx()), stroke, cap = StrokeCap.Round)
                }
                GameControlIcon.Brace -> {
                    val shield = Path().apply {
                        moveTo(cx, cy - 10.dp.toPx())
                        lineTo(cx + 8.dp.toPx(), cy - 6.dp.toPx())
                        lineTo(cx + 7.dp.toPx(), cy + 2.dp.toPx())
                        quadraticTo(cx + 5.dp.toPx(), cy + 7.dp.toPx(), cx, cy + 10.dp.toPx())
                        quadraticTo(cx - 5.dp.toPx(), cy + 7.dp.toPx(), cx - 7.dp.toPx(), cy + 2.dp.toPx())
                        lineTo(cx - 8.dp.toPx(), cy - 6.dp.toPx())
                        close()
                    }
                    drawPath(shield, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    drawLine(ink, Offset(cx - 3.dp.toPx(), cy), Offset(cx - 0.5f.dp.toPx(), cy + 3.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx - 0.5f.dp.toPx(), cy + 3.dp.toPx()), Offset(cx + 4.dp.toPx(), cy - 3.dp.toPx()), stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
    if (onPressedChange != null) LaunchedEffect(pressed) { onPressedChange(pressed) }
}

@Composable
private fun RaceMarker(
    label: String,
    progress: Float,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFF66E0CB),
    isTension: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .35.sp,
            color = Color.White.copy(alpha = .82f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
            fontSize = 12.sp,
            color = if (isTension && progress >= .8f) Color(0xFFFF9B57) else valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HudStatDivider() {
    Box(
        Modifier.padding(horizontal = 5.dp).width(1.dp).height(20.dp)
            .background(Color(0xFF28C8D5).copy(alpha = .38f)),
    )
}

@Composable
private fun CourseCanvas(
    team: RopeboundTeam,
    viewerIdentity: String,
    effect: RopeboundEffect?,
    obstacles: List<RopeboundObstacle>,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
) {
    val backgroundImage = ImageBitmap.imageResource(R.drawable.ropebound_training_yard_v2)
    LaunchedEffect(backgroundImage) {
        withFrameNanos { }
        onReady()
    }
    val hazardTransition = rememberInfiniteTransition(label = "hazard-motion")
    val hazardPhase by hazardTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "hazard-phase",
    )
    val impactProgress = remember(effect?.id) { Animatable(1f) }
    LaunchedEffect(effect?.id) {
        if (effect != null) {
            impactProgress.snapTo(0f)
            impactProgress.animateTo(1f, tween(900))
        }
    }
    val localPlayer = team.players.firstOrNull { it.id == viewerIdentity }
    val density = LocalDensity.current
    val playerNamePaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 9.sp.toPx() }
            textAlign = AndroidPaint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    val playerPingPaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 8.sp.toPx() }
            textAlign = AndroidPaint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    val cameraWidth = .42f
    val cameraMin = -cameraWidth * .45f
    val cameraMax = 1f - cameraWidth * .55f
    val targetCameraLeft = ((localPlayer?.progress ?: team.progress) - cameraWidth * .45f)
        .coerceIn(cameraMin, cameraMax)
    val cameraLeft by animateFloatAsState(targetCameraLeft, tween(180), label = "race-camera")
    val animatedPlayers = team.players.map { player ->
        val x by animateFloatAsState(player.progress, tween(110), label = "runner-x-${player.id}")
        val y by animateFloatAsState(player.height, tween(110), label = "runner-y-${player.id}")
        player.copy(progress = x, height = y)
    }
    val obstacleById = obstacles.associateBy { it.id }
    Canvas(modifier = modifier.fillMaxSize().background(Color(0xFF0D202A))) {
        val w = size.width
        val h = size.height
        val groundY = h * .74f
        // The local runner drives this device's camera; the painted scenery
        // drifts more slowly than the course for a layered parallax effect.
        val cameraProgress = ((cameraLeft - cameraMin) / (cameraMax - cameraMin)).coerceIn(0f, 1f)
        val backgroundWidth = w * 1.30f
        val backgroundHeight = h.roundToInt().coerceAtLeast(1)
        val backgroundCropHeight = (backgroundImage.width * backgroundHeight / backgroundWidth)
            .roundToInt().coerceIn(1, backgroundImage.height)
        val backgroundCropTop = ((backgroundImage.height - backgroundCropHeight) * .76f).roundToInt()
        val parallaxOffset = -(cameraProgress * w * .30f).roundToInt()
        drawImage(
            image = backgroundImage,
            srcOffset = IntOffset(0, backgroundCropTop),
            srcSize = IntSize(backgroundImage.width, backgroundCropHeight),
            dstOffset = IntOffset(parallaxOffset, 0),
            dstSize = IntSize(backgroundWidth.roundToInt(), backgroundHeight),
        )

        // Layered turf, brushy tufts, tiny leaves and stones give the course a
        // hand-painted trail edge. Each blade leans gently with the wind loop.
        drawRect(Color(0xFF284A38).copy(alpha = .30f), Offset(0f, groundY - h * .045f), Size(w, h * .075f))
        drawRect(Color(0xFF8DAF54).copy(alpha = .24f), Offset(0f, groundY - h * .010f), Size(w, h * .018f))
        drawLine(Color(0xFFC5DF83).copy(alpha = .48f), Offset(0f, groundY - h * .012f), Offset(w, groundY - h * .012f), strokeWidth = 2.dp.toPx())
        repeat(24) { clump ->
            val clumpX = w * (clump + .5f) / 24f
            val grassBase = groundY - h * (.008f + (clump % 3) * .002f)
            val breeze = sin(hazardPhase * PI * 2 + clump * .63).toFloat()
            val bladeCount = 4 + clump % 3
            repeat(bladeCount) { blade ->
                val root = clumpX + (blade - bladeCount / 2f) * 2.2f.dp.toPx()
                val bladeHeight = h * (.012f + ((clump * 7 + blade * 3) % 6) * .003f)
                val lean = (blade - bladeCount / 2f) * 2.5f.dp.toPx() + breeze * 3.5f.dp.toPx()
                val bladeColor = when ((clump + blade) % 4) {
                    0 -> Color(0xFFC4E477)
                    1 -> Color(0xFF87C75A)
                    2 -> Color(0xFF5DAE58)
                    else -> Color(0xFFE0D77A)
                }
                drawPath(Path().apply {
                    moveTo(root, grassBase)
                    quadraticTo(root + lean * .25f, grassBase - bladeHeight * .62f, root + lean, grassBase - bladeHeight)
                }, bladeColor.copy(alpha = .78f), style = Stroke(width = (1.2f + blade % 2).dp.toPx(), cap = StrokeCap.Round))
            }
            if (clump % 6 == 2) {
                drawCircle(Color(0xFFFFE49A), 1.7f.dp.toPx(), Offset(clumpX + 5.dp.toPx(), grassBase - h * .025f))
                drawCircle(Color(0xFFE88F9B), 1.dp.toPx(), Offset(clumpX + 5.dp.toPx(), grassBase - h * .025f))
            }
            if (clump % 4 == 1) {
                drawOval(Color(0xFFD1BE8D).copy(alpha = .70f), Offset(clumpX - 7.dp.toPx(), groundY + h * .004f), Size(5.dp.toPx(), 2.5.dp.toPx()))
            }
        }

        val courseLeft = w * .04f
        val courseWidth = w * .92f
        fun courseX(progress: Float) = courseLeft + courseWidth * ((progress - cameraLeft) / cameraWidth)
        fun obstacleStart(id: String, fallback: Float) = obstacleById[id]?.start?.div(100f) ?: fallback
        fun obstacleEnd(id: String, fallback: Float) = obstacleById[id]?.end?.div(100f) ?: fallback
        val pitStart = obstacleStart("pit", .23f)
        val pitEnd = obstacleEnd("pit", .29f)
        val pitLeft = courseX(pitStart)
        val pitRight = courseX(pitEnd)
        drawRect(Color(0xFF17242C), Offset(pitLeft, groundY - 2.dp.toPx()), Size(pitRight - pitLeft, h * .22f))
        drawRect(Color(0xFF1B2930), Offset(pitLeft - 5.dp.toPx(), groundY - 4.dp.toPx()), Size(5.dp.toPx(), h * .06f))
        drawRect(Color(0xFF1B2930), Offset(pitRight, groundY - 4.dp.toPx()), Size(5.dp.toPx(), h * .06f))
        // Spike teeth are drawn taller and below the ground line so the jumpable
        // gap stays readable at a glance. Caution color contrasts with the dark course.
        val spikeCount = 8
        repeat(spikeCount) { index ->
            val spikeWidth = (pitRight - pitLeft) / spikeCount
            val spikeX = pitLeft + spikeWidth * index
            val gap = spikeWidth * .12f
            drawPath(Path().apply {
                moveTo(spikeX + gap, groundY + h * .18f)
                lineTo(spikeX + spikeWidth * .5f, groundY + h * .06f)
                lineTo(spikeX + spikeWidth - gap, groundY + h * .18f)
                close()
            }, Color(0xFFFFB45F))
        }

        // Rivers are painted around each shuffled water obstacle so every route
        // keeps its bridge and open-water gap attached to the correct location.
        fun drawRiver(start: Float, end: Float, openGap: Boolean) {
            val riverLeft = courseX((start - .035f).coerceAtLeast(0f))
            val riverRight = courseX((end + .035f).coerceAtMost(1f))
            val riverWidth = riverRight - riverLeft
            drawRect(Color(0xFF07364D), Offset(riverLeft, groundY), Size(riverWidth, h * .24f))
            drawRect(Color(0xFF126B86).copy(alpha = .94f), Offset(riverLeft, groundY + h * .025f), Size(riverWidth, h * .20f))
            drawRect(Color(0xFF43B8C9).copy(alpha = .22f), Offset(riverLeft, groundY + h * .035f), Size(riverWidth, h * .035f))
            drawLine(Color(0xFFB4F4DE).copy(alpha = .75f), Offset(riverLeft, groundY + h * .012f), Offset(riverRight, groundY + h * .012f), strokeWidth = 2.dp.toPx())
            // Slow traveling caustics and nested ripples make the water visibly
            // flow while keeping the open crossing dark and readable.
            repeat(7) { index ->
                val travel = (hazardPhase * 1.3f + index * .19f) % 1f
                val rippleX = riverLeft + travel * riverWidth
                val rippleY = groundY + h * (.065f + (index % 3) * .045f) + sin(hazardPhase * PI * 2 + index).toFloat() * h * .008f
                val rippleWidth = riverWidth * (.035f + (index % 2) * .018f)
                drawOval(
                    Color(0xFF8BE5E1).copy(alpha = .16f + .14f * (1f - travel)),
                    Offset(rippleX - rippleWidth / 2f, rippleY - h * .008f),
                    Size(rippleWidth, h * .016f),
                    style = Stroke(width = 1.4f.dp.toPx()),
                )
                val wavePath = Path().apply {
                    moveTo(rippleX - rippleWidth, rippleY)
                    quadraticTo(rippleX - rippleWidth * .5f, rippleY - h * .012f, rippleX, rippleY)
                    quadraticTo(rippleX + rippleWidth * .5f, rippleY + h * .012f, rippleX + rippleWidth, rippleY)
                }
                drawPath(wavePath, Color(0xFFB5F2E8).copy(alpha = .38f), style = Stroke(width = 1.2f.dp.toPx(), cap = StrokeCap.Round))
            }
            repeat(5) { index ->
                val shimmerTravel = (hazardPhase * .82f + index * .23f) % 1f
                val shimmerX = riverLeft + shimmerTravel * riverWidth
                val shimmerY = groundY + h * (.12f + (index % 2) * .035f)
                val shimmerLength = riverWidth * (.055f + (index % 3) * .015f)
                drawLine(Color(0xFF6ED9D7).copy(alpha = .24f), Offset(shimmerX, shimmerY), Offset(shimmerX + shimmerLength, shimmerY - h * .008f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            if (openGap) {
                val riverGapLeft = courseX(start)
                val riverGapRight = courseX(end)
                drawRect(Color(0xFF071B27), Offset(riverGapLeft, groundY - 2.dp.toPx()), Size(riverGapRight - riverGapLeft, h * .25f))
                drawRect(Color(0xFF147A94), Offset(riverGapLeft, groundY + 2.dp.toPx()), Size(riverGapRight - riverGapLeft, h * .045f))
                drawLine(Color(0xFF86E5E2).copy(alpha = .8f), Offset(riverGapLeft, groundY + h * .015f), Offset(riverGapLeft, groundY + h * .20f), strokeWidth = 2.dp.toPx())
                drawLine(Color(0xFF86E5E2).copy(alpha = .8f), Offset(riverGapRight, groundY + h * .015f), Offset(riverGapRight, groundY + h * .20f), strokeWidth = 2.dp.toPx())
            }
        }
        drawRiver(obstacleStart("river-bridge", .43f), obstacleEnd("river-bridge", .50f), openGap = false)
        drawRiver(obstacleStart("river-gap", .56f), obstacleEnd("river-gap", .62f), openGap = true)

        // A boulder blocks the trail. Grounded runners bump back; a timed jump clears it.
        val boulderX = courseX((obstacleStart("boulder", .36f) + obstacleEnd("boulder", .39f)) / 2f)
        val boulderRadius = h * .065f
        drawCircle(Color(0xFF747D83), boulderRadius, Offset(boulderX, groundY - boulderRadius * .78f))
        drawCircle(Color(0xFF9AA3A6), boulderRadius * .62f, Offset(boulderX - boulderRadius * .2f, groundY - boulderRadius * 1.18f))
        drawCircle(Color(0xFF525D63), boulderRadius * .48f, Offset(boulderX + boulderRadius * .55f, groundY - boulderRadius * .65f))
        drawCircle(Color(0xFFC2C9C8), boulderRadius * .13f, Offset(boulderX - boulderRadius * .28f, groundY - boulderRadius * 1.38f))

        val bridgeStart = obstacleStart("river-bridge", .43f)
        val bridgeEnd = obstacleEnd("river-bridge", .50f)
        val tileLeft = courseX(bridgeStart)
        val tileRight = courseX(bridgeEnd)
        val tileCount = 6
        val bridgeRunners = animatedPlayers.filter { it.progress in bridgeStart..bridgeEnd }
        val bridgeWidth = tileRight - tileLeft
        val bridgeWavePhase = hazardPhase * PI * 2
        val bridgeFlex = .014f + (.004f * (.5f + .5f * sin(bridgeWavePhase).toFloat())) + bridgeRunners.size * .0015f
        fun bridgeWaveY(t: Float) = sin(t * PI).toFloat() * h * bridgeFlex
        // Draw the woven side ropes first so the planks sit visibly over them.
        val upperRail = Path().apply {
            moveTo(tileLeft, groundY - h * .070f + bridgeWaveY(0f))
            for (step in 1..24) {
                val t = step / 24f
                lineTo(tileLeft + bridgeWidth * t, groundY - h * .070f + bridgeWaveY(t))
            }
        }
        val lowerRail = Path().apply {
            moveTo(tileLeft, groundY + h * .045f + bridgeWaveY(0f) * .45f)
            for (step in 1..24) {
                val t = step / 24f
                lineTo(tileLeft + bridgeWidth * t, groundY + h * .045f + bridgeWaveY(t) * .45f)
            }
        }
        drawPath(upperRail, Color(0xFF55351F), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(upperRail, Color(0xFFC58A4D).copy(alpha = .82f), style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
        drawPath(lowerRail, Color(0xFF765039), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        repeat(tileCount) { index ->
            val tileWidth = bridgeWidth / tileCount
            val tileX = tileLeft + tileWidth * index
            val tileColor = if (index % 2 == 0) Color(0xFFD7A76B) else Color(0xFFC38C55)
            val tileProgress = bridgeStart + (bridgeEnd - bridgeStart) * (index + .5f) / tileCount
            val pressure = bridgeRunners.maxOfOrNull { runner ->
                (1f - abs(runner.progress - tileProgress) / ((bridgeEnd - bridgeStart) / tileCount * .8f)).coerceIn(0f, 1f)
            } ?: 0f
            val wave = bridgeWaveY((index + .5f) / tileCount)
            val sway = sin(bridgeWavePhase + index * .82f).toFloat()
            val plankDrop = pressure * h * .018f
            val plankTilt = sway * (1.8f + pressure * 5f)
            val plankTop = groundY - h * .026f + wave + plankDrop
            val plankHeight = h * .052f
            rotate(plankTilt, Offset(tileX + tileWidth * .5f, groundY)) {
                drawRoundRect(
                    Color(0xFF482F24).copy(alpha = .48f),
                    Offset(tileX + 1.dp.toPx(), plankTop + h * .012f),
                    Size(tileWidth - 2.dp.toPx(), plankHeight),
                    CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    tileColor,
                    Offset(tileX + 2.dp.toPx(), plankTop),
                    Size(tileWidth - 4.dp.toPx(), plankHeight),
                    CornerRadius(4.dp.toPx()),
                )
                drawLine(Color(0xFFFFD99C).copy(alpha = .55f), Offset(tileX + 5.dp.toPx(), plankTop + 2.dp.toPx()), Offset(tileX + tileWidth - 6.dp.toPx(), plankTop + 2.dp.toPx()), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Color(0xFF8F6038).copy(alpha = .72f), Offset(tileX + tileWidth * .18f, plankTop + plankHeight * .68f), Offset(tileX + tileWidth * .43f, plankTop + plankHeight * .58f), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Color(0xFF5A4335), 1.4.dp.toPx(), Offset(tileX + 5.dp.toPx(), plankTop + plankHeight * .5f))
                drawCircle(Color(0xFF5A4335), 1.4.dp.toPx(), Offset(tileX + tileWidth - 5.dp.toPx(), plankTop + plankHeight * .5f))
            }
            val t = (index + .5f) / tileCount
            val railY = groundY - h * .070f + bridgeWaveY(t)
            drawLine(Color(0xFF9A6B42), Offset(tileX + tileWidth * .5f, railY), Offset(tileX + tileWidth * .5f, plankTop + plankHeight * .20f), strokeWidth = 1.5.dp.toPx())
        }

        val windLeft = courseX(obstacleStart("wind-tunnel", .67f))
        val windRight = courseX(obstacleEnd("wind-tunnel", .75f))
        drawRoundRect(Color(0xFF3AA6A0).copy(alpha = .16f), Offset(windLeft, groundY - h * .31f), Size(windRight - windLeft, h * .31f), CornerRadius(18.dp.toPx()))
        repeat(3) { index ->
            val gustY = groundY - h * (.12f + index * .065f)
            drawLine(Color(0xFF9DE4E8).copy(alpha = .75f), Offset(windLeft + 7.dp.toPx(), gustY), Offset(windRight - 8.dp.toPx(), gustY - h * .025f), strokeWidth = 2.dp.toPx())
        }

        // Late-race lava crossing: a pulsing molten pool and independently
        // flickering flames make the hazard readable before players reach it.
        val lavaLeft = courseX(obstacleStart("lava", .84f))
        val lavaRight = courseX(obstacleEnd("lava", .92f))
        val lavaPulse = .5f + .5f * sin(hazardPhase * PI * 2).toFloat()
        val lavaWidth = lavaRight - lavaLeft
        drawOval(Color(0xFFFF4B20).copy(alpha = .10f + lavaPulse * .08f), Offset(lavaLeft - lavaWidth * .12f, groundY - h * .11f), Size(lavaWidth * 1.24f, h * .20f))
        drawRect(Color(0xFF381D27), Offset(lavaLeft, groundY - h * .035f), Size(lavaWidth, h * .24f))
        drawRect(Color(0xFF8E2D1D).copy(alpha = .88f), Offset(lavaLeft, groundY + h * .015f), Size(lavaWidth, h * .18f))
        drawRect(Color(0xFFFF5A24).copy(alpha = .10f + lavaPulse * .17f), Offset(lavaLeft, groundY - h * (.07f + lavaPulse * .025f)), Size(lavaWidth, h * .045f))
        drawLine(Color(0xFFFF6B35).copy(alpha = .72f + lavaPulse * .28f), Offset(lavaLeft, groundY - h * .035f), Offset(lavaRight, groundY - h * .035f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        // Molten seams drift under the surface, with broken highlights like
        // painted strokes rather than a flat solid rectangle.
        repeat(4) { seam ->
            val travel = (hazardPhase * .72f + seam * .27f) % 1f
            val seamX = lavaLeft + travel * lavaWidth
            val seamY = groundY + h * (.07f + (seam % 3) * .038f) + sin(hazardPhase * PI * 2 + seam * 1.4).toFloat() * h * .009f
            val seamPath = Path().apply {
                moveTo(seamX - lavaWidth * .09f, seamY)
                quadraticTo(seamX - lavaWidth * .025f, seamY - h * .012f, seamX + lavaWidth * .025f, seamY + h * .003f)
                quadraticTo(seamX + lavaWidth * .07f, seamY + h * .014f, seamX + lavaWidth * .11f, seamY - h * .002f)
            }
            drawPath(seamPath, Color(0xFFFFC05C).copy(alpha = .28f + lavaPulse * .24f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        repeat(7) { index ->
            val flameWidth = lavaWidth / 7f
            val flameX = lavaLeft + flameWidth * index
            val flamePulse = .5f + .5f * sin(hazardPhase * PI * 2 + index * 1.7).toFloat()
            val sway = sin(hazardPhase * PI * 2 + index * 2.1).toFloat() * flameWidth * .12f
            val flameBase = groundY - h * .025f
            val peakY = groundY - h * (.11f + flamePulse * .095f)
            val outerFlame = Path().apply {
                moveTo(flameX + sway, flameBase)
                quadraticTo(flameX - flameWidth * .04f + sway, peakY + h * .07f, flameX + flameWidth * .50f + sway, peakY)
                quadraticTo(flameX + flameWidth * .91f + sway, peakY + h * .07f, flameX + flameWidth + sway, flameBase)
                close()
            }
            drawPath(outerFlame, (if (index % 2 == 0) Color(0xFFFF9638) else Color(0xFFFF5234)).copy(alpha = .78f + flamePulse * .22f))
            val innerPeak = peakY + h * .055f
            drawPath(Path().apply {
                moveTo(flameX + flameWidth * .22f + sway, flameBase - h * .006f)
                quadraticTo(flameX + flameWidth * .30f + sway, innerPeak + h * .045f, flameX + flameWidth * .52f + sway, innerPeak)
                quadraticTo(flameX + flameWidth * .72f + sway, innerPeak + h * .04f, flameX + flameWidth * .79f + sway, flameBase - h * .006f)
                close()
            }, Color(0xFFFFD166).copy(alpha = .42f + flamePulse * .38f))
        }
        repeat(9) { ember ->
            val emberPhase = (hazardPhase * (1.05f + (ember % 3) * .18f) + ember * .137f) % 1f
            val emberX = lavaLeft + lavaWidth * ((ember * .618f + hazardPhase * .13f) % 1f)
            val emberY = groundY - h * (.045f + emberPhase * (.11f + (ember % 3) * .018f))
            val emberAlpha = (sin(emberPhase * PI).toFloat() * .7f + .15f).coerceIn(.12f, .82f)
            val emberDrift = sin(hazardPhase * PI * 2 + ember).toFloat() * h * .008f
            drawCircle(Color(0xFFFFD166).copy(alpha = emberAlpha), h * (.003f + (ember % 3) * .0012f), Offset(emberX + emberDrift, emberY))
            if (ember % 2 == 0) {
                drawCircle(Color(0xFFFF7A32).copy(alpha = emberAlpha * .30f), h * .010f, Offset(emberX + emberDrift, emberY))
            }
        }

        // Checkpoint flags and the finish arch give players landmarks as they move.
        listOf(.08f, .19f, .30f, .40f, .50f, .64f, .80f).forEachIndexed { index, progress ->
            val markerX = courseX(progress)
            drawLine(Color(0xFFB6D4D0), Offset(markerX, groundY - h * .17f), Offset(markerX, groundY), strokeWidth = 2.dp.toPx())
            drawPath(Path().apply {
                moveTo(markerX, groundY - h * .17f)
                lineTo(markerX + h * .055f, groundY - h * .14f)
                lineTo(markerX, groundY - h * .11f)
                close()
            }, if (index == 0) Color(0xFF66E0CB) else Color(0xFFE4E9E8))
        }
        val finishX = courseX(.995f)
        drawLine(Color(0xFFFFD166), Offset(finishX, groundY - h * .25f), Offset(finishX, groundY), strokeWidth = 5.dp.toPx())
        drawLine(Color(0xFFFFD166), Offset(finishX, groundY - h * .25f), Offset(finishX + h * .12f, groundY - h * .25f), strokeWidth = 5.dp.toPx())
        repeat(4) { index ->
            val checkerX = finishX + h * (.015f + (index % 2) * .03f)
            val checkerY = groundY - h * (.24f - (index / 2) * .055f)
            drawRect(if ((index + index / 2) % 2 == 0) Color.White else Color(0xFF263238), Offset(checkerX, checkerY), Size(h * .03f, h * .055f))
        }

        val runnerSize = (w * .075f).coerceIn(28.dp.toPx(), 50.dp.toPx())
        val trackY = h * .12f
        drawLine(Color(0xFF65847D), Offset(courseLeft, trackY), Offset(courseLeft + courseWidth, trackY), strokeWidth = 4.dp.toPx())
        animatedPlayers.forEachIndexed { index, player ->
            val x = courseX(player.progress)
            val playerHeight = (player.height / 12f).coerceIn(-.35f, .5f) * h
            // runnerY is the runner's upper drawing anchor; shoes extend .16 size below it.
            // Place that bottom edge on the track when the server reports height zero.
            val runnerY = groundY - runnerSize * .16f - playerHeight
            val shadowScale = (1f - abs(playerHeight) / (h * .5f)).coerceIn(.35f, 1f)
            drawOval(
                color = Color(0xFF10271D).copy(alpha = .34f * shadowScale),
                topLeft = Offset(x - runnerSize * .62f * shadowScale, groundY - runnerSize * .10f),
                size = Size(runnerSize * 1.24f * shadowScale, runnerSize * .20f),
            )
            if (player.progress in .56f..62f && player.height < 0f) {
                repeat(4) { bubbleIndex ->
                    val bubblePhase = (hazardPhase + bubbleIndex * .24f) % 1f
                    val bubbleX = x + sin(bubbleIndex * 2.4 + hazardPhase * PI * 2).toFloat() * runnerSize * .58f
                    val bubbleY = runnerY + runnerSize * (.15f - bubblePhase * 1.7f)
                    drawCircle(Color(0xFF9AF3F4).copy(alpha = .35f + bubblePhase * .45f), runnerSize * (.05f + bubblePhase * .035f), Offset(bubbleX, bubbleY))
                }
            }
            if (index > 0) {
                val previous = animatedPlayers[index - 1]
                val previousX = courseX(previous.progress)
                val previousHeight = (previous.height / 12f).coerceIn(-.35f, .5f) * h
                val previousY = groundY - runnerSize * .96f - previousHeight
                val gap = (previous.progress - player.progress).coerceAtLeast(0f)
                val tension = (gap * 100f / 7.5f).coerceIn(0f, 1f)
                val ropeColor = when {
                    tension > .8f -> Color(0xFFFFB45F)
                    tension > .55f -> Color(0xFF9DE4E8)
                    else -> Color(0xFF66E0CB)
                }
                val ropeEndY = runnerY - runnerSize * .8f
                val slack = (1f - tension).coerceIn(0f, 1f)
                val gentleSway = sin(hazardPhase * PI * 2 + index * .55).toFloat() * runnerSize * .035f * slack
                val ropeSag = runnerSize * (.025f + slack * .42f)
                val ropePath = Path().apply {
                    moveTo(previousX, previousY)
                    quadraticTo(
                        (previousX + x) / 2f,
                        (previousY + ropeEndY) / 2f + ropeSag + gentleSway,
                        x,
                        ropeEndY,
                    )
                }
                drawPath(
                    path = ropePath,
                    color = ropeColor,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            val costume = listOf(Color(0xFF8B6CE8), Color(0xFFE17875), Color(0xFF53B8A7), Color(0xFFF0A848), Color(0xFF6AA8E5))[index % 5]
            val characterAlpha = 1f
            val runnerCostume = costume.copy(alpha = characterAlpha)
            if (team.surgeActive) {
                drawCircle(Color(0xFF66E0CB).copy(alpha = .15f + hazardPhase * .12f), runnerSize * 1.18f, Offset(x, runnerY - runnerSize * .48f))
                repeat(3) { streak ->
                    val streakY = runnerY - runnerSize * (.3f + streak * .28f)
                    drawLine(
                        Color(0xFF9DE4E8).copy(alpha = .48f),
                        Offset(x - runnerSize * (1.15f + streak * .2f), streakY),
                        Offset(x - runnerSize * .63f, streakY + runnerSize * .08f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            // Toy runners swing their arms and legs while sprinting, brace into gusts,
            // and glow briefly when their team earns a coordinated speed burst.
            val gait = when {
                player.jumping -> sin(hazardPhase * PI * 5 + index * .7).toFloat() * runnerSize * .28f
                player.grounded && player.velocity > .45f ->
                    sin(hazardPhase * PI * 4 + index * .7).toFloat() * runnerSize * .24f
                else -> 0f
            }
            drawRoundRect(Color(0xFF17242C).copy(alpha = .65f), Offset(x - runnerSize * .44f, runnerY - runnerSize * .77f), Size(runnerSize * .88f, runnerSize * .73f), CornerRadius(runnerSize * .27f))
            drawRoundRect(runnerCostume, Offset(x - runnerSize * .39f, runnerY - runnerSize * .73f), Size(runnerSize * .78f, runnerSize * .64f), CornerRadius(runnerSize * .23f))
            drawRoundRect(Color.White.copy(alpha = .62f), Offset(x - runnerSize * .12f, runnerY - runnerSize * .68f), Size(runnerSize * .13f, runnerSize * .49f), CornerRadius(runnerSize * .06f))
            drawRoundRect(Color(0xFF28323A), Offset(x - runnerSize * .45f, runnerY - runnerSize * .57f), Size(runnerSize * .12f, runnerSize * .30f), CornerRadius(runnerSize * .04f))
            drawCircle(Color(0xFF8B572C).copy(alpha = characterAlpha), runnerSize * .42f, Offset(x, runnerY - runnerSize * 1.05f + 1.dp.toPx()))
            drawCircle(Color(0xFFFFC857).copy(alpha = characterAlpha), runnerSize * .37f, Offset(x, runnerY - runnerSize * 1.05f))
            drawPath(
                path = Path().apply {
                    moveTo(x - runnerSize * .36f, runnerY - runnerSize * 1.02f)
                    quadraticTo(x - runnerSize * .38f, runnerY - runnerSize * 1.48f, x, runnerY - runnerSize * 1.43f)
                    quadraticTo(x + runnerSize * .40f, runnerY - runnerSize * 1.40f, x + runnerSize * .36f, runnerY - runnerSize * 1.02f)
                    close()
                },
                color = Color(0xFF49352E).copy(alpha = characterAlpha),
            )
            drawCircle(Color(0xFF17242C).copy(alpha = characterAlpha), runnerSize * .045f, Offset(x + runnerSize * .15f, runnerY - runnerSize * 1.07f))
            drawCircle(Color(0xFF17242C).copy(alpha = characterAlpha), runnerSize * .045f, Offset(x - runnerSize * .15f, runnerY - runnerSize * 1.07f))
            val armSwing = if (player.bracing) 0f else gait
            val leftHand = Offset(x - runnerSize * .55f, runnerY - runnerSize * .37f + armSwing)
            val rightHand = Offset(x + runnerSize * .55f, runnerY - runnerSize * .37f - armSwing)
            drawLine(runnerCostume, Offset(x - runnerSize * .34f, runnerY - runnerSize * .58f), leftHand, strokeWidth = 2.5.dp.toPx())
            drawLine(runnerCostume, Offset(x + runnerSize * .34f, runnerY - runnerSize * .58f), rightHand, strokeWidth = 2.5.dp.toPx())
            drawCircle(Color(0xFFFFC857).copy(alpha = characterAlpha), runnerSize * .10f, leftHand)
            drawCircle(Color(0xFFFFC857).copy(alpha = characterAlpha), runnerSize * .10f, rightHand)
            drawLine(runnerCostume, Offset(x - runnerSize * .18f, runnerY - runnerSize * .1f), Offset(x - runnerSize * .28f - gait, runnerY + runnerSize * .08f), strokeWidth = 2.5.dp.toPx())
            drawLine(runnerCostume, Offset(x + runnerSize * .18f, runnerY - runnerSize * .1f), Offset(x + runnerSize * .28f + gait, runnerY + runnerSize * .08f), strokeWidth = 2.5.dp.toPx())
            drawRoundRect(
                (if (player.bracing) Color(0xFFB7D9FF) else Color(0xFF252F37)).copy(alpha = characterAlpha),
                Offset(x - runnerSize * .38f, runnerY - runnerSize * .02f),
                Size(runnerSize * .34f, runnerSize * .18f),
                CornerRadius(runnerSize * .08f),
            )
            drawRoundRect((if (player.bracing) Color(0xFFB7D9FF) else Color(0xFF252F37)).copy(alpha = characterAlpha), Offset(x + runnerSize * .04f, runnerY - runnerSize * .02f), Size(runnerSize * .34f, runnerSize * .18f), CornerRadius(runnerSize * .08f))

            val playerName = player.name.trim().ifBlank { "Player" }
            val pingLabel = player.pingMs?.let { "${it}ms" } ?: "—ms"
            playerPingPaint.color = when {
                player.pingMs == null -> android.graphics.Color.LTGRAY
                player.pingMs < 150 -> android.graphics.Color.rgb(119, 224, 181)
                player.pingMs < 300 -> android.graphics.Color.rgb(255, 209, 102)
                else -> android.graphics.Color.rgb(255, 133, 133)
            }
            val labelPadding = 7.dp.toPx()
            val labelGap = 6.dp.toPx()
            val pingWidth = playerPingPaint.measureText(pingLabel)
            val maxLabelWidth = w * .38f
            val nameMaxWidth = (maxLabelWidth - labelPadding * 2 - labelGap - pingWidth).coerceAtLeast(12.dp.toPx())
            var playerLabel = playerName
            while (playerLabel.length > 1 && playerNamePaint.measureText(playerLabel) > nameMaxWidth) {
                playerLabel = playerLabel.dropLast(2) + "…"
            }
            val nameWidth = playerNamePaint.measureText(playerLabel).coerceAtMost(nameMaxWidth)
            val labelWidth = (nameWidth + labelPadding * 2 + labelGap + pingWidth).coerceAtMost(maxLabelWidth)
            val labelHeight = 17.dp.toPx()
            val labelTop = (runnerY - runnerSize * 2.15f).coerceAtLeast(3.dp.toPx())
            val labelLeft = (x - labelWidth / 2f).coerceIn(0f, (w - labelWidth).coerceAtLeast(0f))
            drawRoundRect(
                color = Color(0xE6102028),
                topLeft = Offset(labelLeft, labelTop),
                size = Size(labelWidth, labelHeight),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            drawRoundRect(
                color = costume.copy(alpha = .95f),
                topLeft = Offset(labelLeft, labelTop),
                size = Size(2.dp.toPx(), labelHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            val labelBaseline = labelTop + (labelHeight - (playerNamePaint.ascent() + playerNamePaint.descent())) / 2f
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(playerLabel, labelLeft + labelPadding, labelBaseline, playerNamePaint)
                canvas.nativeCanvas.drawText(pingLabel, labelLeft + labelPadding + nameWidth + labelGap, labelBaseline, playerPingPaint)
            }
        }
        val markerX = courseX(team.progress).coerceIn(courseLeft, courseLeft + courseWidth)
        drawCircle(Color(0xFF66E0CB), 7.dp.toPx(), Offset(markerX, trackY))

        effect?.let { impact ->
            val progress = impactProgress.value.coerceIn(0f, 1f)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val impactX = courseX(impact.progress).coerceIn(courseLeft, courseLeft + courseWidth)
            val waterImpact = impact.type == "water-splash"
            val bridgeBreak = impact.type == "bridge-break"
            val teamRespawn = impact.type == "team-respawn"
            val origin = Offset(impactX, when {
                waterImpact -> groundY + h * .015f
                bridgeBreak -> groundY - h * .025f
                else -> groundY - h * .035f
            })
            val particleColor = when {
                waterImpact -> Color(0xFF9AF3F4)
                bridgeBreak -> Color(0xFFD7A76B)
                teamRespawn -> Color(0xFF66E0CB)
                else -> Color(0xFFFFA13D)
            }
            if (!bridgeBreak) {
                drawCircle(
                    color = particleColor.copy(alpha = alpha * .8f),
                    radius = h * (.015f + progress * (if (waterImpact) .10f else .06f)),
                    center = origin,
                    style = Stroke(width = (2f + 3f * alpha).dp.toPx()),
                )
            }
            repeat(if (waterImpact) 9 else 7) { index ->
                val angle = (index.toDouble() / 8.0 * PI) - PI
                val spread = h * (.025f + progress * (if (waterImpact) .11f else .075f))
                val rise = h * progress * (if (waterImpact) .14f else .19f)
                val particle = Offset(
                    origin.x + cos(angle).toFloat() * spread,
                    origin.y + sin(angle).toFloat() * spread + if (bridgeBreak) progress * h * .12f else -rise,
                )
                if (bridgeBreak) {
                    rotate(index * 31f + progress * 140f, particle) {
                        drawRoundRect(
                            particleColor.copy(alpha = alpha),
                            Offset(particle.x - h * .018f, particle.y - h * .006f),
                            Size(h * .036f, h * .012f),
                            CornerRadius(h * .003f),
                        )
                    }
                } else {
                    drawCircle(
                        color = when {
                            waterImpact || teamRespawn -> particleColor.copy(alpha = alpha)
                            else -> (if (index % 2 == 0) Color(0xFFFFD166) else Color(0xFFFF512F)).copy(alpha = alpha)
                        },
                        radius = h * (.006f + (index % 3) * .002f),
                        center = particle,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultContent(
    state: RopeboundSnapshot,
    busy: Boolean,
    error: String?,
    onRematch: () -> Unit,
    onExit: () -> Unit,
) {
    val celebrates = state.winner != null
    val headline = when {
        state.winner == "tie" -> "It’s a tie!"
        state.winner != null && state.winner == state.viewerTeam -> "Your team wins!"
        state.winner != null -> "Team ${state.winner} wins!"
        else -> "Race ended"
    }
    val details = when {
        state.winner == "tie" -> "Both teams crossed the finish together."
        state.winner != null && state.winner == state.viewerTeam -> "Your team made it to the finish first. Nice run!"
        state.winner != null && state.notice.contains("ran out of rescues", ignoreCase = true) -> state.notice
        state.winner != null -> "Your team can try again in a rematch."
        else -> state.notice.ifBlank { "The race ended before the finish." }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (celebrates) WinnerConfetti(Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (celebrates) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WinnerPartyPopper()
                    Text(headline, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    WinnerPartyPopper(reversed = true)
                }
            } else {
                Text(headline, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(details, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.durationSeconds > 0) {
                val ownSurges = if (state.viewerTeam == "A") state.surgesA else state.surgesB
                val ownRescues = if (state.viewerTeam == "A") state.teamA.rescues else state.teamB.rescues
                Row(
                    modifier = Modifier.fillMaxWidth(.72f).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResultStatCard("TIME", formatRaceTime(state.durationSeconds), Modifier.weight(1f))
                    ResultStatCard("SQUAD SURGES", ownSurges.toString(), Modifier.weight(1f))
                    ResultStatCard("RESCUES LEFT", ownRescues.toString(), Modifier.weight(1f))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRematch, enabled = state.isHost && !busy) {
                    Text(if (state.isHost) "Run it back" else "Waiting for host")
                }
                OutlinedButton(onClick = onExit, enabled = !busy) { Text("Back to voice room") }
            }
        }
    }
}

@Composable
private fun SquadSurgeMeter(team: RopeboundTeam) {
    val charge by animateFloatAsState(
        targetValue = if (team.surgeActive) 1f else team.surgeCharge.coerceIn(0f, 1f),
        animationSpec = tween(220),
        label = "squad-surge-charge",
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                team.surgeActive -> "SQUAD SURGE · SPRINT!"
                team.count < 2 -> "ADD A TEAMMATE TO UNLOCK SQUAD SURGE"
                else -> "RUN TOGETHER · SQUAD SURGE ${(charge * 100).toInt()}%"
            },
            modifier = Modifier.weight(1f),
            color = if (team.surgeActive) Color(0xFFFFD166) else Color(0xFF9DE4E8),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "RESCUES ${team.rescues}/3",
            modifier = Modifier.padding(start = 8.dp),
            color = if (team.rescues <= 1) Color(0xFFFFA8A8) else Color(0xFFFFD166),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(5.dp)) {
        drawRoundRect(Color.White.copy(alpha = .16f), cornerRadius = CornerRadius(size.height / 2f))
        if (charge > 0f) {
            drawRoundRect(
                color = if (team.surgeActive) Color(0xFFFFD166) else Color(0xFF66E0CB),
                size = Size(size.width * charge, size.height),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
    }
}

@Composable
private fun ResultStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .82f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatRaceTime(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun WinnerPartyPopper(reversed: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "winner-popper")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "winner-popper-pulse",
    )
    Text(
        text = "🎉",
        fontSize = 27.sp,
        modifier = Modifier.graphicsLayer {
            scaleX = pulse
            scaleY = pulse
            rotationZ = if (reversed) -8f else 8f
        },
    )
}

@Composable
private fun WinnerConfetti(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "winner-confetti")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600),
            repeatMode = RepeatMode.Restart,
        ),
        label = "winner-confetti-fall",
    )
    val colors = listOf(
        Color(0xFF62E6CE),
        Color(0xFFFFD166),
        Color(0xFFFF6B6B),
        Color(0xFFB9A4FF),
        Color(0xFF9BE564),
    )

    Canvas(modifier = modifier) {
        val pieceCount = 30
        val pieceWidth = (size.width * 0.008f).coerceIn(4.dp.toPx(), 9.dp.toPx())
        val pieceHeight = pieceWidth * 0.55f
        repeat(pieceCount) { index ->
            val fall = (progress + index.toFloat() / pieceCount) % 1f
            val column = ((index * 17) % pieceCount).toFloat() / pieceCount
            val sway = sin((fall * 2f * PI + index * 0.7f)).toFloat() * size.width * 0.018f
            val center = Offset(
                x = (size.width * column + sway).coerceIn(0f, size.width),
                y = fall * size.height,
            )
            val fade = if (fall > 0.84f) ((1f - fall) / 0.16f).coerceIn(0f, 1f) else 1f
            rotate(
                degrees = (fall * 720f + index * 31f) % 360f,
                pivot = center,
            ) {
                drawRoundRect(
                    color = colors[index % colors.size].copy(alpha = fade * 0.78f),
                    topLeft = Offset(center.x - pieceWidth / 2f, center.y - pieceHeight / 2f),
                    size = Size(pieceWidth, pieceHeight),
                    cornerRadius = CornerRadius(pieceHeight / 2f, pieceHeight / 2f),
                )
            }
        }
    }
}
