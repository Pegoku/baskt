package nl.baskt.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge

/**
 * Order step: decide where each item is bought. Quick picks (all at one store, cheapest mix) plus per-item
 * overrides; then jump into shopping mode for each store with only its assigned items.
 */
@Composable
fun OrderScreen(viewModel: AppViewModel, onBack: () -> Unit, onShop: (String) -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val enabled = stores.filter { it.enabled }
    val open = items.filter { !it.isGroup && !it.checked }
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
            Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (store in enabled) FilterChip(selected = open.isNotEmpty() && open.all { it.assignedStore == store.code || it.match(store.code)?.effective == null }, onClick = { viewModel.assign("store:${store.code}") }, label = { Text("All ${store.name}") })
                FilterChip(selected = false, onClick = { viewModel.assign("mix") }, label = { Text("Mix & match") })
            }
            val order = comparison?.order
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (store in enabled) {
                    val total = order?.perStore?.get(store.code)
                    Card(modifier = Modifier.weight(1f), onClick = { if ((total?.count ?: 0) > 0) onShop(store.code) }) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StoreBadge(store.code, stores)
                            Text((total?.totalCents ?: 0).euros(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${total?.count ?: 0} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if ((total?.count ?: 0) > 0) Text("Shop here →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            val unassigned = open.count { it.assignedStore == null }
            if (unassigned > 0) Text("$unassigned item(s) not assigned yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(open, key = { it.id }) { item ->
                    Card {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                val count = enabled.size + 1
                                enabled.forEachIndexed { index, store ->
                                    val product = item.match(store.code)?.effective
                                    SegmentedButton(
                                        selected = item.assignedStore == store.code,
                                        onClick = { viewModel.assignItem(item, store.code) },
                                        enabled = product != null,
                                        shape = SegmentedButtonDefaults.itemShape(index, count),
                                    ) { Text(if (product != null) "${store.name} ${(product.priceCents * item.quantity).euros()}" else "${store.name} —", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                SegmentedButton(selected = item.assignedStore == null, onClick = { viewModel.assignItem(item, null) }, shape = SegmentedButtonDefaults.itemShape(count - 1, count)) { Text("Skip") }
                            }
                        }
                    }
                }
            }
        }
    }
}
