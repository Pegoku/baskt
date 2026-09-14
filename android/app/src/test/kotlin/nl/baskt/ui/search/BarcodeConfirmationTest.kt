package nl.baskt.ui.search

import org.junit.Assert.*
import org.junit.Test

class BarcodeConfirmationTest {
    @Test fun validatesRetailChecksums() {
        listOf("4006381333931", "96385074", "036000291452").forEach { assertTrue(validRetailBarcode(it)) }
        listOf("4006381333932", "12345678", "abc96385074", "123", "９6385074").forEach { assertFalse(validRetailBarcode(it)) }
        assertEquals("042100005264", expandUpce("04252614"))
        assertTrue(validRetailBarcode(expandUpce("04252614")!!))
    }

    @Test fun requiresConsistentRecentFrames() {
        val gate = BarcodeConfirmation()
        val code = "4006381333931"
        assertNull(gate.observe(code, 0))
        assertNull(gate.observe(code, 100))
        assertEquals(code, gate.observe(code, 200))
        assertNull(gate.observe(null, 300))
        assertNull(gate.observe(code, 400))
        assertNull(gate.observe(code, 1400))
        assertNull(gate.observe("036000291452", 1500))
        assertNull(gate.observe(code, 1600))
    }
}
