package com.prodsp.equalizer

/**
 * Descriptive catalogue for the app's built-in EQ curves.
 *
 * These are general listening-style starting points, not measured headphone
 * corrections. Headphone-specific AutoEQ data is loaded through profile import.
 */
internal object PresetCatalog {
    data class Entry(
        val name: String,
        val category: String,
        val description: String,
        val searchTerms: Set<String>
    ) {
        val favoriteId: String get() = "builtin:$name"
    }

    val categories = listOf("All", "Favorites", "Bass", "Music", "Voice", "Neutral", "Custom")

    val entries = listOf(
        Entry("Flat", "Neutral", "Uncoloured response with all EQ bands at zero.", setOf("reference", "neutral", "original")),
        Entry("Bass", "Bass", "Adds weight to low frequencies while keeping mids controlled.", setOf("low", "sub bass", "kick", "warm")),
        Entry("Vocal", "Voice", "Brings speech and lead vocals forward in the mix.", setOf("speech", "singer", "presence", "midrange")),
        Entry("Treble", "Neutral", "Adds extra top-end detail and brightness.", setOf("high", "clarity", "sparkle", "bright")),
        Entry("Rock", "Music", "A lively curve for guitar-driven tracks.", setOf("guitar", "drums", "band")),
        Entry("Classical", "Music", "A balanced starting point for orchestral recordings.", setOf("orchestra", "acoustic", "strings")),
        Entry("Dance", "Music", "Energetic low and high emphasis for electronic dance music.", setOf("edm", "electronic", "club")),
        Entry("Jazz", "Music", "Gentle contour for instruments and classic jazz recordings.", setOf("saxophone", "piano", "instrumental")),
        Entry("Acoustic", "Music", "A little body and presence for unplugged instruments.", setOf("guitar", "folk", "unplugged")),
        Entry("Podcast", "Voice", "Reduces deep rumble and supports speech intelligibility.", setOf("spoken word", "dialogue", "talk")),
        Entry("Electronic", "Music", "A punchy starting point for synthesizers and electronic beats.", setOf("synth", "bass", "edm")),
        Entry("Pop", "Music", "A broad, energetic starting point for modern pop mixes.", setOf("popular", "radio", "mainstream"))
    )

    fun filter(query: String, category: String, favorites: Set<String>): List<Entry> {
        val normalizedQuery = query.trim()
        return entries.filter { entry ->
            val categoryMatches = when (category) {
                "All", "Custom" -> category == "All"
                "Favorites" -> entry.favoriteId in favorites
                else -> entry.category.equals(category, ignoreCase = true)
            }
            val queryMatches = normalizedQuery.isBlank() ||
                entry.name.contains(normalizedQuery, ignoreCase = true) ||
                entry.category.contains(normalizedQuery, ignoreCase = true) ||
                entry.description.contains(normalizedQuery, ignoreCase = true) ||
                entry.searchTerms.any { it.contains(normalizedQuery, ignoreCase = true) }
            categoryMatches && queryMatches
        }
    }
}
