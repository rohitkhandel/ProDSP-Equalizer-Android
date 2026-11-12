package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCatalogTest {
    @Test
    fun allCategoryContainsEveryBuiltInPreset() {
        assertEquals(12, PresetCatalog.filter("", "All", emptySet()).size)
    }

    @Test
    fun categoryFilterOnlyReturnsMatchingProfiles() {
        val voices = PresetCatalog.filter("", "Voice", emptySet())
        assertEquals(listOf("Vocal", "Podcast"), voices.map { it.name })
    }

    @Test
    fun searchMatchesNamesAndAliases() {
        assertEquals(listOf("Dance", "Electronic"), PresetCatalog.filter("edm", "All", emptySet()).map { it.name })
        assertEquals(listOf("Podcast"), PresetCatalog.filter("spoken word", "All", emptySet()).map { it.name })
    }

    @Test
    fun favoritesCategoryShowsOnlyStarredBuiltInProfiles() {
        val favorites = setOf("builtin:Jazz", "builtin:Bass")
        val shown = PresetCatalog.filter("", "Favorites", favorites)
        assertEquals(setOf("Jazz", "Bass"), shown.map { it.name }.toSet())
        assertTrue(shown.all { it.favoriteId in favorites })
    }
}
