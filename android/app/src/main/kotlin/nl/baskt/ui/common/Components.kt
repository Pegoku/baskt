package nl.baskt.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.data.dealPricing
import nl.baskt.data.unitPriceLabel

/** A store's brand colour, or null when the store is unknown or sent none. */
private fun brandColor(store: StoreInfo?): Color? = store?.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

fun storeColor(store: StoreInfo?): Color = brandColor(store) ?: Color.Gray

fun storeName(code: String, stores: List<StoreInfo>) = stores.firstOrNull { it.code == code }?.name ?: code

/** Black or white, whichever reads on [color]: brand colours run from pale yellow to dark blue. */
internal fun readableOn(color: Color): Color = if (0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue > 0.5f) Color.Black else Color.White

/** The tile and text colour for a store: its brand colour, or a neutral theme pair when it has none. */
@Composable
internal fun storeColors(store: StoreInfo?): Pair<Color, Color> {
    val brand = brandColor(store) ?: return MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
    return brand to readableOn(brand)
}

@Composable
fun StoreBadge(code: String, stores: List<StoreInfo>, modifier: Modifier = Modifier) {
    val info = stores.firstOrNull { it.code == code }
    val (color, onColor) = storeColors(info)
    Text(
        text = info?.name ?: code,
        style = MaterialTheme.typography.labelSmallEmphasized,
        color = onColor,
        maxLines = 1,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The picture that belongs to an item, at row size. Product photos come on white because the stores
 * ship them cut out on white; an item without a picture yet gets a quiet tile with a basket on it.
 */
@Composable
fun ItemThumb(imageUrl: String?, contentDescription: String?, size: Int = 44, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (imageUrl != null) Color.White else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.size(size.dp),
    ) {
        if (imageUrl != null) AsyncImage(model = imageUrl, contentDescription = contentDescription, modifier = Modifier.padding((size / 14).dp))
        else Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ShoppingBasket, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
        }
    }
}

/** What stands in for a missing product photo everywhere: a quiet tile with the basket glyph at about half its size. */
@Composable
fun PhotoPlaceholder(size: Int = 44, modifier: Modifier = Modifier) = ItemThumb(null, null, size, modifier)

/** A thumb smaller than this is too small to be a touch target of its own, so it only shows the picture. */
private const val MIN_TAPPABLE_THUMB = 48

/**
 * A product's picture. From 48dp up it opens the product sheet when tapped (or runs [onOpen] instead); smaller
 * thumbs and ones with [openable] off are only pictures, so they never steal a tap from the row they sit in.
 */
@Composable
fun ProductThumb(product: Product, size: Int = 56, onOpen: (() -> Unit)? = null, openable: Boolean = true) {
    var details by rememberSaveable(product.id) { mutableStateOf(false) }
    val tappable = openable && (onOpen != null || size >= MIN_TAPPABLE_THUMB)
    val tap = if (tappable) Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "Product details") { if (onOpen != null) onOpen() else details = true } else Modifier
    ItemThumb(product.imageUrl, if (tappable) product.title else null, size, tap)
    if (details) ProductSheet(product) { details = false }
}

/**
 * One product: picture, name, pack and price. The whole row is one button that opens the product sheet; turn
 * [openable] off where the row sits inside something that already handles the tap or is itself a sheet, or pass
 * [onOpen] to decide what a tap does. [dealMath] adds what a multi-buy works out to per pack and per unit; turn
 * it off where that is already said next to the row.
 */
@Composable
fun ProductRow(
    product: Product,
    modifier: Modifier = Modifier,
    dealMath: Boolean = true,
    showOriginal: Boolean = false,
    openable: Boolean = true,
    onOpen: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    var details by rememberSaveable(product.id) { mutableStateOf(false) }
    val translation = rememberTranslation(listOf(product.title, product.quantityText, product.dealText.orEmpty()), !showOriginal)
    val shown = translation?.takeIf { it.translated && !showOriginal }?.texts
    if (details) ProductSheet(product) { details = false }
    val tap = if (openable) Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "Product details") { if (onOpen != null) onOpen() else details = true } else Modifier
    Row(modifier = modifier.fillMaxWidth().then(tap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The row is the target, so the picture inside it is only a picture.
        ItemThumb(product.imageUrl, null, size = 56)
        Column(modifier = Modifier.weight(1f)) {
            Text(shown?.get(0) ?: product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(shown?.get(1) ?: product.quantityText, product.unitPriceLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (product.dealText != null) {
                val deal = if (dealMath) product.dealPricing() else null
                Text(
                    listOfNotNull(shown?.get(2) ?: product.dealText, deal?.label()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            when (product.personal?.status) {
                "ACTIVATED" -> Text("Your personal Bonus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                "ACTIVATABLE" -> Text(product.personal.dealText?.let { "Bonus Box: $it" } ?: "Bonus Box offer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(product.priceCents.euros(), style = MaterialTheme.typography.titleMediumEmphasized)
            if (product.regularPriceCents != null && product.regularPriceCents > product.priceCents) {
                Text(product.regularPriceCents.euros(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
            }
        }
        trailing?.invoke()
    }
}

/**
 * A read-only label such as "Activated" or a missing ingredient. Chips are for things you can tap; a fact that
 * only informs gets this quieter tag so it does not look like a button.
 */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer, contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(shape = MaterialTheme.shapes.small, color = color, contentColor = contentColor, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** Icon and label inside a button, at the M3 icon size and gap, so every button with an icon lines up the same. */
@Composable
fun IconLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize))
    androidx.compose.foundation.layout.Spacer(Modifier.size(androidx.compose.material3.ButtonDefaults.IconSpacing))
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
