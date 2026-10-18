package com.squadvoice.app

import com.squadvoice.app.game.RopeboundApi
import com.squadvoice.app.game.RopeboundGameScreen
import com.squadvoice.app.voice.LEGACY_NOISE_CANCELLATION_PREFERENCE
import com.squadvoice.app.voice.MIC_NOISE_MODE_PREFERENCE
import com.squadvoice.app.voice.MicNoiseMode
import com.squadvoice.app.voice.OverlayControlsService
import com.squadvoice.app.voice.RoomApi
import com.squadvoice.app.voice.RoomCredentials
import com.squadvoice.app.voice.VoiceRoomService
import com.squadvoice.app.voice.VoiceRoomScreen
import com.squadvoice.app.voice.VoiceSessionManager

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val roomInviteCodes = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roomInviteCodes.value = readInviteRoomCode(intent)
        val preferences = getSharedPreferences("squadvoice", MODE_PRIVATE)
        val initialDarkMode = preferences.getBoolean("dark_mode", true)
        val initialAccentTheme = preferences.getString("accent_theme", "teal") ?: "teal"
        val initialMicNoiseMode = MicNoiseMode.fromPreferences(preferences)
        applySystemBarTheme(true)
        setContent {
            val incomingInviteCode by roomInviteCodes.collectAsState()
            var darkMode by rememberSaveable { mutableStateOf(preferences.getBoolean("dark_mode", true)) }
            var accentTheme by rememberSaveable {
                mutableStateOf(preferences.getString("accent_theme", initialAccentTheme) ?: initialAccentTheme)
            }
            var micNoiseMode by rememberSaveable { mutableStateOf(initialMicNoiseMode) }
            LaunchedEffect(darkMode) { this@MainActivity.applySystemBarTheme(darkMode) }
            SquadVoiceTheme(darkMode, accentTheme) {
                Box(Modifier.fillMaxSize()) {
                    SquadVoiceApp(
                        darkMode = darkMode,
                        accentTheme = accentTheme,
                        micNoiseMode = micNoiseMode,
                        incomingInviteCode = incomingInviteCode,
                        onInviteHandled = { roomInviteCodes.value = null },
                        onSetDarkMode = { selectedDarkMode ->
                            darkMode = selectedDarkMode
                            preferences.edit().putBoolean("dark_mode", darkMode).apply()
                            applySystemBarTheme(darkMode)
                        },
                        onSelectAccentTheme = { selectedTheme ->
                            accentTheme = selectedTheme
                            preferences.edit().putString("accent_theme", selectedTheme).apply()
                        },
                        onSetMicNoiseMode = { selectedMode ->
                            micNoiseMode = selectedMode
                            preferences.edit()
                                .putString(MIC_NOISE_MODE_PREFERENCE, selectedMode.preferenceValue)
                                .putBoolean(LEGACY_NOISE_CANCELLATION_PREFERENCE, selectedMode != MicNoiseMode.NORMAL)
                                .apply()
                            VoiceSessionManager.setMicNoiseMode(this@MainActivity, selectedMode)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        roomInviteCodes.value = readInviteRoomCode(intent)
    }

    private fun applySystemBarTheme(darkMode: Boolean) {
        val color = AndroidColor.parseColor(if (darkMode) "#0A1012" else "#F4FAF9")
        window.setBackgroundDrawable(ColorDrawable(color))
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
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

private data class AccentPalette(
    val id: String,
    val name: String,
    val swatch: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
)

private val accentPalettes = listOf(
    AccentPalette(
        id = "teal", name = "Squad Teal", swatch = Color(0xFF55E2C8),
        darkPrimary = Color(0xFF8BE8D5), darkOnPrimary = Color(0xFF003832),
        darkPrimaryContainer = Color(0xFF005048), darkOnPrimaryContainer = Color(0xFFB7F3E7),
        lightPrimary = Color(0xFF006B5F), lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFB7F3E7), lightOnPrimaryContainer = Color(0xFF00201B),
    ),
    AccentPalette(
        id = "violet", name = "Violet", swatch = Color(0xFFC6A7FF),
        darkPrimary = Color(0xFFD0BCFF), darkOnPrimary = Color(0xFF381E72),
        darkPrimaryContainer = Color(0xFF4F378B), darkOnPrimaryContainer = Color(0xFFE9DDFF),
        lightPrimary = Color(0xFF6750A4), lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFEADDFF), lightOnPrimaryContainer = Color(0xFF21005D),
    ),
    AccentPalette(
        id = "ocean", name = "Ocean Blue", swatch = Color(0xFF76B8FF),
        darkPrimary = Color(0xFFA9C7FF), darkOnPrimary = Color(0xFF00315A),
        darkPrimaryContainer = Color(0xFF00497F), darkOnPrimaryContainer = Color(0xFFD3E4FF),
        lightPrimary = Color(0xFF0061A4), lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFD2E4FF), lightOnPrimaryContainer = Color(0xFF001C38),
    ),
    AccentPalette(
        id = "sunset", name = "Sunset Orange", swatch = Color(0xFFFFB77D),
        darkPrimary = Color(0xFFFFB77D), darkOnPrimary = Color(0xFF4A2800),
        darkPrimaryContainer = Color(0xFF693C00), darkOnPrimaryContainer = Color(0xFFFFDCBE),
        lightPrimary = Color(0xFF8A4C00), lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFFFDCBE), lightOnPrimaryContainer = Color(0xFF2D1600),
    ),
    AccentPalette(
        id = "rose", name = "Rose", swatch = Color(0xFFFF9FB5),
        darkPrimary = Color(0xFFFFB2C1), darkOnPrimary = Color(0xFF5E1132),
        darkPrimaryContainer = Color(0xFF7B2948), darkOnPrimaryContainer = Color(0xFFFFD9E1),
        lightPrimary = Color(0xFFB01B58), lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFFFD9E1), lightOnPrimaryContainer = Color(0xFF3E001D),
    ),
)

@Composable
private fun SquadVoiceTheme(
    darkMode: Boolean,
    accentTheme: String,
    content: @Composable () -> Unit,
) {
    val accent = accentPalettes.firstOrNull { it.id == accentTheme } ?: accentPalettes.first()
    MaterialTheme(
        colorScheme = if (darkMode) {
            darkColorScheme(
                primary = accent.darkPrimary,
                onPrimary = accent.darkOnPrimary,
                primaryContainer = accent.darkPrimaryContainer,
                onPrimaryContainer = accent.darkOnPrimaryContainer,
                background = Color(0xFF0A1012),
                onBackground = Color(0xFFE4ECEC),
                surface = Color(0xFF111A1D),
                onSurface = Color(0xFFE4ECEC),
                surfaceVariant = Color(0xFF182326),
                onSurfaceVariant = Color(0xFF9AAEAF),
                error = Color(0xFFFFB4AB),
                errorContainer = Color(0xFF5C1715),
            )
        } else {
            lightColorScheme(
                primary = accent.lightPrimary,
                onPrimary = accent.lightOnPrimary,
                primaryContainer = accent.lightPrimaryContainer,
                onPrimaryContainer = accent.lightOnPrimaryContainer,
                background = Color(0xFFF4FAF9),
                onBackground = Color(0xFF161D1B),
                surface = Color.White,
                onSurface = Color(0xFF161D1B),
                surfaceVariant = Color(0xFFE1EEEA),
                onSurfaceVariant = Color(0xFF40524D),
                error = Color(0xFFBA1A1A),
                errorContainer = Color(0xFFFFDAD6),
            )
        },
        content = content,
    )
}

@Composable
private fun SquadVoiceApp(
    darkMode: Boolean,
    accentTheme: String,
    micNoiseMode: MicNoiseMode,
    incomingInviteCode: String?,
    onInviteHandled: () -> Unit,
    onSetDarkMode: (Boolean) -> Unit,
    onSelectAccentTheme: (String) -> Unit,
    onSetMicNoiseMode: (MicNoiseMode) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("squadvoice", Context.MODE_PRIVATE) }
    val api = remember { RoomApi() }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val session by VoiceSessionManager.state.collectAsState()

    val restoredRoomCode = preferences.getString("room_code", null)
    val restoredRoomName = preferences.getString("room_name", null)
    val restoredServerUrl = preferences.getString("server_url", null)
    val restoredParticipantToken = preferences.getString("participant_token", null)
    val canRestoreSession =
        !restoredRoomCode.isNullOrBlank() &&
        !restoredRoomName.isNullOrBlank() &&
        !restoredServerUrl.isNullOrBlank() &&
        !restoredParticipantToken.isNullOrBlank()

    var playerName by rememberSaveable { mutableStateOf(preferences.getString("player_name", "") ?: "") }
    var roomName by rememberSaveable { mutableStateOf("") }
    var joinCode by rememberSaveable { mutableStateOf("") }
    var savedRoomCode by remember { mutableStateOf(if (canRestoreSession) restoredRoomCode else null) }
    var savedRoomName by remember { mutableStateOf(if (canRestoreSession) restoredRoomName else null) }
    var savedServerUrl by remember { mutableStateOf(if (canRestoreSession) restoredServerUrl else null) }
    var savedParticipantToken by remember { mutableStateOf(if (canRestoreSession) restoredParticipantToken else null) }
    val activeRoom = if (
        savedRoomCode != null && savedRoomName != null && savedServerUrl != null && savedParticipantToken != null
    ) {
        RoomCredentials(savedRoomCode!!, savedRoomName!!, savedServerUrl!!, savedParticipantToken!!)
    } else {
        null
    }
    var isInRoom by remember { mutableStateOf(canRestoreSession) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingRoomAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var overlayEnabled by remember { mutableStateOf(OverlayControlsService.isRunning) }
    var pushToTalk by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showRopeboundGame by rememberSaveable { mutableStateOf(false) }
    var showLocalGamePreview by rememberSaveable { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<LatestApkRelease?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableStateOf<Int?>(null) }
    var updateDownloadMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context, showRopeboundGame) {
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (showRopeboundGame) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            AppUpdateChecker.fetchLatestApk()
        }.onSuccess { latest ->
            if (compareVersions(latest.version, BuildConfig.VERSION_NAME) > 0) {
                availableUpdate = latest
            }
        }
    }

    fun downloadAndInstallUpdate(release: LatestApkRelease) {
        if (isDownloadingUpdate) return

        if (!AppUpdateChecker.canInstallPackages(context)) {
            updateDownloadMessage = "Allow installs from SquadVoice once in Android settings, then return and tap the update button again."
            runCatching { AppUpdateChecker.openInstallPermissionSettings(context) }
                .onFailure { updateDownloadMessage = "Open Android Settings and allow installs from SquadVoice, then try again." }
            return
        }

        val cachedApk = AppUpdateChecker.findDownloadedApk(context, release)
        if (cachedApk != null) {
            runCatching {
                AppUpdateChecker.openApkInstaller(context, cachedApk)
                updateDownloadMessage = "Android's installer opened. Confirm the update there to finish."
            }.onFailure { error ->
                updateDownloadMessage = error.message ?: "Couldn't open Android's installer."
            }
            return
        }

        isDownloadingUpdate = true
        updateDownloadProgress = null
        updateDownloadMessage = "Downloading SquadVoice v${release.version}…"
        coroutineScope.launch {
            try {
                val apk = AppUpdateChecker.downloadApk(context, release) { progress ->
                    updateDownloadProgress = progress
                }
                updateDownloadMessage = "Download complete. Confirm Install in Android's installer."
                AppUpdateChecker.openApkInstaller(context, apk)
            } catch (error: Exception) {
                updateDownloadMessage = error.message ?: "Couldn't download the update. Check your connection and try again."
            } finally {
                isDownloadingUpdate = false
                updateDownloadProgress = null
            }
        }
    }

    fun closeRopeboundGame() {
        val room = activeRoom
        showRopeboundGame = false
        val wasLocalPreview = showLocalGamePreview
        showLocalGamePreview = false
        if (room != null && !wasLocalPreview) {
            coroutineScope.launch {
                runCatching { RopeboundApi().leave(room.code, room.participantToken) }
            }
        }
    }

    BackHandler(enabled = showSettings || showRopeboundGame) {
        if (showSettings) showSettings = false else closeRopeboundGame()
    }

    DisposableEffect(preferences) {
        val roomPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "room_code" && prefs.getString("room_code", null).isNullOrBlank()) {
                savedRoomCode = null
                savedRoomName = null
                savedServerUrl = null
                savedParticipantToken = null
                isInRoom = false
                showRopeboundGame = false
                overlayEnabled = false
                pushToTalk = false
                OverlayControlsService.stop(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(roomPreferenceListener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(roomPreferenceListener) }
    }

    LaunchedEffect(session.isConnected, session.isConnecting) {
        val isSessionAlive = session.isConnected || session.isConnecting
        if (isSessionAlive) {
            if (!isInRoom) {
                val code = preferences.getString("room_code", null)
                val name = preferences.getString("room_name", null)
                val url = preferences.getString("server_url", null)
                val token = preferences.getString("participant_token", null)
                if (!code.isNullOrBlank() && !name.isNullOrBlank() && !url.isNullOrBlank() && !token.isNullOrBlank()) {
                    savedRoomCode = code
                    savedRoomName = name
                    savedServerUrl = url
                    savedParticipantToken = token
                    isInRoom = true
                }
            }
            val desiredOverlay = preferences.getBoolean("overlay_enabled", false)
            if (desiredOverlay) {
                if (Settings.canDrawOverlays(context)) {
                    if (!OverlayControlsService.isRunning) {
                        OverlayControlsService.start(context)
                        overlayEnabled = true
                    }
                } else {
                    preferences.edit().putBoolean("overlay_enabled", false).apply()
                    overlayEnabled = false
                }
            } else {
                overlayEnabled = OverlayControlsService.isRunning
            }
        } else if (
            !isLoading &&
            isInRoom &&
            preferences.getString("room_code", null).isNullOrBlank()
        ) {
            // A missing room code means the user explicitly left (including from the
            // notification). Keep the room screen on transient disconnects so it can
            // recover when the service reconnects.
            OverlayControlsService.stop(context)
            savedRoomCode = null
            savedRoomName = null
            savedServerUrl = null
            savedParticipantToken = null
            isInRoom = false
            overlayEnabled = false
        }
    }

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
            preferences.edit().putBoolean("overlay_enabled", true).apply()
            errorMessage = null
        } else {
            overlayEnabled = false
            preferences.edit().putBoolean("overlay_enabled", false).apply()
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
                preferences.edit()
                    .putString("room_code", credentials.code)
                    .putString("room_name", credentials.roomName)
                    .putString("server_url", credentials.serverUrl)
                    .putString("participant_token", credentials.participantToken)
                    .apply()
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

    LaunchedEffect(incomingInviteCode) {
        val code = incomingInviteCode ?: return@LaunchedEffect
        joinCode = code
        onInviteHandled()
        if (isInRoom) {
            errorMessage = "Leave your current room before joining this invite."
            return@LaunchedEffect
        }
        if (playerName.isBlank()) {
            errorMessage = "Invite ready. Add your name, then tap Join room."
            return@LaunchedEffect
        }
        withMicrophonePermission {
            startRoom { api.joinRoom(code, playerName.trim()) }
        }
    }

    LaunchedEffect(activeRoom, isInRoom) {
        val credentials = activeRoom
        if (isInRoom && credentials != null && !session.isConnected && !session.isConnecting && session.errorMessage == null) {
            runCatching { VoiceRoomService.start(context, credentials, playerName.trim()) }
                .onFailure { error ->
                    errorMessage = error.message ?: "Android couldn't start the microphone service."
                }
        }
    }

    LaunchedEffect(session.isConnected, pushToTalk) {
        if (session.isConnected && pushToTalk && session.micEnabled) {
            VoiceSessionManager.setMicrophoneEnabled(false)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = Ink,
    ) {
        if (showSettings) {
            SettingsScreen(
                darkMode = darkMode,
                accentTheme = accentTheme,
                micNoiseMode = micNoiseMode,
                noiseCancellationActive = session.noiseCancellationActive,
                noiseCancellationMessage = session.noiseCancellationMessage,
                voiceRoomConnected = session.isConnected,
                voiceRoomConnecting = session.isConnecting,
                isDownloadingUpdate = isDownloadingUpdate,
                updateDownloadProgress = updateDownloadProgress,
                updateDownloadMessage = updateDownloadMessage,
                onDownloadUpdate = { version, downloadUrl ->
                    downloadAndInstallUpdate(LatestApkRelease(version, downloadUrl))
                },
                onSetDarkMode = onSetDarkMode,
                onSelectAccentTheme = onSelectAccentTheme,
                onSetMicNoiseMode = onSetMicNoiseMode,
                onBack = { showSettings = false },
            )
        } else if (isInRoom && activeRoom != null) {
            if (showRopeboundGame) {
                RopeboundGameScreen(
                    roomCode = activeRoom.code,
                    participantToken = activeRoom.participantToken,
                    microphoneEnabled = session.micEnabled,
                    onToggleMicrophone = { VoiceSessionManager.toggleMicrophone() },
                    onBack = ::closeRopeboundGame,
                    previewMode = showLocalGamePreview,
                )
            } else VoiceRoomScreen(
                room = activeRoom,
                session = session,
                errorMessage = errorMessage ?: session.errorMessage,
                onReconnect = {
                    errorMessage = null
                    runCatching { VoiceRoomService.start(context, activeRoom, playerName.trim()) }
                        .onFailure { error ->
                            errorMessage = error.message ?: "Android couldn't reconnect to the room."
                        }
                },
                pushToTalk = pushToTalk,
                overlayEnabled = overlayEnabled,
                onOpenSettings = { showSettings = true },
                onPlayRopebound = { showRopeboundGame = true },
                onOpenLocalPreview = {
                    if (BuildConfig.DEBUG) {
                        showLocalGamePreview = true
                        showRopeboundGame = true
                    }
                },
                onToggleMicrophone = { VoiceSessionManager.toggleMicrophone() },
                onSetMicrophone = { enabled -> VoiceSessionManager.setMicrophoneEnabled(enabled) },
                onSetParticipantVolume = { identity, volumePercent ->
                    VoiceSessionManager.setParticipantVolume(identity, volumePercent)
                },
                onTogglePushToTalk = { enabled ->
                    pushToTalk = enabled
                },
                onCopyCode = { copyRoomCode(context, activeRoom.code) },
                onShareCode = { shareRoomCode(context, activeRoom.code) },
                onToggleOverlay = {
                    if (OverlayControlsService.isRunning) {
                        OverlayControlsService.stop(context)
                        overlayEnabled = false
                        preferences.edit().putBoolean("overlay_enabled", false).apply()
                    } else if (Settings.canDrawOverlays(context)) {
                        OverlayControlsService.start(context)
                        overlayEnabled = true
                        preferences.edit().putBoolean("overlay_enabled", true).apply()
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
                    VoiceRoomService.leave(context)
                    OverlayControlsService.stop(context)
                    preferences.edit()
                        .remove("room_code")
                        .remove("room_name")
                        .remove("server_url")
                        .remove("participant_token")
                        .remove("overlay_enabled")
                        .apply()
                    savedRoomCode = null
                    savedRoomName = null
                    savedServerUrl = null
                    savedParticipantToken = null
                    isInRoom = false
                    showRopeboundGame = false
                    showLocalGamePreview = false
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
                availableUpdate = availableUpdate,
                onDownloadUpdate = {
                    availableUpdate?.let(::downloadAndInstallUpdate)
                },
                isDownloadingUpdate = isDownloadingUpdate,
                updateDownloadProgress = updateDownloadProgress,
                updateDownloadMessage = updateDownloadMessage,
                onOpenSettings = { showSettings = true },
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
    availableUpdate: LatestApkRelease?,
    onDownloadUpdate: () -> Unit,
    isDownloadingUpdate: Boolean,
    updateDownloadProgress: Int?,
    updateDownloadMessage: String?,
    onOpenSettings: () -> Unit,
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
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(RaisedPanel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("SQUADVOICE", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("VOICE FOR YOUR SQUAD", fontSize = 9.sp, color = MutedText, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButtonLabel("⚙", onOpenSettings, accessibilityLabel = "Open settings")
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Quick voice rooms for your squad. Keep the game open while you talk.",
                color = MutedText,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }

        availableUpdate?.let { update ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("🎉 Great news! A new update is here", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "SquadVoice v${update.version} is ready for you.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                    )
                    Button(
                        onClick = onDownloadUpdate,
                        enabled = !isDownloadingUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isDownloadingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(9.dp))
                            Text("Downloading${updateDownloadProgress?.let { " $it%" }.orEmpty()}")
                        } else {
                            Text("Download & install v${update.version}")
                        }
                    }
                    updateDownloadMessage?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
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
private fun SettingsScreen(
    darkMode: Boolean,
    accentTheme: String,
    micNoiseMode: MicNoiseMode,
    noiseCancellationActive: Boolean,
    noiseCancellationMessage: String?,
    voiceRoomConnected: Boolean,
    voiceRoomConnecting: Boolean,
    isDownloadingUpdate: Boolean,
    updateDownloadProgress: Int?,
    updateDownloadMessage: String?,
    onDownloadUpdate: (String, String) -> Unit,
    onSetDarkMode: (Boolean) -> Unit,
    onSelectAccentTheme: (String) -> Unit,
    onSetMicNoiseMode: (MicNoiseMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showAppearancePicker by rememberSaveable { mutableStateOf(false) }
    var showMicNoisePicker by rememberSaveable { mutableStateOf(false) }
    var showAuthorDetails by rememberSaveable { mutableStateOf(false) }
    var isCheckingForUpdates by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var availableVersion by remember { mutableStateOf<String?>(null) }
    var availableApkUrl by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButtonLabel("← Back", onBack, accessibilityLabel = "Back")
            Spacer(Modifier.width(14.dp))
            Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MAKE IT YOURS", fontSize = 10.sp, color = Mint, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text("Appearance", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("Choose how SquadVoice looks on this phone.", color = MutedText, fontSize = 13.sp)
        }

        val selectedPalette = accentPalettes.firstOrNull { it.id == accentTheme } ?: accentPalettes.first()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Current theme", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${if (darkMode) "Night" else "Day"} · ${selectedPalette.name}", color = MutedText, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showAppearancePicker = true }) {
                Text("Choose")
            }
        }

        if (showAppearancePicker) {
            AlertDialog(
                onDismissRequest = { showAppearancePicker = false },
                title = { Text("Choose your theme", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("MODE", fontSize = 10.sp, color = MutedText, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(true to "Night", false to "Day").forEach { (isDark, label) ->
                                val isSelected = darkMode == isDark
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Panel)
                                        .clickable { onSetDarkMode(isDark) }
                                        .semantics {
                                            role = Role.RadioButton
                                            contentDescription = "$label${if (isSelected) ", selected" else ""}"
                                        }
                                        .padding(vertical = 13.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Icon(
                                            painter = painterResource(if (isDark) R.drawable.ic_night else R.drawable.ic_day),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text("ACCENT COLOR", fontSize = 10.sp, color = MutedText, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        accentPalettes.forEach { palette ->
                            val isSelected = palette.id == accentTheme
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Panel)
                                    .clickable { onSelectAccentTheme(palette.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .semantics {
                                        role = Role.RadioButton
                                        contentDescription = "${palette.name}${if (isSelected) ", selected" else ""}"
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(23.dp)
                                        .clip(CircleShape)
                                        .background(palette.swatch)
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape),
                                )
                                Spacer(Modifier.width(11.dp))
                                Text(palette.name, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (isSelected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAppearancePicker = false }) { Text("Done") }
                },
            )
        }

        val selectedMicNoiseMode = MicNoiseMode.entries.firstOrNull { it == micNoiseMode } ?: MicNoiseMode.NORMAL
        Text("VOICE AUDIO", fontSize = 10.sp, color = Mint, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Microphone noise filter", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(selectedMicNoiseMode.title, color = MutedText, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showMicNoisePicker = true }) {
                Text("Choose")
            }
        }

        if (showMicNoisePicker) {
            AlertDialog(
                onDismissRequest = { showMicNoisePicker = false },
                title = { Text("Choose microphone filter", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Choose how much background sound to remove from your microphone. This affects your mic only.",
                            color = MutedText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        MicNoiseMode.entries.forEach { mode ->
                            val selected = micNoiseMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Panel)
                                    .clickable { onSetMicNoiseMode(mode) }
                                    .semantics {
                                        role = Role.RadioButton
                                        contentDescription = "${mode.title}${if (selected) ", selected" else ""}"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(mode.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(mode.description, color = MutedText, fontSize = 11.sp, lineHeight = 15.sp)
                                }
                                if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMicNoisePicker = false }) { Text("Done") }
                },
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val noiseStatus = when {
                noiseCancellationMessage != null -> noiseCancellationMessage
                micNoiseMode == MicNoiseMode.NORMAL -> "Normal mode is selected."
                noiseCancellationActive -> "${micNoiseMode.title} is active on this microphone."
                voiceRoomConnecting -> "Preparing ${micNoiseMode.title} for your microphone…"
                voiceRoomConnected -> "Applying ${micNoiseMode.title} to your microphone…"
                else -> "${micNoiseMode.title} will be applied when you join a room."
            }
            Text(
                noiseStatus,
                color = if (noiseCancellationMessage != null) MaterialTheme.colorScheme.error else MutedText,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("APP UPDATES", fontSize = 10.sp, color = Mint, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text("Check for updates", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Installed version: v${BuildConfig.VERSION_NAME}", color = MutedText, fontSize = 12.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        isCheckingForUpdates = true
                        updateMessage = null
                        availableVersion = null
                        availableApkUrl = null
                        coroutineScope.launch {
                            try {
                                val latest = withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatestApk() }
                                val comparison = compareVersions(latest.version, BuildConfig.VERSION_NAME)
                                when {
                                    comparison > 0 -> {
                                        availableVersion = latest.version
                                        availableApkUrl = latest.downloadUrl
                                        updateMessage = "Version ${latest.version} is available. You have v${BuildConfig.VERSION_NAME}."
                                    }
                                    comparison == 0 -> {
                                        updateMessage = "You're using the latest version (v${BuildConfig.VERSION_NAME})."
                                    }
                                    else -> {
                                        updateMessage = "This installed build (v${BuildConfig.VERSION_NAME}) is newer than the latest public release (v${latest.version})."
                                    }
                                }
                            } catch (error: Exception) {
                                updateMessage = error.message?.let { "Couldn't check for updates: $it" }
                                    ?: "Couldn't check for updates. Check your internet connection and try again."
                            } finally {
                                isCheckingForUpdates = false
                            }
                        }
                    },
                    enabled = !isCheckingForUpdates,
                ) {
                    if (isCheckingForUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("Checking…", maxLines = 1)
                    } else {
                        Text("Check updates", maxLines = 1)
                    }
                }
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Open SquadVoice website" },
                    onClick = { openExternalLink(context, "https://alok4041.github.io/voiceover/") },
                ) {
                    Text("Website", maxLines = 1)
                }
            }
            updateMessage?.let { message ->
                Text(
                    message,
                    color = if (availableApkUrl != null) MaterialTheme.colorScheme.primary else MutedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            availableApkUrl?.let { downloadUrl ->
                Button(
                    enabled = !isDownloadingUpdate,
                    onClick = {
                        onDownloadUpdate(availableVersion.orEmpty(), downloadUrl)
                    },
                ) {
                    if (isDownloadingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("Downloading${updateDownloadProgress?.let { " $it%" }.orEmpty()}")
                    } else {
                        Text("Download & install v${availableVersion.orEmpty()}")
                    }
                }
            }
            updateDownloadMessage?.let { message ->
                Text(
                    message,
                    color = if (isDownloadingUpdate) MaterialTheme.colorScheme.primary else MutedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "About the author",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = { showAuthorDetails = !showAuthorDetails },
                    modifier = Modifier.semantics {
                        contentDescription = if (showAuthorDetails) "Hide author details" else "Show author details"
                    },
                ) {
                    Text(if (showAuthorDetails) "⌃" else "⌄", fontSize = 20.sp, lineHeight = 20.sp)
                }
            }
            if (showAuthorDetails) {
                Text("Alok Kumar", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("B.Tech IT · 2028", color = MutedText, fontSize = 12.sp)
                ContactDetailRow(
                    icon = R.drawable.ic_email,
                    description = "Send email",
                    value = "alokcu2k24@gmail.com",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:alokcu2k24@gmail.com")))
                        }
                    },
                )
                ContactDetailRow(
                    icon = R.drawable.ic_phone,
                    description = "Call Alok",
                    value = "+91-6206475411",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+916206475411")))
                        }
                    },
                )
                ContactDetailRow(
                    icon = R.drawable.ic_location,
                    description = "Open location in maps",
                    value = "Gaya, Bihar",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Gaya,Bihar")))
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileLinkIcon(
                        icon = R.drawable.ic_linkedin,
                        description = "Open LinkedIn profile",
                        onClick = { openExternalLink(context, "https://www.linkedin.com/in/alok-kumar-a1404x") },
                    )
                    ProfileLinkIcon(
                        icon = R.drawable.ic_github,
                        description = "Open GitHub profile",
                        onClick = { openExternalLink(context, "https://github.com/alok4041") },
                    )
                    ProfileLinkIcon(
                        icon = R.drawable.ic_instagram,
                        description = "Open Instagram profile",
                        onClick = { openExternalLink(context, "https://www.instagram.com/n_alok4041/") },
                    )
                }
            }
        }

        Text(
            "SquadVoice · Voice for your squad",
            color = MutedText,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
    }
}

private fun compareVersions(left: String, right: String): Int {
    val leftParts = left.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val rightParts = right.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

@Composable
private fun ContactDetailRow(
    icon: Int,
    description: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$description: $value"
            }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Mint,
            modifier = Modifier.size(19.dp),
        )
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@Composable
private fun ProfileLinkIcon(
    icon: Int,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RaisedPanel)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Mint,
            modifier = Modifier.size(21.dp),
        )
    }
}

private fun openExternalLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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
private fun TextButtonLabel(
    label: String,
    onClick: () -> Unit,
    accessibilityLabel: String = label,
) {
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

private const val ROOM_INVITE_HOST = "voiceover-1.onrender.com"

private fun readInviteRoomCode(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    val validHost = uri.host.equals(ROOM_INVITE_HOST, ignoreCase = true)
    val isHttpsInvite = uri.scheme.equals("https", ignoreCase = true) && validHost && uri.path == "/join"
    val isAppSchemeInvite = uri.scheme.equals("squadvoice", ignoreCase = true) && uri.host == "join"
    if (!isHttpsInvite && !isAppSchemeInvite) return null
    return uri.getQueryParameter("code")
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.matches(Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$")) }
}

private fun copyRoomCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("SquadVoice room code", code))
}

private fun shareRoomCode(context: Context, code: String) {
    val inviteLink = Uri.parse("${BuildConfig.TOKEN_SERVER_URL.trimEnd('/')}/join")
        .buildUpon()
        .appendQueryParameter("code", code)
        .build()
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "Join my SquadVoice voice room:\n$inviteLink\nRoom code: $code\nIf SquadVoice isn't installed, get it here: https://alok4041.github.io/voiceover/",
        )
    }
    context.startActivity(Intent.createChooser(sendIntent, "Invite your squad"))
}
