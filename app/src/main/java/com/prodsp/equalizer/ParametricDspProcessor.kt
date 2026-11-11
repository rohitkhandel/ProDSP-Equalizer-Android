package com.prodsp.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time PCM DSP processor for ProDSP's Media3 playback path.
 *
 * The engine supports 31 second-order bands, linked per-channel filter state,
 * Graphic/Parametric editing, RBJ filter modes, smooth coefficient changes,
 * preamp, stereo image processing and dynamics.
 */
class ParametricDspProcessor : BaseAudioProcessor() {

    @Volatile private var bandCount = 10
    @Volatile private var gainsDb = FloatArray(31)
    @Volatile private var qValues = FloatArray(31) { 1.0f }
    @Volatile private var bandTypes = Array(31) { BiquadFilterType.PEAK }
    @Volatile private var bandEnabled = BooleanArray(31) { true }
    @Volatile private var soloBandIndex = -1
    @Volatile private var centerFrequencies = FloatArray(31) { i ->
        if (i < 10) defaultFrequency(i, 10) else defaultFrequency(i, 31)
    }
    @Volatile private var lowToneDb = 0f
    @Volatile private var highToneDb = 0f
    @Volatile private var lowToneFrequencyHz = 120f
    @Volatile private var highToneFrequencyHz = 8_000f
    @Volatile private var preampDb = 0f
    @Volatile private var autoHeadroomEnabled = false
    @Volatile private var automaticHeadroomDb = 0f
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
    @Volatile private var limiterReleaseMs = 80f
    @Volatile private var stereoWidth = 1f
    @Volatile private var stereoBalance = 0f

    private var compressorEnvelope = 0f
    private var compressorGain = 1f
    private var limiterGain = 1f
    private val fftSize = 2048
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)
    private val fftRightReal = FloatArray(fftSize)
    private val fftRightImag = FloatArray(fftSize)
    private val fftWindow = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private var fftWriteIndex = 0
    @Volatile private var spectrumBands = FloatArray(48)
    @Volatile private var sampleRate = 0
    private var channelCount = 0
    private val frameScratch = FloatArray(2)
    private val dryFrameScratch = FloatArray(2)
    private var filters = emptyArray<Filter>()
    private var lowShelfFilter: Filter? = null
    private var highShelfFilter: Filter? = null
    @Volatile private var transitionRemaining = 0
    private var enabledMix = 1f
    private var currentPreampGain = 1f
    private var currentStereoWidth = 1f
    private var currentStereoBalance = 0f

    data class Snapshot(
        val bandCount: Int,
        val gainsDb: FloatArray,
        val centerFrequencies: FloatArray,
        val qValues: FloatArray,
        val bandTypes: Array<BiquadFilterType>,
        val bandEnabled: BooleanArray,
        val soloBandIndex: Int,
        val preampDb: Float,
        val autoHeadroomEnabled: Boolean,
        val enabled: Boolean,
        val peakGuard: Boolean,
        val lowToneDb: Float,
        val highToneDb: Float,
        val lowToneFrequencyHz: Float,
        val highToneFrequencyHz: Float,
        val compressorEnabled: Boolean,
        val compressorThresholdDb: Float,
        val compressorRatio: Float,
        val compressorAttackMs: Float,
        val compressorReleaseMs: Float,
        val compressorMakeupDb: Float,
        val limiterEnabled: Boolean,
        val limiterCeilingDb: Float,
        val limiterReleaseMs: Float,
        val stereoWidthPercent: Float,
        val stereoBalancePercent: Float
    )

    @Synchronized fun bandFrequency(index: Int): Float = centerFrequencies.getOrElse(index) { 1000f }
    @Synchronized fun bandQ(index: Int): Float = qValues.getOrElse(index) { 1f }

    @Synchronized fun configureBands(count: Int) {
        // Preserve custom frequencies and Q values when the active band
        // count changes. The UI explicitly supplies the new layout.
        bandCount = count.coerceIn(1, 31)
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0 until bandCount) return
        val copy = gainsDb.copyOf()
        copy[index] = gainDb.coerceIn(-15f, 15f)
        gainsDb = copy
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setAllGains(values: FloatArray) {
        val copy = FloatArray(31)
        for (i in 0 until minOf(values.size, 31)) copy[i] = values[i].coerceIn(-15f, 15f)
        gainsDb = copy
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setBandFrequency(index: Int, frequencyHz: Float) {
        if (index !in 0 until bandCount) return
        val copy = centerFrequencies.copyOf()
        copy[index] = frequencyHz.coerceIn(20f, minOf(20_000f, sampleRate.takeIf { it > 0 }?.times(0.475f) ?: 20_000f))
        centerFrequencies = copy
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setBandQ(index: Int, value: Float) {
        if (index !in 0 until bandCount) return
        val copy = qValues.copyOf()
        copy[index] = value.coerceIn(0.3f, 8f)
        qValues = copy
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setBandType(index: Int, type: BiquadFilterType) {
        if (index !in 0 until bandCount) return
        val copy = bandTypes.copyOf()
        copy[index] = type
        bandTypes = copy
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setBandEnabled(index: Int, isEnabled: Boolean) {
        if (index !in 0 until bandCount) return
        val copy = bandEnabled.copyOf()
        copy[index] = isEnabled
        bandEnabled = copy
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setSoloBand(index: Int) {
        soloBandIndex = if (index in 0 until bandCount) index else -1
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setLowToneGain(valueDb: Float) {
        lowToneDb = valueDb.coerceIn(-12f, 12f)
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setHighToneGain(valueDb: Float) {
        highToneDb = valueDb.coerceIn(-12f, 12f)
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setLowToneFrequency(valueHz: Float) {
        lowToneFrequencyHz = valueHz.coerceIn(30f, 800f)
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    @Synchronized fun setHighToneFrequency(valueHz: Float) {
        highToneFrequencyHz = valueHz.coerceIn(1_000f, 18_000f)
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }

    fun setPreamp(valueDb: Float) { preampDb = valueDb.coerceIn(-12f, 6f); ProDspAudioEngine.notifySettingsChanged() }
    @Synchronized fun setAutoHeadroom(enabled: Boolean) {
        autoHeadroomEnabled = enabled
        updateTargets()
        ProDspAudioEngine.notifySettingsChanged()
    }
    fun currentAutoHeadroomDb(): Float = automaticHeadroomDb
    fun setEnabled(value: Boolean) { enabled = value; ProDspAudioEngine.notifySettingsChanged() }
    fun setPeakGuard(value: Boolean) { peakGuard = value; ProDspAudioEngine.notifySettingsChanged() }
    fun setCompressorEnabled(value: Boolean) { compressorEnabled = value; ProDspAudioEngine.notifySettingsChanged() }
    fun setCompressorThreshold(valueDb: Float) { compressorThresholdDb = valueDb.coerceIn(-36f, 0f); ProDspAudioEngine.notifySettingsChanged() }
    fun setCompressorRatio(value: Float) { compressorRatio = value.coerceIn(1f, 20f); ProDspAudioEngine.notifySettingsChanged() }
    fun setCompressorAttack(valueMs: Float) { compressorAttackMs = valueMs.coerceIn(1f, 100f); ProDspAudioEngine.notifySettingsChanged() }
    fun setCompressorRelease(valueMs: Float) { compressorReleaseMs = valueMs.coerceIn(10f, 1000f); ProDspAudioEngine.notifySettingsChanged() }
    fun setCompressorMakeup(valueDb: Float) { compressorMakeupDb = valueDb.coerceIn(0f, 12f); ProDspAudioEngine.notifySettingsChanged() }
    fun setLimiterEnabled(value: Boolean) { limiterEnabled = value; ProDspAudioEngine.notifySettingsChanged() }
    fun setLimiterCeiling(valueDb: Float) { limiterCeilingDb = valueDb.coerceIn(-12f, 0f); ProDspAudioEngine.notifySettingsChanged() }
    fun setLimiterRelease(valueMs: Float) { limiterReleaseMs = valueMs.coerceIn(10f, 500f); ProDspAudioEngine.notifySettingsChanged() }
    fun setStereoWidth(percent: Float) { stereoWidth = percent.coerceIn(0f, 200f) / 100f; ProDspAudioEngine.notifySettingsChanged() }
    fun setStereoBalance(percent: Float) { stereoBalance = percent.coerceIn(-100f, 100f); ProDspAudioEngine.notifySettingsChanged() }
    @Synchronized fun snapshot() = Snapshot(
        bandCount = bandCount,
        gainsDb = gainsDb.copyOf(),
        centerFrequencies = centerFrequencies.copyOf(),
        qValues = qValues.copyOf(),
        bandTypes = bandTypes.copyOf(),
        bandEnabled = bandEnabled.copyOf(),
        soloBandIndex = soloBandIndex,
        preampDb = preampDb,
        autoHeadroomEnabled = autoHeadroomEnabled,
        enabled = enabled,
        peakGuard = peakGuard,
        lowToneDb = lowToneDb,
        highToneDb = highToneDb,
        lowToneFrequencyHz = lowToneFrequencyHz,
        highToneFrequencyHz = highToneFrequencyHz,
        compressorEnabled = compressorEnabled,
        compressorThresholdDb = compressorThresholdDb,
        compressorRatio = compressorRatio,
        compressorAttackMs = compressorAttackMs,
        compressorReleaseMs = compressorReleaseMs,
        compressorMakeupDb = compressorMakeupDb,
        limiterEnabled = limiterEnabled,
        limiterCeilingDb = limiterCeilingDb,
        limiterReleaseMs = limiterReleaseMs,
        stereoWidthPercent = stereoWidth * 100f,
        stereoBalancePercent = stereoBalance
    )

    fun spectrumSnapshot(): FloatArray = spectrumBands.copyOf()
    fun currentSampleRate(): Int = sampleRate.takeIf { it > 0 } ?: 48_000
    fun maximumFrequencyHz(): Float = minOf(20_000f, currentSampleRate() * 0.475f).coerceAtLeast(20f)

    private fun pushSpectrumSample(leftSample: Float, rightSample: Float) {
        val window = fftWindow[fftWriteIndex]
        fftReal[fftWriteIndex] = leftSample * window
        fftImag[fftWriteIndex] = 0f
        fftRightReal[fftWriteIndex] = rightSample * window
        fftRightImag[fftWriteIndex] = 0f
        fftWriteIndex++
        if (fftWriteIndex < fftSize) return
        fftWriteIndex = 0

        // Bit-reverse both channels before the radix-2 FFT.
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val leftReal = fftReal[i]
                fftReal[i] = fftReal[j]
                fftReal[j] = leftReal
                val leftImag = fftImag[i]
                fftImag[i] = fftImag[j]
                fftImag[j] = leftImag

                val rightReal = fftRightReal[i]
                fftRightReal[i] = fftRightReal[j]
                fftRightReal[j] = rightReal
                val rightImag = fftRightImag[i]
                fftRightImag[i] = fftRightImag[j]
                fftRightImag[j] = rightImag
            }
        }

        var size = 2
        while (size <= fftSize) {
            val half = size shr 1
            val angle = (-2.0 * PI / size).toFloat()
            val stepReal = kotlin.math.cos(angle)
            val stepImag = kotlin.math.sin(angle)
            var blockStart = 0
            while (blockStart < fftSize) {
                var wr = 1f
                var wi = 0f
                for (offset in 0 until half) {
                    val even = blockStart + offset
                    val odd = even + half

                    val leftTr = wr * fftReal[odd] - wi * fftImag[odd]
                    val leftTi = wr * fftImag[odd] + wi * fftReal[odd]
                    fftReal[odd] = fftReal[even] - leftTr
                    fftImag[odd] = fftImag[even] - leftTi
                    fftReal[even] += leftTr
                    fftImag[even] += leftTi

                    val rightTr = wr * fftRightReal[odd] - wi * fftRightImag[odd]
                    val rightTi = wr * fftRightImag[odd] + wi * fftRightReal[odd]
                    fftRightReal[odd] = fftRightReal[even] - rightTr
                    fftRightImag[odd] = fftRightImag[even] - rightTi
                    fftRightReal[even] += rightTr
                    fftRightImag[even] += rightTi

                    val nextWr = wr * stepReal - wi * stepImag
                    wi = wr * stepImag + wi * stepReal
                    wr = nextWr
                }
                blockStart += size
            }
            size = size shl 1
        }

        val previous = spectrumBands
        val next = FloatArray(48)
        val rate = sampleRate.coerceAtLeast(1).toFloat()
        val scale = 2f / fftSize
        for (band in next.indices) {
            val lowHz = 30f * (16_000f / 30f).pow(band.toFloat() / next.size)
            val highHz = 30f * (16_000f / 30f).pow((band + 1f) / next.size)
            val firstBin = (lowHz * fftSize / rate).toInt().coerceIn(1, fftSize / 2 - 1)
            val lastBin = (highHz * fftSize / rate).toInt().coerceIn(firstBin + 1, fftSize / 2)
            var magnitude = 0f
            for (bin in firstBin until lastBin) {
                val leftReal = fftReal[bin]
                val leftImag = fftImag[bin]
                val rightReal = fftRightReal[bin]
                val rightImag = fftRightImag[bin]
                val leftPower = leftReal * leftReal + leftImag * leftImag
                val rightPower = rightReal * rightReal + rightImag * rightImag
                val combined = sqrt((leftPower + rightPower) * 0.5f) * scale
                magnitude = maxOf(magnitude, combined)
            }
            val db = 20f * kotlin.math.log10(magnitude.coerceAtLeast(1e-5f))
            val normalized = ((db + 72f) / 72f).coerceIn(0f, 1f)
            next[band] = maxOf(normalized, previous[band] * 0.72f)
        }
        spectrumBands = next
    }

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
        if (sampleRate <= 0 || filters.isEmpty()) {
            ProDspAudioEngine.notifySettingsChanged()
            return
        }
        val count = bandCount
        val gains = gainsDb.copyOf()
        val frequencies = centerFrequencies.copyOf()
        val qs = qValues.copyOf()
        val types = bandTypes.copyOf()
        val enabledBands = bandEnabled.copyOf()
        val solo = soloBandIndex
        for (i in 0 until 31) {
            if (i >= count || !enabledBands[i] || (solo >= 0 && solo != i)) {
                filters[i].setTarget(BiquadCoefficients(1f, 0f, 0f, 0f, 0f))
            } else {
                val frequency = frequencies[i].coerceIn(20f, sampleRate * 0.475f)
                filters[i].setTarget(BiquadMath.coefficients(sampleRate, frequency, qs[i], gains[i], types[i]))
            }
        }
        lowShelfFilter?.setTarget(BiquadMath.coefficients(sampleRate, lowToneFrequencyHz, 0.707f, lowToneDb, BiquadFilterType.LOW_SHELF))
        highShelfFilter?.setTarget(BiquadMath.coefficients(sampleRate, highToneFrequencyHz, 0.707f, highToneDb, BiquadFilterType.HIGH_SHELF))

        if (autoHeadroomEnabled) {
            val effectiveEnabled = BooleanArray(31) { index ->
                index < count && enabledBands[index] && (solo < 0 || solo == index)
            }
            automaticHeadroomDb = AutoHeadroomMath.calculateAttenuationDb(
                sampleRate,
                count,
                frequencies,
                gains,
                qs,
                types,
                effectiveEnabled,
                lowToneDb,
                lowToneFrequencyHz,
                highToneDb,
                highToneFrequencyHz
            )
        } else {
            automaticHeadroomDb = 0f
        }
        transitionRemaining = (sampleRate * 0.02f).toInt().coerceAtLeast(1)
        ProDspAudioEngine.notifySettingsChanged()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes <= 0) return
        val output = replaceOutputBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val targetDspMix = if (enabled) 1f else 0f
        val eqMixStep = if (sampleRate > 0) 1f / (sampleRate * 0.01f) else 1f
        val effectivePreampDb = preampDb - automaticHeadroomDb
        val targetPreampGain = 10.0.pow((effectivePreampDb / 20.0).toDouble()).toFloat()
        val protect = peakGuard
        val useCompressor = compressorEnabled
        val useLimiter = limiterEnabled
        val attackSeconds = compressorAttackMs / 1000f
        val releaseSeconds = compressorReleaseMs / 1000f
        val attackCoeff = if (sampleRate > 0) exp(-1f / (attackSeconds * sampleRate)) else 0f
        val releaseCoeff = if (sampleRate > 0) exp(-1f / (releaseSeconds * sampleRate)) else 0f
        val makeup = 10.0.pow((compressorMakeupDb / 20.0).toDouble()).toFloat()
        val ceiling = 10.0.pow((limiterCeilingDb / 20.0).toDouble()).toFloat()
        val limiterReleaseSeconds = limiterReleaseMs / 1000f
        val limiterReleaseCoeff = if (sampleRate > 0) {
            exp(-1f / (limiterReleaseSeconds * sampleRate))
        } else 0f
        val targetWidth = stereoWidth
        val targetBalance = stereoBalance
        val controlSmoothing = if (sampleRate > 0) {
            (1f - exp(-1f / (0.01f * sampleRate))).coerceIn(0f, 1f)
        } else 1f
        val frame = frameScratch

        while (input.remaining() >= 2 * channelCount) {
            // Keep filter state warm even while bypassed, then blend the
            // processed and dry signals over 10 ms to avoid abrupt switching.
            lowShelfFilter?.advance(transitionRemaining)
            for (band in 0 until bandCount) filters[band].advance(transitionRemaining)
            highShelfFilter?.advance(transitionRemaining)
            enabledMix += (targetDspMix - enabledMix).coerceIn(-eqMixStep, eqMixStep)
            currentPreampGain += (targetPreampGain - currentPreampGain) * controlSmoothing
            currentStereoWidth += (targetWidth - currentStereoWidth) * controlSmoothing
            currentStereoBalance += (targetBalance - currentStereoBalance) * controlSmoothing

            var framePeak = 0f
            for (channel in 0 until channelCount) {
                val drySample = input.short.toInt() / 32768f
                dryFrameScratch[channel] = drySample
                var wetSample = lowShelfFilter?.process(drySample, channel) ?: drySample
                for (band in 0 until bandCount) wetSample = filters[band].process(wetSample, channel)
                wetSample = highShelfFilter?.process(wetSample, channel) ?: wetSample
                wetSample *= currentPreampGain
                frame[channel] = wetSample
            }

            // Mid/Side stereo width. 100% is transparent, 0% folds to mono,
            // and values above 100% increase the side component.
            if (channelCount == 2 && kotlin.math.abs(currentStereoWidth - 1f) > 0.0001f) {
                val left = frame[0]
                val right = frame[1]
                frame[0] = StereoWidthMath.left(left, right, currentStereoWidth * 100f)
                frame[1] = StereoWidthMath.right(left, right, currentStereoWidth * 100f)
            }
            if (channelCount == 2 && kotlin.math.abs(currentStereoBalance) > 0.0001f) {
                frame[0] *= StereoWidthMath.leftBalanceGain(currentStereoBalance)
                frame[1] *= StereoWidthMath.rightBalanceGain(currentStereoBalance)
            }
            for (channel in 0 until channelCount) framePeak = maxOf(framePeak, abs(frame[channel]))

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

            // Apply compression and peak guard first, then measure the
            // post-dynamics stereo-linked peak for the output limiter.
            var postDynamicsPeak = 0f
            for (channel in 0 until channelCount) {
                var sample = frame[channel]
                if (useCompressor) sample *= compressorGain * makeup
                if (protect) sample = softLimit(sample)
                frame[channel] = sample
                postDynamicsPeak = maxOf(postDynamicsPeak, abs(sample))
            }

            if (useLimiter) {
                val targetLimiterGain = if (postDynamicsPeak > ceiling) {
                    ceiling / postDynamicsPeak.coerceAtLeast(1e-9f)
                } else {
                    1f
                }
                // Instant linked attack protects the ceiling. Recovery uses
                // a smooth exponential release to avoid abrupt gain pumping.
                if (targetLimiterGain < limiterGain) {
                    limiterGain = targetLimiterGain
                } else {
                    limiterGain += (1f - limiterGain) * (1f - limiterReleaseCoeff)
                }
            } else {
                limiterGain = 1f
            }

            for (channel in 0 until channelCount) {
                var sample = frame[channel]
                if (useLimiter) sample *= limiterGain
                // The master DSP switch crossfades the complete processed
                // signal against the original PCM, not just the EQ filters.
                sample = dryFrameScratch[channel] +
                    (sample - dryFrameScratch[channel]) * enabledMix
                frame[channel] = sample
                val quantized = (sample.coerceIn(-1f, 0.9999695f) * 32768f).toInt()
                output.putShort(quantized.toShort())
            }
            val spectrumLeft = frame[0]
            val spectrumRight = if (channelCount == 2) frame[1] else frame[0]
            pushSpectrumSample(spectrumLeft, spectrumRight)
            if (transitionRemaining > 0) transitionRemaining--
        }
        inputBuffer.position(input.position())
        output.flip()
    }

    private fun defaultFrequency(index: Int, count: Int): Float =
        EqBandLayout.frequencyAt(index, count)

    private fun softLimit(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= 0.88f) return x
        val compressed = 0.88f + 0.12f * (1f - exp(-((magnitude - 0.88f) / 0.12f)))
        return if (x < 0f) -compressed else compressed
    }

    override fun onFlush() {
        compressorEnvelope = 0f
        compressorGain = 1f
        limiterGain = 1f
        enabledMix = if (enabled) 1f else 0f
        currentPreampGain = 10.0.pow(((preampDb - automaticHeadroomDb) / 20.0).toDouble()).toFloat()
        currentStereoWidth = stereoWidth
        currentStereoBalance = stereoBalance
        fftWriteIndex = 0
        spectrumBands = FloatArray(48)
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
        limiterGain = 1f
        fftWriteIndex = 0
        spectrumBands = FloatArray(48)
        filters = emptyArray()
        lowShelfFilter = null
        highShelfFilter = null
    }

    private class Filter(channels: Int) {
        // Mutable scalar coefficients avoid allocating a data class on the
        // audio thread for every sample during a parameter transition.
        private var currentB0 = 1f
        private var currentB1 = 0f
        private var currentB2 = 0f
        private var currentA1 = 0f
        private var currentA2 = 0f
        @Volatile private var target = BiquadCoefficients(1f, 0f, 0f, 0f, 0f)
        private val z1 = FloatArray(channels)
        private val z2 = FloatArray(channels)

        fun setTarget(value: BiquadCoefficients) {
            target = value
        }

        fun advance(remaining: Int) {
            val next = target
            if (remaining <= 0) {
                currentB0 = next.b0
                currentB1 = next.b1
                currentB2 = next.b2
                currentA1 = next.a1
                currentA2 = next.a2
                return
            }
            val amount = 1f / remaining.coerceAtLeast(1)
            currentB0 += (next.b0 - currentB0) * amount
            currentB1 += (next.b1 - currentB1) * amount
            currentB2 += (next.b2 - currentB2) * amount
            currentA1 += (next.a1 - currentA1) * amount
            currentA2 += (next.a2 - currentA2) * amount
        }

        fun process(input: Float, channel: Int): Float {
            val output = currentB0 * input + z1[channel]
            z1[channel] = currentB1 * input - currentA1 * output + z2[channel]
            z2[channel] = currentB2 * input - currentA2 * output
            return output
        }

        fun clearState() {
            z1.fill(0f)
            z2.fill(0f)
        }
    }
}
