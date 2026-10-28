package nl.baskt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductCodeTest {
    @Test fun plainCodes() {
        assertEquals("8718907471534", productGtin("8718907471534"))
        assertEquals("8712345678901", productGtin("08712345678901"))
        assertNull(productGtin("123"))
    }

    @Test fun weighedLabels() {
        assertEquals("8712345678901", productGtin("]d2010871234567890131030004523922000699"))
        assertEquals("8712345678901", productGtin("\u001d010871234567890131030004523922000699"))
        assertEquals("8712345678901", productGtin("(01)08712345678901(3922)000499"))
        assertEquals("8712345678901", productGtin("https://id.gs1.org/01/08712345678901/10/ABC"))
        assertEquals("2301234005994", productGtin("2301234005994"))
    }

    @Test fun notProducts() {
        assertNull(productGtin("https://example.com/promo"))
        assertNull(productGtin("hello"))
    }
}
