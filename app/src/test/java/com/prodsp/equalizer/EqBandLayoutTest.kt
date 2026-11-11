package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EqBandLayoutTest {
    @Test
    fun supportedBandCountsSpanTheEntireAudibleRange() {
        for (count in listOf(10, 15, 20, 31)) {
            val frequencies = EqBandLayout.frequencies(count)
            assertEquals(count, frequencies.size)
            assertEquals(20f, frequencies.first(), 0.01f)
            assertEquals(20_000f, frequencies.last(), 0.5f)
            for (index in 1 until frequencies.size) {
                assertTrue("Band centres must be strictly increasing", frequencies[index] > frequencies[index - 1])
            }
        }
    }

    @Test
    fun oneBandUsesTheGeometricCentre() {
        val centre = EqBandLayout.frequencies(1).single()
        assertEquals(sqrt(20f * 20_000f), centre, 0.1f)
    }

    @Test
    fun gainResamplingInterpolatesOnLogFrequencyAxis() {
        val sourceFrequencies = floatArrayOf(100f, 1_000f, 10_000f)
        val sourceGains = floatArrayOf(0f, 10f, 0f)
        val targets = floatArrayOf(100f, 316.2278f, 1_000f, 3_162.278f, 10_000f)

        val result = EqBandLayout.resampleGains(sourceFrequencies, sourceGains, targets)

        assertEquals(0f, result[0], 0.001f)
        assertEquals(5f, result[1], 0.01f)
        assertEquals(10f, result[2], 0.001f)
        assertEquals(5f, result[3], 0.01f)
        assertEquals(0f, result[4], 0.001f)
    }

    @Test
    fun changingBandCountPreservesTheBroadResponseEndpoints() {
        val tenBandFrequencies = EqBandLayout.frequencies(10)
        val gains = FloatArray(10) { index -> if (index == 0) 6f else if (index == 9) -4f else 0f }
        val thirtyOneBandFrequencies = EqBandLayout.frequencies(31)

        val remapped = EqBandLayout.resampleGains(tenBandFrequencies, gains, thirtyOneBandFrequencies)

        assertEquals(6f, remapped.first(), 0.001f)
        assertEquals(-4f, remapped.last(), 0.001f)
        assertEquals(31, remapped.size)
    }
}
