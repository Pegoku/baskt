package nl.baskt.data

import java.text.Normalizer
import java.util.Locale

private val ingredientUnits = setOf("g", "kg", "mg", "ml", "cl", "dl", "l", "tsp", "tbsp", "cup", "cups", "of", "de", "el", "tl")

/** Compare whole words, ignoring quantities and units; avoid partial-word matches. */
fun ingredientMatches(line: String, item: String): Boolean {
    fun words(text: String): Set<String> = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^\\p{L}]+"))
        .filter { it.isNotBlank() && it !in ingredientUnits }.toSet()
    val ingredient = words(line)
    val owned = words(item)
    return ingredient.isNotEmpty() && owned.isNotEmpty() && (ingredient.containsAll(owned) || owned.containsAll(ingredient))
}
