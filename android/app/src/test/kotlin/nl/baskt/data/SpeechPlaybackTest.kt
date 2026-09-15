package nl.baskt.data

import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackTest {
    @Test fun longRepliesKeepAllWordsWithinProviderLimit() {
        val text = (1..900).joinToString(" ") { "word$it" }
        val chunks = speechChunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 1800 })
        assertEquals(text, chunks.joinToString(" "))
    }
    @Test fun unbrokenTextIsNotTruncated() {
        val text = "a".repeat(4000)
        assertEquals(text, speechChunks(text).joinToString(""))
        assertTrue(speechChunks(text).all { it.length <= 1800 })
    }
    @Test fun removesDisplayMarkupAndSkipsEmptyText() {
        assertEquals(listOf("Hello there"), speechChunks("**Hello** <b>there</b>"))
        assertTrue(speechChunks(" ").isEmpty())
    }
}
