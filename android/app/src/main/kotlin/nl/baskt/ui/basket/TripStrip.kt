package nl.baskt.ui.basket

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.counted

/**
 * What the open items would cost at each store, summed from the matches already in the basket, with the cheapest
 * marked. It sits above the input so the price of the basket is always in view; tapping it opens the plan.
 *
 * The strip is the visible way to the Plan page, so it stays up as long as the basket has items: while nothing
 * is priced yet it shows "Plan" alone. The store totals scroll sideways so three or four stores never push
 * "Plan" off the edge.
 */
@Composable
fun TripStrip(items: List<BasketItem>, stores: List<StoreInfo>, onPlan: () -> Unit) {
    if (items.isEmpty()) return
    val open = items.filter { !it.isGroup && it.isOpen }
    val totals = stores.map { store ->
        val priced = open.mapNotNull { item -> item.match(store.code)?.effective?.let { it.priceCents * item.quantity } }
        Triple(store, priced.sum(), open.size - priced.size)
    }.filter { it.second > 0 }
    // Cheapest among the stores that have everything; otherwise among all, since partial totals are not comparable.
    val complete = totals.filter { it.third == 0 }
    val cheapest = (complete.ifEmpty { totals }).minByOrNull { it.second }?.first?.code
    Surface(
        onClick = onPlan,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen, vertical = 6.dp).semantics { role = Role.Button },
    ) {
        Row(modifier = Modifier.heightIn(min = 48.dp).padding(start = Spacing.Card, end = Spacing.Card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                for ((store, total, missing) in totals) {
                    val best = store.code == cheapest && totals.size > 1
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StoreLogo(store.code, stores, size = 22)
                        Column {
                            Text(
                                total.euros(),
                                style = if (best) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall,
                                color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                when {
                                    missing > 0 -> "$missing missing"
                                    best -> "Cheapest"
                                    open.size == 1 -> "1 item"
                                    else -> "All ${counted(open.size, "item")}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (best && missing == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
        }
    }
}
