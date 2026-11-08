package nl.baskt.data

import java.text.Normalizer
import java.util.Locale

private val ingredientUnits = setOf("g", "kg", "mg", "ml", "cl", "dl", "l", "tsp", "tbsp", "cup", "cups", "of", "de", "el", "tl")

private val marks = Regex("\\p{M}+")
private val nonLetters = Regex("[^\\p{L}]+")

/** The words of an ingredient or product name, without accents, quantities or units. */
fun ingredientWords(text: String): Set<String> = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(marks, "")
    .split(nonLetters)
    .filter { it.isNotBlank() && it !in ingredientUnits }.toSet()

/** Compare whole words, ignoring quantities and units; avoid partial-word matches. */
fun ingredientWordsMatch(ingredient: Set<String>, owned: Set<String>): Boolean =
    ingredient.isNotEmpty() && owned.isNotEmpty() && (ingredient.containsAll(owned) || owned.containsAll(ingredient))

fun ingredientMatches(line: String, item: String): Boolean = ingredientWordsMatch(ingredientWords(line), ingredientWords(item))
