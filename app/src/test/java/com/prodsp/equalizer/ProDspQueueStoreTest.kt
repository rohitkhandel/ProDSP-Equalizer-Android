package com.prodsp.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProDspQueueStoreTest {
    @Test
    fun queueRoundTripsUrisAndUnicodeTitles() {
        val original = listOf(
            ProDspQueueStore.Entry(
                "content://media/external/audio/media/123",
                "A normal track"
            ),
            ProDspQueueStore.Entry(
                "content://provider/tree/a%2Fb/document/c%3Ad",
                "বাংলা 🎧 · dots.newlines\nand separators"
            )
        )

        assertEquals(original, ProDspQueueStore.decode(ProDspQueueStore.encode(original)))
    }

    @Test
    fun malformedRowsAreIgnoredWithoutLosingValidEntries() {
        val valid = ProDspQueueStore.Entry("file:///music/song.flac", "Song")
        val encoded = ProDspQueueStore.encode(listOf(valid))
        val decoded = ProDspQueueStore.decode("not-a-valid-row\n$encoded\n.")

        assertEquals(listOf(valid), decoded)
    }

    @Test
    fun queueIsCappedToFiveHundredEntries() {
        val entries = List(520) { index ->
            ProDspQueueStore.Entry("file:///music/$index.flac", "Track $index")
        }

        val decoded = ProDspQueueStore.decode(ProDspQueueStore.encode(entries))

        assertEquals(500, decoded.size)
        assertTrue(decoded.last().title == "Track 499")
    }
}
