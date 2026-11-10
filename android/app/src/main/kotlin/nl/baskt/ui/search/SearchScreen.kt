package nl.baskt.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.rememberBarcodeScanner
import nl.baskt.ui.common.storeName

/** Results listed per store before "Show more", and how many each tap on it adds. */
private const val RESULTS_PER_STEP = 10

/**
 * Add concrete products: scan a barcode (the scanned store's product becomes the pick there, other stores are
 * matched) or search the store catalogues by name.
 */
@Composable
fun SearchScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val stores by viewModel.basket.stores.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val scan by viewModel.scanResult.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val items by viewModel.basket.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // Products added from this screen, so "Add" turns into "Added" at once, before the basket has caught up.
    var justAdded by rememberSaveable { mutableStateOf(listOf<String>()) }
    // How many results each store shows; reset by a new search.
    var shownPerStore by remember(results?.query) { mutableStateOf(mapOf<String, Int>()) }

    LaunchedEffect(Unit) { viewModel.clearSearch() }
    // Results belong to this visit: nothing lingers for the screen underneath or the next time search opens.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.clearSearch() } }

    // A product is "added" while an open item in the basket has it as its pick.
    val inBasket = items.filter { !it.isBought }.flatMap { item -> item.matches.mapNotNull { it.chosen?.id } }.toSet() + justAdded
    fun add(product: Product) {
        if (product.id in inBasket) return
        justAdded = justAdded + product.id
        viewModel.addFromProduct(product)
        viewModel.notify("Added “${product.title}”", "View") {
            // Looked up when tapped: by then the item exists, and has its final id if it has synced meanwhile.
            viewModel.basket.items.value.lastOrNull { item -> item.matches.any { it.chosen?.id == product.id } }?.let { onOpenItem(it.id) }
        }
    }
    fun search() { if (query.isNotBlank()) viewModel.searchProducts(query.trim()) }

    val startScan = rememberBarcodeScanner(onFailure = { viewModel.notify(it) }) { viewModel.lookupBarcode(it) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(value = query, onValueChange = { query = it }, placeholder = "Search products", onSearch = ::search, modifier = Modifier.weight(1f))
                // As tall as the field next to it.
                FilledTonalButton(onClick = { startScan() }, modifier = Modifier.height(56.dp)) { IconLabel(Icons.Default.QrCodeScanner, "Scan") }
            }
            val scanned = scan
            val found = results
            when {
                searching -> LoadingRow("Searching…", modifier = Modifier.padding(Spacing.Screen))
                scanned == null && found == null -> EmptyState("Find a product", "Search by name or scan a barcode.", icon = Icons.Default.Search, modifier = Modifier.fillMaxSize())
                scanned == null && found != null && found.results.all { it.products.isEmpty() && it.error == null } ->
                    EmptyState("No results", "Nothing matches “${found.query}”.", icon = Icons.Default.SearchOff, modifier = Modifier.fillMaxSize())
                scanned != null && found == null && scanned.results.all { it.product == null } ->
                    EmptyState("No store knows this barcode", "Try searching by name.", icon = Icons.Default.SearchOff, modifier = Modifier.fillMaxSize())
                else -> LazyColumn(contentPadding = PaddingValues(start = Spacing.Screen, end = Spacing.Screen, top = 8.dp, bottom = Spacing.Screen), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
                    if (scanned != null) {
                        for (result in scanned.results) {
                            val product = result.product ?: continue
                            // A scan lists one product per store with no header above it, so the card names its store.
                            item("scan-${result.store}") { ProductCard(product, stores, showStore = true, added = product.id in inBasket, onAdd = { add(product) }) }
                        }
                    }
                    if (found != null) {
                        for (storeResult in found.results) {
                            val shown = shownPerStore[storeResult.store] ?: RESULTS_PER_STEP
                            item("store-${storeResult.store}") {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    StoreBadge(storeResult.store, stores)
                                    if (storeResult.error != null) {
                                        Text("Couldn’t search ${storeName(storeResult.store, stores)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { viewModel.searchProducts(found.query) }) { Text("Try again") }
                                    } else Text(counted(storeResult.products.size, "result"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            for (product in storeResult.products.take(shown)) {
                                item("${storeResult.store}-${product.id}") { ProductCard(product, stores, showStore = false, added = product.id in inBasket, onAdd = { add(product) }) }
                            }
                            val left = storeResult.products.size - shown
                            if (left > 0) item("more-${storeResult.store}") {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { shownPerStore = shownPerStore + (storeResult.store to shown + RESULTS_PER_STEP) }) { Text("Show ${minOf(left, RESULTS_PER_STEP)} more") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(product: Product, stores: List<StoreInfo>, showStore: Boolean, added: Boolean, onAdd: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showStore) StoreBadge(product.store, stores)
            ProductRow(product) {
                if (added) Tag("Added") else FilledTonalButton(onClick = onAdd) { Text("Add") }
            }
        }
    }
}
