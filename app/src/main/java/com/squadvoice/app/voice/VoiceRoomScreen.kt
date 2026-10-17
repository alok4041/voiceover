package com.squadvoice.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squadvoice.app.BuildConfig
import kotlin.math.roundToInt

@Composable
internal fun VoiceRoomScreen(
    room: RoomCredentials,
    session: VoiceSessionState,
    errorMessage: String?,
    onReconnect: () -> Unit,
    pushToTalk: Boolean,
    overlayEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onPlayRopebound: () -> Unit,
    onOpenLocalPreview: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onSetMicrophone: (Boolean) -> Unit,
    onSetParticipantVolume: (String, Int) -> Unit,
    onTogglePushToTalk: (Boolean) -> Unit,
    onCopyCode: () -> Unit,
    onShareCode: () -> Unit,
    onToggleOverlay: () -> Unit,
    onLeave: () -> Unit,
) {
    var expandedVolumeIdentity by remember(room.code) { mutableStateOf<String?>(null) }
    val roomPlayerCount = session.participants.map { it.identity }.distinct().size
    val canPlayRopebound = session.isConnected && roomPlayerCount >= MIN_ROPEBOUND_PLAYERS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                Text("VOICE ROOM", fontSize = 10.sp, color = Mint, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Text(room.roomName, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            TextButtonLabel("⚙", onOpenSettings, accessibilityLabel = "Open settings")
            Button(
                onClick = onLeave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = Alert,
                ),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Leave") }
        }

        Button(
            onClick = onPlayRopebound,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(15.dp),
            enabled = canPlayRopebound,
        ) {
            Text(
                if (canPlayRopebound) "Play Ropebound · 2D"
                else "Waiting for players · ${roomPlayerCount.coerceAtMost(10)}/$MIN_ROPEBOUND_PLAYERS",
            )
        }
        Text(
            if (canPlayRopebound) "Four players are in the room. Ropebound is ready."
            else "Ropebound unlocks when four players are connected · ${roomPlayerCount.coerceAtMost(10)}/$MIN_ROPEBOUND_PLAYERS",
            modifier = Modifier.padding(horizontal = 4.dp),
            fontSize = 11.sp,
            color = MutedText,
        )
        if (BuildConfig.DEBUG) {
            OutlinedButton(
                onClick = onOpenLocalPreview,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Preview playground · 4 local demo runners")
            }
            Text(
                "Visual preview only. Live multiplayer still requires four connected players.",
                modifier = Modifier.padding(horizontal = 4.dp),
                fontSize = 10.sp,
                color = MutedText,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .padding(horizontal = 14.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ROOM CODE", fontSize = 10.sp, color = MutedText, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    room.code,
                    fontSize = 24.sp,
                    color = Mint,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButtonLabel("Copy", onCopyCode, horizontalPadding = 8.dp)
                TextButtonLabel("Share", onShareCode, horizontalPadding = 8.dp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYERS", fontSize = 11.sp, color = MutedText, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${session.participants.size.coerceAtMost(10)} / 10", color = MutedText, fontSize = 12.sp)
        }

        Surface(shape = RoundedCornerShape(22.dp), color = Panel) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (session.participants.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = Mint)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (session.isConnecting) "Connecting to voice…" else "Waiting for players…",
                            color = MutedText,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    session.participants.forEachIndexed { index, participant ->
                        ParticipantRow(
                            participant = participant,
                            volumeExpanded = !participant.isLocal && expandedVolumeIdentity == participant.identity,
                            onToggleVolume = {
                                expandedVolumeIdentity =
                                    if (expandedVolumeIdentity == participant.identity) null else participant.identity
                            },
                            onVolumeChange = { percent -> onSetParticipantVolume(participant.identity, percent) },
                        )
                        if (index != session.participants.lastIndex) {
                            Spacer(Modifier.height(1.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(RaisedPanel))
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MicControl(
                micEnabled = session.micEnabled,
                pushToTalk = pushToTalk,
                enabled = session.isConnected,
                isConnecting = session.isConnecting,
                onToggle = onToggleMicrophone,
                onSetMicrophone = onSetMicrophone,
            )
        }

        if (!session.isConnected && !session.isConnecting) {
            Button(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(15.dp),
            ) { Text("Reconnect to room") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Panel)
                .padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hold to talk", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Keep the mic open only while pressing", fontSize = 11.sp, color = MutedText)
            }
            Switch(checked = pushToTalk, onCheckedChange = onTogglePushToTalk)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier.weight(1f).height(56.dp),
                color = RaisedPanel,
                shape = RoundedCornerShape(15.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Game + chat volume", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use the media volume", fontSize = 10.sp, color = MutedText)
                }
            }
            Button(
                onClick = onToggleOverlay,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaisedPanel,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(15.dp),
            ) { Text(if (overlayEnabled) "Overlay on" else "Game overlay") }
        }

        if (errorMessage != null) ErrorNotice(errorMessage)

        Text(
            "Only your voice goes to the room; footsteps and game sounds stay on your phone. Turn off the game's built-in voice chat, but keep its sound effects on. Wired or USB headphones usually keep game audio clearest.",
            color = MutedText,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

private const val MIN_ROPEBOUND_PLAYERS = 4

@Composable
private fun MicControl(
    micEnabled: Boolean,
    pushToTalk: Boolean,
    enabled: Boolean,
    isConnecting: Boolean,
    onToggle: () -> Unit,
    onSetMicrophone: (Boolean) -> Unit,
) {
    val color = if (micEnabled) Mint else RaisedPanel
    val textColor = if (micEnabled) Ink else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .semantics {
                role = Role.Button
                contentDescription = if (micEnabled) "Microphone on" else "Microphone muted"
                if (!pushToTalk) {
                    onClick(label = "Toggle microphone") {
                        if (enabled) {
                            onToggle()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .pointerInput(pushToTalk, enabled, micEnabled) {
                if (pushToTalk) {
                    detectTapGestures(onPress = {
                        if (pushToTalk && enabled) {
                            onSetMicrophone(true)
                            tryAwaitRelease()
                            onSetMicrophone(false)
                        }
                    })
                } else {
                    detectTapGestures(onTap = { if (enabled) onToggle() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                when {
                    !enabled && isConnecting -> "CONNECTING"
                    !enabled -> "VOICE OFF"
                    pushToTalk -> "HOLD TO TALK"
                    micEnabled -> "MIC IS LIVE"
                    else -> "TAP TO UNMUTE"
                },
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Text(
                when {
                    !enabled && isConnecting -> "Joining your squad"
                    !enabled -> "Tap Reconnect to try again"
                    pushToTalk -> "Release to mute"
                    micEnabled -> "Tap to mute your voice"
                    else -> "Your squad can't hear you yet"
                },
                color = if (micEnabled) Ink.copy(alpha = 0.72f) else MutedText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: ParticipantUi,
    volumeExpanded: Boolean,
    onToggleVolume: () -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (participant.isSpeaking) Mint else RaisedPanel)
                    .border(2.dp, if (participant.isSpeaking) Mint else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(participant.name.firstOrNull()?.uppercase() ?: "?", color = if (participant.isSpeaking) Ink else MutedText, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (participant.isLocal) "${participant.name} (you)" else participant.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (participant.microphoneEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        shape = RoundedCornerShape(7.dp),
                    ) {
                        Text(
                            if (participant.microphoneEnabled) "Mic on" else "Mic muted",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = if (participant.microphoneEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (participant.isSpeaking) "Speaking" else "In the room",
                        fontSize = 11.sp,
                        color = if (participant.isSpeaking) Mint else MutedText,
                    )
                }
            }
            if (participant.isLocal) {
                if (participant.isSpeaking) Box(Modifier.size(8.dp).clip(CircleShape).background(Mint))
            } else {
                TextButtonLabel("Volume ${participant.volumePercent}%", onToggleVolume)
            }
        }
        if (volumeExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Only on your phone", fontSize = 11.sp, color = MutedText)
                Spacer(Modifier.weight(1f))
                TextButtonLabel("Reset", { onVolumeChange(100) })
            }
            Slider(
                value = participant.volumePercent.toFloat(),
                onValueChange = { onVolumeChange(it.roundToInt()) },
                valueRange = 0f..200f,
                steps = 19,
                modifier = Modifier.padding(start = 42.dp, end = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("0% · muted", fontSize = 10.sp, color = MutedText)
                Spacer(Modifier.weight(1f))
                Text("100% · normal", fontSize = 10.sp, color = MutedText)
                Spacer(Modifier.weight(1f))
                Text("200% · boost", fontSize = 10.sp, color = MutedText)
            }
        }
    }
}

private val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val RaisedPanel: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val Mint: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val MutedText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Alert: Color
    @Composable get() = MaterialTheme.colorScheme.error

@Composable
private fun TextButtonLabel(
    label: String,
    onClick: () -> Unit,
    accessibilityLabel: String = label,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Text(
        label,
        color = Mint,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RaisedPanel)
            .padding(horizontal = horizontalPadding, vertical = 7.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityLabel },
    )
}

@Composable
private fun ErrorNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Alert, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
