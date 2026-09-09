package nl.baskt.ui.compare

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.Comparison
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.storeName

@Composable
fun CompareScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit, onOrder: () -> Unit = {}, swipe: (onLeft: () -> Unit, onRight: () -> Unit) -> Modifier = { _, _ -> Modifier }) {
    val comparison by viewModel.comparison.collectAsState()
    val comparing by viewModel.comparing.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val items by viewModel.basket.items.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(items) { viewModel.compare() }

    Scaffold(
        floatingActionButton = {
            if (comparison != null) {
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = onOrder,
                    icon = { Icon(androidx.compose.material.icons.Icons.Default.ShoppingCart, contentDescription = null) },
                    text = { Text("Order") },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { viewModel.compare(refreshPrices = true) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh prices") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).then(swipe(onOrder, onBack))) {
            nl.baskt.ui.common.OfflineBanner(viewModel, needsServer = "Showing the last comparison.")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Per store") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Per item") }
            }
            if (tab == 1) {
                val byUnit = comparison?.rankBy == "unitPrice"
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cheapest by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.FilterChip(selected = !byUnit, onClick = { viewModel.setRankBy("price") }, label = { Text("pack") })
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.FilterChip(selected = byUnit, onClick = { viewModel.setRankBy("unitPrice") }, label = { Text("per kg / l") })
                }
            }
            if (comparing) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val data = comparison
            when {
                data == null && comparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No comparison available") }
                tab == 0 -> StoreTab(data, stores)
                else -> ItemTab(data, stores, onOpenItem)
            }
        }
    }
}

@Composable
private fun StoreTab(data: Comparison, stores: List<StoreInfo>) {
    val itemCount = data.items.size
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (summary in data.stores) {
            item(summary.store) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (summary.rank == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("#${summary.rank}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            StoreBadge(summary.store, stores)
                            Spacer(Modifier.weight(1f))
                            Text(summary.fullTotalCents.euros(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        val allMatched = summary.missingItemIds.isEmpty()
                        Text(
                            if (allMatched) "Everything on the list ($itemCount items)"
                            else "${summary.matchedCount} of $itemCount items · ${summary.missingItemIds.size} missing",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!allMatched) {
                            Text("Shared items only: ${summary.comparableTotalCents.euros()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val missing = data.items.filter { it.itemId in summary.missingItemIds }.map { it.text }
                            Text("Missing: ${missing.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (summary.unconfirmedCount > 0) {
                            Text("${summary.unconfirmedCount} item(s) still use the suggested product", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
        item("mix") {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mix & match", style = MaterialTheme.typography.titleMedium)
                    Text("Cheapest store for every item: ${data.mixAndMatchTotalCents.euros()}", style = MaterialTheme.typography.bodyMedium)
                    if (data.missingEverywhere.isNotEmpty()) {
                        Text("Not found anywhere: ${data.items.filter { it.itemId in data.missingEverywhere }.joinToString { it.text }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemTab(data: Comparison, stores: List<StoreInfo>, onOpenItem: (String) -> Unit) {
    val codes = data.stores.sortedBy { it.rank }.map { it.store }
    val byUnit = data.rankBy == "unitPrice"
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item("header") {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Spacer(Modifier.weight(1.4f))
                for (code in codes) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { StoreBadge(code, stores) }
            }
            HorizontalDivider()
        }
        for (row in data.items) {
            item(row.itemId) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1.4f).padding(end = 8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        for (code in codes) {
                            val line = row.perStore[code]
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                if (line == null) {
                                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    val best = if (byUnit) (row.cheapestByUnitPriceStore ?: row.cheapestStore) else row.cheapestStore
                                    Text(
                                        line.lineCents.euros() + if (!line.confirmed) "?" else "",
                                        fontWeight = if (best == code) FontWeight.Bold else FontWeight.Normal,
                                        color = if (best == code) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    val product = data.products[line.productId]
                                    val unit = if (line.unitPriceCents != null && line.unitPriceUnit != null) "${line.unitPriceCents.euros()}/${if (line.unitPriceUnit == "piece") "st" else line.unitPriceUnit}" else null
                                    Text(
                                        listOfNotNull(if (line.unitsToBuy > 1) "${line.unitsToBuy}×" else null, product?.quantityText, unit).joinToString(" "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (row.packSizeDiffers && row.cheapestByUnitPriceStore != null && row.cheapestByUnitPriceStore != row.cheapestStore) {
                        Text(
                            "Different pack sizes: ${storeName(row.cheapestByUnitPriceStore, stores)} is cheaper per unit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Row { Spacer(Modifier.width(0.dp)); androidx.compose.material3.TextButton(onClick = { onOpenItem(row.itemId) }) { Text("Change products") } }
                }
                HorizontalDivider()
            }
        }
        item("footer") { Spacer(Modifier.height(24.dp)) }
    }
}
