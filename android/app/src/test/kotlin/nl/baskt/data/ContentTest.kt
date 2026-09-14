package nl.baskt.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContentTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun recipeOriginalSurvivesOfflineSerialization() {
        val source = RecipeDetail("Koekjes", ingredientLines = listOf("250 g bloem"), steps = listOf(RecipeStep("Bak 20 minuten op 180°C.", "https://example.com/step.jpg")), description = "Voor vier personen")
        val translated = source.copy(title = "Cookies", ingredientLines = listOf("250 g flour"), original = source, language = "en", translationAvailable = true)
        val saved = json.decodeFromString<RecipeDetail>(json.encodeToString(translated))
        assertEquals(source, saved.original)
        assertEquals("Cookies", saved.title)
        assertEquals("250 g bloem", saved.original!!.ingredientLines.single())
    }
    @Test fun olderDownloadsStillDecodeWithoutInventingOriginalContent() {
        val legacy = json.decodeFromString<RecipeDetail>("""{"title":"Cookies","ingredientLines":["250 g flour"],"originalTitle":"Koekjes","language":"en"}""")
        assertNull(legacy.original)
        assertFalse(legacy.translationAvailable)
    }
}
