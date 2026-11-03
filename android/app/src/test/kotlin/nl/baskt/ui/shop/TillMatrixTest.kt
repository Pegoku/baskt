package nl.baskt.ui.shop

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import nl.baskt.data.TillCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The re-drawn code must read back as what the camera saw, separators and all. */
class TillMatrixTest {
    private class MatrixSource(private val matrix: BitMatrix) : LuminanceSource(matrix.width, matrix.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = row?.takeIf { it.size >= width } ?: ByteArray(width)
            for (x in 0 until width) out[x] = if (matrix.get(x, y)) 0 else 255.toByte()
            return out
        }
        override fun getMatrix(): ByteArray = ByteArray(width * height) { i -> if (matrix.get(i % width, i / width)) 0 else 255.toByte() }
    }

    private fun decode(code: TillCode): String {
        val matrix = tillMatrix(code)!!
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(MatrixSource(matrix))), mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.PURE_BARCODE to true))
        return result.text
    }

    @Test fun ean13RoundTrips() {
        assertEquals("8718907471534", decode(TillCode("1", "AH", "8718907471534", 32, 0)))
    }

    @Test fun weighedLabelRoundTrips() {
        assertEquals("2112345012346", decode(TillCode("1", "AH", "2112345012346", 32, 0)))
    }

    @Test fun gs1WithoutSeparatorsStaysGs1() {
        // Only fixed-length fields: ML Kit hands it over as plain digits, longer than any GTIN.
        assertEquals("\u001d010871000000000031030004523922000699", decode(TillCode("1", "AH", "]d2010871000000000031030004523922000699", 16, 0)))
    }

    @Test fun gs1DataMatrixKeepsItsSeparators() {
        val raw = "0108710000000000\u001d3103000452\u001d3922000699"
        // zxing reads every FNC1 back as GS, the leading one too: that one marks the symbol as GS1.
        assertEquals("\u001d" + raw, decode(TillCode("1", "AH", raw, 16, 0)))
    }
}
