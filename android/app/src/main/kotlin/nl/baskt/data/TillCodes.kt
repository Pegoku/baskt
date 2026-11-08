package nl.baskt.data

/**
 * "label" for a code printed when the product was weighed (an in-store EAN-13 starting with 2, or a GS1 string
 * with a weight or price in it), "pack" for a fixed product barcode, null when it is not a product code.
 */
fun tillCodeKind(raw: String): String? {
    val text = raw.trim().removePrefix("]d2").removePrefix("]C1").removePrefix("]Q3").removePrefix("]e0").trimStart('\u001d')
    if (text.isEmpty()) return null
    // A GS1 string without separators (only fixed-length fields) is all digits too, just longer than a GTIN.
    if (text.all { it.isDigit() } && text.length <= 14) {
        if (text.length < 8) return null
        val gtin = if (text.length == 14 && text.startsWith("0")) text.drop(1) else text
        return if (gtin.length == 13 && gtin.startsWith("2")) "label" else "pack"
    }
    if (text.contains("/01/")) return "pack" // GS1 Digital Link: identifies the product, not this piece.
    val ais = gs1Ais(text) ?: return productGtin(raw)?.let { "pack" }
    return if (ais.any { it.startsWith("310") || it.startsWith("392") || it.startsWith("393") }) "label" else "pack"
}

private val FIXED_LENGTH = mapOf("00" to 18, "01" to 14, "02" to 14, "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6, "20" to 2)

/** The application identifiers in a GS1 element string, or null when it does not parse as one. */
internal fun gs1Ais(text: String): List<String>? {
    val ais = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        if (text[index] == '\u001d') { index++; continue }
        val two = text.substring(index, minOf(index + 2, text.length))
        val four = text.substring(index, minOf(index + 4, text.length))
        when {
            two in FIXED_LENGTH -> { ais += two; index += 2 + FIXED_LENGTH.getValue(two) }
            four.length == 4 && (four.startsWith("31") || four.startsWith("32") || four.startsWith("33") || four.startsWith("34") || four.startsWith("35") || four.startsWith("36")) -> {
                ais += four; index += 4 + 6
            }
            four.length == 4 && (four.startsWith("390") || four.startsWith("391") || four.startsWith("392") || four.startsWith("393")) -> {
                ais += four; index = text.indexOf('\u001d', index + 4).let { if (it < 0) text.length else it }
            }
            two == "10" || two == "21" || two == "22" || two == "30" || two == "37" || two == "90" || two == "91" || two == "92" -> {
                ais += two; index = text.indexOf('\u001d', index + 2).let { if (it < 0) text.length else it }
            }
            else -> return null
        }
    }
    return ais.takeIf { it.isNotEmpty() && (it.contains("01") || it.contains("02")) }
}

/**
 * The barcodes to show the till, in order: every code linked to a list item, a pack code repeated up to the
 * item's quantity when it was scanned only once, then the extras picked up along the way.
 */
fun tillSequence(codes: List<TillCode>, items: List<BasketItem>): List<TillCode> {
    val byItem = codes.groupBy { it.itemId }
    val quantities = items.associate { it.id to it.quantity }
    return codes.flatMap { code ->
        val linked = code.itemId?.let { byItem[it] }.orEmpty()
        val quantity = code.itemId?.let { quantities[it] } ?: 1
        if (linked.size == 1 && quantity > 1 && tillCodeKind(code.raw) == "pack") List(quantity) { code } else listOf(code)
    }
}

/**
 * What to send AH self-scan for a camera read, best first. The symbology identifier ("]C1", "]d2", "]Q3") is the
 * scanner's, not part of the code, and AH answers BARCODE_UNKNOWN when it is left on. A GS1 string goes as its
 * element string first, exactly as Appie sends it (captured 2026-11-07: a chicken DataMatrix went up as
 * `010871890768042417261115` and came back priced, flagged THT): a weighed label needs its weight or price and the
 * best-before date drives AH's short-date markdown. The bare GTIN-13 is only a fallback for a pack code.
 */
fun selfScanCodes(raw: String): List<String> {
    val text = raw.trim().removePrefix("]d2").removePrefix("]C1").removePrefix("]Q3").removePrefix("]e0").trimStart('\u001d').trimEnd('\u001d')
    if (text.isEmpty()) return emptyList()
    if (text.all { it.isDigit() } && text.length <= 14) return listOf(text)
    val gtin = if (gs1Ais(text) != null && tillCodeKind(text) == "pack") productGtin(text) else null
    return listOfNotNull(text, gtin).distinct()
}
