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

/** Product picture; tapping it opens a large view (title, size, price) that closes on tap. */
@Composable
fun ProductThumb(product: Product, size: Int = 56) {
    var zoomed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.size(size.dp).clickable(enabled = product.imageUrl != null) { zoomed = true }) {
        if (product.imageUrl != null) {
            AsyncImage(model = product.imageUrl, contentDescription = product.title, modifier = Modifier.padding(4.dp))
        } else {
            Box(contentAlignment = Alignment.Center) { Text("🛒", textAlign = TextAlign.Center) }
        }
    }
    if (zoomed && product.imageUrl != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { zoomed = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.clickable { zoomed = false }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    AsyncImage(model = product.imageUrl, contentDescription = product.title, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(product.title, style = MaterialTheme.typography.titleMedium, color = Color.Black, textAlign = TextAlign.Center)
                    Text(listOfNotNull(product.quantityText, product.priceCents.euros(), product.unitPriceLabel()).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
fun ProductRow(product: Product, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductThumb(product)
        Column(modifier = Modifier.weight(1f)) {
            Text(product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(product.quantityText, product.unitPriceLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (product.dealText != null) {
                Text(product.dealText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
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
