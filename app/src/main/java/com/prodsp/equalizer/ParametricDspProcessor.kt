package com.prodsp.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real PCM parametric equalizer for ProDSP's Media3 playback path.
 * 31 logarithmically spaced peaking filters, per-channel state, coefficient
 * smoothing, preamp and a final peak guard. Processing stays on the audio thread.
 */
class ParametricDspProcessor : BaseAudioProcessor() {
    companion object {
        private const val MIN_HZ = 20f
        private const val MAX_HZ = 20_000f
        private const val TRANSITION_SAMPLES = 960 // 20 ms at 48 kHz
        private val BAND_COUNTS = intArrayOf(10, 15, 20, 31)
    }

    @Volatile private var bandCount = 10
    @Volatile private var gainsDb = FloatArray(31)
    @Volatile private var qValues = FloatArray(31) { 1.0f }
    @Volatile private var centerFrequencies = FloatArray(31) { i -> defaultFrequency(i, 31) }
    @Volatile private var lowToneDb = 0f
    @Volatile private var highToneDb = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var enabled = true
    @Volatile private var peakGuard = true
    @Volatile private var compressorEnabled = false
    @Volatile private var compressorThresholdDb = -18f
    @Volatile private var compressorRatio = 3f
    @Volatile private var compressorAttackMs = 10f
    @Volatile private var compressorReleaseMs = 120f
    @Volatile private var compressorMakeupDb = 0f
    @Volatile private var limiterEnabled = true
    @Volatile private var limiterCeilingDb = -1f

    private var compressorEnvelope = 0f
    private var compressorGain = 1f
    private var sampleRate = 0
    private var channelCount = 0
    private var filters = emptyArray<Filter>()
    private var lowShelfFilter: Filter? = null
    private var highShelfFilter: Filter? = null
    @Volatile private var transitionRemaining = 0

    data class Snapshot(val bandCount: Int, val gainsDb: FloatArray, val centerFrequencies: FloatArray, val qValues: FloatArray, val preampDb: Float, val enabled: Boolean, val peakGuard: Boolean)

    @Synchronized fun bandFrequency(index: Int): Float = centerFrequencies.getOrElse(index) { 1000f }
    @Synchronized fun bandQ(index: Int): Float = qValues.getOrElse(index) { 1f }

    @Synchronized fun configureBands(count: Int) {
        bandCount = BAND_COUNTS.minBy { abs(it - count) }
        centerFrequencies = FloatArray(31) { i -> defaultFrequency(i, bandCount) }
        updateTargets()
    }

    @Synchronized fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0 until bandCount) return
        val copy = gainsDb.copyOf()
        copy[index] = gainDb.coerceIn(-15f, 15f)
        gainsDb = copy
        updateTargets()
    }

    @Synchronized fun setAllGains(values: FloatArray) {
        val copy = FloatArray(31)
        for (i in 0 until minOf(values.size, 31)) copy[i] = values[i].coerceIn(-15f, 15f)
        gainsDb = copy
        updateTargets()
    }

    @Synchronized fun setBandFrequency(index: Int, frequencyHz: Float) {
        if (index !in 0 until bandCount) return
        val copy = centerFrequencies.copyOf()
        copy[index] = frequencyHz.coerceIn(20f, minOf(20_000f, sampleRate.takeIf { it > 0 }?.times(0.475f) ?: 20_000f))
        centerFrequencies = copy
        updateTargets()
    }

    @Synchronized fun setBandQ(index: Int, value: Float) {
        if (index !in 0 until bandCount) return
        val copy = qValues.copyOf()
        copy[index] = value.coerceIn(0.3f, 8f)
        qValues = copy
        updateTargets()
    }

    @Synchronized fun setLowToneGain(valueDb: Float) {
        lowToneDb = valueDb.coerceIn(-12f, 12f)
        updateTargets()
    }

    @Synchronized fun setHighToneGain(valueDb: Float) {
        highToneDb = valueDb.coerceIn(-12f, 12f)
        updateTargets()
    }

    fun setPreamp(valueDb: Float) { preampDb = valueDb.coerceIn(-12f, 6f) }
    fun setEnabled(value: Boolean) { enabled = value }
    fun setPeakGuard(value: Boolean) { peakGuard = value }
    fun setCompressorEnabled(value: Boolean) { compressorEnabled = value }
    fun setCompressorThreshold(valueDb: Float) { compressorThresholdDb = valueDb.coerceIn(-36f, 0f) }
    fun setCompressorRatio(value: Float) { compressorRatio = value.coerceIn(1f, 20f) }
    fun setCompressorAttack(valueMs: Float) { compressorAttackMs = valueMs.coerceIn(1f, 100f) }
    fun setCompressorRelease(valueMs: Float) { compressorReleaseMs = valueMs.coerceIn(10f, 1000f) }
    fun setCompressorMakeup(valueDb: Float) { compressorMakeupDb = valueDb.coerceIn(0f, 12f) }
    fun setLimiterEnabled(value: Boolean) { limiterEnabled = value }
    fun setLimiterCeiling(valueDb: Float) { limiterCeilingDb = valueDb.coerceIn(-12f, 0f) }
    @Synchronized fun snapshot() = Snapshot(bandCount, gainsDb.copyOf(), centerFrequencies.copyOf(), qValues.copyOf(), preampDb, enabled, peakGuard)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..2 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        filters = Array(31) { Filter(channelCount) }
        lowShelfFilter = Filter(channelCount)
        highShelfFilter = Filter(channelCount)
        updateTargets()
        return inputAudioFormat
    }

    @Synchronized private fun updateTargets() {
        if (sampleRate <= 0 || filters.isEmpty()) return
        val count = bandCount
        val gains = gainsDb.copyOf()
        val frequencies = centerFrequencies.copyOf()
        val qs = qValues.copyOf()
        for (i in 0 until 31) {
            val frequency = frequencies[i].coerceIn(20f, sampleRate * 0.475f)
            val gain = if (i < count) gains[i] else 0f
            filters[i].setTarget(peakingCoefficients(sampleRate, frequency, qs[i], gain))
        }
        lowShelfFilter?.setTarget(shelfCoefficients(sampleRate, 120f, lowToneDb, true))
        highShelfFilter?.setTarget(shelfCoefficients(sampleRate, 8_000f, highToneDb, false))
        transitionRemaining = TRANSITION_SAMPLES
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes <= 0) return
        val output = replaceOutputBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val active = enabled
        val gain = 10.0.pow((preampDb / 20.0).toDouble()).toFloat()
        val protect = peakGuard
        val useCompressor = compressorEnabled
        val useLimiter = limiterEnabled
        val attackSeconds = compressorAttackMs / 1000f
        val releaseSeconds = compressorReleaseMs / 1000f
        val attackCoeff = if (sampleRate > 0) exp(-1f / (attackSeconds * sampleRate)) else 0f
        val releaseCoeff = if (sampleRate > 0) exp(-1f / (releaseSeconds * sampleRate)) else 0f
        val makeup = 10.0.pow((compressorMakeupDb / 20.0).toDouble()).toFloat()
        val ceiling = 10.0.pow((limiterCeilingDb / 20.0).toDouble()).toFloat()
        val frame = FloatArray(channelCount)

        while (input.remaining() >= 2 * channelCount) {
            if (active) {
                lowShelfFilter?.advance(transitionRemaining)
                for (band in 0 until bandCount) filters[band].advance(transitionRemaining)
                highShelfFilter?.advance(transitionRemaining)
            }
            var framePeak = 0f
            for (channel in 0 until channelCount) {
                var sample = input.short.toInt() / 32768f
                if (active) {
                    sample = lowShelfFilter?.process(sample, channel) ?: sample
                    for (band in 0 until bandCount) sample = filters[band].process(sample, channel)
                    sample = highShelfFilter?.process(sample, channel) ?: sample
                    sample *= gain
                }
                frame[channel] = sample
                framePeak = maxOf(framePeak, abs(sample))
            }

            if (useCompressor) {
                val detectorCoeff = if (framePeak > compressorEnvelope) attackCoeff else releaseCoeff
                compressorEnvelope = detectorCoeff * compressorEnvelope + (1f - detectorCoeff) * framePeak
                val levelDb = 20f * kotlin.math.log10(compressorEnvelope.coerceAtLeast(1e-6f))
                val overThreshold = (levelDb - compressorThresholdDb).coerceAtLeast(0f)
                val compressedLevelDb = compressorThresholdDb + overThreshold / compressorRatio
                val targetGain = 10.0.pow(((compressedLevelDb - levelDb) / 20.0).toDouble()).toFloat()
                val gainCoeff = if (targetGain < compressorGain) attackCoeff else releaseCoeff
                compressorGain = gainCoeff * compressorGain + (1f - gainCoeff) * targetGain
            } else {
                compressorEnvelope = 0f
                compressorGain = 1f
            }

            for (channel in 0 until channelCount) {
                var sample = frame[channel]
                if (useCompressor) sample *= compressorGain * makeup
                if (protect) sample = softLimit(sample)
                if (useLimiter) sample = sample.coerceIn(-ceiling, ceiling)
                val quantized = (sample.coerceIn(-1f, 0.9999695f) * 32768f).toInt()
                output.putShort(quantized.toShort())
            }
            if (transitionRemaining > 0) transitionRemaining--
        }
        inputBuffer.position(input.position())
        output.flip()
    }

    private fun defaultFrequency(index: Int, count: Int): Float {
        if (count <= 1) return 1000f
        return (MIN_HZ * (MAX_HZ / MIN_HZ).pow(index.toFloat() / (count - 1))).coerceIn(MIN_HZ, MAX_HZ)
    }

    private fun peakingCoefficients(rate: Int, frequency: Float, q: Float, gainDb: Float): Coefficients {
        val f = frequency.coerceIn(10f, rate * 0.475f)
        val a = 10.0.pow((gainDb / 40.0).toDouble()).toFloat()
        val w0 = (2.0 * PI * f / rate).toFloat()
        val alpha = sin(w0) / (2f * q)
        val c = cos(w0)
        val a0 = 1f + alpha / a
        return Coefficients(
            (1f + alpha * a) / a0,
            (-2f * c) / a0,
            (1f - alpha * a) / a0,
            (-2f * c) / a0,
            (1f - alpha / a) / a0
        )
    }

    private fun shelfCoefficients(rate: Int, frequency: Float, gainDb: Float, lowShelf: Boolean): Coefficients {
        if (abs(gainDb) < 0.0001f) return Coefficients(1f, 0f, 0f, 0f, 0f)
        val a = 10.0.pow((gainDb / 40.0).toDouble()).toFloat()
        val w0 = (2.0 * PI * frequency.coerceIn(20f, rate * 0.45f) / rate).toFloat()
        val cosine = cos(w0)
        val sine = sin(w0)
        val alpha = sine * 0.5f * sqrt((a + 1f / a) * 0f + 2f)
        val beta = 2f * sqrt(a) * alpha
        val a0: Float
        val b0: Float
        val b1: Float
        val b2: Float
        val a1: Float
        val a2: Float
        if (lowShelf) {
            b0 = a * ((a + 1f) - (a - 1f) * cosine + beta)
            b1 = 2f * a * ((a - 1f) - (a + 1f) * cosine)
            b2 = a * ((a + 1f) - (a - 1f) * cosine - beta)
            a0 = (a + 1f) + (a - 1f) * cosine + beta
            a1 = -2f * ((a - 1f) + (a + 1f) * cosine)
            a2 = (a + 1f) + (a - 1f) * cosine - beta
        } else {
            b0 = a * ((a + 1f) + (a - 1f) * cosine + beta)
            b1 = -2f * a * ((a - 1f) + (a + 1f) * cosine)
            b2 = a * ((a + 1f) + (a - 1f) * cosine - beta)
            a0 = (a + 1f) - (a - 1f) * cosine + beta
            a1 = 2f * ((a - 1f) - (a + 1f) * cosine)
            a2 = (a + 1f) - (a - 1f) * cosine - beta
        }
        return Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun softLimit(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= 0.88f) return x
        val compressed = 0.88f + 0.12f * (1f - exp(-((magnitude - 0.88f) / 0.12f)))
        return if (x < 0f) -compressed else compressed
    }

    override fun onFlush() {
        compressorEnvelope = 0f
        compressorGain = 1f
        filters.forEach { it.clearState() }
        lowShelfFilter?.clearState()
        highShelfFilter?.clearState()
        updateTargets()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        compressorEnvelope = 0f
        compressorGain = 1f
        filters = emptyArray()
        lowShelfFilter = null
        highShelfFilter = null
    }

    private data class Coefficients(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    private class Filter(channels: Int) {
        private var current = Coefficients(1f, 0f, 0f, 0f, 0f)
        @Volatile private var target = current
        private val z1 = FloatArray(channels)
        private val z2 = FloatArray(channels)

        fun setTarget(value: Coefficients) { target = value }

        fun advance(remaining: Int) {
            if (remaining <= 0) {
                current = target
                return
            }
            val t = 1f / remaining.coerceAtLeast(1)
            val from = current
            val to = target
            current = Coefficients(
                from.b0 + (to.b0 - from.b0) * t,
                from.b1 + (to.b1 - from.b1) * t,
                from.b2 + (to.b2 - from.b2) * t,
                from.a1 + (to.a1 - from.a1) * t,
                from.a2 + (to.a2 - from.a2) * t
            )
        }

        fun process(input: Float, channel: Int): Float {
            val c = current
            val output = c.b0 * input + z1[channel]
            z1[channel] = c.b1 * input - c.a1 * output + z2[channel]
            z2[channel] = c.b2 * input - c.a2 * output
            return output
        }

        fun clearState() {
            z1.fill(0f)
            z2.fill(0f)
        }
    }
}
