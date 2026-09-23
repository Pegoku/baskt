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

/**
 * What one pack works out to per kilo, litre or piece at [cents]. The pack size the store states wins;
 * without one the listed unit price is scaled, since the store prints it for the single-pack price.
 */
fun Product.unitPriceAt(cents: Int): Pair<Int, String>? {
    val amount = unitAmount?.takeIf { it > 0 }
    if (amount != null && unit != null) return (cents / amount).roundToInt() to unit
    val listed = unitPriceCents ?: return null
    val listedUnit = unitPriceUnit ?: return null
    if (priceCents <= 0) return null
    return (listed.toDouble() * cents / priceCents).roundToInt() to listedUnit
}

/**
 * What taking a promotion is worth next to the product an entry buys now: the price a pack really comes
 * to once a multi-buy is worked out, and the €/unit on both sides, which is the only fair comparison when
 * the deal pack is a different size.
 */
data class DealSaving(
    /** What one pack costs when the deal is used. */
    val eachCents: Int,
    /** Packs you have to take for that price; 1 when there is no multi-buy. */
    val packCount: Int,
    val packSavingCents: Int?,
    val unitPriceCents: Int?,
    val unitPriceUnit: String?,
    val currentUnitPriceCents: Int?,
    val currentPackCents: Int?,
) {
    /** Both sides state a price per the same unit, so the comparison can be made there. */
    val perUnit: Boolean get() = unitPriceCents != null && currentUnitPriceCents != null
    val percentOff: Int?
        get() {
            val from = if (perUnit) currentUnitPriceCents!! else currentPackCents ?: return null
            val to = if (perUnit) unitPriceCents!! else eachCents
            return if (from > to && from > 0) ((from - to) * 100.0 / from).roundToInt() else null
        }

    /** "Save €0,66 a pack when you take 3 · €1,98 for 3"; the share is left to [percentOff] so it is not said twice. */
    fun savingLine(quantity: Int = 1): String? {
        val saving = packSavingCents?.takeIf { it > 0 } ?: return null
        val total = if (quantity > 1) " · ${(saving * quantity).euros()} for $quantity" else ""
        return "Save ${saving.euros()} a pack$takeClause$total"
    }

    /** "€7,50/kg with the deal, now €9,20/kg" — the final price per unit, which is what the choice hangs on. */
    fun unitLine(): String? {
        val unit = unitPriceUnit?.shortUnit() ?: return null
        val deal = unitPriceCents ?: return null
        val now = currentUnitPriceCents?.let { ", now ${it.euros()}/$unit" } ?: ""
        return "${deal.euros()}/$unit with the deal$now"
    }

    /**
     * The price before and after the deal on the fairest footing there is: per kilo, litre or piece when both
     * sides state one, else per pack. [unit] is null for a pack; [fromCents] is null when nothing is bought now.
     */
    data class PriceStep(val fromCents: Int?, val toCents: Int, val unit: String?)

    fun step(): PriceStep = when {
        perUnit -> PriceStep(currentUnitPriceCents, unitPriceCents!!, unitPriceUnit!!.shortUnit())
        currentPackCents == null && unitPriceCents != null -> PriceStep(null, unitPriceCents, unitPriceUnit!!.shortUnit())
        else -> PriceStep(currentPackCents, eachCents, null)
    }

    /**
     * What is left to say under the [step]: the pack saving when the step was per unit, the unit price when it
     * was per pack, the multi-buy condition, and the total for the quantity wanted.
     */
    fun detail(quantity: Int = 1): String? {
        val parts = mutableListOf<String>()
        val saving = packSavingCents
        if (step().unit != null) {
            when {
                saving != null && saving > 0 -> parts += "Save ${saving.euros()} a pack$takeClause"
                // The bigger pack costs more at the till even though it is cheaper per kilo; worth saying plainly.
                saving != null && saving < 0 -> parts += "${eachCents.euros()} a pack$takeClause, now ${currentPackCents!!.euros()}"
                packCount > 1 -> parts += "${eachCents.euros()} a pack$takeClause"
            }
        } else {
            if (packCount > 1) parts += "When you take $packCount"
            if (unitPriceCents != null && unitPriceUnit != null) parts += "${unitPriceCents.euros()}/${unitPriceUnit.shortUnit()}"
        }
        if (saving != null && saving > 0 && quantity > 1) parts += "${(saving * quantity).euros()} for $quantity"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** True when the strip would say more than the price already on the product row. */
    fun worthShowing(): Boolean = step().let { it.fromCents != null || it.unit != null } || percentOff != null || packCount > 1

    /** Something is bought now and the deal does not beat it on the fairest footing there is: not a deal for this entry. */
    val worse: Boolean get() = currentPackCents != null && percentOff == null

    private val takeClause: String get() = if (packCount > 1) " when you take $packCount" else ""
}

/** [current] is the product the entry is set to buy at this store; without it only the deal's own prices are known. */
fun Deal.saving(current: Product? = null): DealSaving {
    val pricing = product.dealPricing()
    val each = pricing?.eachCents ?: product.priceCents
    // A multi-buy divides the whole bundle by the packs in it, which is a cent truer than dividing the rounded each-price.
    val dealUnit = pricing?.unitPriceCents?.let { cents -> pricing.unitPriceUnit?.let { cents to it } } ?: product.unitPriceAt(each)
    val currentPack = current?.priceCents ?: currentPriceCents
    val currentUnit = current?.let { it.unitPriceAt(it.priceCents) }?.takeIf { it.second.shortUnit() == dealUnit?.second?.shortUnit() }
        // A multipack priced per piece against a single carton or bottle: that single item is one piece, whatever it is measured in.
        ?: current?.takeIf { dealUnit?.second?.shortUnit() == "st" && (it.unit != "piece" || (it.unitAmount ?: 1.0) == 1.0) }?.let { it.priceCents to "st" }
    return DealSaving(
        eachCents = each,
        packCount = pricing?.count ?: 1,
        packSavingCents = currentPack?.let { it - each },
        unitPriceCents = dealUnit?.first,
        unitPriceUnit = dealUnit?.second,
        currentUnitPriceCents = currentUnit?.first,
        currentPackCents = currentPack,
    )
}
