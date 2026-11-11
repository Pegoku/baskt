package nl.baskt.ui.basket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.Product
import nl.baskt.data.dealPricing
import nl.baskt.data.euros
import nl.baskt.data.unitPriceLabel
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.rememberTranslation

/**
 * A product as a plain fact: photo, name, size, deal and price, with nothing in it that takes a tap. It is for
 * places where the row around it owns the tap (choosing a deal) or where the product already sits in a sheet,
 * so no second sheet opens on top of the first. With [openableImage] the picture alone opens the product sheet,
 * for rows whose tap chooses something but where the shopper still wants a closer look.
 */
@Composable
fun ProductLine(product: Product, modifier: Modifier = Modifier, dealMath: Boolean = true, openableImage: Boolean = false) {
    val shown = rememberTranslation(listOf(product.title, product.quantityText, product.dealText.orEmpty()))?.takeIf { it.translated }?.texts
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (openableImage) nl.baskt.ui.common.ProductThumb(product, size = 56) else ItemThumb(product.imageUrl, null, size = 56)
        Column(modifier = Modifier.weight(1f)) {
            Text(shown?.getOrNull(0) ?: product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(shown?.getOrNull(1) ?: product.quantityText, product.unitPriceLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (product.dealText != null) {
                val deal = if (dealMath) product.dealPricing() else null
                Text(
                    listOfNotNull(shown?.getOrNull(2) ?: product.dealText, deal?.label()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(product.priceCents.euros(), style = MaterialTheme.typography.titleMediumEmphasized)
            if (product.regularPriceCents != null && product.regularPriceCents > product.priceCents) {
                Text(product.regularPriceCents.euros(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
            }
        }
    }
}
