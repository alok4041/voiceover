package com.squadvoice.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class OverlayControlsService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var control: TextView? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WindowManager::class.java)
        control = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 31, 34))
                cornerRadius = 28f
                setStroke(2, Color.rgb(139, 232, 213))
            }
            elevation = 8f
            setOnClickListener { VoiceSessionManager.toggleMicrophone() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 180
        }

        runCatching { windowManager?.addView(control, params) }
            .onFailure { stopSelf() }

        serviceScope.launch {
            VoiceSessionManager.state.collect { state ->
                control?.text = if (state.micEnabled) "MIC ON  ·  TAP TO MUTE" else "MIC MUTED  ·  TAP TO TALK"
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { control?.let { windowManager?.removeView(it) } }
        control = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startService(Intent(context, OverlayControlsService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayControlsService::class.java))
        }
    }
}
