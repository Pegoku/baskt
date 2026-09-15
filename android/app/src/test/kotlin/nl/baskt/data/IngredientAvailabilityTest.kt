package nl.baskt.data

import org.junit.Assert.*
import org.junit.Test

class IngredientAvailabilityTest {
    @Test fun matchesQuantitiesAccentsAndWholeWords() {
        assertTrue(ingredientMatches("200 g azúcar", "Azucar"))
        assertTrue(ingredientMatches("Milk", "Whole milk 1 l"))
        assertTrue(ingredientMatches("2 tbsp olive oil", "Olive oil"))
        assertFalse(ingredientMatches("butter", "butternut squash"))
        assertFalse(ingredientMatches("500 ml", "1 l"))
        assertFalse(ingredientMatches("flour", "Milk"))
    }
}
