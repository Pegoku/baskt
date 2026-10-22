package nl.baskt.ui.search

/** Requires a valid retail checksum and three consecutive readings before accepting a code. */
internal class BarcodeConfirmation {
    private var candidate: String? = null
    private var count = 0
    private var lastAt = 0L
    private var latched: String? = null
    private var lastVisibleAt = 0L

    fun observe(raw: String?, now: Long): String? {
        val code = raw?.takeIf(::validRetailBarcode)
        // Re-arm only after the captured label leaves view, or another label is confirmed.
        // Brief detector dropouts must not undo a dismissal or replay a stock action.
        if (code == latched && code != null) lastVisibleAt = now
        if (code == null && now - lastVisibleAt >= 500) latched = null
        count = if (code != null && code == candidate && now - lastAt <= 750) count + 1 else 1
        candidate = code
        lastAt = now
        if (code == null || count < 3 || code == latched) return null
        latched = code
        lastVisibleAt = now
        return code
    }
}

internal fun validRetailBarcode(code: String): Boolean {
    if (code.length !in listOf(8, 12, 13) || code.any { it !in '0'..'9' }) return false
    return code.dropLast(1).reversed().mapIndexed { index, c ->
        c.digitToInt() * if (index % 2 == 0) 3 else 1
    }.sum().let { (10 - it % 10) % 10 == code.last().digitToInt() }
}

/** Expand UPC-E to UPC-A before checksum validation and lookup. */
internal fun expandUpce(code: String): String? {
    if (code.length != 8 || code.any { it !in '0'..'9' } || code[0] !in "01") return null
    val d = code.substring(1, 7)
    val body = when (d[5]) {
        '0', '1', '2' -> "${d.take(2)}${d[5]}0000${d.substring(2, 5)}"
        '3' -> "${d.take(3)}00000${d.substring(3, 5)}"
        '4' -> "${d.take(4)}00000${d[4]}"
        else -> "${d.take(5)}0000${d[5]}"
    }
    return "${code[0]}$body${code[7]}"
}

/**
 * Turn any supported scan into the retail code products are looked up by.
 * Fresh and weighed goods (Albert Heijn meat, fish, cheese) print GS1-128 or GS1 DataMatrix labels that
 * carry the GTIN under application identifier 01 next to dates and batch numbers; only that GTIN matters here.
 */
internal fun retailCode(format: Int, raw: String?): String? {
    raw ?: return null
    val code = when (format) {
        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E -> expandUpce(raw)
        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128,
        com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX -> gs1Gtin(raw)
        else -> raw
    } ?: return null
    return if (code.length == 12) "0$code" else code
}

/** GTIN-13 from a GS1 element string, or null for other Code 128 / DataMatrix content and outer-case GTINs. */
internal fun gs1Gtin(raw: String): String? {
    // Strip the symbology identifier (]C1, ]d2) and any leading FNC1 the decoder kept as a group separator.
    val data = raw.removePrefix("]C1").removePrefix("]d2").trimStart('\u001D')
    if (!data.startsWith("01") || data.length < 16) return null
    val gtin14 = data.substring(2, 16)
    // A non-zero indicator digit marks a trade unit of several items, which no shelf product matches.
    if (gtin14[0] != '0') return null
    return gtin14.drop(1).takeIf(::validRetailBarcode)
}
