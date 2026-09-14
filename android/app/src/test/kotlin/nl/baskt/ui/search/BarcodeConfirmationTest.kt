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
    @Test fun heldBarcodeDoesNotRepeatButCanBeRescannedAfterLeavingFrame() {
        val gate = BarcodeConfirmation()
        val code = "4006381333931"
        assertNull(gate.observe(code, 0))
        assertNull(gate.observe(code, 100))
        assertEquals(code, gate.observe(code, 200))
        assertNull(gate.observe(code, 800)) // Capture animation finished, same label still visible.
        assertNull(gate.observe(null, 900)) // A brief detector dropout must not re-arm it.
        assertNull(gate.observe(code, 1000))
        assertNull(gate.observe(code, 1100))
        assertNull(gate.observe(code, 1200))
        assertNull(gate.observe(null, 1800)) // Label deliberately moved out of the frame.
        assertNull(gate.observe(code, 1900))
        assertNull(gate.observe(code, 2000))
        assertEquals(code, gate.observe(code, 2100))
    }

    @Test fun anotherBarcodeCanBeCapturedWithoutAnEmptyFrame() {
        val gate = BarcodeConfirmation()
        val first = "4006381333931"
        val second = "036000291452"
        repeat(3) { gate.observe(first, it * 100L) }
        assertNull(gate.observe(second, 300))
        assertNull(gate.observe(second, 400))
        assertEquals(second, gate.observe(second, 500))
        assertNull(gate.observe(first, 600))
        assertNull(gate.observe(first, 700))
        assertEquals(first, gate.observe(first, 800))
    }
}
