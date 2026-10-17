package com.squadvoice.app.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.squadvoice.app.R

/** Local game audio. It deliberately uses the media/game route and never requests voice-call focus. */
internal class RopeboundGameAudio(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile
    private var enabled = preferences.getBoolean(ENABLED_KEY, true)
    @Volatile
    private var ambienceVolume = preferences.getFloat(AMBIENCE_VOLUME_KEY, DEFAULT_AMBIENCE_VOLUME)
    @Volatile
    private var sfxVolume = preferences.getFloat(SFX_VOLUME_KEY, DEFAULT_SFX_VOLUME)
    private val loadedSounds = mutableSetOf<Int>()
    private val soundIds = mutableMapOf<Cue, Int>()
    private var ambiencePlayer: MediaPlayer? = null
    private var footstepStreamId = 0
    private var released = false

    init {
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            if (status == 0) synchronized(loadedSounds) { loadedSounds += soundId }
        }
        soundIds[Cue.JUMP] = soundPool.load(appContext, R.raw.ropebound_jump, 1)
        soundIds[Cue.WATER] = soundPool.load(appContext, R.raw.ropebound_water, 1)
        soundIds[Cue.LAVA] = soundPool.load(appContext, R.raw.ropebound_lava, 1)
        soundIds[Cue.BRIDGE_BREAK] = soundPool.load(appContext, R.raw.ropebound_bridge_break, 1)
        soundIds[Cue.BOULDER] = soundPool.load(appContext, R.raw.ropebound_boulder, 1)
        soundIds[Cue.VICTORY] = soundPool.load(appContext, R.raw.ropebound_victory, 1)
        soundIds[Cue.MATCH_START] = soundPool.load(appContext, R.raw.ropebound_start, 1)
        soundIds[Cue.CHECKPOINT] = soundPool.load(appContext, R.raw.ropebound_checkpoint, 1)
        soundIds[Cue.SQUAD_SURGE] = soundPool.load(appContext, R.raw.ropebound_checkpoint, 1)
        soundIds[Cue.ROPE_TENSION] = soundPool.load(appContext, R.raw.ropebound_rope_tension, 1)
        soundIds[Cue.FOOTSTEPS] = soundPool.load(appContext, R.raw.ropebound_footsteps, 1)
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean): Boolean {
        enabled = value
        preferences.edit().putBoolean(ENABLED_KEY, value).apply()
        if (!value) {
            stopAmbience()
            stopFootsteps()
            soundPool.autoPause()
        } else {
            soundPool.autoResume()
        }
        return value
    }

    fun ambienceVolume(): Float = ambienceVolume

    fun setAmbienceVolume(value: Float) {
        ambienceVolume = value.coerceIn(0f, 1f)
        preferences.edit().putFloat(AMBIENCE_VOLUME_KEY, ambienceVolume).apply()
        ambiencePlayer?.setVolume(ambienceVolume, ambienceVolume)
    }

    fun sfxVolume(): Float = sfxVolume

    fun setSfxVolume(value: Float) {
        sfxVolume = value.coerceIn(0f, 1f)
        preferences.edit().putFloat(SFX_VOLUME_KEY, sfxVolume).apply()
        if (footstepStreamId != 0) {
            val volume = sfxVolume * FOOTSTEP_VOLUME_MULTIPLIER
            soundPool.setVolume(footstepStreamId, volume, volume)
        }
    }

    fun play(cue: Cue) {
        if (!enabled || released) return
        if (cue == Cue.FOOTSTEPS) return
        val soundId = soundIds[cue] ?: return
        val isLoaded = synchronized(loadedSounds) { soundId in loadedSounds }
        if (isLoaded) soundPool.play(soundId, sfxVolume, sfxVolume, 1, 0, 1f)
    }

    fun startFootsteps() {
        if (!enabled || released || footstepStreamId != 0) return
        val soundId = soundIds[Cue.FOOTSTEPS] ?: return
        val isLoaded = synchronized(loadedSounds) { soundId in loadedSounds }
        if (isLoaded) {
            val volume = sfxVolume * FOOTSTEP_VOLUME_MULTIPLIER
            footstepStreamId = soundPool.play(soundId, volume, volume, 1, -1, 1f)
        }
    }

    fun stopFootsteps() {
        if (footstepStreamId == 0) return
        soundPool.stop(footstepStreamId)
        footstepStreamId = 0
    }

    fun startAmbience() {
        if (!enabled || released || ambiencePlayer != null) return

        val player = MediaPlayer()
        ambiencePlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            val audioFile = appContext.resources.openRawResourceFd(R.raw.ropebound_ambience)
            audioFile.use { descriptor ->
                player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            player.isLooping = true
            player.setVolume(ambienceVolume, ambienceVolume)
            player.setOnPreparedListener { prepared ->
                if (ambiencePlayer === prepared && enabled && !released) prepared.start()
                else prepared.release()
            }
            player.setOnErrorListener { failed, _, _ ->
                if (ambiencePlayer === failed) ambiencePlayer = null
                failed.release()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            if (ambiencePlayer === player) ambiencePlayer = null
            player.release()
        }
    }

    fun stopAmbience() {
        val player = ambiencePlayer ?: return
        ambiencePlayer = null
        runCatching { if (player.isPlaying) player.stop() }
        player.release()
    }

    fun release() {
        if (released) return
        released = true
        stopAmbience()
        stopFootsteps()
        soundPool.release()
        synchronized(loadedSounds) { loadedSounds.clear() }
    }

    enum class Cue {
        JUMP,
        WATER,
        LAVA,
        BRIDGE_BREAK,
        BOULDER,
        VICTORY,
        MATCH_START,
        CHECKPOINT,
        SQUAD_SURGE,
        ROPE_TENSION,
        FOOTSTEPS,
    }

    private companion object {
        const val PREFERENCES = "ropebound_game_audio"
        const val ENABLED_KEY = "enabled"
        const val AMBIENCE_VOLUME_KEY = "ambience_volume"
        const val SFX_VOLUME_KEY = "sfx_volume"
        // Keep the soundtrack clearly behind LiveKit voice chat on first launch.
        const val DEFAULT_AMBIENCE_VOLUME = 0.08f
        const val DEFAULT_SFX_VOLUME = 0.48f
        const val FOOTSTEP_VOLUME_MULTIPLIER = 0.45f
    }
}
