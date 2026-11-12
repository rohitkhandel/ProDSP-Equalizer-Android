package com.prodsp.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer

/** Optional device effects. The main parametric EQ runs in our PCM processor. */
class AudioEffectsEngine {
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    @Volatile private var effectsEnabled = true
    @Volatile private var bassStrength = 0
    @Volatile private var virtualizerStrength = 0

    data class Snapshot(val enabled: Boolean, val bassStrength: Int, val virtualizerStrength: Int)

    fun snapshot() = Snapshot(effectsEnabled, bassStrength, virtualizerStrength)

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        bassBoost = try { BassBoost(0, audioSessionId).also { it.enabled = effectsEnabled } } catch (_: Exception) { null }
        virtualizer = try { Virtualizer(0, audioSessionId).also { it.enabled = effectsEnabled } } catch (_: Exception) { null }
        applyStoredStrengths()
    }

    fun setBassStrength(value: Int) {
        bassStrength = value.coerceIn(0, 1000)
        try { bassBoost?.setStrength(bassStrength.toShort()) } catch (_: Exception) { }
        ProDspAudioEngine.notifySettingsChanged()
    }

    fun setVirtualizerStrength(value: Int) {
        virtualizerStrength = value.coerceIn(0, 1000)
        try { virtualizer?.setStrength(virtualizerStrength.toShort()) } catch (_: Exception) { }
        ProDspAudioEngine.notifySettingsChanged()
    }

    private fun applyStoredStrengths() {
        try { bassBoost?.setStrength(bassStrength.toShort()) } catch (_: Exception) { }
        try { virtualizer?.setStrength(virtualizerStrength.toShort()) } catch (_: Exception) { }
    }

    fun setEnabled(enabled: Boolean) {
        effectsEnabled = enabled
        try { bassBoost?.enabled = enabled } catch (_: Exception) { }
        try { virtualizer?.enabled = enabled } catch (_: Exception) { }
        ProDspAudioEngine.notifySettingsChanged()
    }

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) { }
        try { virtualizer?.release() } catch (_: Exception) { }
        bassBoost = null
        virtualizer = null
    }
}
