package com.prodsp.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer

data class BandInfo(
    val index: Short,
    val frequencyHz: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int,
    val levelMb: Int
)

/**
 * Thin, defensive wrapper around Android's session-based audio effects.
 * Effects are attached to this app's ExoPlayer audio session, not promised
 * to affect every other app on every Android device.
 */
class AudioEffectsEngine {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    var isAvailable: Boolean = false
        private set

    fun attach(audioSessionId: Int): List<BandInfo> {
        release()
        if (audioSessionId <= 0) return emptyList()
        return try {
            val eq = Equalizer(0, audioSessionId)
            eq.enabled = true
            equalizer = eq

            bassBoost = try {
                BassBoost(0, audioSessionId).also { it.enabled = true }
            } catch (_: Exception) { null }

            virtualizer = try {
                Virtualizer(0, audioSessionId).also { it.enabled = true }
            } catch (_: Exception) { null }

            isAvailable = true
            val range = eq.bandLevelRange
            (0 until eq.numberOfBands.toInt()).map { i ->
                val index = i.toShort()
                BandInfo(
                    index = index,
                    frequencyHz = (eq.getCenterFreq(index) / 1000).coerceAtLeast(1),
                    minLevelMb = range[0].toInt(),
                    maxLevelMb = range[1].toInt(),
                    levelMb = eq.getBandLevel(index).toInt()
                )
            }
        } catch (_: Exception) {
            release()
            emptyList()
        }
    }

    fun setBandLevel(index: Short, levelMb: Int) {
        try {
            val eq = equalizer ?: return
            val range = eq.bandLevelRange
            eq.setBandLevel(index, levelMb.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
        } catch (_: Exception) { }
    }

    fun setBassStrength(value: Int) {
        try {
            bassBoost?.setStrength(value.coerceIn(0, 1000).toShort())
        } catch (_: Exception) { }
    }

    fun setVirtualizerStrength(value: Int) {
        try {
            virtualizer?.setStrength(value.coerceIn(0, 1000).toShort())
        } catch (_: Exception) { }
    }

    fun setEnabled(enabled: Boolean) {
        try { equalizer?.enabled = enabled } catch (_: Exception) { }
        try { bassBoost?.enabled = enabled } catch (_: Exception) { }
        try { virtualizer?.enabled = enabled } catch (_: Exception) { }
    }

    fun release() {
        isAvailable = false
        try { equalizer?.release() } catch (_: Exception) { }
        try { bassBoost?.release() } catch (_: Exception) { }
        try { virtualizer?.release() } catch (_: Exception) { }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }
}
