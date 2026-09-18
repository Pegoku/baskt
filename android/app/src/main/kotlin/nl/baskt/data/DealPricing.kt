package nl.baskt.data

import kotlin.math.roundToInt

/**
 * What a multi-buy deal ("3 voor 7,00", "1+1 gratis", "2e halve prijs") works out to per item, because the
 * store keeps listing the single-item price for those mechanisms.
 */
data class DealPricing(val count: Int, val bundleCents: Int, val eachCents: Int, val unitPriceCents: Int?, val unitPriceUnit: String?) {
    /** "€2,33 each · €11,67/kg" */
    fun label(): String = listOfNotNull("${eachCents.euros()} each", unitLabel()).joinToString(" · ")
    fun unitLabel(): String? = if (unitPriceCents != null && unitPriceUnit != null) "${unitPriceCents.euros()}/${unitPriceUnit.shortUnit()}" else null
}

private val N_FOR_PRICE = Regex("""(?<!\d)(\d+)\s*voor\s*€?\s*(\d+)[.,](\d{2})(?!\d)""", RegexOption.IGNORE_CASE)
private val N_PLUS_M_FREE = Regex("""(?<!\d)(\d+)\s*\+\s*(\d+)\s*gratis""", RegexOption.IGNORE_CASE)
private val SECOND_HALF = Regex("""2e\s+halve\s+prijs""", RegexOption.IGNORE_CASE)

/** Returns null for deals that already show in [Product.priceCents] (percent off, "VOOR 2.49") or that are not about price. */
fun Product.dealPricing(): DealPricing? {
    val text = dealText ?: return null
    val listed = regularPriceCents?.takeIf { it > priceCents } ?: priceCents
    val (count, bundleCents) = N_FOR_PRICE.find(text)?.let { match ->
        val count = match.groupValues[1].toInt()
        count to match.groupValues[2].toInt() * 100 + match.groupValues[3].toInt()
    } ?: N_PLUS_M_FREE.find(text)?.let { match ->
        val paid = match.groupValues[1].toInt()
        (paid + match.groupValues[2].toInt()) to paid * listed
    } ?: SECOND_HALF.find(text)?.let { 2 to (listed * 3 + 1) / 2 }
    ?: return null
    if (count < 2 || bundleCents <= 0) return null
    val each = (bundleCents.toDouble() / count).roundToInt()
    if (each >= priceCents) return null
    val perUnit = unitAmount?.takeIf { it > 0 }?.let { (bundleCents / (count * it)).roundToInt() }
    return DealPricing(count, bundleCents, each, perUnit, if (perUnit != null) unit else null)
}

fun String.shortUnit(): String = if (this == "piece") "st" else this
