package com.prodsp.equalizer

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Small deterministic queue codec. Base64-url fields keep arbitrary titles,
 * Unicode, separators and line breaks safe without a JSON dependency.
 */
internal object ProDspQueueStore {
    data class Entry(val uri: String, val title: String)

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(entries: List<Entry>): String = entries
        .take(MAX_ENTRIES)
        .joinToString("\n") { entry ->
            "${encodeField(entry.uri)}.${encodeField(entry.title)}"
        }

    fun decode(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .take(MAX_ENTRIES)
            .mapNotNull { line ->
                val separator = line.indexOf('.')
                if (separator <= 0 || separator == line.lastIndex) return@mapNotNull null
                runCatching {
                    Entry(
                        decodeField(line.substring(0, separator)),
                        decodeField(line.substring(separator + 1))
                    ).takeIf { it.uri.isNotBlank() }
                }.getOrNull()
            }
            .toList()
    }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)

    private const val MAX_ENTRIES = 500
}
