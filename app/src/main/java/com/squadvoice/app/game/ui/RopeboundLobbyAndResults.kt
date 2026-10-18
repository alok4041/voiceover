package com.squadvoice.app.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun LoadingLobby(error: String?, onRetry: () -> Unit) {
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
internal fun LobbyHeader(
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
internal fun LobbyContent(
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
internal fun PingBadge(pingMs: Int?) {
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
internal fun ResultContent(
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
