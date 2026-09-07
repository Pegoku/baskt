package nl.baskt.ui.common

import android.content.Context
import android.content.Intent
import nl.baskt.data.Basket
import nl.baskt.data.BasketItem
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros

/** Plain-text shopping list, grouped per store, for WhatsApp or any share target. */
fun buildShareText(basket: Basket?, items: List<BasketItem>, stores: List<StoreInfo>, store: String? = null): String {
    val open = items.filter { !it.checked && !it.isGroup }
    val groups = items.filter { it.isGroup }.associateBy { it.id }
    val lines = mutableListOf<String>()
    lines += "🧺 ${basket?.name ?: "baskt"}"
    val targets = if (store != null) stores.filter { it.code == store } else stores
    for (info in targets) {
        val withProduct = open.mapNotNull { item -> item.match(info.code)?.effective?.let { item to it } }
        if (withProduct.isEmpty()) continue
        val total = withProduct.sumOf { (item, product) -> product.priceCents * item.quantity }
        lines += ""
        lines += "*${info.name}* · ${total.euros()}"
        for ((item, product) in withProduct.sortedBy { (it.first.match(info.code)?.effective?.category ?: "") + it.first.text }) {
            val folder = item.parentId?.let { groups[it]?.text }?.let { " ($it)" } ?: ""
            lines += "☐ ${if (item.quantity > 1) "${item.quantity}× " else ""}${product.title} ${product.quantityText} – ${(product.priceCents * item.quantity).euros()}$folder"
        }
    }
    val unmatched = open.filter { item -> targets.none { item.match(it.code)?.effective != null } }
    if (unmatched.isNotEmpty()) {
        lines += ""
        lines += "Still to find:"
        unmatched.forEach { lines += "☐ ${it.text}" }
    }
    return lines.joinToString("\n")
}

fun shareText(context: Context, text: String, whatsApp: Boolean) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (whatsApp) setPackage("com.whatsapp")
    }
    try {
        context.startActivity(if (whatsApp) intent else Intent.createChooser(intent, "Share list"))
    } catch (_: Exception) {
        context.startActivity(Intent.createChooser(intent.apply { setPackage(null) }, "Share list"))
    }
}
