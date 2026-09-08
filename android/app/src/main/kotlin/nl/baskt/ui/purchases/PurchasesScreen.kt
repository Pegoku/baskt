package nl.baskt.ui.purchases

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.baskt.data.ReceiptScan
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Receipts and spending: scan receipt photos, review the lines, keep a purchase history and monthly totals. */
@Composable
fun PurchasesScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val purchases by viewModel.purchases.collectAsState()
    val spend by viewModel.spend.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    val detail by viewModel.purchaseDetail.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    LaunchedEffect(Unit) { viewModel.loadPurchases() }

    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val images = uris.mapIndexedNotNull { index, uri -> context.contentResolver.openInputStream(uri)?.use { "receipt-$index.jpg" to it.readBytes() } }
        viewModel.scanReceipt(images, null)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris: List<Uri> -> upload(uris) }
    // ML Kit document scanner: camera with automatic edge detection, crop, filters and multi-page capture.
    val documentScanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val scanned = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        upload(scanned?.pages?.map { it.imageUri } ?: emptyList())
    }
    fun startCamera() {
        val activity = context as? android.app.Activity ?: return
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(5)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { intent -> documentScanner.launch(androidx.activity.result.IntentSenderRequest.Builder(intent).build()) }
            .addOnFailureListener { error -> android.widget.Toast.makeText(context, error.message ?: "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipts & spending") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.SmallFloatingActionButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.Image, contentDescription = "From photos")
                }
                ExtendedFloatingActionButton(
                    onClick = { startCamera() },
                    icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                    text = { Text("Scan receipt") },
                )
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) item("scanning") { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) { LoadingIndicator(); Text("Reading the receipt…") } }
            if (scanError != null) item("error") { Text(scanError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            val summary = spend
            if (summary != null && summary.purchases > 0) {
                item("summary") {
                    Card {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Last ${summary.months.size} months", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(summary.totalCents.euros(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${summary.purchases} receipts", style = MaterialTheme.typography.bodySmall)
                            val max = summary.months.maxOfOrNull { it.totalCents }?.coerceAtLeast(1) ?: 1
                            for (month in summary.months) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(month.month, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
                                    androidx.compose.material3.LinearProgressIndicator(progress = { month.totalCents.toFloat() / max }, modifier = Modifier.weight(1f))
                                    Text(month.totalCents.euros(), style = MaterialTheme.typography.labelMedium)
                                }
                                Text(month.perStore.entries.joinToString("  ") { "${it.key} ${it.value.euros()}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (summary.topProducts.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("Most spent on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                for (top in summary.topProducts.take(5)) Text("${top.name} · ${top.times}× · ${top.totalCents.euros()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (purchases.isEmpty() && !scanning) {
                item("empty") {
                    Text("No receipts yet. Scan a receipt photo and baskt reads the lines, matches them to products and keeps your spending per store.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            }
            items(purchases, key = { it.id }) { purchase ->
                Card(onClick = { viewModel.openPurchase(purchase.id) }) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StoreBadge(purchase.store, stores)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dateFormat.format(Date(purchase.purchasedAt)), style = MaterialTheme.typography.titleSmall)
                            Text("${purchase.lineCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(purchase.totalCents.euros(), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    val current = scan
    if (current != null) {
        ReviewScanSheet(current, stores.map { it.code }, onChange = { viewModel.updateScan(it) }, onDismiss = { viewModel.clearScan() }, onSave = { store -> viewModel.savePurchase(current, store) })
    }
    val open = detail
    if (open != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.closePurchase() }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoreBadge(open.purchase.store, stores)
                    Text(dateFormat.format(Date(open.purchase.purchasedAt)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(open.purchase.totalCents.euros(), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { viewModel.deletePurchase(open.purchase.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
                for (line in open.lines) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text((if (line.quantity != 1.0) "${line.quantity.toString().removeSuffix(".0")}× " else "") + line.name)
                            open.products[line.productId]?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            line.dealText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        Text(line.totalPriceCents.euros())
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewScanSheet(scan: ReceiptScan, storeCodes: List<String>, onChange: (ReceiptScan) -> Unit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var store by remember(scan.store) { mutableStateOf(scan.store ?: storeCodes.firstOrNull() ?: "AH") }
    var editing by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check the receipt", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (code in (storeCodes + listOfNotNull(scan.store)).distinct()) FilterChip(selected = store == code, onClick = { store = code }, label = { Text(code) })
            }
            Text(listOfNotNull(scan.purchasedAt, scan.totalCents?.euros()?.let { "total $it" }, scan.notes).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((index, line) in scan.lines.withIndex()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text((if (line.quantity != 1.0) "${line.quantity.toString().removeSuffix(".0")}× " else "") + line.name)
                        Text(line.product?.title ?: "no product match", style = MaterialTheme.typography.bodySmall, color = if (line.product != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
                    }
                    Text(line.totalPriceCents.euros())
                    IconButton(onClick = { editing = index }) { Icon(Icons.Default.Delete, contentDescription = "Remove line") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(store) }, enabled = scan.lines.isNotEmpty()) { Text("Save purchase") }
                TextButton(onClick = onDismiss) { Text("Discard") }
            }
        }
    }
    editing?.let { index ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Remove this line?") },
            text = { Text(scan.lines[index].name) },
            confirmButton = { TextButton(onClick = { onChange(scan.copy(lines = scan.lines.filterIndexed { i, _ -> i != index })); editing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Keep") } },
        )
    }
}
