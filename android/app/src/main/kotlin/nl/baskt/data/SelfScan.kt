package nl.baskt.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * AH in-store self-scan (Zelfscan), the protocol of docs/ah-selfscan.md "Confirmed protocol". The server lives on
 * the store's LAN only, so this is the one place the phone talks to a supermarket itself; the baskt backend never
 * sees it. Read-only scope: a trip is started, items are added and adjusted, and at checkout the Bonuskaart is shown
 * for the betaalpaal. Payment is never touched.
 *
 * Every response is the same envelope, `{"message":{code,level,message}, "result":{...}}`, either part optional.
 * Every field below is optional too: only what the capture showed is modelled, and AH adds fields freely.
 */

const val SELF_SCAN_BASE = "https://selfscan.store.ah.nl:8881/api/selfscan/v2.0/"

/** The Appie version the capture was taken with; the server may refuse ("Update the app") a version it does not know. */
const val SELF_SCAN_APP_VERSION = "9.47.3"

internal val selfScanJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

@Serializable
data class SsMessage(val code: String? = null, val level: String? = null, val message: String? = null) {
    /** "alert" and "error" stop the shopper (unknown barcode, age check, blocked card); "info"/"message" only inform. */
    val isProblem: Boolean get() = level == "alert" || level == "error"
}

@Serializable
data class SsEnvelope<T>(val message: SsMessage? = null, val result: T? = null)

@Serializable
data class SsService(val storeNumber: Int? = null, val storeFormat: String? = null, val storeType: String? = null)

/**
 * A variable-barcode range the store recognises. In the capture every range is `BOK` (Bonuskaart) and the member's
 * own card `2623065000013` falls inside one, so these are Bonuskaart number ranges, not weighed-product ranges.
 */
@Serializable
data class SsEanRange(val minRange: String = "", val maxRange: String = "", val eanType: String? = null)

@Serializable
data class SsSession(val token: String? = null, val eanRis: List<SsEanRange> = emptyList())

@Serializable
data class SsBonusInfo(val promoLabel: String? = null)

/** Weighed or counted items: the unit price and the amount (kg) the label carried. */
@Serializable
data class SsUnitInfo(val price: Int? = null, val quantity: Double? = null, val type: String? = null)

@Serializable
data class SsItem(
    val barcode: String = "",
    val description: String? = null,
    /** Line price in cents, before discounts (2 × 2.49 shows 498 even when the second one is free). */
    val price: Int? = null,
    val quantity: Int? = null,
    val nasaNr: String? = null,
    val flags: List<String> = emptyList(),
    val icons: List<String> = emptyList(),
    val bonusInfo: SsBonusInfo? = null,
    /** Statiegeld in cents. */
    val deposit: Int? = null,
    val unitInfo: SsUnitInfo? = null,
    /** False for lines the shopper cannot add more of (the Bonuskaart itself). */
    val plusAllowed: Boolean = true,
    val infos: List<String> = emptyList(),
    /** Cents off for a short best-before date; AH reads the date from the GS1 code (flag `THT`). */
    val wasteDiscount: Int? = null,
    @SerialName("private") val privateNumber: String? = null,
) {
    /** The Bonuskaart line AH puts first in every basket. */
    val isCard: Boolean get() = "BOK" in flags
    val isBonus: Boolean get() = "BONUS" in flags || bonusInfo?.promoLabel != null
    /** Part of a promotion that is not triggered yet (one pizza dough of a 1+1). */
    val inPromotion: Boolean get() = "AKTIE" in flags && !isBonus
    val isPriceFavourite: Boolean get() = "PRICEFAV" in flags
    /** AH read a best-before (THT) date off the code, so a short-dated markdown can apply. */
    val hasDate: Boolean get() = "THT" in flags
}

@Serializable
data class SsDiscount(
    val text: String? = null,
    val descriptions: List<String> = emptyList(),
    /** Barcodes of the items the discount applies to. */
    val items: List<String> = emptyList(),
    val icon: String? = null,
    val discount: Int? = null,
) {
    /** "ALLEAHPIZZAD", "2 PIZZADEEG   " -> one readable line. */
    val label: String get() = (listOfNotNull(text?.trim()?.ifBlank { null }) + descriptions.map { it.trim() }.filter { it.isNotEmpty() }).joinToString(" · ")
}

@Serializable
data class SsTotals(
    /** What is left to pay, in cents: already net of [discount]. */
    val subtotal: Int? = null,
    val bonusDiscount: Int? = null,
    val personalDiscount: Int? = null,
    val discount: Int? = null,
    val itemCount: Int? = null,
    val uniqueItemCount: Int? = null,
    val regularItemCount: Int? = null,
    /** Only on payment/totals: the amount the betaalpaal will ask. */
    val total: Int? = null,
)

@Serializable
data class SsBasket(
    val items: List<SsItem> = emptyList(),
    val discounts: List<SsDiscount> = emptyList(),
    val totals: SsTotals? = null,
    val transactionId: String? = null,
) {
    /** The products, without the Bonuskaart line. */
    val products: List<SsItem> get() = items.filterNot { it.isCard }
    val toPayCents: Int get() = totals?.subtotal ?: 0
    val savedCents: Int get() = totals?.discount ?: discounts.sumOf { it.discount ?: 0 }
    val itemCount: Int get() = totals?.itemCount ?: products.sumOf { it.quantity ?: 1 }
    fun item(barcode: String): SsItem? = products.firstOrNull { it.barcode == barcode }
}

@Serializable
data class SsBasketResult(val basket: SsBasket? = null)

@Serializable
data class SsBackButton(val enabled: Boolean? = null, val label: String? = null)

@Serializable
data class SsPaymentTotals(
    val totals: SsTotals? = null,
    val barcode: String? = null,
    val symbology: String? = null,
    val imageUrl: String? = null,
    val showToPay: Boolean? = null,
    val backButton: SsBackButton? = null,
)

/** `paying` while the betaalpaal works, `paid` after; [barcode] is then the gate code (the Bonuskaart again). */
@Serializable
data class SsPaymentStatus(
    val status: String? = null,
    val barcode: String? = null,
    val symbology: String? = null,
    val backButton: SsBackButton? = null,
)

/** The gate's state; its shape was not in the capture, so it is kept raw. */
@Serializable
data class SsExitgate(val status: JsonElement? = null)

/** Decodes one envelope; a body that is not JSON (an HTML error page, an empty 204) gives an empty envelope. */
fun <T> decodeSelfScan(text: String, result: KSerializer<T>): SsEnvelope<T> =
    if (text.isBlank()) SsEnvelope() else runCatching { selfScanJson.decodeFromString(SsEnvelope.serializer(result), text) }.getOrElse { SsEnvelope() }

/** True when [code] is a Bonuskaart number in one of the store's `BOK` ranges (same-length digit strings compare as numbers). */
fun isBonuskaart(code: String, ranges: List<SsEanRange>): Boolean {
    val digits = code.trim()
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return false
    return ranges.any { range ->
        range.eanType.equals("BOK", ignoreCase = true) && range.minRange.length == digits.length && range.maxRange.length == digits.length &&
            digits >= range.minRange && digits <= range.maxRange
    }
}

/** "0,512 kg" for a weighed line, null for a counted one. */
fun SsItem.weightText(): String? {
    val info = unitInfo ?: return null
    val amount = info.quantity ?: return null
    if (!info.type.equals("kg", ignoreCase = true) && !info.type.equals("weight", ignoreCase = true)) return null
    return String.format(java.util.Locale.GERMANY, "%.3f kg", amount)
}

/** The short tags a line carries, as shown on the AH screen. */
fun SsItem.tags(): List<String> = buildList {
    if (isBonus) add(bonusInfo?.promoLabel?.trim()?.ifBlank { null } ?: "Bonus")
    if (inPromotion) add("In a deal")
    if (isPriceFavourite) add("Prijsfavoriet")
    deposit?.takeIf { it > 0 }?.let { add("+ ${it.euros()} deposit") }
    wasteDiscount?.takeIf { it > 0 }?.let { add("− ${it.euros()} short date") }
}

/**
 * An English line for the refusals AH sends with a known code; null keeps AH's own Dutch text. BONUS_BLOCKED is not
 * about the scanned item: AH holds every add on the card until a service desk employee frees it, then the same scan
 * goes through (field capture 2026-11-07).
 */
fun SsMessage.shopperText(): String? = when (code) {
    "BONUS_BLOCKED" -> "AH paused self-scan on your Bonuskaart. Ask at the service desk, then scan again."
    "BARCODE_UNKNOWN" -> "AH does not know this barcode"
    else -> null
}
