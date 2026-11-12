package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalEqMapperTest {
    @Test
    fun mapperPreservesParametricGainAtBandCenter() {
        val processor = ParametricDspProcessor()
        processor.configureBands(10)
        processor.setBandFrequency(0, 1_000f)
        processor.setBandQ(0, 1f)
        processor.setBandGain(0, 6f)

        val response = ExternalEqMapper.responseDb(processor.snapshot(), 1_000f)
        assertEquals(6f, response, 0.02f)
    }

    @Test
    fun mapperClampsGainToDeviceSupportedMillibelRange() {
        assertEquals(
            1_200.toShort(),
            ExternalEqMapper.toMillibels(24f, (-1_200).toShort(), 1_200.toShort())
        )
        assertEquals(
            (-1_200).toShort(),
            ExternalEqMapper.toMillibels(-24f, (-1_200).toShort(), 1_200.toShort())
        )
    }
}
