package nl.baskt.ui.common

import android.content.Context
import android.content.Intent
import nl.baskt.data.Basket
import nl.baskt.data.BasketItem
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros

/**
 * Plain-text basket, grouped per store, for WhatsApp or any share target. Without a [store] every item is listed
 * once: under the store it is assigned to, or else under the store where it is cheapest. Store headings carry
 * WhatsApp's bold markup; [shareText] takes it out again for every other app.
 */
fun buildShareText(basket: Basket?, items: List<BasketItem>, stores: List<StoreInfo>, store: String? = null): String {
    val open = items.filter { !it.checked && !it.isGroup }
    val groups = items.filter { it.isGroup }.associateBy { it.id }
    val lines = mutableListOf<String>()
    lines += "🧺 ${basket?.name ?: "baskt"}"
    val targets = if (store != null) stores.filter { it.code == store } else stores
    val codes = targets.map { it.code }.toSet()
    /** Where an item is listed, and as which product. */
    fun place(item: BasketItem): Pair<String, Product>? {
        if (store != null) return item.match(store)?.effective?.let { store to it }
        val assigned = item.assignedStore?.takeIf { it in codes }?.let { code -> item.match(code)?.effective?.let { code to it } }
        return assigned ?: item.matches.filter { it.store in codes }.mapNotNull { match -> match.effective?.let { match.store to it } }.minByOrNull { it.second.priceCents }
    }
    val placed = open.mapNotNull { item -> place(item)?.let { (code, product) -> Triple(code, item, product) } }
    for (info in targets) {
        val here = placed.filter { it.first == info.code }
        if (here.isEmpty()) continue
        val total = here.sumOf { (_, item, product) -> product.priceCents * item.quantity }
        lines += ""
        lines += "*${info.name}* · ${total.euros()}"
        for ((_, item, product) in here.sortedBy { (it.third.category ?: "") + it.second.text }) {
            val folder = item.parentId?.let { groups[it]?.text }?.let { " ($it)" } ?: ""
            lines += "☐ ${if (item.quantity > 1) "${item.quantity}× " else ""}${product.title} ${product.quantityText} · ${(product.priceCents * item.quantity).euros()}$folder"
        }
    }
    val placedIds = placed.map { it.second.id }.toSet()
    val unmatched = open.filter { it.id !in placedIds }
    if (unmatched.isNotEmpty()) {
        lines += ""
        lines += "Items without a product yet:"
        unmatched.forEach { lines += "☐ ${if (it.quantity > 1) "${it.quantity}× " else ""}${it.text}" }
    }
    return lines.joinToString("\n")
}

/** A store heading as [buildShareText] writes it: "*Albert Heijn* · €12,30". */
private val boldHeading = Regex("^\\*(.+?)\\*( · .*)?$")

/** Opens WhatsApp or the system share sheet. Only WhatsApp reads `*bold*`, so everything else gets the text without the asterisks. */
fun shareText(context: Context, text: String, whatsApp: Boolean) {
    val plain = text.lines().joinToString("\n") { line -> boldHeading.replace(line) { it.groupValues[1] + it.groupValues[2] } }
    fun intent(toWhatsApp: Boolean) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, if (toWhatsApp) text else plain)
        if (toWhatsApp) setPackage("com.whatsapp")
    }
    try {
        context.startActivity(if (whatsApp) intent(true) else Intent.createChooser(intent(false), "Share basket"))
    } catch (_: Exception) {
        // WhatsApp is not installed: fall back to the share sheet.
        context.startActivity(Intent.createChooser(intent(false), "Share basket"))
    }
}
