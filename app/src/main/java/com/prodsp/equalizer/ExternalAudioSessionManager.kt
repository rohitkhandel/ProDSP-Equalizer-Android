package com.prodsp.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper

/**
 * Applies ProDSP's current EQ curve to Android's session-based Equalizer for
 * external players that publish the standard audio-effect session broadcasts.
 *
 * This is a platform bridge, not the app's full PCM DSP chain. Android decides
 * the number of EQ bands and the quality/availability of each effect.
 */
class ExternalAudioSessionManager {
    private data class SessionKey(val packageName: String, val audioSessionId: Int)

    private data class SessionEffects(
        val equalizer: Equalizer?,
        val bassBoost: BassBoost?,
        val virtualizer: Virtualizer?
    ) {
        fun release() {
            runCatching { equalizer?.release() }
            runCatching { bassBoost?.release() }
            runCatching { virtualizer?.release() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<SessionKey, SessionEffects>()
    private val applyRunnable = Runnable { applyToAllSessions() }

    @Synchronized
    fun openSession(packageName: String, audioSessionId: Int) {
        if (packageName.isBlank() || audioSessionId <= 0) return
        val key = SessionKey(packageName, audioSessionId)
        val effects = SessionEffects(
            equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull(),
            bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull(),
            virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        )
        if (effects.equalizer == null && effects.bassBoost == null && effects.virtualizer == null) {
            return
        }
        sessions.remove(key)?.release()
        sessions[key] = effects
        applySession(
            effects,
            ProDspAudioEngine.processor.snapshot(),
            ProDspAudioEngine.deviceEffects.snapshot()
        )
    }

    @Synchronized
    fun closeSession(packageName: String, audioSessionId: Int) {
        sessions.remove(SessionKey(packageName, audioSessionId))?.release()
    }

    fun scheduleApply() {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, APPLY_DEBOUNCE_MS)
    }

    @Synchronized
    private fun applyToAllSessions() {
        if (sessions.isEmpty()) return
        val dsp = ProDspAudioEngine.processor.snapshot()
        val device = ProDspAudioEngine.deviceEffects.snapshot()
        sessions.values.forEach { effects -> applySession(effects, dsp, device) }
    }

    private fun applySession(
        effects: SessionEffects,
        dsp: ParametricDspProcessor.Snapshot,
        device: AudioEffectsEngine.Snapshot
    ) {
        val enabled = dsp.enabled && device.enabled

        effects.equalizer?.let { equalizer ->
            runCatching {
                if (equalizer.hasControl()) {
                    val bandCount = equalizer.numberOfBands.toInt()
                    val range = equalizer.bandLevelRange
                    if (range.size >= 2) {
                        for (index in 0 until bandCount) {
                            val centerHz = equalizer.getCenterFreq(index.toShort()) / 1000f
                            val gainDb = if (enabled) {
                                ExternalEqMapper.responseDb(dsp, centerHz)
                            } else {
                                0f
                            }
                            equalizer.setBandLevel(
                                index.toShort(),
                                ExternalEqMapper.toMillibels(gainDb, range[0], range[1])
                            )
                        }
                    }
                    equalizer.enabled = enabled
                }
            }
        }

        effects.bassBoost?.let { bass ->
            runCatching {
                if (bass.hasControl()) {
                    bass.setStrength(device.bassStrength.toShort())
                    bass.enabled = enabled
                }
            }
        }

        effects.virtualizer?.let { virtualizer ->
            runCatching {
                if (virtualizer.hasControl()) {
                    virtualizer.setStrength(device.virtualizerStrength.toShort())
                    virtualizer.enabled = enabled
                }
            }
        }
    }

    @Synchronized
    fun releaseAll() {
        handler.removeCallbacks(applyRunnable)
        sessions.values.forEach { it.release() }
        sessions.clear()
    }

    @Synchronized
    fun activeSessionCount(): Int = sessions.size

    private companion object {
        const val APPLY_DEBOUNCE_MS = 40L
    }
}
