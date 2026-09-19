package nl.baskt.ui.compare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.StoreLogo

/**
 * Order step: decide where each item is bought. Quick picks (all at one store, cheapest mix) plus per-item
 * choices shown as pictures of what you would get at each store; Skip leaves the item home (the same tick as
 * on the main list). Then jump into shopping mode for each store with only its assigned items.
 */
@Composable
fun OrderScreen(viewModel: AppViewModel, onBack: () -> Unit, onShop: (String) -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val enabled = stores.filter { it.enabled }
    val listed = items.filter { !it.isGroup && !it.isBought }
    val open = listed.filter { it.isOpen }
    LaunchedEffect(Unit) { if (comparison == null) viewModel.compare() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text("Where do you buy what?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (store in enabled) FilterChip(
                    selected = open.isNotEmpty() && open.all { it.assignedStore == store.code || it.match(store.code)?.effective == null },
                    onClick = { viewModel.assign("store:${store.code}") },
                    leadingIcon = { StoreLogo(store.code, stores, size = 20) },
                    label = { Text("All ${store.name}") },
                )
                FilterChip(selected = false, onClick = { viewModel.assign("mix") }, label = { Text("Mix & match") })
            }
            val order = comparison?.order
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (store in enabled) {
                    val total = order?.perStore?.get(store.code)
                    val count = total?.count ?: 0
                    Card(modifier = Modifier.weight(1f), onClick = { if (count > 0) onShop(store.code) }) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoreLogo(store.code, stores, size = 28)
                                Text(store.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text((total?.totalCents ?: 0).euros(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("$count items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (count > 0) Text("Shop here →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            val unassigned = open.count { it.assignedStore == null }
            val skipped = listed.count { it.isSkipped }
            val notes = listOfNotNull(
                if (unassigned > 0) "$unassigned item(s) not assigned yet" else null,
                if (skipped > 0) "$skipped skipped" else null,
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listed, key = { it.id }) { item ->
                    OrderItemCard(item, enabled, stores, onPick = { viewModel.buyAt(item, it) }, onSkip = { viewModel.skip(item, true) })
                }
            }
        }
    }
}

@Composable
private fun OrderItemCard(item: BasketItem, enabled: List<StoreInfo>, stores: List<StoreInfo>, onPick: (String) -> Unit, onSkip: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (item.isSkipped) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (item.quantity > 1) "${item.quantity}× " else "") + item.text,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (item.isSkipped) TextDecoration.LineThrough else null,
                    color = if (item.isSkipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.isSkipped) Text("Skipped", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (store in enabled) {
                    val product = item.match(store.code)?.effective
                    StoreOption(
                        store = store,
                        stores = stores,
                        product = product,
                        quantity = item.quantity,
                        selected = item.isOpen && item.assignedStore == store.code,
                        onClick = { if (product != null) onPick(store.code) },
                        modifier = Modifier.weight(1f),
                    )
                }
                SkipOption(selected = item.isSkipped, onClick = onSkip, modifier = Modifier.width(72.dp))
            }
        }
    }
}

/** What you would get at this store: picture, pack and price; dimmed when the store has nothing for the item. */
@Composable
private fun StoreOption(store: StoreInfo, stores: List<StoreInfo>, product: Product?, quantity: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = when {
        selected -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }
    Card(
        onClick = onClick,
        enabled = product != null,
        colors = colors,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StoreLogo(store.code, stores, size = 18)
                Text(store.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (product == null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { Text("—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Not found", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductThumb(product, size = 56)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(product.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(product.quantityText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text((product.priceCents * quantity).euros(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SkipOption(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.height(2.dp))
            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(28.dp), tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Skip", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            Text(if (selected) "Not this trip" else "Leave home", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
