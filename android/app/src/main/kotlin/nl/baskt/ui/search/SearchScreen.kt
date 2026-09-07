package nl.baskt.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import nl.baskt.data.Product
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/**
 * Add concrete products: scan a barcode (the scanned store's product is pinned, other stores are matched)
 * or search the store catalogues by text.
 */
@Composable
fun SearchScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val stores by viewModel.basket.stores.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val scan by viewModel.scanResult.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val items by viewModel.basket.items.collectAsState()
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var lastAddedText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.clearSearch() }
    LaunchedEffect(items.size) {
        val added = lastAddedText ?: return@LaunchedEffect
        val item = items.lastOrNull { it.text == added } ?: return@LaunchedEffect
        lastAddedText = null
        snackbar.showSnackbar("Added “${item.text}”")
    }

    fun startScan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { viewModel.lookupBarcode(it) } }
            .addOnFailureListener { error -> viewModel.basket.run { } ; query = ""; viewModel.clearSearch(); android.widget.Toast.makeText(context, error.message ?: "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Scan or search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { startScan() }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcode") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search products, e.g. pindakaas") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) viewModel.searchProducts(query.trim()) }),
                    trailingIcon = { IconButton(onClick = { if (query.isNotBlank()) viewModel.searchProducts(query.trim()) }) { Icon(Icons.Default.Search, contentDescription = "Search") } },
                )
                FilledTonalButton(onClick = { startScan() }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text("  Scan")
                }
            }
            if (searching) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator()
                    Text("Asking the stores…")
                }
            }
            val scanned = scan
            val found = results
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scanned != null) {
                    item("scan-header") {
                        Text("Barcode ${scanned.gtin}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (scanned.results.all { it.product == null }) {
                            Text("No store recognised this barcode. Try searching by name.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    for (result in scanned.results) {
                        val product = result.product ?: continue
                        item("scan-${result.store}") {
                            ProductCard(product, stores.map { it.code to it }.toMap().keys.toList(), stores, onAdd = { lastAddedText = "${product.title} ${product.quantityText}".trim(); viewModel.addFromProduct(product) })
                        }
                    }
                }
                if (found != null) {
                    for (storeResult in found.results) {
                        item("store-${storeResult.store}") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                StoreBadge(storeResult.store, stores)
                                Text(
                                    if (storeResult.error != null) storeResult.error else "${storeResult.products.size} results",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (storeResult.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        for (product in storeResult.products.take(10)) {
                            item("${storeResult.store}-${product.id}") {
                                ProductCard(product, emptyList(), stores, onAdd = { lastAddedText = "${product.title} ${product.quantityText}".trim(); viewModel.addFromProduct(product) })
                            }
                        }
                    }
                }
                if (scanned == null && found == null && !searching) {
                    item("hint") {
                        Text(
                            "Scan the barcode of something you already bought, or search a store by name. The product you pick is used for that store and the other stores are matched automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(product: Product, @Suppress("UNUSED_PARAMETER") codes: List<String>, stores: List<nl.baskt.data.StoreInfo>, onAdd: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreBadge(product.store, stores) }
            ProductRow(product) {
                Button(onClick = onAdd) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = null)
                    Text("  Add")
                }
            }
        }
    }
}
