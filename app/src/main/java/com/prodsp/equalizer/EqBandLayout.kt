package com.prodsp.equalizer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shared logarithmic band layout used by the UI and audio engine.
 *
 * Frequencies are distributed across the audible range. Keeping this
 * calculation in one place prevents the UI and DSP from using different
 * centre frequencies for the same EQ band.
 */
internal object EqBandLayout {
    const val MIN_FREQUENCY_HZ = 20f
    const val MAX_FREQUENCY_HZ = 20_000f
    const val MAX_BANDS = 31

    fun frequencies(
        count: Int,
        minHz: Float = MIN_FREQUENCY_HZ,
        maxHz: Float = MAX_FREQUENCY_HZ
    ): FloatArray {
        require(minHz > 0f && maxHz > minHz) { "Invalid frequency range" }
        val safeCount = count.coerceIn(1, MAX_BANDS)
        if (safeCount == 1) return floatArrayOf(sqrt(minHz * maxHz))

        val minLog = ln(minHz)
        val span = ln(maxHz) - minLog
        return FloatArray(safeCount) { index ->
            exp(minLog + span * index / (safeCount - 1).toFloat())
        }
    }

    fun frequencyAt(index: Int, count: Int): Float {
        val layout = frequencies(count)
        return layout[index.coerceIn(layout.indices)]
    }

    /** Resamples gain in dB along the logarithmic frequency axis. */
    fun resampleGains(
        sourceFrequenciesHz: FloatArray,
        sourceGainsDb: FloatArray,
        targetFrequenciesHz: FloatArray
    ): FloatArray {
        val size = minOf(sourceFrequenciesHz.size, sourceGainsDb.size)
        if (size == 0) return FloatArray(targetFrequenciesHz.size)

        val points = (0 until size)
            .map { sourceFrequenciesHz[it].coerceAtLeast(1f) to sourceGainsDb[it] }
            .sortedBy { it.first }

        return FloatArray(targetFrequenciesHz.size) { index ->
            val target = targetFrequenciesHz[index].coerceAtLeast(1f)
            when {
                target <= points.first().first -> points.first().second
                target >= points.last().first -> points.last().second
                else -> {
                    val upperIndex = points.indexOfFirst { it.first >= target }.coerceAtLeast(1)
                    val lower = points[upperIndex - 1]
                    val upper = points[upperIndex]
                    val lowerLog = ln(lower.first)
                    val upperLog = ln(upper.first)
                    if (upperLog <= lowerLog) {
                        upper.second
                    } else {
                        val fraction = ((ln(target) - lowerLog) / (upperLog - lowerLog))
                            .coerceIn(0f, 1f)
                        lower.second + (upper.second - lower.second) * fraction
                    }
                }
            }
        }
    }

    /** Index of the nearest old centre frequency for each new centre. */
    fun nearestSourceIndices(
        sourceFrequenciesHz: FloatArray,
        targetFrequenciesHz: FloatArray
    ): IntArray {
        if (sourceFrequenciesHz.isEmpty()) return IntArray(targetFrequenciesHz.size) { 0 }
        return IntArray(targetFrequenciesHz.size) { index ->
            val targetLog = ln(targetFrequenciesHz[index].coerceAtLeast(1f))
            sourceFrequenciesHz.indices.minBy { sourceIndex ->
                abs(ln(sourceFrequenciesHz[sourceIndex].coerceAtLeast(1f)) - targetLog)
            }
        }
    }

    /** Carries the nearest old control value to each new centre frequency. */
    fun resampleNearest(
        sourceFrequenciesHz: FloatArray,
        sourceValues: FloatArray,
        targetFrequenciesHz: FloatArray
    ): FloatArray {
        val size = minOf(sourceFrequenciesHz.size, sourceValues.size)
        if (size == 0) return FloatArray(targetFrequenciesHz.size)
        val safeSources = sourceFrequenciesHz.copyOf(size)
        val indices = nearestSourceIndices(safeSources, targetFrequenciesHz)
        return FloatArray(targetFrequenciesHz.size) { sourceValues[indices[it]] }
    }
}
