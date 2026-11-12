package com.prodsp.equalizer

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Calculates the positive EQ response that should be compensated by an
 * automatic preamp headroom stage. Filter coefficients are built once for
 * each parameter edit; only response magnitudes are evaluated on the grid.
 */
object AutoHeadroomMath {
    fun calculateAttenuationDb(
        sampleRate: Int,
        bandCount: Int,
        centerFrequenciesHz: FloatArray,
        gainsDb: FloatArray,
        qValues: FloatArray,
        filterTypes: Array<BiquadFilterType>,
        bandEnabled: BooleanArray,
        lowToneGainDb: Float,
        lowToneFrequencyHz: Float,
        highToneGainDb: Float,
        highToneFrequencyHz: Float,
        gridPoints: Int = 192
    ): Float {
        if (sampleRate <= 0 || gridPoints < 2) return 0f
        val maxFrequency = minOf(20_000f, sampleRate * 0.475f).coerceAtLeast(20f)
        val activeCount = minOf(
            bandCount,
            centerFrequenciesHz.size,
            gainsDb.size,
            qValues.size,
            filterTypes.size,
            bandEnabled.size
        ).coerceAtLeast(0)

        val activeFilters = (0 until activeCount)
            .filter { bandEnabled[it] }
            .map { index ->
                BiquadMath.coefficients(
                    sampleRate,
                    centerFrequenciesHz[index],
                    qValues[index],
                    gainsDb[index],
                    filterTypes[index]
                )
            }

        val lowShelf = BiquadMath.coefficients(
            sampleRate,
            lowToneFrequencyHz,
            0.707f,
            lowToneGainDb,
            BiquadFilterType.LOW_SHELF
        )
        val highShelf = BiquadMath.coefficients(
            sampleRate,
            highToneFrequencyHz,
            0.707f,
            highToneGainDb,
            BiquadFilterType.HIGH_SHELF
        )

        // The logarithmic grid captures broad overlapping boosts. Adding each
        // centre frequency explicitly prevents a narrow, high-Q peak from
        // falling between adjacent grid samples.
        val frequencies = FloatArray(gridPoints + activeCount) { index ->
            if (index < gridPoints) {
                val fraction = index.toFloat() / (gridPoints - 1).toFloat()
                20f * (maxFrequency / 20f).pow(fraction)
            } else {
                centerFrequenciesHz[index - gridPoints].coerceIn(20f, maxFrequency)
            }
        }.sortedArray()

        // Compute trigonometric frequency terms once per analysis point,
        // then reuse them for every active band and both tone shelves.
        val responsePoints = frequencies.map { BiquadMath.frequencyPoint(it, sampleRate) }
        var maximumBoost = 0f
        for (point in responsePoints) {
            var responseDb = 0f
            for (filter in activeFilters) {
                responseDb += BiquadMath.magnitudeDb(filter, point)
            }
            responseDb += BiquadMath.magnitudeDb(lowShelf, point)
            responseDb += BiquadMath.magnitudeDb(highShelf, point)
            maximumBoost = max(maximumBoost, responseDb)
        }
        return maximumBoost.coerceAtLeast(0f)
    }
}
