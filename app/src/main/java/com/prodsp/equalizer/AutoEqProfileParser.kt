package com.prodsp.equalizer

/**
 * Parser for common AutoEQ exports:
 * - Equalizer APO parametric profiles (Preamp + Filter N: ON PK/LS/HS ...)
 * - AutoEQ GraphicEQ frequency/gain point lists.
 *
 * This class only parses profile data. The Activity maps that data onto the
 * available DSP bands and applies Android/sample-rate-safe limits.
 */
internal object AutoEqProfileParser {
    data class Filter(
        val enabled: Boolean,
        val type: BiquadFilterType,
        val frequencyHz: Float,
        val gainDb: Float,
        val q: Float
    )

    data class Profile(
        val preampDb: Float,
        val filters: List<Filter>,
        val graphicFrequenciesHz: FloatArray,
        val graphicGainsDb: FloatArray
    )

    private const val NUMBER = """[+-]?(?:\d+(?:\.\d*)?|\.\d+)"""
    private val preampRegex = Regex(
        """^Preamp\s*:\s*($NUMBER)\s*(?:dB)?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val filterRegex = Regex(
        """^Filter\s+\d+\s*:\s*(ON|OFF)\s+(PK|LS|HS|LP|HP|BP|NO)\s+Fc\s+($NUMBER)\s*Hz\s+Gain\s+($NUMBER)\s*dB(?:\s+Q\s+($NUMBER))?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val graphicPointRegex = Regex("""^($NUMBER)\s+($NUMBER)$""")

    fun parse(source: String): Profile {
        require(source.isNotBlank()) { "Profile is empty" }
        require(source.length <= 256 * 1024) { "Profile is too large" }

        var preamp = 0f
        var preampSeen = false
        val filters = ArrayList<Filter>()
        var graphicLine: String? = null

        source.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach

            if (line.startsWith("Preamp", ignoreCase = true)) {
                val match = preampRegex.matchEntire(line)
                    ?: throw IllegalArgumentException("Invalid Preamp line")
                preamp = match.groupValues[1].toFloat()
                require(preamp.isFinite() && preamp in -36f..36f) { "Preamp value is out of range" }
                preampSeen = true
                return@forEach
            }

            if (line.startsWith("Filter", ignoreCase = true)) {
                val match = filterRegex.matchEntire(line)
                    ?: throw IllegalArgumentException("Unsupported AutoEQ filter syntax")
                val type = when (match.groupValues[2].uppercase()) {
                    "PK" -> BiquadFilterType.PEAK
                    "LS" -> BiquadFilterType.LOW_SHELF
                    "HS" -> BiquadFilterType.HIGH_SHELF
                    "LP" -> BiquadFilterType.LOW_PASS
                    "HP" -> BiquadFilterType.HIGH_PASS
                    "BP" -> BiquadFilterType.BAND_PASS
                    "NO" -> BiquadFilterType.NOTCH
                    else -> throw IllegalArgumentException("Unsupported filter type")
                }
                val frequency = match.groupValues[3].toFloat()
                val gain = match.groupValues[4].toFloat()
                val q = match.groupValues[5].ifBlank { "0.707" }.toFloat()
                require(frequency.isFinite() && frequency in 1f..24_000f) { "Filter frequency is out of range" }
                require(gain.isFinite() && gain in -36f..36f) { "Filter gain is out of range" }
                require(q.isFinite() && q in 0.05f..50f) { "Filter Q is out of range" }
                filters += Filter(
                    enabled = match.groupValues[1].equals("ON", ignoreCase = true),
                    type = type,
                    frequencyHz = frequency,
                    gainDb = gain,
                    q = q
                )
                require(filters.size <= EqBandLayout.MAX_BANDS) { "AutoEQ profile has more than 31 filters" }
                return@forEach
            }

            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                require(graphicLine == null) { "Profile contains multiple GraphicEQ lines" }
                require(line.substringBefore(':', "").equals("GraphicEQ", ignoreCase = true)) {
                    "Invalid GraphicEQ line"
                }
                graphicLine = line.substringAfter(':', "").trim()
            }
        }

        if (filters.isNotEmpty()) {
            return Profile(
                preampDb = if (preampSeen) preamp else 0f,
                filters = filters.sortedBy { it.frequencyHz },
                graphicFrequenciesHz = FloatArray(0),
                graphicGainsDb = FloatArray(0)
            )
        }

        val graphic = graphicLine ?: throw IllegalArgumentException("No supported AutoEQ filters or GraphicEQ data found")
        val points = graphic.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { point ->
                val match = graphicPointRegex.matchEntire(point)
                    ?: throw IllegalArgumentException("Invalid GraphicEQ point")
                val frequency = match.groupValues[1].toFloat()
                val gain = match.groupValues[2].toFloat()
                require(frequency.isFinite() && frequency in 1f..24_000f) { "GraphicEQ frequency is out of range" }
                require(gain.isFinite() && gain in -36f..36f) { "GraphicEQ gain is out of range" }
                frequency to gain
            }
            .sortedBy { it.first }

        require(points.size in 2..512) { "GraphicEQ must contain between 2 and 512 points" }
        require(points.zipWithNext().all { (a, b) -> b.first > a.first }) {
            "GraphicEQ frequencies must be unique"
        }

        return Profile(
            preampDb = if (preampSeen) preamp else 0f,
            filters = emptyList(),
            graphicFrequenciesHz = FloatArray(points.size) { points[it].first },
            graphicGainsDb = FloatArray(points.size) { points[it].second }
        )
    }
}
