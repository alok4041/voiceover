package com.squadvoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class VoiceRoomService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var roomCode: String = ""
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MIC -> {
                VoiceSessionManager.toggleMicrophone()
                return START_NOT_STICKY
            }

            ACTION_LEAVE -> {
                getSharedPreferences("squadvoice", MODE_PRIVATE).edit()
                    .remove("room_code")
                    .remove("room_name")
                    .remove("server_url")
                    .remove("participant_token")
                    .remove("overlay_enabled")
                    .apply()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val credentials = intent?.toCredentials()
        val participantName = intent?.getStringExtra(EXTRA_PARTICIPANT_NAME)
        if (credentials == null || participantName.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val session = VoiceSessionManager.state.value
        if (roomCode == credentials.code && (session.isConnecting || session.isConnected)) {
            return START_NOT_STICKY
        }

        roomCode = credentials.code
        startAsForeground()
        VoiceSessionManager.connect(applicationContext, credentials, participantName)
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            VoiceSessionManager.state.collect { refreshNotification(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        serviceScope.cancel()
        VoiceSessionManager.endSession()
        OverlayControlsService.stop(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = makeNotification(VoiceSessionManager.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification(state: VoiceSessionState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, makeNotification(state))
    }

    private fun makeNotification(state: VoiceSessionState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleMic = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceRoomService::class.java).setAction(ACTION_TOGGLE_MIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val leaveRoom = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceRoomService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val status = when {
            state.isConnecting -> "Connecting to your squad…"
            state.isConnected && state.micEnabled -> "Mic on · game audio stays separate"
            state.isConnected -> "Mic muted · tap to return"
            state.errorMessage != null -> "Voice connection ended"
            else -> "Voice room active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("SquadVoice · $roomCode")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_mic_notification,
                if (state.micEnabled) "Mute" else "Unmute",
                toggleMic,
            )
            .addAction(R.drawable.ic_close_notification, "Leave", leaveRoom)
            .build()
            .apply {
                // Keep the active-room notification visible until the user taps Leave.
                flags = flags or Notification.FLAG_NO_CLEAR
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SquadVoice room",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps voice chat connected while you play" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun Intent.toCredentials(): RoomCredentials? {
        val code = getStringExtra(EXTRA_CODE) ?: return null
        val roomName = getStringExtra(EXTRA_ROOM_NAME) ?: return null
        val serverUrl = getStringExtra(EXTRA_SERVER_URL) ?: return null
        val participantToken = getStringExtra(EXTRA_TOKEN) ?: return null
        return RoomCredentials(code, roomName, serverUrl, participantToken)
    }

    companion object {
        private const val CHANNEL_ID = "squadvoice-room"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_START = "com.squadvoice.app.action.START_ROOM"
        private const val ACTION_TOGGLE_MIC = "com.squadvoice.app.action.TOGGLE_MIC"
        private const val ACTION_LEAVE = "com.squadvoice.app.action.LEAVE_ROOM"
        private const val EXTRA_CODE = "room_code"
        private const val EXTRA_ROOM_NAME = "room_name"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_TOKEN = "participant_token"
        private const val EXTRA_PARTICIPANT_NAME = "participant_name"

        fun start(context: Context, credentials: RoomCredentials, participantName: String) {
            val intent = Intent(context, VoiceRoomService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, credentials.code)
                .putExtra(EXTRA_ROOM_NAME, credentials.roomName)
                .putExtra(EXTRA_SERVER_URL, credentials.serverUrl)
                .putExtra(EXTRA_TOKEN, credentials.participantToken)
                .putExtra(EXTRA_PARTICIPANT_NAME, participantName)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
