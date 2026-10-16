package com.squadvoice.app.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.squadvoice.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class GameControlState(
    val backHeld: Boolean = false,
    val runHeld: Boolean = false,
    val bracing: Boolean = false,
    val jumpSequence: Long = 0,
) {
    val move: Int
        get() = when {
            backHeld && !runHeld -> -1
            runHeld && !backHeld -> 1
            else -> 0
        }
}

/** Manages the game connection, controls, and lobby/race/results UI within an active voice room. */
@Composable
internal fun RopeboundGameScreen(
    roomCode: String,
    participantToken: String,
    microphoneEnabled: Boolean,
    onToggleMicrophone: () -> Unit,
    onBack: () -> Unit,
) {
    val api = remember { RopeboundApi() }
    val scope = rememberCoroutineScope()
    val identity = remember(participantToken) { participantIdentity(participantToken) }
    var snapshot by remember(roomCode) { mutableStateOf<RopeboundSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val controls = remember(roomCode, participantToken) { MutableStateFlow(GameControlState()) }

    LaunchedEffect(roomCode, participantToken) {
        while (true) {
            try {
                snapshot = api.join(roomCode, participantToken)
                error = null
                api.events(roomCode, participantToken).collect { current ->
                    snapshot = current
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Couldn't connect to the Ropebound lobby."
                delay(1_000)
            }
        }
    }

    /** Runs one lobby or results request at a time, updating the snapshot or displayed error. */
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

    /** Updates the held back/run control for direction -1 or 1. */
    fun setMove(direction: Int, pressed: Boolean) {
        controls.update { current ->
            when (direction) {
                -1 -> current.copy(backHeld = pressed)
                1 -> current.copy(runHeld = pressed)
                else -> current
            }
        }
    }

    /** Updates whether the brace control is held. */
    fun setBrace(pressed: Boolean) {
        controls.update { it.copy(bracing = pressed) }
    }

    /** Queues a jump pulse by advancing the local jump sequence. */
    fun jump() {
        controls.update { it.copy(jumpSequence = it.jumpSequence + 1) }
    }

    LaunchedEffect(snapshot?.phase) {
        if (snapshot?.phase != "race") controls.value = GameControlState()
    }

    LaunchedEffect(roomCode, participantToken, snapshot?.phase) {
        if (snapshot?.phase != "race") return@LaunchedEffect

        var sequence = System.currentTimeMillis() * 1_000L
        var lastMove: Int? = null
        var lastBrace: Boolean? = null
        var sentJumpSequence = controls.value.jumpSequence
        var releaseJumpPulse = false

        while (true) {
            val current = controls.value
            val jumpPending = current.jumpSequence > sentJumpSequence
            val sendJumpPulse = jumpPending && !releaseJumpPulse
            val active = current.move != 0 || current.bracing
            val needsSend = active || current.move != lastMove || current.bracing != lastBrace || sendJumpPulse || releaseJumpPulse

            if (needsSend) {
                try {
                    api.input(
                        roomCode = roomCode,
                        participantToken = participantToken,
                        seq = ++sequence,
                        move = current.move,
                        jump = sendJumpPulse,
                        brace = current.bracing,
                    )
                    lastMove = current.move
                    lastBrace = current.bracing
                    if (sendJumpPulse) {
                        sentJumpSequence = current.jumpSequence
                        releaseJumpPulse = true
                    } else if (releaseJumpPulse) {
                        releaseJumpPulse = false
                    }
                    error = null
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "Game controls could not reach the server."
                    delay(250)
                }
            }
            delay(if (active) 100 else 60)
        }
    }

    /** Attempts to leave the game for up to 1.5 seconds before returning to the voice room. */
    fun exitGame() {
        scope.launch {
            withTimeoutOrNull(1_500) { runCatching { api.leave(roomCode, participantToken) } }
            onBack()
        }
    }

    val state = snapshot
    val ownTeam = when (state?.viewerTeam) {
        "A" -> state.teamA
        "B" -> state.teamB
        else -> null
    }
    val ownPlayer = ownTeam?.players?.firstOrNull { it.id == identity }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ROPEBOUND · 2D", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(if (state?.phase == "race") "Training Yard" else "Game lobby", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onToggleMicrophone) { Text(if (microphoneEnabled) "Mic on" else "Mic muted") }
                OutlinedButton(onClick = ::exitGame, enabled = !busy) { Text("Back to voice") }
            }

            when {
                state == null -> LoadingLobby(error)
                state.phase == "lobby" -> LobbyContent(
                    modifier = Modifier.weight(1f),
                    state = state,
                    busy = busy,
                    error = error,
                    ready = ownPlayer?.ready ?: false,
                    onChooseTeam = { team -> action { api.chooseTeam(roomCode, participantToken, team) } },
                    onReady = { ready -> action { api.setReady(roomCode, participantToken, ready) } },
                    onStart = { action { api.start(roomCode, participantToken) } },
                )
                state.phase == "race" -> RaceContent(
                    modifier = Modifier.weight(1f),
                    state = state,
                    error = error,
                    viewerIdentity = identity,
                    onMove = ::setMove,
                    onJump = ::jump,
                    onBrace = ::setBrace,
                )
                else -> ResultContent(
                    state = state,
                    busy = busy,
                    error = error,
                    onRematch = { action { api.rematch(roomCode, participantToken) } },
                    onExit = ::exitGame,
                )
            }
            Text(
                if (state?.phase == "race") {
                    "Hold Run / Back · tap Jump · hold Brace · voice stays connected"
                } else {
                    "Your SquadVoice voice room remains connected while you play."
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shows connection progress or the latest lobby connection error. */
@Composable
private fun LoadingLobby(error: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(error ?: "Joining the game lobby…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows team selection and readiness, enabling race start for a host with ready teams. */
@Composable
private fun LobbyContent(
    modifier: Modifier = Modifier,
    state: RopeboundSnapshot,
    busy: Boolean,
    error: String?,
    ready: Boolean,
    onChooseTeam: (String) -> Unit,
    onReady: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val teamsReady = state.teamA.players.isNotEmpty() && state.teamB.players.isNotEmpty()
        val everyoneReady = teamsReady && state.teamA.players.all { it.ready } && state.teamB.players.all { it.ready }
        Text("Play 1v1 or add up to 4 runners per team. Stay together so the rope does not pull you back.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            TeamCard("TEAM A", "A", state.teamA, state.viewerTeam == "A", busy, onChooseTeam, Modifier.weight(1f))
            TeamCard("TEAM B", "B", state.teamB, state.viewerTeam == "B", busy, onChooseTeam, Modifier.weight(1f))
        }
        Text(state.notice, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onReady(!ready) },
                modifier = Modifier.weight(1f).height(50.dp),
                enabled = !busy && state.viewerTeam != null,
            ) { Text(if (ready) "Not ready" else "Ready up") }
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f).height(50.dp),
                enabled = !busy && state.isHost && everyoneReady,
            ) {
                Text(
                    when {
                        !state.isHost -> "Host starts"
                        !teamsReady -> "Need both teams"
                        !everyoneReady -> "Waiting for ready"
                        else -> "Start race"
                    },
                )
            }
        }
    }
}

/** Lists a team’s runners and offers a team switch when no action is pending. */
@Composable
private fun TeamCard(
    title: String,
    teamId: String,
    team: RopeboundTeam,
    selected: Boolean,
    busy: Boolean,
    onChooseTeam: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title · ${team.count}/5", fontWeight = FontWeight.Bold)
            if (team.players.isEmpty()) Text("Open team", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            team.players.forEach { player ->
                Text(
                    "${if (player.ready) "✓" else "○"} ${player.name}${if (player.host) " · host" else ""}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { onChooseTeam(teamId) },
                enabled = !busy && !selected && team.count < 5,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        selected -> "Your team"
                        team.count >= 5 -> "Full"
                        else -> "Join team"
                    },
                )
            }
        }
    }
}

/** Shows team progress, the local course, hazard hints, and movement controls. */
@Composable
private fun RaceContent(
    modifier: Modifier = Modifier,
    state: RopeboundSnapshot,
    error: String?,
    viewerIdentity: String,
    onMove: (Int, Boolean) -> Unit,
    onJump: () -> Unit,
    onBrace: (Boolean) -> Unit,
) {
    val ownTeam = if (state.viewerTeam == "A") state.teamA else state.teamB
    val otherTeam = if (state.viewerTeam == "A") state.teamB else state.teamA
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaceMarker("YOUR TEAM", ownTeam.progress, Modifier.weight(1f))
            RaceMarker("RIVALS", otherTeam.progress, Modifier.weight(1f))
            RopeTensionMarker(ownTeam.ropeTension, Modifier.weight(1f))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CourseCanvas(ownTeam, viewerIdentity, state.effect)
        }
        state.hazard?.let { hazard ->
            val actionName = when (hazard.action) {
                "jump" -> "JUMP"
                "brace" -> "BRACE"
                else -> "KEEP MOVING"
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(
                    if (hazard.active) Color(0xFF493622) else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    if (hazard.active) "${hazard.label.uppercase()} · $actionName NOW"
                    else "NEXT · ${hazard.label.uppercase()} · ${hazard.distance.roundToInt()}m",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hazard.active) Color(0xFFFFD166) else MaterialTheme.colorScheme.primary,
                )
                Text(hazard.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(state.notice, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HoldMoveButton("◀ Back", -1, Modifier.weight(1f).height(52.dp), onMove)
            HoldMoveButton("Run ▶", 1, Modifier.weight(1.2f).height(52.dp), onMove)
            OutlinedButton(onClick = onJump, modifier = Modifier.weight(.85f).height(52.dp)) { Text("Jump") }
            HoldBraceButton(onBrace, Modifier.weight(.85f).height(52.dp))
        }
    }
}

/** Reports press and release state for a directional movement control. */
@Composable
private fun HoldMoveButton(
    label: String,
    direction: Int,
    modifier: Modifier = Modifier,
    onMove: (Int, Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed, direction) { onMove(direction, pressed) }

    OutlinedButton(
        onClick = {},
        modifier = modifier,
        interactionSource = interactionSource,
    ) { Text(label) }
}

/** Reports press and release state for the brace control. */
@Composable
private fun HoldBraceButton(onBrace: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { onBrace(pressed) }
    OutlinedButton(onClick = {}, modifier = modifier, interactionSource = interactionSource) { Text("Brace") }
}

/** Displays a team’s normalized course progress as a clamped percentage. */
@Composable
private fun RaceMarker(label: String, progress: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(9.dp)) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

/** Displays normalized rope tension as a clamped percentage. */
@Composable
private fun RopeTensionMarker(tension: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(9.dp)) {
        Text("ROPE TENSION", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        Text("${(tension.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 15.sp, color = if (tension >= .8f) Color(0xFFFFB45F) else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

/** Draws the local team, ropes, hazards, and impact effects with a player-following camera. */
@Composable
private fun CourseCanvas(team: RopeboundTeam, viewerIdentity: String, effect: RopeboundEffect?) {
    val backgroundImage = ImageBitmap.imageResource(R.drawable.ropebound_training_yard_v2)
    val hazardTransition = rememberInfiniteTransition(label = "hazard-motion")
    val hazardPhase by hazardTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
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
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF0D202A), RoundedCornerShape(20.dp))) {
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

        val courseLeft = w * .04f
        val courseWidth = w * .92f
        /** Maps normalized course progress to canvas x coordinates relative to the camera. */
        fun courseX(progress: Float) = courseLeft + courseWidth * ((progress - cameraLeft) / cameraWidth)
        val pitLeft = courseX(.23f)
        val pitRight = courseX(.29f)
        drawRect(Color(0xFF17242C), Offset(pitLeft, groundY - 2.dp.toPx()), Size(pitRight - pitLeft, h * .22f))
        drawRect(Color(0xFF1B2930), Offset(pitLeft - 5.dp.toPx(), groundY - 4.dp.toPx()), Size(5.dp.toPx(), h * .06f))
        drawRect(Color(0xFF1B2930), Offset(pitRight, groundY - 4.dp.toPx()), Size(5.dp.toPx(), h * .06f))
        // Bright spike teeth make the existing fall-and-regroup pit readable.
        val spikeCount = 8
        repeat(spikeCount) { index ->
            val spikeWidth = (pitRight - pitLeft) / spikeCount
            val spikeX = pitLeft + spikeWidth * index
            drawPath(Path().apply {
                moveTo(spikeX, groundY + h * .19f)
                lineTo(spikeX + spikeWidth * .5f, groundY + h * .07f)
                lineTo(spikeX + spikeWidth, groundY + h * .19f)
                close()
            }, Color(0xFFE5DCE6))
        }

        // The blue river runs under the bridge and through a real open gap.
        val riverLeft = courseX(.415f)
        val riverRight = courseX(.67f)
        drawRect(Color(0xFF0C435C), Offset(riverLeft, groundY), Size(riverRight - riverLeft, h * .24f))
        val riverGapLeft = courseX(.56f)
        val riverGapRight = courseX(.62f)
        drawRect(Color(0xFF071B27), Offset(riverGapLeft, groundY - 2.dp.toPx()), Size(riverGapRight - riverGapLeft, h * .25f))
        drawRect(Color(0xFF126B86), Offset(riverLeft, groundY + h * .025f), Size(riverRight - riverLeft, h * .20f))
        drawRect(Color(0xFF147A94), Offset(riverGapLeft, groundY + 2.dp.toPx()), Size(riverGapRight - riverGapLeft, h * .045f))
        repeat(4) { index ->
            val waveY = groundY + h * (.075f + (index % 2) * .065f) + sin(hazardPhase * PI * 2 + index).toFloat() * h * .008f
            val waveStart = riverLeft + (riverRight - riverLeft) * (index * .18f + hazardPhase * .025f)
            drawLine(Color(0xFF64D5DC).copy(alpha = .65f + hazardPhase * .3f), Offset(waveStart, waveY), Offset(waveStart + (riverRight - riverLeft) * .16f, waveY - h * .012f), strokeWidth = 2.dp.toPx())
        }

        // A boulder blocks the trail. Grounded runners bump back; a timed jump clears it.
        val boulderX = courseX(.375f)
        val boulderRadius = h * .065f
        drawCircle(Color(0xFF747D83), boulderRadius, Offset(boulderX, groundY - boulderRadius * .78f))
        drawCircle(Color(0xFF9AA3A6), boulderRadius * .62f, Offset(boulderX - boulderRadius * .2f, groundY - boulderRadius * 1.18f))
        drawCircle(Color(0xFF525D63), boulderRadius * .48f, Offset(boulderX + boulderRadius * .55f, groundY - boulderRadius * .65f))
        drawCircle(Color(0xFFC2C9C8), boulderRadius * .13f, Offset(boulderX - boulderRadius * .28f, groundY - boulderRadius * 1.38f))

        val tileLeft = courseX(.43f)
        val tileRight = courseX(.50f)
        val tileCount = 6
        val bridgeRunners = animatedPlayers.filter { it.progress in .43f..50f }
        repeat(tileCount) { index ->
            val tileWidth = (tileRight - tileLeft) / tileCount
            val tileX = tileLeft + tileWidth * index
            val tileColor = if (index % 2 == 0) Color(0xFFD7A76B) else Color(0xFFC38C55)
            val tileProgress = .43f + .07f * (index + .5f) / tileCount
            val pressure = bridgeRunners.maxOfOrNull { runner ->
                (1f - abs(runner.progress - tileProgress) / .018f).coerceIn(0f, 1f)
            } ?: 0f
            val sway = sin(hazardPhase * PI * 2 + index * 1.25).toFloat()
            val plankDrop = if (pressure > 0f) h * (.012f + pressure * .018f) else 0f
            val plankTilt = sway * (if (bridgeRunners.isEmpty()) 1.5f else 4f + pressure * 7f)
            rotate(plankTilt, Offset(tileX + tileWidth * .5f, groundY)) {
                drawRoundRect(
                    tileColor,
                    Offset(tileX + 2.dp.toPx(), groundY - h * .025f + plankDrop + sway * h * .006f),
                    Size(tileWidth - 4.dp.toPx(), h * .05f),
                    CornerRadius(4.dp.toPx()),
                )
            }
            drawLine(Color(0xFF765039), Offset(tileX + tileWidth * .53f, groundY - h * .015f), Offset(tileX + tileWidth * .46f, groundY + h * .018f), strokeWidth = 1.5.dp.toPx())
        }
        val bridgeSag = if (bridgeRunners.isEmpty()) 0f else sin(hazardPhase * PI * 2).toFloat() * h * .012f
        drawLine(Color(0xFF9A6B42), Offset(tileLeft, groundY - h * .065f + bridgeSag), Offset(tileRight, groundY - h * .065f - bridgeSag), strokeWidth = 2.dp.toPx())
        drawLine(Color(0xFF9A6B42), Offset(tileLeft, groundY + h * .035f + bridgeSag), Offset(tileRight, groundY + h * .035f - bridgeSag), strokeWidth = 2.dp.toPx())

        val windLeft = courseX(.67f)
        val windRight = courseX(.75f)
        drawRoundRect(Color(0xFF3AA6A0).copy(alpha = .16f), Offset(windLeft, groundY - h * .31f), Size(windRight - windLeft, h * .31f), CornerRadius(18.dp.toPx()))
        repeat(3) { index ->
            val gustY = groundY - h * (.12f + index * .065f)
            drawLine(Color(0xFF9DE4E8).copy(alpha = .75f), Offset(windLeft + 7.dp.toPx(), gustY), Offset(windRight - 8.dp.toPx(), gustY - h * .025f), strokeWidth = 2.dp.toPx())
        }

        // Late-race lava crossing: a pulsing molten pool and independently
        // flickering flames make the hazard readable before players reach it.
        val lavaLeft = courseX(.84f)
        val lavaRight = courseX(.92f)
        val lavaPulse = .5f + .5f * sin(hazardPhase * PI * 2).toFloat()
        drawRect(Color(0xFF631E1B), Offset(lavaLeft, groundY - h * .035f), Size(lavaRight - lavaLeft, h * .24f))
        drawRect(Color(0xFFFF4D22).copy(alpha = .12f + lavaPulse * .12f), Offset(lavaLeft, groundY - h * (.07f + lavaPulse * .025f)), Size(lavaRight - lavaLeft, h * .045f))
        drawLine(Color(0xFFFF6B35).copy(alpha = .7f + lavaPulse * .3f), Offset(lavaLeft, groundY - h * .035f), Offset(lavaRight, groundY - h * .035f), strokeWidth = 4.dp.toPx())
        repeat(7) { index ->
            val flameWidth = (lavaRight - lavaLeft) / 7f
            val flameX = lavaLeft + flameWidth * index
            val flamePulse = .5f + .5f * sin(hazardPhase * PI * 2 + index * 1.7).toFloat()
            val peakY = groundY - h * (.12f + flamePulse * .085f)
            drawPath(Path().apply {
                moveTo(flameX, groundY - h * .025f)
                quadraticTo(flameX + flameWidth * .24f, peakY + h * .06f, flameX + flameWidth * .50f, peakY)
                quadraticTo(flameX + flameWidth * .76f, peakY + h * .055f, flameX + flameWidth, groundY - h * .025f)
                close()
            }, (if (index % 2 == 0) Color(0xFFFF9F43) else Color(0xFFFF5B35)).copy(alpha = .78f + flamePulse * .22f))
            val emberX = flameX + flameWidth * (.2f + flamePulse * .6f)
            val emberY = groundY - h * (.04f + flamePulse * .08f)
            drawCircle(Color(0xFFFFD166).copy(alpha = .45f + flamePulse * .5f), h * (.004f + flamePulse * .003f), Offset(emberX, emberY))
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

        val runnerSize = (w * .035f).coerceIn(13.dp.toPx(), 25.dp.toPx())
        val trackY = h * .12f
        drawLine(Color(0xFF65847D), Offset(courseLeft, trackY), Offset(courseLeft + courseWidth, trackY), strokeWidth = 4.dp.toPx())
        animatedPlayers.forEachIndexed { index, player ->
            val x = courseX(player.progress)
            val playerHeight = (player.height / 12f).coerceIn(-.35f, .5f) * h
            val runnerY = groundY - runnerSize - playerHeight
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
                val previousY = groundY - runnerSize - previousHeight
                val gap = (previous.progress - player.progress).coerceAtLeast(0f)
                val tension = (gap * 100f / 7.5f).coerceIn(0f, 1f)
                val ropeColor = when {
                    tension > .8f -> Color(0xFFFFB45F)
                    tension > .55f -> Color(0xFF9DE4E8)
                    else -> Color(0xFF66E0CB)
                }
                drawLine(ropeColor, Offset(previousX, previousY), Offset(x, runnerY), strokeWidth = 4.dp.toPx())
            }
            val costume = listOf(Color(0xFF8B6CE8), Color(0xFFE17875), Color(0xFF53B8A7), Color(0xFFF0A848), Color(0xFF6AA8E5))[index % 5]
            // Toy runner: rounded helmet, body, arms, boots, and a visible rope grip.
            drawLine(costume.copy(alpha = .9f), Offset(x - runnerSize * .62f, runnerY - runnerSize * .62f), Offset(x + runnerSize * .62f, runnerY - runnerSize * .42f), strokeWidth = 3.dp.toPx())
            drawRoundRect(costume, Offset(x - runnerSize * .42f, runnerY - runnerSize * .76f), Size(runnerSize * .84f, runnerSize * .7f), CornerRadius(runnerSize * .26f))
            drawCircle(Color(0xFFFFC857), runnerSize * .40f, Offset(x, runnerY - runnerSize * 1.05f))
            drawCircle(Color(0xFF17242C), runnerSize * .045f, Offset(x + runnerSize * .15f, runnerY - runnerSize * 1.07f))
            drawLine(costume, Offset(x - runnerSize * .18f, runnerY - runnerSize * .1f), Offset(x - runnerSize * .36f, runnerY + runnerSize * .08f), strokeWidth = 2.5.dp.toPx())
            drawLine(costume, Offset(x + runnerSize * .18f, runnerY - runnerSize * .1f), Offset(x + runnerSize * .35f, runnerY + runnerSize * .08f), strokeWidth = 2.5.dp.toPx())
            drawRoundRect(
                if (player.bracing) Color(0xFFB7D9FF) else Color(0xFF252F37),
                Offset(x - runnerSize * .38f, runnerY - runnerSize * .02f),
                Size(runnerSize * .34f, runnerSize * .18f),
                CornerRadius(runnerSize * .08f),
            )
            drawRoundRect(if (player.bracing) Color(0xFFB7D9FF) else Color(0xFF252F37), Offset(x + runnerSize * .04f, runnerY - runnerSize * .02f), Size(runnerSize * .34f, runnerSize * .18f), CornerRadius(runnerSize * .08f))
        }
        val markerX = courseX(team.progress).coerceIn(courseLeft, courseLeft + courseWidth)
        drawCircle(Color(0xFF66E0CB), 7.dp.toPx(), Offset(markerX, trackY))

        effect?.let { impact ->
            val progress = impactProgress.value.coerceIn(0f, 1f)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val impactX = courseX(impact.progress).coerceIn(courseLeft, courseLeft + courseWidth)
            val waterImpact = impact.type == "water-splash"
            val bridgeBreak = impact.type == "bridge-break"
            val origin = Offset(impactX, when {
                waterImpact -> groundY + h * .015f
                bridgeBreak -> groundY - h * .025f
                else -> groundY - h * .035f
            })
            val particleColor = when {
                waterImpact -> Color(0xFF9AF3F4)
                bridgeBreak -> Color(0xFFD7A76B)
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
                        color = (if (waterImpact) particleColor else if (index % 2 == 0) Color(0xFFFFD166) else Color(0xFFFF512F)).copy(alpha = alpha),
                        radius = h * (.006f + (index % 3) * .002f),
                        center = particle,
                    )
                }
            }
        }
    }
}

/** Shows the race outcome, winner celebration, and host-controlled rematch action. */
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

/** Animates a party-popper emoji with an optional reversed tilt. */
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

/** Draws repeating falling confetti across the supplied canvas bounds. */
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
