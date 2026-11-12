package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadMathTest {
    private val sampleRate = 48_000

    @Test
    fun doublePrecisionResponseRetainsRequestedCentreGain() {
        val coefficients = BiquadMath.coefficients64(
            sampleRate, 1_000f, 1f, 6f, BiquadFilterType.PEAK
        )
        assertEquals(6f, BiquadMath.magnitudeDb64(coefficients, 1_000f, sampleRate), 0.02f)
    }

    @Test
    fun peakingFilterHasRequestedGainAtItsCentreFrequency() {
        val coefficients = BiquadMath.coefficients(
            sampleRate, 1_000f, 1f, 6f, BiquadFilterType.PEAK
        )
        val response = BiquadMath.magnitudeDb(coefficients, 1_000f, sampleRate)
        assertEquals(6f, response, 0.02f)
    }

    @Test
    fun peakingGainRemainsCorrectAcrossCommonSampleRates() {
        for (rate in listOf(22_050, 44_100, 48_000, 96_000)) {
            val coefficients = BiquadMath.coefficients(
                rate, 1_000f, 0.9f, 4.5f, BiquadFilterType.PEAK
            )
            assertEquals(
                "Incorrect centre gain at $rate Hz",
                4.5f,
                BiquadMath.magnitudeDb(coefficients, 1_000f, rate),
                0.03f
            )
        }
    }

    @Test
    fun zeroGainPeakFilterIsUnityAcrossTheSpectrum() {
        val coefficients = BiquadMath.coefficients(
            sampleRate, 2_400f, 1.2f, 0f, BiquadFilterType.PEAK
        )
        for (frequency in listOf(30f, 100f, 1_000f, 8_000f, 18_000f)) {
            assertEquals(0f, BiquadMath.magnitudeDb(coefficients, frequency, sampleRate), 0.001f)
        }
    }

    @Test
    fun lowPassAndHighPassHaveExpectedPassAndStopBands() {
        val lowPass = BiquadMath.coefficients(sampleRate, 1_000f, 0.707f, 0f, BiquadFilterType.LOW_PASS)
        val highPass = BiquadMath.coefficients(sampleRate, 1_000f, 0.707f, 0f, BiquadFilterType.HIGH_PASS)

        assertTrue(BiquadMath.magnitudeDb(lowPass, 100f, sampleRate) > -0.1f)
        assertTrue(BiquadMath.magnitudeDb(lowPass, 10_000f, sampleRate) < -35f)
        assertTrue(BiquadMath.magnitudeDb(highPass, 100f, sampleRate) < -35f)
        assertTrue(BiquadMath.magnitudeDb(highPass, 10_000f, sampleRate) > -0.1f)
    }

    @Test
    fun notchFilterAttenuatesItsCentreFrequency() {
        val notch = BiquadMath.coefficients(sampleRate, 1_000f, 2f, 0f, BiquadFilterType.NOTCH)
        assertTrue(BiquadMath.magnitudeDb(notch, 1_000f, sampleRate) < -60f)
        assertTrue(BiquadMath.magnitudeDb(notch, 100f, sampleRate) > -0.1f)
    }

    @Test
    fun shelvesHaveCorrectLowAndHighFrequencyGains() {
        val lowShelf = BiquadMath.coefficients(sampleRate, 150f, 0.707f, 6f, BiquadFilterType.LOW_SHELF)
        val highShelf = BiquadMath.coefficients(sampleRate, 8_000f, 0.707f, -4f, BiquadFilterType.HIGH_SHELF)

        assertEquals(6f, BiquadMath.magnitudeDb(lowShelf, 25f, sampleRate), 0.1f)
        assertEquals(0f, BiquadMath.magnitudeDb(lowShelf, 15_000f, sampleRate), 0.1f)
        assertEquals(0f, BiquadMath.magnitudeDb(highShelf, 100f, sampleRate), 0.1f)
        assertEquals(-4f, BiquadMath.magnitudeDb(highShelf, 18_000f, sampleRate), 0.1f)
    }

    @Test
    fun everyFilterTypeProducesFiniteCoefficients() {
        for (type in BiquadFilterType.entries) {
            val coefficients = BiquadMath.coefficients(sampleRate, 900f, 1.1f, 5f, type)
            val values = listOf(coefficients.b0, coefficients.b1, coefficients.b2, coefficients.a1, coefficients.a2)
            assertTrue("$type has non-finite coefficients", values.all { it.isFinite() })
        }
    }
}
