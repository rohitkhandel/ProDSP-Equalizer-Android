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
