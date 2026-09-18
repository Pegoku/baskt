package nl.baskt.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.data.dealPricing
import nl.baskt.data.unitPriceLabel

fun storeColor(store: StoreInfo?): Color = store?.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color.Gray

fun storeName(code: String, stores: List<StoreInfo>) = stores.firstOrNull { it.code == code }?.name ?: code

@Composable
fun StoreBadge(code: String, stores: List<StoreInfo>, modifier: Modifier = Modifier) {
    val info = stores.firstOrNull { it.code == code }
    val color = storeColor(info)
    val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White
    Text(
        text = info?.name ?: code,
        style = MaterialTheme.typography.labelSmall,
        color = onColor,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Opening a product shows its facts first; images can then be enlarged inside the detail sheet. */
@Composable
fun ProductThumb(product: Product, size: Int = 56, onOpen: (() -> Unit)? = null) {
    var details by androidx.compose.runtime.remember(product.id) { androidx.compose.runtime.mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.size(size.dp).clickable { if (onOpen != null) onOpen() else details = true }) {
        if (product.imageUrl != null) AsyncImage(model = product.imageUrl, contentDescription = "View ${product.title}", modifier = Modifier.padding(4.dp))
        else Box(contentAlignment = Alignment.Center) { Text("🛒", textAlign = TextAlign.Center) }
    }
    if (details) ProductSheet(product) { details = false }
}

@Composable
fun ProductRow(product: Product, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    var details by androidx.compose.runtime.remember(product.id) { androidx.compose.runtime.mutableStateOf(false) }
    val translation = rememberTranslation(listOf(product.title, product.quantityText, product.dealText.orEmpty()))
    val shown = translation?.takeIf { it.translated }?.texts
    if (details) ProductSheet(product) { details = false }
    Row(modifier = modifier.fillMaxWidth().clickable { details = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductThumb(product, onOpen = { details = true })
        Column(modifier = Modifier.weight(1f)) {
            Text(shown?.get(0) ?: product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(shown?.get(1) ?: product.quantityText, product.unitPriceLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (product.dealText != null) {
                val deal = product.dealPricing()
                Text(
                    listOfNotNull(shown?.get(2) ?: product.dealText, deal?.label()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(product.priceCents.euros(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (product.regularPriceCents != null && product.regularPriceCents > product.priceCents) {
                Text(product.regularPriceCents.euros(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
