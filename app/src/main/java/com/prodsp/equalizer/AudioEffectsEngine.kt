package com.prodsp.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer

/** Optional device effects. The main parametric EQ runs in our PCM processor. */
class AudioEffectsEngine {
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    @Volatile private var effectsEnabled = true

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        bassBoost = try { BassBoost(0, audioSessionId).also { it.enabled = effectsEnabled } } catch (_: Exception) { null }
        virtualizer = try { Virtualizer(0, audioSessionId).also { it.enabled = effectsEnabled } } catch (_: Exception) { null }
    }

    fun setBassStrength(value: Int) {
        try { bassBoost?.setStrength(value.coerceIn(0, 1000).toShort()) } catch (_: Exception) { }
    }

    fun setVirtualizerStrength(value: Int) {
        try { virtualizer?.setStrength(value.coerceIn(0, 1000).toShort()) } catch (_: Exception) { }
    }

    fun setEnabled(enabled: Boolean) {
        effectsEnabled = enabled
        try { bassBoost?.enabled = enabled } catch (_: Exception) { }
        try { virtualizer?.enabled = enabled } catch (_: Exception) { }
    }

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) { }
        try { virtualizer?.release() } catch (_: Exception) { }
        bassBoost = null
        virtualizer = null
    }
}
