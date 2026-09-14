package nl.baskt.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class BarcodeGeometryTest {
    @Test fun offCenterPointMapsBackToSameBufferPositionAtEveryRotation() {
        // Raw point (140, 310) in a 1280 × 720 buffer, deliberately far from the center.
        val expected = 140f to 310f
        assertEquals(expected, barcodePointInBuffer(140f, 310f, 1280, 720, 0))
        assertEquals(expected, barcodePointInBuffer(410f, 140f, 1280, 720, 90))
        assertEquals(expected, barcodePointInBuffer(1140f, 410f, 1280, 720, 180))
        assertEquals(expected, barcodePointInBuffer(310f, 1140f, 1280, 720, 270))
    }

    @Test fun portraitHorizontalMovementIsPreservedThroughRotation() {
        val left = barcodePointInBuffer(100f, 200f, 1280, 720, 90)
        val right = barcodePointInBuffer(600f, 200f, 1280, 720, 90)
        assertEquals(200f to 620f, left)
        assertEquals(200f to 120f, right)
    }
}
