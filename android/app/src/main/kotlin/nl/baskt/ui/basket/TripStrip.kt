package nl.baskt.ui.basket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.common.StoreLogo

/**
 * What the open items would cost at each store, summed from the matches already on the list, with the cheapest
 * marked. It sits above the input so the price of the trip is always in view; tapping it opens the plan.
 */
@Composable
fun TripStrip(items: List<BasketItem>, stores: List<StoreInfo>, onPlan: () -> Unit) {
    val open = items.filter { !it.isGroup && it.isOpen }
    val totals = stores.map { store ->
        val priced = open.mapNotNull { item -> item.match(store.code)?.effective?.let { it.priceCents * item.quantity } }
        Triple(store, priced.sum(), open.size - priced.size)
    }
    if (open.isEmpty() || totals.all { it.second == 0 }) return
    // Cheapest among the stores that have everything; otherwise among all, since partial totals are not comparable.
    val complete = totals.filter { it.third == 0 && it.second > 0 }
    val cheapest = (complete.ifEmpty { totals.filter { it.second > 0 } }).minByOrNull { it.second }?.first?.code
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(onClick = onPlan),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((store, total, missing) in totals) {
                val best = store.code == cheapest && stores.size > 1
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoreLogo(store.code, stores, size = 22)
                    Column {
                        Text(
                            if (total > 0) total.euros() else "—",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (best) FontWeight.Bold else FontWeight.Medium,
                            color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when {
                                missing > 0 -> if (missing == 1) "1 missing" else "$missing missing"
                                best -> "cheapest"
                                else -> "all ${open.size}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (best && missing == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open the plan", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
        }
    }
}
