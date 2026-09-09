package nl.baskt.ui.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/** Promotions (bonus, 1+1, x%) for the items in the current basket, with one-tap switching. */
@Composable
fun DealsScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val deals by viewModel.deals.collectAsState()
    val loading by viewModel.loadingDeals.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val changes by viewModel.priceChanges.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.findDeals(live = false); viewModel.loadPriceChanges() }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message, withDismissAction = true) } }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Deals") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { viewModel.findDeals(live = true) }) { Icon(Icons.Default.Refresh, contentDescription = "Search the stores again") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            nl.baskt.ui.common.OfflineBanner(viewModel, needsServer = "Deals need the server.")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("My items") }
                SegmentedButton(selected = tab == 2, onClick = { tab = 2 }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("All deals") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Prices") }
            }
            if (tab == 2) {
                AllDealsTab(viewModel, stores, onAdded = { notify(it) })
                return@Column
            }
            if (tab == 1) {
                val data = changes
                val format = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                Text(
                    data?.scan?.let { scan -> "Nightly scan: " + (scan.lastRunAt?.let { "last ${format.format(java.util.Date(it))}" } ?: "not run yet") + (scan.nextRunAt?.let { ", next ${format.format(java.util.Date(it))}" } ?: "") } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (data != null && data.changes.isEmpty()) {
                    Text("No price changes in the last week for the products in your baskets.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data?.changes ?: emptyList(), key = { it.product.id }) { change ->
                        Card {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreBadge(change.product.store, stores)
                                    Text(
                                        (if (change.diffCents < 0) "↓ " else "↑ ") + kotlin.math.abs(change.diffCents).euros(),
                                        color = if (change.diffCents < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text("was ${change.previousCents.euros()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ProductRow(change.product)
                            }
                        }
                    }
                }
                return@Column
            }
            if (loading) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator()
                    Text("Looking for promotions…")
                }
            }
            val list = deals?.deals ?: emptyList()
            if (!loading && list.isEmpty()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No promotions found for your open items", style = MaterialTheme.typography.titleMedium)
                    Text("Tap the refresh icon to search the stores again for bonus packs, 1+1 and discounts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.findDeals(live = true) }) { Text("Search the stores") }
                }
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { "${it.itemId}-${it.store}-${it.product.id}" }) { deal ->
                    Card {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoreBadge(deal.store, stores)
                                Text(deal.itemText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                val saving = deal.savingCents
                                if (saving != null && saving > 0) Text("save ${saving.euros()}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            ProductRow(deal.product)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (deal.currentProductId != deal.product.id) {
                                    Button(onClick = { viewModel.takeDeal(deal); notify("Switched “${deal.itemText}” to ${deal.product.title}") }) { Text("Use this deal") }
                                } else {
                                    Text("Already your pick", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                                }
                                TextButton(onClick = { onOpenItem(deal.itemId) }) { Text("Open item") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Store-wide promotions with a store filter and keyword search; each card can be added to the basket. */
@Composable
private fun AllDealsTab(viewModel: AppViewModel, stores: List<nl.baskt.data.StoreInfo>, onAdded: (String) -> Unit) {
    val data by viewModel.allDeals.collectAsState()
    val loading by viewModel.loadingAllDeals.collectAsState()
    var query by remember { androidx.compose.runtime.mutableStateOf("") }
    var store by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val enabled = stores.filter { it.enabled }
    LaunchedEffect(store) { viewModel.loadAllDeals(query, store) }
    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search the deals, e.g. kaas") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.loadAllDeals(query.trim(), store) }),
            trailingIcon = { IconButton(onClick = { viewModel.loadAllDeals(query.trim(), store) }) { Icon(Icons.Default.Search, contentDescription = "Search") } },
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = store == null, onClick = { store = null }, label = { Text("All stores") })
            for (info in enabled) androidx.compose.material3.FilterChip(selected = store == info.code, onClick = { store = info.code }, label = { Text(info.name) })
        }
        if (loading) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        val cards = data?.results?.flatMap { result -> result.deals } ?: emptyList()
        val errors = data?.results?.mapNotNull { it.error } ?: emptyList()
        if (errors.isNotEmpty()) Text(errors.joinToString("; "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (!loading && data != null && cards.isEmpty()) Text("No deals found.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!loading && data != null && cards.isNotEmpty() && query.isNotBlank()) Text("${cards.size} deals for “$query”" + (data?.terms?.takeIf { it.size > 1 }?.let { "  (also searched: ${it.drop(1).joinToString(", ")})" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cards, key = { "${it.store}-${it.id}" }) { card ->
                Card {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (card.imageUrl != null) {
                            coil3.compose.AsyncImage(model = card.imageUrl, contentDescription = null, modifier = Modifier.padding(2.dp).then(Modifier.size(64.dp)))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StoreBadge(card.store, stores)
                                if (card.dealText != null) Text(card.dealText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text(card.title, style = MaterialTheme.typography.titleSmall)
                            if (card.subtitle != null) Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val price = listOfNotNull(card.priceCents?.euros(), card.regularPriceCents?.let { "was ${it.euros()}" }, card.validUntil?.let { "until $it" }).joinToString(" · ")
                            if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.addDeal(card); onAdded("Added “${card.title}” to your basket") }) { Text("Add") }
                    }
                }
            }
        }
    }
}
