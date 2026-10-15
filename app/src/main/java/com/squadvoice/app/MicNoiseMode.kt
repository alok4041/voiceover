package com.squadvoice.app

import android.content.SharedPreferences

internal enum class MicNoiseMode(
    val preferenceValue: String,
    val title: String,
    val description: String,
) {
    NORMAL(
        preferenceValue = "normal",
        title = "Normal",
        description = "Use Android and WebRTC's standard voice processing.",
    ),
    KRISP(
        preferenceValue = "krisp",
        title = "Krisp",
        description = "Enhanced removal of steady background sounds such as a TV or fan.",
    ),
    DEEP_FILTER_NET(
        preferenceValue = "deepfilternet",
        title = "DeepFilterNet",
        description = "On-device AI filter; it uses more CPU and battery and makes the app download larger.",
    ),
    ;

    companion object {
        fun fromPreferences(preferences: SharedPreferences): MicNoiseMode {
            val saved = preferences.getString(MIC_NOISE_MODE_PREFERENCE, null)
            if (saved != null) {
                entries.firstOrNull { it.preferenceValue == saved }?.let { return it }
            }

            // Preserve the selection used by versions that stored only a Boolean.
            return if (preferences.getBoolean(LEGACY_NOISE_CANCELLATION_PREFERENCE, true)) KRISP else NORMAL
        }
    }
}

internal const val MIC_NOISE_MODE_PREFERENCE = "mic_noise_mode"
internal const val LEGACY_NOISE_CANCELLATION_PREFERENCE = "noise_cancellation_enabled"
