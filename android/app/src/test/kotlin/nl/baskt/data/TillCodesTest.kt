package nl.baskt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TillCodesTest {
    @Test fun packAndLabelCodes() {
        assertEquals("pack", tillCodeKind("8718907471534"))
        assertEquals("pack", tillCodeKind("12345670"))
        // In-store EAN-13 with the price or weight in it.
        assertEquals("label", tillCodeKind("2112345012346"))
        assertEquals("label", tillCodeKind("]d2010871000000000031030004523922000699"))
        assertEquals("label", tillCodeKind("0108710000000000\u001d3922000699"))
        // GS1 DataMatrix with only an expiry date and batch: still the same product every time.
        assertEquals("pack", tillCodeKind("010871000000000017261231\u001d10AB12"))
        assertNull(tillCodeKind("hello"))
        assertNull(tillCodeKind("123"))
    }

    @Test fun packCodesRepeatForTheQuantityWhenScannedOnce() {
        val item = BasketItem(id = "a", text = "melk", quantity = 3, status = "MATCHED")
        val other = BasketItem(id = "b", text = "kaas", quantity = 2, status = "MATCHED")
        val codes = listOf(
            TillCode("1", "AH", "8718907471534", 32, 1, itemId = "a"),
            TillCode("2", "AH", "2112345012346", 32, 2, itemId = "b"),
            TillCode("3", "AH", "8718907471541", 32, 3),
        )
        val sequence = tillSequence(codes, listOf(item, other))
        assertEquals(listOf("1", "1", "1", "2", "3"), sequence.map { it.id })
    }
}
