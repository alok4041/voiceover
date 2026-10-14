package com.squadvoice.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SquadVoiceTheme { SquadVoiceApp() } }
    }
}

private val Ink = Color(0xFF0A1012)
private val Panel = Color(0xFF111A1D)
private val RaisedPanel = Color(0xFF182326)
private val Mint = Color(0xFF8BE8D5)
private val MutedText = Color(0xFF9AAEAF)
private val Alert = Color(0xFFFFB4AB)

@Composable
private fun SquadVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Color(0xFF003832),
            primaryContainer = Color(0xFF005048),
            onPrimaryContainer = Color(0xFFB7F3E7),
            background = Ink,
            onBackground = Color(0xFFE4ECEC),
            surface = Panel,
            onSurface = Color(0xFFE4ECEC),
            surfaceVariant = RaisedPanel,
            onSurfaceVariant = MutedText,
            error = Alert,
        ),
        content = content,
    )
}

@Composable
private fun SquadVoiceApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val preferences = remember { context.getSharedPreferences("squadvoice", Context.MODE_PRIVATE) }
    val api = remember { RoomApi() }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val session by VoiceSessionManager.state.collectAsState()

    var playerName by rememberSaveable { mutableStateOf(preferences.getString("player_name", "") ?: "") }
    var roomName by rememberSaveable { mutableStateOf("") }
    var joinCode by rememberSaveable { mutableStateOf("") }
    var savedRoomCode by rememberSaveable { mutableStateOf<String?>(null) }
    var savedRoomName by rememberSaveable { mutableStateOf<String?>(null) }
    var savedServerUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var savedParticipantToken by rememberSaveable { mutableStateOf<String?>(null) }
    val activeRoom = if (
        savedRoomCode != null && savedRoomName != null && savedServerUrl != null && savedParticipantToken != null
    ) {
        RoomCredentials(savedRoomCode!!, savedRoomName!!, savedServerUrl!!, savedParticipantToken!!)
    } else {
        null
    }
    var isInRoom by rememberSaveable { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingRoomAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var overlayEnabled by rememberSaveable { mutableStateOf(false) }
    var pushToTalk by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val action = pendingRoomAction
        pendingRoomAction = null
        action?.invoke()
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            pendingRoomAction = null
            errorMessage = "Allow microphone access to join voice chat."
        } else {
            val action = pendingRoomAction
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                pendingRoomAction = null
                action?.invoke()
            }
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            OverlayControlsService.start(context)
            overlayEnabled = true
            errorMessage = null
        } else {
            errorMessage = "Overlay permission was not enabled. You can still use the in-app controls."
        }
    }

    fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingRoomAction = action
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                action()
            }
        } else {
            pendingRoomAction = action
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun startRoom(action: suspend () -> RoomCredentials) {
        val cleanName = playerName.trim()
        if (cleanName.isBlank()) {
            errorMessage = "Add your name before joining a room."
            return
        }
        errorMessage = null
        isLoading = true
        coroutineScope.launch {
            try {
                val credentials = action()
                savedRoomCode = credentials.code
                savedRoomName = credentials.roomName
                savedServerUrl = credentials.serverUrl
                savedParticipantToken = credentials.participantToken
                isInRoom = true
            } catch (error: Throwable) {
                errorMessage = error.message ?: "Couldn't reach the room server."
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(activeRoom, isInRoom) {
        val credentials = activeRoom
        if (isInRoom && credentials != null && !session.isConnected && !session.isConnecting && session.errorMessage == null) {
            VoiceRoomService.start(context, credentials, playerName.trim())
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = Ink,
    ) {
        if (isInRoom && activeRoom != null) {
            RoomScreen(
                room = activeRoom!!,
                session = session,
                errorMessage = errorMessage ?: session.errorMessage,
                pushToTalk = pushToTalk,
                overlayEnabled = overlayEnabled,
                onToggleMicrophone = { VoiceSessionManager.toggleMicrophone() },
                onSetMicrophone = { enabled -> VoiceSessionManager.setMicrophoneEnabled(enabled) },
                onTogglePushToTalk = { enabled ->
                    pushToTalk = enabled
                    if (enabled) VoiceSessionManager.setMicrophoneEnabled(false)
                },
                onToggleSpeaker = { VoiceSessionManager.setSpeakerOutput(!session.speakerOutput) },
                onCopyCode = { copyRoomCode(context, activeRoom!!.code) },
                onShareCode = { shareRoomCode(context, activeRoom!!.code) },
                onToggleOverlay = {
                    if (overlayEnabled) {
                        OverlayControlsService.stop(context)
                        overlayEnabled = false
                    } else if (Settings.canDrawOverlays(context)) {
                        OverlayControlsService.start(context)
                        overlayEnabled = true
                    } else {
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
                onLeave = {
                    OverlayControlsService.stop(context)
                    activity.stopService(Intent(context, VoiceRoomService::class.java))
                    savedRoomCode = null
                    savedRoomName = null
                    savedServerUrl = null
                    savedParticipantToken = null
                    isInRoom = false
                    overlayEnabled = false
                    pushToTalk = false
                    errorMessage = null
                },
            )
        } else {
            HomeScreen(
                playerName = playerName,
                roomName = roomName,
                joinCode = joinCode,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onPlayerNameChange = {
                    playerName = it
                    preferences.edit().putString("player_name", it).apply()
                },
                onRoomNameChange = { roomName = it },
                onJoinCodeChange = { joinCode = it.uppercase().take(6) },
                onCreateRoom = {
                    if (roomName.trim().isBlank()) {
                        errorMessage = "Give your room a name first."
                    } else {
                        withMicrophonePermission {
                            startRoom { api.createRoom(roomName.trim(), playerName.trim()) }
                        }
                    }
                },
                onJoinRoom = {
                    if (joinCode.trim().length != 6) {
                        errorMessage = "Enter the full six-character room code."
                    } else {
                        withMicrophonePermission {
                            startRoom { api.joinRoom(joinCode.trim(), playerName.trim()) }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    playerName: String,
    roomName: String,
    joinCode: String,
    isLoading: Boolean,
    errorMessage: String?,
    onPlayerNameChange: (String) -> Unit,
    onRoomNameChange: (String) -> Unit,
    onJoinCodeChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Mint),
                contentAlignment = Alignment.Center,
            ) {
                Text("S", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("SQUADVOICE", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("VOICE FOR YOUR SQUAD", fontSize = 9.sp, color = MutedText, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("NO VIDEO", fontSize = 10.sp, color = Mint, letterSpacing = 1.sp)
        }

        Spacer(Modifier.height(3.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOUR GAME.\nYOUR PEOPLE.", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Quick voice rooms for your squad. Keep the game open while you talk.",
                color = MutedText,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }

        OutlinedTextField(
            value = playerName,
            onValueChange = { onPlayerNameChange(it.take(24)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your name") },
            placeholder = { Text("Player") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(18.dp),
        )

        SectionCard(kicker = "START A SQUAD", title = "Create a room", detail = "Get a short code and invite your friends.") {
            OutlinedTextField(
                value = roomName,
                onValueChange = { onRoomNameChange(it.take(40)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Room name") },
                placeholder = { Text("Friday night squad") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(16.dp),
            )
            PrimaryAction(
                label = if (isLoading) "Opening room…" else "Create room",
                enabled = !isLoading,
                onClick = onCreateRoom,
            )
        }

        SectionCard(kicker = "GOT AN INVITE?", title = "Join a room", detail = "Enter the code your friend shared with you.") {
            OutlinedTextField(
                value = joinCode,
                onValueChange = onJoinCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Six-character code") },
                placeholder = { Text("K7P2QX") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(16.dp),
            )
            PrimaryAction(
                label = if (isLoading) "Joining room…" else "Join room",
                enabled = !isLoading,
                onClick = onJoinRoom,
            )
        }

        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Mint)
                Text("Getting the room ready", color = MutedText, fontSize = 13.sp)
            }
        }
        if (errorMessage != null) ErrorNotice(errorMessage)

        Text(
            "Voice only · up to 10 players · your mic audio is sent to the room",
            color = MutedText,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun RoomScreen(
    room: RoomCredentials,
    session: VoiceSessionState,
    errorMessage: String?,
    pushToTalk: Boolean,
    overlayEnabled: Boolean,
    onToggleMicrophone: () -> Unit,
    onSetMicrophone: (Boolean) -> Unit,
    onTogglePushToTalk: (Boolean) -> Unit,
    onToggleSpeaker: () -> Unit,
    onCopyCode: () -> Unit,
    onShareCode: () -> Unit,
    onToggleOverlay: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("VOICE ROOM", fontSize = 10.sp, color = Mint, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Text(room.roomName, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onLeave,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34201E), contentColor = Alert),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Leave") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("ROOM CODE", fontSize = 10.sp, color = MutedText, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(4.dp))
                Text(room.code, fontSize = 27.sp, color = Mint, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                TextButtonLabel("Copy", onCopyCode)
                Spacer(Modifier.height(8.dp))
                TextButtonLabel("Share", onShareCode)
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
                        ParticipantRow(participant)
                        if (index != session.participants.lastIndex) {
                            Spacer(Modifier.height(1.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(RaisedPanel))
                        }
                    }
                }
            }
        }

        MicControl(
            micEnabled = session.micEnabled,
            pushToTalk = pushToTalk,
            enabled = session.isConnected,
            onToggle = onToggleMicrophone,
            onSetMicrophone = onSetMicrophone,
        )

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
            Button(
                onClick = onToggleSpeaker,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = RaisedPanel, contentColor = Color.White),
                shape = RoundedCornerShape(15.dp),
            ) { Text(if (session.speakerOutput) "Speaker on" else "Audio: auto") }
            Button(
                onClick = onToggleOverlay,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = RaisedPanel, contentColor = Color.White),
                shape = RoundedCornerShape(15.dp),
            ) { Text(if (overlayEnabled) "Overlay on" else "Game overlay") }
        }

        if (errorMessage != null) ErrorNotice(errorMessage)

        Text(
            "SquadVoice uses your microphone only. The game mix is not captured. Wired or USB headphones usually keep game audio clearest.",
            color = MutedText,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun MicControl(
    micEnabled: Boolean,
    pushToTalk: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onSetMicrophone: (Boolean) -> Unit,
) {
    val color = if (micEnabled) Mint else RaisedPanel
    val textColor = if (micEnabled) Ink else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .semantics {
                role = Role.Button
                contentDescription = if (micEnabled) "Microphone on" else "Microphone muted"
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
                    !enabled -> "CONNECTING"
                    pushToTalk -> "HOLD TO TALK"
                    micEnabled -> "MIC IS LIVE"
                    else -> "TAP TO UNMUTE"
                },
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Text(
                if (pushToTalk) "Release to mute" else if (micEnabled) "Tap to mute your voice" else "Your squad can't hear you yet",
                color = if (micEnabled) Ink.copy(alpha = 0.72f) else MutedText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ParticipantRow(participant: ParticipantUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(if (participant.isSpeaking) "Speaking" else "In the room", fontSize = 11.sp, color = if (participant.isSpeaking) Mint else MutedText)
        }
        if (participant.isSpeaking) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Mint))
        }
    }
}

@Composable
private fun SectionCard(
    kicker: String,
    title: String,
    detail: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(23.dp))
            .background(Panel)
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(kicker, fontSize = 10.sp, color = Mint, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(detail, fontSize = 12.sp, color = MutedText)
        content()
    }
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TextButtonLabel(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Mint,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RaisedPanel)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun ErrorNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF34201E))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Alert, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

private fun copyRoomCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("SquadVoice room code", code))
}

private fun shareRoomCode(context: Context, code: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Join my SquadVoice room with code $code")
    }
    context.startActivity(Intent.createChooser(sendIntent, "Invite your squad"))
}
