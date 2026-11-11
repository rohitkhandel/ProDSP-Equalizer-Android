package com.prodsp.equalizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter responses supported by each parametric EQ band. */
enum class BiquadFilterType(val title: String) {
    PEAK("Peak"),
    LOW_SHELF("Low shelf"),
    HIGH_SHELF("High shelf"),
    LOW_PASS("Low-pass"),
    HIGH_PASS("High-pass"),
    BAND_PASS("Band-pass"),
    NOTCH("Notch")
}

/** Normalized second-order biquad coefficients. */
data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float
)

/**
 * RBJ Audio EQ Cookbook coefficient and response math.
 *
 * This is intentionally pure Kotlin so filter math can be unit-tested without
 * Android audio classes. The real-time processor and response graph use the
 * same coefficient generator.
 */
object BiquadMath {
    private const val MIN_Q = 0.3f
    private const val MAX_Q = 8f

    fun coefficients(
        sampleRate: Int,
        frequencyHz: Float,
        q: Float,
        gainDb: Float,
        type: BiquadFilterType
    ): BiquadCoefficients {
        require(sampleRate > 0) { "Sample rate must be positive" }
        val nyquistSafeFrequency = (sampleRate * 0.475f).coerceAtLeast(10.1f)
        val frequency = frequencyHz.coerceIn(10f, nyquistSafeFrequency)
        val safeQ = q.coerceIn(MIN_Q, MAX_Q)
        val omega = (2.0 * PI * frequency / sampleRate).toFloat()
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2f * safeQ)
        val a = 10.0.pow(gainDb / 40.0).toFloat()

        val b0: Float
        val b1: Float
        val b2: Float
        val a0: Float
        val a1: Float
        val a2: Float

        when (type) {
            BiquadFilterType.PEAK -> {
                b0 = 1f + alpha * a
                b1 = -2f * cosine
                b2 = 1f - alpha * a
                a0 = 1f + alpha / a
                a1 = -2f * cosine
                a2 = 1f - alpha / a
            }
            BiquadFilterType.LOW_SHELF -> {
                val beta = 2f * sqrt(a) * alpha
                b0 = a * ((a + 1f) - (a - 1f) * cosine + beta)
                b1 = 2f * a * ((a - 1f) - (a + 1f) * cosine)
                b2 = a * ((a + 1f) - (a - 1f) * cosine - beta)
                a0 = (a + 1f) + (a - 1f) * cosine + beta
                a1 = -2f * ((a - 1f) + (a + 1f) * cosine)
                a2 = (a + 1f) + (a - 1f) * cosine - beta
            }
            BiquadFilterType.HIGH_SHELF -> {
                val beta = 2f * sqrt(a) * alpha
                b0 = a * ((a + 1f) + (a - 1f) * cosine + beta)
                b1 = -2f * a * ((a - 1f) + (a + 1f) * cosine)
                b2 = a * ((a + 1f) + (a - 1f) * cosine - beta)
                a0 = (a + 1f) - (a - 1f) * cosine + beta
                a1 = 2f * ((a - 1f) - (a + 1f) * cosine)
                a2 = (a + 1f) - (a - 1f) * cosine - beta
            }
            BiquadFilterType.LOW_PASS -> {
                b0 = (1f - cosine) / 2f
                b1 = 1f - cosine
                b2 = (1f - cosine) / 2f
                a0 = 1f + alpha
                a1 = -2f * cosine
                a2 = 1f - alpha
            }
            BiquadFilterType.HIGH_PASS -> {
                b0 = (1f + cosine) / 2f
                b1 = -(1f + cosine)
                b2 = (1f + cosine) / 2f
                a0 = 1f + alpha
                a1 = -2f * cosine
                a2 = 1f - alpha
            }
            BiquadFilterType.BAND_PASS -> {
                b0 = alpha
                b1 = 0f
                b2 = -alpha
                a0 = 1f + alpha
                a1 = -2f * cosine
                a2 = 1f - alpha
            }
            BiquadFilterType.NOTCH -> {
                b0 = 1f
                b1 = -2f * cosine
                b2 = 1f
                a0 = 1f + alpha
                a1 = -2f * cosine
                a2 = 1f - alpha
            }
        }

        val safeA0 = if (kotlin.math.abs(a0) < 1e-12f) 1e-12f else a0
        return BiquadCoefficients(
            b0 / safeA0,
            b1 / safeA0,
            b2 / safeA0,
            a1 / safeA0,
            a2 / safeA0
        )
    }

    /** Returns this filter's magnitude response at one frequency, in dB. */
    data class FrequencyPoint(
        val sampleRate: Int,
        val frequencyHz: Float,
        val cos1: Float,
        val sin1: Float,
        val cos2: Float,
        val sin2: Float
    )

    fun frequencyPoint(frequencyHz: Float, sampleRate: Int): FrequencyPoint {
        val safeRate = sampleRate.coerceAtLeast(1)
        val safeFrequency = frequencyHz.coerceIn(0f, safeRate * 0.5f)
        val omega = (2.0 * PI * safeFrequency / safeRate).toFloat()
        return FrequencyPoint(
            sampleRate = safeRate,
            frequencyHz = safeFrequency,
            cos1 = cos(omega),
            sin1 = sin(omega),
            cos2 = cos(2f * omega),
            sin2 = sin(2f * omega)
        )
    }

    fun magnitudeDb(coefficients: BiquadCoefficients, frequency: FrequencyPoint): Float {
        val numeratorReal =
            coefficients.b0 + coefficients.b1 * frequency.cos1 + coefficients.b2 * frequency.cos2
        val numeratorImag =
            -coefficients.b1 * frequency.sin1 - coefficients.b2 * frequency.sin2
        val denominatorReal =
            1f + coefficients.a1 * frequency.cos1 + coefficients.a2 * frequency.cos2
        val denominatorImag =
            -coefficients.a1 * frequency.sin1 - coefficients.a2 * frequency.sin2
        val numeratorPower = numeratorReal * numeratorReal + numeratorImag * numeratorImag
        val denominatorPower = denominatorReal * denominatorReal + denominatorImag * denominatorImag
        val magnitude = sqrt(numeratorPower / denominatorPower.coerceAtLeast(1e-18f))
        return 20f * log10(magnitude.coerceAtLeast(1e-9f))
    }

    fun magnitudeDb(coefficients: BiquadCoefficients, frequencyHz: Float, sampleRate: Int): Float =
        magnitudeDb(coefficients, frequencyPoint(frequencyHz, sampleRate))

    fun combinedMagnitudeDb(
        filters: List<Pair<BiquadCoefficients, Int>>,
        frequencyHz: Float
    ): Float {
        return filters.sumOf { (coefficients, sampleRate) ->
            magnitudeDb(coefficients, frequencyHz, sampleRate).toDouble()
        }.toFloat()
    }
}
