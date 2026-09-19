package nl.baskt.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.StoreInfo

/** The store's logo on a tile in its brand colour; falls back to the first letter when there is no picture. */
@Composable
fun StoreLogo(code: String, stores: List<StoreInfo>, size: Int = 32, modifier: Modifier = Modifier) {
    val info = stores.firstOrNull { it.code == code }
    val color = storeColor(info)
    Box(
        modifier = modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (info?.logoUrl != null) {
            AsyncImage(model = info.logoUrl, contentDescription = info.name, modifier = Modifier.size((size * 0.72f).dp))
        } else {
            Box(Modifier.size(size.dp).background(color), contentAlignment = Alignment.Center) {
                Text((info?.name ?: code).take(1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.padding(2.dp))
            }
        }
    }
}
