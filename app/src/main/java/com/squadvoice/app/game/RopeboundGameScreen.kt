package com.squadvoice.app.game

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos
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
    val context = LocalContext.current.applicationContext
    val gameAudio = remember(context) { RopeboundGameAudio(context) }
    var soundEnabled by remember(gameAudio) { mutableStateOf(gameAudio.isEnabled()) }
    var ambienceVolume by remember(gameAudio) { mutableStateOf(gameAudio.ambienceVolume()) }
    var sfxVolume by remember(gameAudio) { mutableStateOf(gameAudio.sfxVolume()) }
    var gameSettingsOpen by remember { mutableStateOf(false) }
    var hasInitializedGameEffect by remember(roomCode) { mutableStateOf(false) }
    var observedGameEffectId by remember(roomCode) { mutableStateOf<Long?>(null) }
    var hasInitializedNotice by remember(roomCode) { mutableStateOf(false) }
    var hasObservedGamePhase by remember(roomCode) { mutableStateOf(false) }
    var hasInitializedCheckpoints by remember(roomCode) { mutableStateOf(false) }
    var observedCheckpointA by remember(roomCode) { mutableStateOf<Float?>(null) }
    var observedCheckpointB by remember(roomCode) { mutableStateOf<Float?>(null) }
    var ropeTensionWasHigh by remember(roomCode) { mutableStateOf(false) }
    val identity = remember(participantToken) { participantIdentity(participantToken) }
    var snapshot by remember(roomCode) { mutableStateOf<RopeboundSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectAttempt by remember(roomCode) { mutableStateOf(0) }
    val controls = remember(roomCode, participantToken) { MutableStateFlow(GameControlState()) }

    DisposableEffect(gameAudio) {
        onDispose { gameAudio.release() }
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

    LaunchedEffect(roomCode, participantToken, reconnectAttempt) {
        while (true) {
            try {
                snapshot = api.join(roomCode, participantToken)
                error = null
                api.events(roomCode, participantToken).collect { current ->
                    snapshot = current
                }
                error = "The game update connection closed. Reconnecting…"
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Couldn't connect to the Ropebound lobby."
            }
            if (snapshot == null) return@LaunchedEffect
            delay(1_500)
        }
    }

    LaunchedEffect(roomCode, participantToken, snapshot?.phase) {
        if (snapshot?.phase != "lobby" && snapshot?.phase != "race") return@LaunchedEffect
        var smoothedPingMs: Int? = null
        while (true) {
            val requestStartedAt = SystemClock.elapsedRealtime()
            try {
                snapshot = api.heartbeat(roomCode, participantToken, smoothedPingMs)
                val sampleMs = (SystemClock.elapsedRealtime() - requestStartedAt).coerceIn(0L, 5_000L).toInt()
                smoothedPingMs = smoothedPingMs?.let { previous ->
                    (previous * .75f + sampleMs * .25f).roundToInt()
                } ?: sampleMs
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
            }
            delay(3_000)
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

    fun setMove(direction: Int, pressed: Boolean) {
        controls.update { current ->
            when (direction) {
                -1 -> current.copy(backHeld = pressed)
                1 -> current.copy(runHeld = pressed)
                else -> current
            }
        }
        if (controls.value.move != 0) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
    }

    fun setBrace(pressed: Boolean) {
        controls.update { it.copy(bracing = pressed) }
    }

    fun openGameSettings() {
        controls.value = GameControlState()
        gameAudio.stopFootsteps()
        gameSettingsOpen = true
    }

    fun jump() {
        controls.update { it.copy(jumpSequence = it.jumpSequence + 1) }
    }

    LaunchedEffect(snapshot?.phase, snapshot?.viewerSpectating) {
        if (snapshot?.phase != "race" || snapshot?.viewerSpectating == true) {
            controls.value = GameControlState()
            gameAudio.stopFootsteps()
        }
    }

    LaunchedEffect(roomCode, participantToken, snapshot?.phase, snapshot?.viewerSpectating) {
        if (snapshot?.phase != "race" || snapshot?.viewerSpectating == true) return@LaunchedEffect

        var sequence = System.currentTimeMillis() * 1_000L
        var lastMove: Int? = null
        var lastBrace: Boolean? = null
        var sentJumpSequence = controls.value.jumpSequence
        var releaseJumpPulse = false

        while (true) {
            val current = controls.value
            if (current.move != 0) gameAudio.startFootsteps() else gameAudio.stopFootsteps()
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

    fun exitGame() {
        scope.launch {
            gameAudio.stopAmbience()
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

    LaunchedEffect(snapshot?.phase, ownTeam?.ropeTension) {
        val isHigh = snapshot?.phase == "race" && (ownTeam?.ropeTension ?: 0f) >= .78f
        if (isHigh && !ropeTensionWasHigh) gameAudio.play(RopeboundGameAudio.Cue.ROPE_TENSION)
        ropeTensionWasHigh = isHigh
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        if (state?.phase == "race") {
            RaceContent(
                modifier = Modifier.fillMaxSize(),
                state = state,
                error = error,
                viewerIdentity = identity,
                microphoneEnabled = microphoneEnabled,
                onToggleMicrophone = onToggleMicrophone,
                onOpenGameSettings = ::openGameSettings,
                onExit = ::exitGame,
                viewerSpectating = state.viewerSpectating,
                onMove = ::setMove,
                onJump = {
                    gameAudio.play(RopeboundGameAudio.Cue.JUMP)
                    jump()
                },
                onBrace = ::setBrace,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ROPEBOUND · 2D", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(if (state?.phase == "results") "Race results" else "Game lobby", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    GameSettingsButton(onClick = ::openGameSettings)
                    OutlinedButton(onClick = onToggleMicrophone) { Text(if (microphoneEnabled) "Mic on" else "Mic muted") }
                    OutlinedButton(onClick = ::exitGame, enabled = !busy) { Text("Back to voice") }
                }

                when {
                    state == null -> LoadingLobby(error) { reconnectAttempt += 1 }
                    state.phase == "lobby" -> LobbyContent(
                        modifier = Modifier.weight(1f),
                        state = state,
                        busy = busy,
                        error = error,
                        ready = ownPlayer?.ready ?: false,
                        viewerIdentity = identity,
                        onChooseTeam = { team -> action { api.chooseTeam(roomCode, participantToken, team) } },
                        onSetFreeTeamSwitching = { enabled ->
                            action { api.setFreeTeamSwitching(roomCode, participantToken, enabled) }
                        },
                        onSwapPlayers = { playerId, otherPlayerId ->
                            action { api.swapPlayers(roomCode, participantToken, playerId, otherPlayerId) }
                        },
                        onReady = { ready -> action { api.setReady(roomCode, participantToken, ready) } },
                        onStart = { action { api.start(roomCode, participantToken) } },
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
                    "Your SquadVoice voice room remains connected while you play.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (gameSettingsOpen) {
            GameAudioSettingsOverlay(
                soundEnabled = soundEnabled,
                ambienceVolume = ambienceVolume,
                sfxVolume = sfxVolume,
                onSoundEnabledChange = { soundEnabled = gameAudio.setEnabled(it) },
                onAmbienceVolumeChange = {
                    ambienceVolume = it
                    gameAudio.setAmbienceVolume(it)
                },
                onSfxVolumeChange = {
                    sfxVolume = it
                    gameAudio.setSfxVolume(it)
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
private fun LobbyContent(
    modifier: Modifier = Modifier,
    state: RopeboundSnapshot,
    busy: Boolean,
    error: String?,
    ready: Boolean,
    viewerIdentity: String,
    onChooseTeam: (String) -> Unit,
    onSetFreeTeamSwitching: (Boolean) -> Unit,
    onSwapPlayers: (String, String) -> Unit,
    onReady: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    var swapSelection by remember { mutableStateOf<Pair<String, RopeboundPlayer>?>(null) }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val teamsReady = state.teamA.count >= 2 && state.teamB.count >= 2
        val everyoneReady = teamsReady && state.teamA.players.all { it.ready } && state.teamB.players.all { it.ready }
        val currentTeamCount = when (state.viewerTeam) {
            "A" -> state.teamA.count
            "B" -> state.teamB.count
            else -> 0
        }
        val switchingAllowed = state.isHost || state.freeTeamSwitching
        Text("At least two runners are needed on each team. Swap players to keep both teams balanced.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
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
                canJoin = !busy && state.viewerTeam == "B" && currentTeamCount > 2 &&
                    state.teamA.count < 5 && switchingAllowed,
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
                canJoin = !busy && state.viewerTeam == "A" && currentTeamCount > 2 &&
                    state.teamB.count < 5 && switchingAllowed,
                onChooseTeam = onChooseTeam,
                onRequestSwap = { player -> swapSelection = "B" to player },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.isHost) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow free team switching", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Players can swap with someone on the other team.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.freeTeamSwitching,
                    onCheckedChange = onSetFreeTeamSwitching,
                    enabled = !busy,
                )
            }
        } else if (state.freeTeamSwitching) {
            Text("Free team switching is on. Tap Swap beside your name to switch.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        !teamsReady -> "Need 2 per team"
                        !everyoneReady -> "Waiting for ready"
                        else -> "Start race"
                    },
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title · ${team.count}/5", fontWeight = FontWeight.Bold)
            if (team.players.isEmpty() && team.spectators.isEmpty()) {
                Text("Open team", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            team.players.forEach { player ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${if (player.ready) "✓" else "○"} ${player.name}${if (player.host) " · host" else ""}",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PingBadge(player.pingMs)
                    if (isHost || (freeTeamSwitching && viewerTeam == teamId && player.id == viewerIdentity)) {
                        Text(
                            "Swap",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !busy) { onRequestSwap(player) }
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            team.spectators.forEach { spectator ->
                Text(
                    "◦ $spectator",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { onChooseTeam(teamId) },
                enabled = canJoin && !selected && team.count < 5,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        selected -> "Your team"
                        team.count >= 5 -> "Full"
                        canJoin -> "Join team"
                        !isHost && !freeTeamSwitching -> "Ask host"
                        else -> "Need 3 on your team"
                    },
                )
            }
        }
    }
}

@Composable
private fun PingBadge(pingMs: Int?) {
    val color = when {
        pingMs == null -> Color(0xFFB7CACE)
        pingMs < 150 -> Color(0xFF77E0B5)
        pingMs < 300 -> Color(0xFFFFD166)
        else -> Color(0xFFFF8585)
    }
    Surface(color = color.copy(alpha = .16f), shape = RoundedCornerShape(50)) {
        Text(
            pingMs?.let { "$it ms" } ?: "PING —",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            color = color,
            fontSize = 9.sp,
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
    viewerSpectating: Boolean,
    onToggleMicrophone: () -> Unit,
    onOpenGameSettings: () -> Unit,
    onExit: () -> Unit,
    onMove: (Int, Boolean) -> Unit,
    onJump: () -> Unit,
    onBrace: (Boolean) -> Unit,
) {
    val ownTeam = if (state.viewerTeam == "A") state.teamA else state.teamB
    val otherTeam = if (state.viewerTeam == "A") state.teamB else state.teamA
    var playgroundReady by remember { mutableStateOf(false) }
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
        CourseCanvas(
            team = ownTeam,
            viewerIdentity = viewerIdentity,
            effect = state.effect,
            obstacles = state.obstacles,
            modifier = Modifier.fillMaxSize(),
            onReady = { playgroundReady = true },
        )

        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RaceMarker("YOUR TEAM", ownTeam.progress, Modifier.weight(1f))
            RaceMarker("RIVALS", otherTeam.progress, Modifier.weight(1f))
            RopeTensionMarker(ownTeam.ropeTension, Modifier.weight(1f))
            GameSettingsButton(onOpenGameSettings, Modifier.height(42.dp))
            OutlinedButton(
                onClick = onToggleMicrophone,
                modifier = Modifier.height(42.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xCC10212A), contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .6f)),
            ) { Text(if (microphoneEnabled) "Mic on" else "Mic off", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.height(42.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xCC10212A), contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .6f)),
            ) { Text("Voice", fontSize = 12.sp) }
        }

        if (ownTeam.players.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(.60f).padding(top = 58.dp)
                    .background(Color(0xB9102028), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ownTeam.players.forEach { player ->
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(player.name, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        PingBadge(player.pingMs)
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(.66f).padding(bottom = 82.dp)
                .background(Color(0xD9102028), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            state.hazard?.let { hazard ->
                val actionName = when (hazard.action) {
                    "jump" -> "JUMP"
                    "brace" -> "BRACE"
                    else -> "KEEP MOVING"
                }
                Text(
                    if (hazard.active) "${hazard.label.uppercase()} · $actionName NOW"
                    else "NEXT · ${hazard.label.uppercase()} · ${hazard.distance.roundToInt()}m",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hazard.active) Color(0xFFFFD166) else Color(0xFF79E7D5),
                )
                Text(hazard.hint, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SquadSurgeMeter(ownTeam)
            if (ownTeam.spectators.isNotEmpty()) {
                Text(
                    "SPECTATING · ${ownTeam.spectators.joinToString()}",
                    fontSize = 9.sp,
                    color = Color(0xFFFFD166),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(state.notice, fontSize = 10.sp, color = Color.White.copy(alpha = .82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, color = Color(0xFFFFA8A8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoldMoveButton("◀ Backward", -1, Modifier.weight(1f).height(58.dp), onMove)
                HoldMoveButton("Forward ▶", 1, Modifier.weight(1f).height(58.dp), onMove)
                Button(
                    onClick = onJump,
                    modifier = Modifier.weight(1f).height(58.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66E0CB), contentColor = Color(0xFF082521)),
                ) { Text("Jump ↑", fontWeight = FontWeight.Bold) }
                HoldBraceButton(onBrace, Modifier.weight(1f).height(58.dp))
            }
        }

        AnimatedVisibility(
            visible = !playgroundReady,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(420)),
        ) {
            MatchLoadingOverlay(logoPulse = logoPulse, runnerTravel = runnerTravel)
        }
    }
}

@Composable
private fun GameSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Game audio settings"
        },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xCC10212A),
            contentColor = Color.White,
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .6f)),
    ) {
        Text("⚙", fontSize = 18.sp)
    }
}

@Composable
private fun GameAudioSettingsOverlay(
    soundEnabled: Boolean,
    ambienceVolume: Float,
    sfxVolume: Float,
    onSoundEnabledChange: (Boolean) -> Unit,
    onAmbienceVolumeChange: (Float) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
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
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
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
                    OutlinedButton(onClick = onReset) { Text("Reset audio") }
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
private fun MatchLoadingOverlay(logoPulse: Float, runnerTravel: Float) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xED07151C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(.68f).background(Color(0xE9162932), RoundedCornerShape(26.dp)).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "ROPEBOUND",
                modifier = Modifier.graphicsLayer { scaleX = logoPulse; scaleY = logoPulse },
                color = Color(0xFF66E0CB),
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Canvas(modifier = Modifier.fillMaxWidth(.72f).height(34.dp)) {
                val middle = size.height * .5f
                drawLine(Color(0xFF66E0CB).copy(alpha = .65f), Offset(0f, middle), Offset(size.width, middle), strokeWidth = 3.dp.toPx())
                val runnerColors = listOf(Color(0xFF8B6CE8), Color(0xFFE17875), Color(0xFF53B8A7), Color(0xFFF0A848))
                runnerColors.forEachIndexed { index, color ->
                    val position = (runnerTravel + index * .22f) % 1f
                    val x = size.width * position
                    val y = middle + sin(position * PI * 4).toFloat() * size.height * .12f
                    drawCircle(color, radius = 6.dp.toPx(), center = Offset(x, y))
                    drawCircle(Color.White.copy(alpha = .86f), radius = 1.5.dp.toPx(), center = Offset(x + 1.dp.toPx(), y - 1.dp.toPx()))
                }
            }
            Text("Preparing your playground…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Getting the team and course ready", color = Color(0xFFB6C9CC), fontSize = 12.sp)
        }
    }
}

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
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xD9102028), contentColor = Color.White),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .68f)),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun HoldBraceButton(onBrace: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { onBrace(pressed) }
    OutlinedButton(
        onClick = {},
        modifier = modifier,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xD9102028), contentColor = Color.White),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .68f)),
    ) { Text("Brace", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun RaceMarker(label: String, progress: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color(0xD9102028), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp, color = Color.White.copy(alpha = .82f), maxLines = 1)
        Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 15.sp, color = Color(0xFF66E0CB), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RopeTensionMarker(tension: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color(0xD9102028), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text("ROPE TENSION", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp, color = Color.White.copy(alpha = .82f), maxLines = 1)
        Text("${(tension.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 15.sp, color = if (tension >= .8f) Color(0xFFFFB45F) else Color(0xFF66E0CB), fontWeight = FontWeight.Bold)
    }
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

        // Rivers are painted around each shuffled water obstacle so every route
        // keeps its bridge and open-water gap attached to the correct location.
        fun drawRiver(start: Float, end: Float, openGap: Boolean) {
            val riverLeft = courseX((start - .035f).coerceAtLeast(0f))
            val riverRight = courseX((end + .035f).coerceAtMost(1f))
            drawRect(Color(0xFF0C435C), Offset(riverLeft, groundY), Size(riverRight - riverLeft, h * .24f))
            drawRect(Color(0xFF126B86), Offset(riverLeft, groundY + h * .025f), Size(riverRight - riverLeft, h * .20f))
            if (openGap) {
                val riverGapLeft = courseX(start)
                val riverGapRight = courseX(end)
                drawRect(Color(0xFF071B27), Offset(riverGapLeft, groundY - 2.dp.toPx()), Size(riverGapRight - riverGapLeft, h * .25f))
                drawRect(Color(0xFF147A94), Offset(riverGapLeft, groundY + 2.dp.toPx()), Size(riverGapRight - riverGapLeft, h * .045f))
            }
            repeat(4) { index ->
                val waveY = groundY + h * (.075f + (index % 2) * .065f) + sin(hazardPhase * PI * 2 + index).toFloat() * h * .008f
                val waveStart = riverLeft + (riverRight - riverLeft) * (index * .18f + hazardPhase * .025f)
                drawLine(Color(0xFF64D5DC).copy(alpha = .65f + hazardPhase * .3f), Offset(waveStart, waveY), Offset(waveStart + (riverRight - riverLeft) * .16f, waveY - h * .012f), strokeWidth = 2.dp.toPx())
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
        repeat(tileCount) { index ->
            val tileWidth = (tileRight - tileLeft) / tileCount
            val tileX = tileLeft + tileWidth * index
            val tileColor = if (index % 2 == 0) Color(0xFFD7A76B) else Color(0xFFC38C55)
            val tileProgress = bridgeStart + (bridgeEnd - bridgeStart) * (index + .5f) / tileCount
            val pressure = bridgeRunners.maxOfOrNull { runner ->
                (1f - abs(runner.progress - tileProgress) / ((bridgeEnd - bridgeStart) / tileCount * .8f)).coerceIn(0f, 1f)
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
            val poorConnection = player.pingMs?.let { it >= 300 } == true
            val characterAlpha = if (poorConnection) {
                .62f + sin(hazardPhase * PI * 3 + index * .4).toFloat().let { it * it } * .30f
            } else 1f
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
            drawRoundRect(runnerCostume, Offset(x - runnerSize * .42f, runnerY - runnerSize * .76f), Size(runnerSize * .84f, runnerSize * .7f), CornerRadius(runnerSize * .26f))
            drawCircle(Color(0xFFFFC857).copy(alpha = characterAlpha), runnerSize * .40f, Offset(x, runnerY - runnerSize * 1.05f))
            drawCircle(Color(0xFF17242C).copy(alpha = characterAlpha), runnerSize * .045f, Offset(x + runnerSize * .15f, runnerY - runnerSize * 1.07f))
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
