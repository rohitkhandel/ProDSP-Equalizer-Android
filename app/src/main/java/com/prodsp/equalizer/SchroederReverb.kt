package com.prodsp.equalizer

/**
 * Lightweight stereo Schroeder reverb for the local PCM playback chain.
 *
 * Four parallel feedback comb filters feed two serial all-pass diffusers per
 * channel. Delay lines are allocated only when the audio format is configured;
 * processing a frame does not allocate objects or arrays.
 */
internal class SchroederReverb(sampleRate: Int, channelCount: Int) {
    private val channelStates = Array(channelCount.coerceIn(1, 2)) { channel ->
        ChannelState(sampleRate, if (channel == 1) 23 else 0)
    }

    fun process(input: Float, channel: Int, roomSizePercent: Float, dampingPercent: Float): Float {
        val state = channelStates[channel.coerceIn(0, channelStates.lastIndex)]
        val room = roomSizePercent.coerceIn(0f, 100f) / 100f
        val damping = dampingPercent.coerceIn(0f, 100f) / 100f * 0.9f
        val feedback = 0.55f + room * 0.38f

        var output = 0f
        for (comb in state.combs) {
            output += comb.process(input, feedback, damping)
        }
        output *= 0.25f
        for (allPass in state.allPasses) {
            output = allPass.process(output)
        }
        return output * 0.5f
    }

    fun clear() {
        channelStates.forEach { it.clear() }
    }

    private class ChannelState(sampleRate: Int, stereoOffset: Int) {
        val combs = intArrayOf(1116, 1188, 1277, 1356).map { base ->
            Comb(delayLength(base + stereoOffset, sampleRate))
        }.toTypedArray()

        val allPasses = intArrayOf(556, 441).map { base ->
            AllPass(delayLength(base + stereoOffset, sampleRate))
        }.toTypedArray()

        fun clear() {
            combs.forEach { it.clear() }
            allPasses.forEach { it.clear() }
        }

        private fun delayLength(referenceSamples: Int, rate: Int): Int =
            ((referenceSamples.toLong() * rate.coerceAtLeast(1)) / 44_100L)
                .toInt()
                .coerceAtLeast(1)
    }

    private class Comb(delaySamples: Int) {
        private val buffer = FloatArray(delaySamples)
        private var index = 0
        private var dampingState = 0f

        fun process(input: Float, feedback: Float, damping: Float): Float {
            val delayed = buffer[index]
            dampingState = delayed * (1f - damping) + dampingState * damping
            buffer[index] = input + dampingState * feedback
            index++
            if (index == buffer.size) index = 0
            return delayed
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
            dampingState = 0f
        }
    }

    private class AllPass(delaySamples: Int) {
        private val buffer = FloatArray(delaySamples)
        private var index = 0

        fun process(input: Float): Float {
            val delayed = buffer[index]
            val output = delayed - input
            buffer[index] = input + delayed * 0.5f
            index++
            if (index == buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }
    }
}
