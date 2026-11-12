package com.prodsp.equalizer

import org.junit.Assert.assertTrue
import org.junit.Test

class SchroederReverbTest {
    @Test
    fun impulseProducesDelayedStereoReflections() {
        val reverb = SchroederReverb(sampleRate = 48_000, channelCount = 2)
        var leftTailPeak = 0f
        var rightTailPeak = 0f
        for (frame in 0 until 8_000) {
            val impulse = if (frame == 0) 1f else 0f
            val left = reverb.process(impulse, 0, roomSizePercent = 70f, dampingPercent = 30f)
            val right = reverb.process(impulse, 1, roomSizePercent = 70f, dampingPercent = 30f)
            if (frame > 1_500) {
                leftTailPeak = maxOf(leftTailPeak, kotlin.math.abs(left))
                rightTailPeak = maxOf(rightTailPeak, kotlin.math.abs(right))
            }
            assertTrue("Reverb output must remain finite", left.isFinite() && right.isFinite())
        }
        assertTrue("Left channel should contain delayed room reflections", leftTailPeak > 0.0001f)
        assertTrue("Right channel should contain delayed room reflections", rightTailPeak > 0.0001f)
    }

    @Test
    fun reverbRemainsStableForLargeRoomSettings() {
        val reverb = SchroederReverb(sampleRate = 48_000, channelCount = 2)
        var peak = 0f
        for (frame in 0 until 96_000) {
            val input = if (frame % 64 < 32) 0.2f else -0.2f
            val left = reverb.process(input, 0, roomSizePercent = 100f, dampingPercent = 0f)
            val right = reverb.process(-input, 1, roomSizePercent = 100f, dampingPercent = 0f)
            peak = maxOf(peak, kotlin.math.abs(left), kotlin.math.abs(right))
            assertTrue("Reverb output must remain finite", left.isFinite() && right.isFinite())
        }
        assertTrue("Reverb wet output should stay safely bounded", peak < 2f)
    }
}
