package com.squadvoice.app.game

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
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

    val liveControlFeed = remember(roomCode, participantToken) { RopeboundLiveControlFeed() }
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
                            snapshot = event.snapshot
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
        val m = move.coerceIn(-1f, 1f)
        val s = steer.coerceIn(-1f, 1f)
        liveControlFeed.move = m
        liveControlFeed.steer = s
        controls.update { it.copy(move = m, steer = s) }
        inputTrigger.trySend(Unit)
        if (abs(m) > .12f || abs(s) > .12f) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
    }

    fun setBrace(pressed: Boolean) {
        liveControlFeed.bracing = pressed
        controls.update { it.copy(bracing = pressed) }
        inputTrigger.trySend(Unit)
    }

    fun openGameSettings() {
        liveControlFeed.move = 0f
        liveControlFeed.steer = 0f
        liveControlFeed.bracing = false
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
        val localJumpAgeMs = if (localJumpStartedAt == 0L) Long.MAX_VALUE else (now - localJumpStartedAt)
        val locallyAirborne = localJumpAgeMs < 1_280L
        if (locallyAirborne || now < jumpRequestBlockedUntil || (!ownPlayer.grounded && localJumpAgeMs < 1_450L)) return
        jumpRequestBlockedUntil = now + 1_260L
        localJumpStartedAt = now
        liveControlFeed.jumpStartedAtMs = now
        controls.update { it.copy(jumpSequence = it.jumpSequence + 1) }
        inputTrigger.trySend(Unit)
        gameAudio.play(RopeboundGameAudio.Cue.JUMP)
    }

    LaunchedEffect(snapshot?.phase, snapshot?.viewerSpectating) {
        if (snapshot?.phase != "race" || snapshot?.viewerSpectating == true) {
            liveControlFeed.move = 0f
            liveControlFeed.steer = 0f
            liveControlFeed.bracing = false
            controls.value = GameControlState()
            inputTrigger.trySend(Unit)
            gameAudio.stopFootsteps()
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
            if (current.jumpSequence > sentJumpSequence) jumpPulseUntil = maxOf(jumpPulseUntil, now + 220L)
            val jumpActive = now < jumpPulseUntil
            if (abs(current.move) > .12f || abs(current.steer) > .12f) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
            val active = abs(current.move) > .04f || abs(current.steer) > .04f || current.bracing || jumpActive
            val needsSend = active || current.move != lastMove || current.steer != lastSteer || current.bracing != lastBrace || jumpActive != lastJump

            if (needsSend) {
                val sinceLastSend = now - lastSentAt
                if (lastSentAt != 0L && sinceLastSend < 34L) {
                    delay(34L - sinceLastSend)
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
            val waitTimeout = if (active) 34L else 120L
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

                    val leadProgress = (rawMeters / 100f).coerceIn(0.02f, .99f)
                    val updated = team.players.mapIndexed { idx, player ->
                        if (player.id == identity) {
                            player.copy(
                                progress = leadProgress,
                                lateralOffset = (player.lateralOffset + lateralVelocity * deltaSeconds).coerceIn(-1.25f, 1.25f),
                                height = jumpHeight,
                                velocity = hypot(forwardVelocity, lateralVelocity),
                                grounded = !jumping,
                                jumping = jumping,
                                bracing = input.bracing,
                            )
                        } else {
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
            animationSpec = tween(durationMillis = 320),
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
                    liveControlFeed = liveControlFeed,
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
    liveControlFeed: RopeboundLiveControlFeed? = null,
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
    var cameraMode by remember { mutableStateOf(0) }
    var orbitYawOffset by remember { mutableStateOf(0f) }
    var orbitPitchOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        delay(360)
        minimumLoadingTimeComplete = true
    }
    LaunchedEffect(playgroundReady, minimumLoadingTimeComplete) {
        if (playgroundReady && minimumLoadingTimeComplete) {
            gameUiVisible = true
            delay(110)
            splashVisible = false
        }
    }
    val cameraBlend by animateFloatAsState(
        targetValue = if (!gameUiVisible) .42f else if (cameraMode == 2) 1f else 0f,
        animationSpec = tween(durationMillis = 560),
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
            liveControlFeed = liveControlFeed,
            previewMode = previewMode,
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
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(320)),
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
