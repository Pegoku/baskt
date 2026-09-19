package nl.baskt.ui.shop

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.StoreBadge

/**
 * In-store mode: big checkboxes, items grouped by aisle (product category), screen stays on. Ticking records the
 * item on today's trip (it moves to the orders page); the scan button adds things picked up that were not on the
 * list; the card button shows the store's loyalty card for the checkout.
 */
@Composable
fun ShopModeScreen(viewModel: AppViewModel, store: String, onBack: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tripScans by viewModel.tripScans.collectAsState()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // When the order step assigned stores, shop only those items; otherwise everything matched here. Skipped items
    // stay home; items bought this trip stay visible (ticked) so a wrong tap can be undone.
    val assigned = items.any { !it.isGroup && it.assignedStore != null }
    val candidates = items.filter { !it.isGroup && !it.isSkipped && (it.isBought || !assigned || it.assignedStore == store) }
    val shopping = candidates.filter { !it.isBought || it.assignedStore == store }.mapNotNull { item -> item.match(store)?.effective?.let { item to it } }
    val remaining = shopping.filter { !it.first.isBought }
    val total = shopping.sumOf { (item, product) -> product.priceCents * item.quantity }
    val sections = shopping.groupBy { it.second.category?.substringBefore(" / ")?.ifBlank { null } ?: "Other" }.toSortedMap()
    val scansHere = tripScans.filter { it.store == store && System.currentTimeMillis() - it.at < 12 * 60 * 60 * 1000 }

    val card = settings?.loyaltyCards?.get(store)
    var showCard by remember { mutableStateOf(false) }
    var editCard by remember { mutableStateOf(false) }
    if (showCard && card != null) LoyaltyCardDialog(store, stores, card, onDismiss = { showCard = false }, onEdit = { showCard = false; editCard = true })
    if (editCard) LoyaltyCardEditor(store, stores, card, onDismiss = { editCard = false }) { number -> viewModel.saveLoyaltyCard(store, number); editCard = false; if (number != null) showCard = true }

    fun scan() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E,
            )
            .enableAutoZoom()
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { viewModel.scanItem(store, it) } }
            .addOnFailureListener { error ->
                val cancelled = error is com.google.mlkit.common.MlKitException && error.errorCode == com.google.mlkit.common.MlKitException.CODE_SCANNER_CANCELLED
                if (!cancelled) android.widget.Toast.makeText(context, error.message ?: "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreBadge(store, stores); Text("${remaining.size} left · ${total.euros()}") } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { if (card != null) showCard = true else editCard = true }) {
                        Icon(Icons.Default.CardMembership, contentDescription = if (card != null) "Show loyalty card" else "Add loyalty card", tint = if (card != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { scan() }, icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) }, text = { Text("Scan") })
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val done = shopping.size - remaining.size
            LinearWavyProgressIndicator(progress = { if (shopping.isEmpty()) 0f else done.toFloat() / shopping.size }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                for ((section, entries) in sections) {
                    item("h-$section") {
                        Text(section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(entries, key = { it.first.id }) { (item, product) ->
                        // Buying (ticking) here records the purchase and puts the product in stock; ticking again undoes it.
                        val buy = { if (item.isBought) viewModel.unbuy(item) else viewModel.markBought(item, store, product) }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { buy() }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = item.isBought, onCheckedChange = { buy() }, modifier = Modifier.padding(4.dp))
                            ProductThumb(product, size = 44)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (item.quantity > 1) "${item.quantity}× " else "") + product.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    textDecoration = if (item.isBought) TextDecoration.LineThrough else null,
                                    color = if (item.isBought) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (item.isBought) Icon(Icons.Default.Kitchen, contentDescription = "In stock", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (item.isBought) "Bought · in stock · in today's order" else "${product.quantityText} · for “${item.text}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text((product.priceCents * item.quantity).euros(), fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider()
                    }
                }
                if (scansHere.isNotEmpty()) {
                    item("h-scanned") {
                        Text("Scanned here", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(scansHere, key = { it.id }) { scan ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val product = scan.product
                            if (product != null) ProductThumb(product, size = 44)
                            else Icon(if (scan.synced) Icons.Default.QrCodeScanner else Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product?.title ?: scan.barcode, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        product != null -> "${product.quantityText} · in today's order and in stock"
                                        scan.synced -> "Unknown product · saved by its barcode"
                                        else -> "Waiting for connection · stocked once online"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (product != null) Text(product.priceCents.euros(), fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider()
                    }
                }
                val missing = items.filter { !it.isGroup && it.isOpen && (!assigned || it.assignedStore == store) && it.match(store)?.effective == null }
                if (missing.isNotEmpty()) {
                    item("missing") {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Not matched at this store", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (item in missing) Text("• ${item.text}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
