package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHeadroomMathTest {
    private val sampleRate = 48_000

    @Test
    fun flatCurveNeedsNoHeadroomCompensation() {
        val attenuation = AutoHeadroomMath.calculateAttenuationDb(
            sampleRate,
            10,
            EqBandLayout.frequencies(10),
            FloatArray(10),
            FloatArray(10) { 1f },
            Array(10) { BiquadFilterType.PEAK },
            BooleanArray(10) { true },
            0f,
            120f,
            0f,
            8_000f
        )
        assertEquals(0f, attenuation, 0.01f)
    }

    @Test
    fun positivePeakBoostIsCompensatedAtItsCentre() {
        val frequencies = EqBandLayout.frequencies(10)
        val gains = FloatArray(10)
        gains[4] = 6f
        val attenuation = AutoHeadroomMath.calculateAttenuationDb(
            sampleRate,
            10,
            frequencies,
            gains,
            FloatArray(10) { 1f },
            Array(10) { BiquadFilterType.PEAK },
            BooleanArray(10) { true },
            0f,
            120f,
            0f,
            8_000f
        )
        assertEquals(6f, attenuation, 0.05f)
    }

    @Test
    fun overlappingBoostsAreAddedBeforeHeadroomIsCalculated() {
        val frequencies = floatArrayOf(1_000f, 1_000f)
        val attenuation = AutoHeadroomMath.calculateAttenuationDb(
            sampleRate,
            2,
            frequencies,
            floatArrayOf(4f, 3f),
            floatArrayOf(1f, 1f),
            arrayOf(BiquadFilterType.PEAK, BiquadFilterType.PEAK),
            booleanArrayOf(true, true),
            0f,
            120f,
            0f,
            8_000f
        )
        assertEquals(7f, attenuation, 0.05f)
    }

    @Test
    fun disabledBoostBandDoesNotConsumeHeadroom() {
        val attenuation = AutoHeadroomMath.calculateAttenuationDb(
            sampleRate,
            2,
            floatArrayOf(500f, 1_000f),
            floatArrayOf(12f, 0f),
            floatArrayOf(1f, 1f),
            arrayOf(BiquadFilterType.PEAK, BiquadFilterType.PEAK),
            booleanArrayOf(false, true),
            0f,
            120f,
            0f,
            8_000f
        )
        assertEquals(0f, attenuation, 0.01f)
    }

    @Test
    fun toneShelfBoostAlsoConsumesHeadroom() {
        val attenuation = AutoHeadroomMath.calculateAttenuationDb(
            sampleRate,
            10,
            EqBandLayout.frequencies(10),
            FloatArray(10),
            FloatArray(10) { 1f },
            Array(10) { BiquadFilterType.PEAK },
            BooleanArray(10) { true },
            5f,
            120f,
            4f,
            8_000f
        )
        assertTrue("Shelf boost was not included", attenuation >= 4.9f)
    }
}
