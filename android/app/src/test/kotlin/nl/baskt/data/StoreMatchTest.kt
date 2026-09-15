package nl.baskt.data

import org.junit.Assert.*
import org.junit.Test

class StoreMatchTest {
    @Test fun resetOnlyAppearsAfterRejectionWhenUnselectedOrChanging() {
        val pending = StoreMatch("AH", "PENDING")
        assertFalse(pending.canResetSuggestions(false))
        assertFalse(pending.canResetSuggestions(true))
        val rejected = pending.copy(hasRejectedSuggestions = true)
        assertTrue(rejected.canResetSuggestions(false))
        assertTrue(rejected.copy(status = "EXHAUSTED").canResetSuggestions(false))
        val chosen = rejected.copy(status = "CHOSEN", chosen = Product("1", "AH", "1", "Milk", quantityText = "1 l", priceCents = 100))
        assertFalse(chosen.canResetSuggestions(false))
        assertTrue(chosen.canResetSuggestions(true))
        assertFalse(chosen.copy(hasRejectedSuggestions = false).canResetSuggestions(true))
    }
}
