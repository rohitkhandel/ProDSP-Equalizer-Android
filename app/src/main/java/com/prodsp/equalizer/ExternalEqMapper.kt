package com.prodsp.equalizer

import kotlin.math.roundToInt

/**
 * Maps ProDSP's parametric response onto the fixed-band Android Equalizer
 * effect for external player sessions. The local Media3 player continues to
 * use the full ProDSP PCM processor.
 */
internal object ExternalEqMapper {
    fun responseDb(
        snapshot: ParametricDspProcessor.Snapshot,
        frequencyHz: Float,
        sampleRate: Int = 48_000
    ): Float {
        if (!snapshot.enabled || sampleRate <= 0) return 0f
        val safeFrequency = frequencyHz.coerceIn(20f, sampleRate * 0.475f)
        val count = snapshot.bandCount.coerceIn(0, 31)
        val activeBands = BooleanArray(31) { index ->
            index < count &&
                snapshot.bandEnabled[index] &&
                (snapshot.soloBandIndex < 0 || snapshot.soloBandIndex == index)
        }

        val attenuation = if (snapshot.autoHeadroomEnabled) {
            AutoHeadroomMath.calculateAttenuationDb(
                sampleRate = sampleRate,
                bandCount = count,
                centerFrequenciesHz = snapshot.centerFrequencies,
                gainsDb = snapshot.gainsDb,
                qValues = snapshot.qValues,
                filterTypes = snapshot.bandTypes,
                bandEnabled = activeBands,
                lowToneGainDb = snapshot.lowToneDb,
                lowToneFrequencyHz = snapshot.lowToneFrequencyHz,
                highToneGainDb = snapshot.highToneDb,
                highToneFrequencyHz = snapshot.highToneFrequencyHz
            )
        } else {
            0f
        }

        var response = snapshot.preampDb - attenuation
        if (snapshot.lowToneDb != 0f) {
            val shelf = BiquadMath.coefficients64(
                sampleRate, snapshot.lowToneFrequencyHz, 0.707f,
                snapshot.lowToneDb, BiquadFilterType.LOW_SHELF
            )
            response += BiquadMath.magnitudeDb64(shelf, safeFrequency, sampleRate)
        }

        for (index in 0 until count) {
            if (!activeBands[index]) continue
            val filter = BiquadMath.coefficients64(
                sampleRate,
                snapshot.centerFrequencies[index],
                snapshot.qValues[index],
                snapshot.gainsDb[index],
                snapshot.bandTypes[index]
            )
            response += BiquadMath.magnitudeDb64(filter, safeFrequency, sampleRate)
        }

        if (snapshot.highToneDb != 0f) {
            val shelf = BiquadMath.coefficients64(
                sampleRate, snapshot.highToneFrequencyHz, 0.707f,
                snapshot.highToneDb, BiquadFilterType.HIGH_SHELF
            )
            response += BiquadMath.magnitudeDb64(shelf, safeFrequency, sampleRate)
        }
        return response
    }

    fun toMillibels(gainDb: Float, minimum: Short, maximum: Short): Short {
        return (gainDb * 100f).roundToInt()
            .coerceIn(minimum.toInt(), maximum.toInt())
            .toShort()
    }
}
