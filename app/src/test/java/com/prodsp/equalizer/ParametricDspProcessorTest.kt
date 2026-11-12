package com.prodsp.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class ParametricDspProcessorTest {
    private fun createProcessor(): ParametricDspProcessor {
        return ParametricDspProcessor().also { processor ->
            processor.configure(
                AudioProcessor.AudioFormat(
                    48_000,
                    2,
                    C.ENCODING_PCM_16BIT
                )
            )
            processor.flush()
            processor.setPeakGuard(false)
            processor.setLimiterEnabled(false)
            processor.setCompressorEnabled(false)
        }
    }

    private fun process(
        processor: ParametricDspProcessor,
        samples: ShortArray
    ): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val result = ShortArray(output.remaining() / 2) { output.short }
        return result
    }

    @Test
    fun flatEqPassesPcmSignalWithoutChangingItsLevel() {
        val processor = createProcessor()
        try {
            val input = ShortArray(2_048) { index ->
                (4_000.0 * sin(2.0 * PI * index / 71.0)).toInt().toShort()
            }
            val output = process(processor, input)
            assertEquals(input.size, output.size)
            for (index in input.indices) {
                assertTrue(
                    "Unexpected sample delta at $index: ${input[index]} -> ${output[index]}",
                    kotlin.math.abs(input[index].toInt() - output[index].toInt()) <= 1
                )
            }
        } finally {
            processor.reset()
        }
    }

    @Test
    fun masterDspBypassReturnsDryPcmEvenWhenProcessingControlsAreSet() {
        val processor = createProcessor()
        try {
            processor.setPreamp(6f)
            processor.setStereoWidth(0f)
            processor.setStereoBalance(80f)
            processor.setEnabled(false)
            processor.flush()

            val input = ShortArray(2_048) { index ->
                (3_000.0 * sin(2.0 * PI * index / 83.0)).toInt().toShort()
            }
            val output = process(processor, input)
            assertEquals(input.size, output.size)
            for (index in input.indices) {
                assertTrue(
                    "Bypass changed dry PCM at $index",
                    kotlin.math.abs(input[index].toInt() - output[index].toInt()) <= 1
                )
            }
        } finally {
            processor.reset()
        }
    }

    @Test
    fun disabledBandLeavesItsSignalRegionUnchanged() {
        val processor = createProcessor()
        try {
            processor.setBandFrequency(0, 1_000f)
            processor.setBandGain(0, 9f)
            processor.setBandEnabled(0, false)
            processor.flush()

            val frames = 6_000
            val input = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val sample = (3_000.0 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0)).toInt().toShort()
                input[frame * 2] = sample
                input[frame * 2 + 1] = sample
            }
            val output = process(processor, input)
            var inputPower = 0.0
            var outputPower = 0.0
            for (frame in frames / 2 until frames) {
                val source = input[frame * 2].toDouble()
                val rendered = output[frame * 2].toDouble()
                inputPower += source * source
                outputPower += rendered * rendered
            }
            assertEquals(1.0, sqrt(outputPower / inputPower), 0.01)
        } finally {
            processor.reset()
        }
    }

    @Test
    fun soloModeAuditionsOneBandAndBypassesTheOthers() {
        val processor = createProcessor()
        try {
            processor.setBandFrequency(0, 1_000f)
            processor.setBandQ(0, 8f)
            processor.setBandGain(0, 6f)
            processor.setBandFrequency(1, 2_000f)
            processor.setBandQ(1, 1f)
            processor.setBandGain(1, 6f)
            processor.setSoloBand(0)
            processor.flush()

            val frames = 24_000
            val input = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val time = frame.toDouble() / 48_000.0
                val sample = (
                    1_000.0 * sin(2.0 * PI * 1_000.0 * time) +
                    1_000.0 * sin(2.0 * PI * 2_000.0 * time)
                ).toInt().toShort()
                input[frame * 2] = sample
                input[frame * 2 + 1] = sample
            }
            val output = process(processor, input)

            fun componentAmplitude(pcm: ShortArray, frequencyHz: Double): Double {
                var sum = 0.0
                val startFrame = frames / 2
                for (frame in startFrame until frames) {
                    sum += pcm[frame * 2].toDouble() *
                        sin(2.0 * PI * frequencyHz * frame / 48_000.0)
                }
                return 2.0 * sum / (frames - startFrame)
            }

            val inputOneK = componentAmplitude(input, 1_000.0)
            val inputTwoK = componentAmplitude(input, 2_000.0)
            val outputOneK = componentAmplitude(output, 1_000.0)
            val outputTwoK = componentAmplitude(output, 2_000.0)

            assertEquals(10.0.pow(6.0 / 20.0), outputOneK / inputOneK, 0.08)
            assertEquals(1.0, outputTwoK / inputTwoK, 0.02)
        } finally {
            processor.reset()
        }
    }

    @Test
    fun autoHeadroomCompensatesThePeakEqBoostInTheRealPcmChain() {
        val processor = createProcessor()
        try {
            processor.setBandFrequency(0, 1_000f)
            processor.setBandQ(0, 1f)
            processor.setBandGain(0, 6f)
            processor.setAutoHeadroom(true)
            processor.flush()

            val frames = 12_000
            val input = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val sample = (2_000.0 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0))
                    .toInt().toShort()
                input[frame * 2] = sample
                input[frame * 2 + 1] = sample
            }

            val output = process(processor, input)
            var inputPower = 0.0
            var outputPower = 0.0
            for (frame in frames / 2 until frames) {
                val source = input[frame * 2].toDouble()
                val rendered = output[frame * 2].toDouble()
                inputPower += source * source
                outputPower += rendered * rendered
            }
            assertEquals(1.0, sqrt(outputPower / inputPower), 0.02)
            assertEquals(6f, processor.currentAutoHeadroomDb(), 0.05f)
        } finally {
            processor.reset()
        }
    }

    @Test
    fun stereoLinkedLimiterKeepsBothChannelsBelowTheConfiguredCeiling() {
        val processor = createProcessor()
        try {
            processor.setLimiterEnabled(true)
            processor.setLimiterCeiling(-3f)
            processor.setLimiterRelease(80f)
            processor.flush()

            val frames = 8_192
            val input = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val sample = (30_000.0 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0))
                    .toInt().toShort()
                input[frame * 2] = sample
                input[frame * 2 + 1] = sample
            }

            val output = process(processor, input)
            val outputPeak = output.maxOf { kotlin.math.abs(it.toInt()) }
            val ceilingSamples = 10.0.pow(-3.0 / 20.0) * 32_768.0

            assertTrue("Limiter did not reduce the signal peak", outputPeak > 20_000)
            assertTrue(
                "Limiter exceeded its -3 dBFS ceiling: $outputPeak",
                outputPeak <= ceilingSamples + 2.0
            )
            for (frame in 0 until frames) {
                assertEquals("Limiter must preserve the stereo image", output[frame * 2], output[frame * 2 + 1])
            }
        } finally {
            processor.reset()
        }
    }

    @Test
    fun snapshotContainsEveryLiveDspControlForUiRestoration() {
        val processor = createProcessor()
        try {
            processor.configureBands(15)
            processor.setBandFrequency(4, 1_250f)
            processor.setBandGain(4, 5.5f)
            processor.setBandQ(4, 1.7f)
            processor.setBandType(4, BiquadFilterType.NOTCH)
            processor.setBandEnabled(4, false)
            processor.setSoloBand(3)
            processor.setPreamp(-2.5f)
            processor.setAutoHeadroom(true)
            processor.setEnabled(false)
            processor.setPeakGuard(true)
            processor.setLowToneGain(3f)
            processor.setHighToneGain(-2f)
            processor.setLowToneFrequency(95f)
            processor.setHighToneFrequency(9_500f)
            processor.setCompressorEnabled(true)
            processor.setCompressorThreshold(-22f)
            processor.setCompressorRatio(4.5f)
            processor.setCompressorAttack(7f)
            processor.setCompressorRelease(180f)
            processor.setCompressorMakeup(1.5f)
            processor.setLimiterEnabled(true)
            processor.setLimiterCeiling(-2f)
            processor.setLimiterRelease(125f)
            processor.setStereoWidth(145f)
            processor.setStereoBalance(-25f)
            processor.setReverbEnabled(true)
            processor.setReverbWetPercent(62f)
            processor.setReverbRoomSizePercent(78f)
            processor.setReverbDampingPercent(41f)

            val snapshot = processor.snapshot()

            assertEquals(15, snapshot.bandCount)
            assertEquals(1_250f, snapshot.centerFrequencies[4], 0.1f)
            assertEquals(5.5f, snapshot.gainsDb[4], 0.001f)
            assertEquals(1.7f, snapshot.qValues[4], 0.001f)
            assertEquals(BiquadFilterType.NOTCH, snapshot.bandTypes[4])
            assertEquals(false, snapshot.bandEnabled[4])
            assertEquals(3, snapshot.soloBandIndex)
            assertEquals(-2.5f, snapshot.preampDb, 0.001f)
            assertEquals(true, snapshot.autoHeadroomEnabled)
            assertEquals(false, snapshot.enabled)
            assertEquals(true, snapshot.peakGuard)
            assertEquals(3f, snapshot.lowToneDb, 0.001f)
            assertEquals(-2f, snapshot.highToneDb, 0.001f)
            assertEquals(95f, snapshot.lowToneFrequencyHz, 0.1f)
            assertEquals(9_500f, snapshot.highToneFrequencyHz, 0.1f)
            assertEquals(true, snapshot.compressorEnabled)
            assertEquals(-22f, snapshot.compressorThresholdDb, 0.001f)
            assertEquals(4.5f, snapshot.compressorRatio, 0.001f)
            assertEquals(7f, snapshot.compressorAttackMs, 0.001f)
            assertEquals(180f, snapshot.compressorReleaseMs, 0.001f)
            assertEquals(1.5f, snapshot.compressorMakeupDb, 0.001f)
            assertEquals(true, snapshot.limiterEnabled)
            assertEquals(-2f, snapshot.limiterCeilingDb, 0.001f)
            assertEquals(125f, snapshot.limiterReleaseMs, 0.001f)
            assertEquals(145f, snapshot.stereoWidthPercent, 0.001f)
            assertEquals(-25f, snapshot.stereoBalancePercent, 0.001f)
            assertEquals(true, snapshot.reverbEnabled)
            assertEquals(62f, snapshot.reverbWetPercent, 0.001f)
            assertEquals(78f, snapshot.reverbRoomSizePercent, 0.001f)
            assertEquals(41f, snapshot.reverbDampingPercent, 0.001f)
        } finally {
            processor.reset()
        }
    }

    @Test
    fun spectrumAnalyzerDoesNotCancelOppositePhaseStereoChannels() {
        val processor = createProcessor()
        try {
            val frames = 8_192
            val input = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val sample = (5_000.0 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0))
                    .toInt().toShort()
                input[frame * 2] = sample
                input[frame * 2 + 1] = (-sample.toInt()).toShort()
            }

            process(processor, input)
            val spectrum = processor.spectrumSnapshot()
            assertEquals(48, spectrum.size)
            assertTrue(
                "Opposite-phase stereo signal vanished from the spectrum",
                (spectrum.maxOrNull() ?: 0f) > 0.2f
            )
        } finally {
            processor.reset()
        }
    }

    @Test
    fun liveDspChangesNotifyTheDebouncedPersistenceHook() {
        val processor = createProcessor()
        var notifications = 0
        ProDspAudioEngine.settingsChangedListener = { notifications++ }
        try {
            processor.setBandGain(0, 4f)
            processor.setPreamp(1.5f)
            processor.setCompressorRatio(4f)

            assertEquals(3, notifications)
        } finally {
            ProDspAudioEngine.settingsChangedListener = null
            processor.reset()
        }
    }

    @Test
    fun allMutableDspControlsNotifyTheDebouncedPersistenceListener() {
        var notifications = 0
        ProDspAudioEngine.settingsChangedListener = { notifications++ }
        val processor = ParametricDspProcessor()
        try {
            processor.configureBands(10)
            processor.setBandGain(0, 2f)
            processor.setAllGains(FloatArray(31) { 0.5f })
            processor.setBandFrequency(0, 150f)
            processor.setBandQ(0, 1.2f)
            processor.setBandType(0, BiquadFilterType.PEAK)
            processor.setBandEnabled(0, false)
            processor.setSoloBand(0)
            processor.setLowToneGain(1f)
            processor.setHighToneGain(2f)
            processor.setLowToneFrequency(90f)
            processor.setHighToneFrequency(9_000f)
            processor.setPreamp(-1f)
            processor.setAutoHeadroom(true)
            processor.setEnabled(true)
            processor.setPeakGuard(true)
            processor.setCompressorEnabled(true)
            processor.setCompressorThreshold(-20f)
            processor.setCompressorRatio(4f)
            processor.setCompressorAttack(8f)
            processor.setCompressorRelease(150f)
            processor.setCompressorMakeup(1f)
            processor.setLimiterEnabled(true)
            processor.setLimiterCeiling(-2f)
            processor.setLimiterRelease(100f)
            processor.setStereoWidth(120f)
            processor.setStereoBalance(-10f)
            processor.setReverbEnabled(true)
            processor.setReverbWetPercent(35f)
            processor.setReverbRoomSizePercent(65f)
            processor.setReverbDampingPercent(45f)
            assertTrue("DSP mutations did not notify persistence", notifications >= 29)
        } finally {
            ProDspAudioEngine.settingsChangedListener = null
            processor.reset()
        }
    }

    @Test
    fun preampSixDbProducesExpectedRmsIncreaseWithoutClipping() {
        val processor = createProcessor()
        try {
            processor.setPreamp(6f)
            val frames = 12_000
            val input = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                val sample = (2_500.0 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0)).toInt().toShort()
                input[frame * 2] = sample
                input[frame * 2 + 1] = sample
            }

            val output = process(processor, input)
            var inputPower = 0.0
            var outputPower = 0.0
            val firstFrame = frames / 2
            for (frame in firstFrame until frames) {
                val source = input[frame * 2].toDouble()
                val rendered = output[frame * 2].toDouble()
                inputPower += source * source
                outputPower += rendered * rendered
            }
            val ratio = sqrt(outputPower / inputPower)
            assertEquals(10.0.pow(6.0 / 20.0), ratio, 0.05)
        } finally {
            processor.reset()
        }
    }
}
