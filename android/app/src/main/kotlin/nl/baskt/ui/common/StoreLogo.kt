package nl.baskt.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.StoreInfo

/** The store's logo on a white tile; falls back to the first letter on its brand colour when there is no picture. */
@Composable
fun StoreLogo(code: String, stores: List<StoreInfo>, size: Int = 32, modifier: Modifier = Modifier) {
    val info = stores.firstOrNull { it.code == code }
    // Small logos sit inside text lines, so they get the smaller corner.
    val shape = if (size < 28) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.small
    Box(
        // White behind the logo itself: the stores ship their marks for a white background.
        modifier = modifier.size(size.dp).clip(shape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (info?.logoUrl != null) {
            AsyncImage(model = info.logoUrl, contentDescription = info.name, modifier = Modifier.size((size * 0.72f).dp))
        } else {
            val (color, onColor) = storeColors(info)
            Box(Modifier.size(size.dp).background(color), contentAlignment = Alignment.Center) {
                Text((info?.name ?: code).take(1).uppercase(), style = if (size < 28) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.titleMediumEmphasized, color = onColor)
            }
        }
    }
}
