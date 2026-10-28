package nl.baskt.data

private val DIGITAL_LINK = Regex("""/01/(\d{14})""")
private val ELEMENT_GTIN = Regex("""^\(?01\)?(\d{14})""")

/**
 * The GTIN in a scanned product code, or null when it is not one: plain EAN/UPC digits, a GS1 element string
 * from a DataMatrix or GS1-128 ("]d2" 01 + 14 digits + weight, price...), or a GS1 Digital Link QR.
 */
fun productGtin(raw: String): String? {
    val text = raw.trim()
    // Kotlin's trim() also drops a leading GS separator, so a GS1 string can arrive as digits only.
    if (text.all { it.isDigit() } && text.length <= 14) return text.takeIf { it.length >= 8 }?.let { if (it.length == 14 && it.startsWith("0")) it.drop(1) else it }
    val gtin = DIGITAL_LINK.find(text)?.groupValues?.get(1)
        ?: ELEMENT_GTIN.find(text.removePrefix("]d2").removePrefix("]C1").removePrefix("]Q3").trimStart('\u001d'))?.groupValues?.get(1)
        ?: return null
    return if (gtin.startsWith("0")) gtin.drop(1) else gtin
}
