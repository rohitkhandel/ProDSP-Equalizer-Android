package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqProfileParserTest {
    @Test
    fun parsesEqualizerApoParametricFilters() {
        val profile = AutoEqProfileParser.parse(
            """
            # Example AutoEQ profile
            Preamp: -4.5 dB
            Filter 1: ON PK Fc 20 Hz Gain -1.2 dB Q 0.71
            Filter 2: ON LS Fc 105 Hz Gain 2.4 dB Q 0.8
            Filter 3: OFF HS Fc 9000 Hz Gain -1.0 dB Q 0.707
            """.trimIndent()
        )

        assertEquals(-4.5f, profile.preampDb, 0.0001f)
        assertEquals(3, profile.filters.size)
        assertEquals(BiquadFilterType.PEAK, profile.filters[0].type)
        assertEquals(20f, profile.filters[0].frequencyHz, 0.0001f)
        assertEquals(-1.2f, profile.filters[0].gainDb, 0.0001f)
        assertEquals(BiquadFilterType.LOW_SHELF, profile.filters[1].type)
        assertEquals(0.8f, profile.filters[1].q, 0.0001f)
        assertFalse(profile.filters[2].enabled)
        assertEquals(BiquadFilterType.HIGH_SHELF, profile.filters[2].type)
    }

    @Test
    fun parsesGraphicEqPointsAndSortsByFrequency() {
        val profile = AutoEqProfileParser.parse(
            """
            Preamp: -2 dB
            GraphicEQ: 100 1.5; 20 -2; 1000 3;
            """.trimIndent()
        )

        assertEquals(-2f, profile.preampDb, 0.0001f)
        assertTrue(profile.filters.isEmpty())
        assertEquals(3, profile.graphicFrequenciesHz.size)
        assertEquals(20f, profile.graphicFrequenciesHz[0], 0.0001f)
        assertEquals(100f, profile.graphicFrequenciesHz[1], 0.0001f)
        assertEquals(1000f, profile.graphicFrequenciesHz[2], 0.0001f)
        assertEquals(-2f, profile.graphicGainsDb[0], 0.0001f)
        assertEquals(1.5f, profile.graphicGainsDb[1], 0.0001f)
    }

    @Test
    fun rejectsMalformedGraphicEqInsteadOfSilentlyApplyingPartialData() {
        try {
            AutoEqProfileParser.parse("GraphicEQ: 20 0; invalid; 1000 2")
            throw AssertionError("Malformed GraphicEQ should fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("Invalid GraphicEQ point") == true)
        }
    }

    @Test
    fun rejectsProfilesWithTooManyParametricFilters() {
        val filters = (1..32).joinToString("\n") {
            "Filter $it: ON PK Fc ${20 + it} Hz Gain 0 dB Q 1"
        }
        try {
            AutoEqProfileParser.parse(filters)
            throw AssertionError("More than 31 filters should fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("more than 31") == true)
        }
    }
}
