package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoWidthMathTest {
    @Test
    fun oneHundredPercentIsTransparent() {
        val left = -0.42f
        val right = 0.73f
        assertEquals(left, StereoWidthMath.left(left, right, 100f), 1e-6f)
        assertEquals(right, StereoWidthMath.right(left, right, 100f), 1e-6f)
    }

    @Test
    fun zeroPercentFoldsStereoToMono() {
        val left = -0.8f
        val right = 0.4f
        val expectedMono = (left + right) * 0.5f
        assertEquals(expectedMono, StereoWidthMath.left(left, right, 0f), 1e-6f)
        assertEquals(expectedMono, StereoWidthMath.right(left, right, 0f), 1e-6f)
    }

    @Test
    fun twoHundredPercentDoublesSideSignalAroundTheSameMidpoint() {
        val left = 0.8f
        val right = 0.2f
        assertEquals(1.1f, StereoWidthMath.left(left, right, 200f), 1e-6f)
        assertEquals(-0.1f, StereoWidthMath.right(left, right, 200f), 1e-6f)
    }

    @Test
    fun balanceAttenuatesOnlyTheOppositeChannel() {
        assertEquals(0.25f, StereoWidthMath.leftBalanceGain(75f), 1e-6f)
        assertEquals(1f, StereoWidthMath.rightBalanceGain(75f), 1e-6f)
        assertEquals(1f, StereoWidthMath.leftBalanceGain(-40f), 1e-6f)
        assertEquals(0.6f, StereoWidthMath.rightBalanceGain(-40f), 1e-6f)
        assertEquals(1f, StereoWidthMath.leftBalanceGain(0f), 1e-6f)
        assertEquals(1f, StereoWidthMath.rightBalanceGain(0f), 1e-6f)
    }

    @Test
    fun requestedWidthIsClampedToSafeControlRange() {
        assertEquals(
            StereoWidthMath.left(0.7f, -0.2f, 200f),
            StereoWidthMath.left(0.7f, -0.2f, 500f),
            1e-6f
        )
        assertEquals(
            StereoWidthMath.right(0.7f, -0.2f, 0f),
            StereoWidthMath.right(0.7f, -0.2f, -50f),
            1e-6f
        )
    }
}
