package nl.baskt.ui.purchases

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.nullable
import nl.baskt.data.PurchaseDetail
import nl.baskt.data.ReceiptLine
import nl.baskt.data.ReceiptScan
import nl.baskt.data.ReceiptUpload
import nl.baskt.data.SpendMonth
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.storeName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "2026-08" as "Aug 2026"; anything else is shown as it came. */
private fun monthLabel(month: String): String = runCatching {
    java.time.YearMonth.parse(month.take(7)).format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
}.getOrDefault(month)

/** Receipts: scan or import a receipt, check its lines, and see what was spent per month and on what. */
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
    // A failed scan is reported like every other failure, in the app's one snackbar.
    LaunchedEffect(scanError) { scanError?.let { viewModel.notify("Couldn’t read the receipt: $it"); viewModel.clearScan() } }

    fun upload(uris: List<Uri>) = viewModel.scanReceipt(readReceiptUris(context, uris), null)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris: List<Uri> -> upload(uris) }
    // Any file: digital receipts the store app saves as PDF, downloads, email attachments.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> upload(uris) }
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
            .addOnFailureListener { error -> viewModel.notify("Couldn’t open the scanner" + (error.message?.let { ": $it" } ?: "")) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Receipts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // Importing is the secondary way in; the camera keeps the one FAB.
                    var importMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { importMenu = true }) { Icon(Icons.Default.UploadFile, contentDescription = "Import a receipt") }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("From photos") },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = { importMenu = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            DropdownMenuItem(
                                text = { Text("From a PDF or file") },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                onClick = { importMenu = false; filePicker.launch(arrayOf("application/pdf", "image/*")) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { startCamera() },
                icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                text = { Text("Scan receipt") },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Spacing.Screen, 12.dp, Spacing.Screen, Spacing.FabClearance), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
            if (scanning) item("scanning") { LoadingRow("Reading the receipt…", Modifier.padding(vertical = 8.dp)) }
            val summary = spend
            if (summary != null && summary.purchases > 0) item("summary") { SpendCard(summary, stores) }
            if (purchases.isEmpty() && !scanning) item("empty") {
                EmptyState("No receipts yet", "Scan one to see what you spend per store", icon = Icons.AutoMirrored.Filled.ReceiptLong, modifier = Modifier.fillParentMaxHeight(0.8f))
            }
            items(purchases, key = { it.id }) { purchase ->
                Card(onClick = { viewModel.openPurchase(purchase.id) }) {
                    Row(modifier = Modifier.padding(Spacing.Card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StoreBadge(purchase.store, stores)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dateFormat.format(Date(purchase.purchasedAt)), style = MaterialTheme.typography.titleSmall)
                            Text(counted(purchase.lineCount, "line"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(purchase.totalCents.euros(), style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                }
            }
        }
    }

    val current = scan
    if (current != null) {
        ReceiptEditor(viewModel, current, stores, original = null, onChange = { viewModel.updateScan(it) }, onDismiss = { viewModel.clearScan() }, onSave = { store -> viewModel.savePurchase(current, store) })
    }
    // Correcting a receipt that was already saved: same editor, the saved lines as the starting point. The detail
    // sheet closes first, so the editor never sits on top of it.
    var correcting by rememberSaveable(stateSaver = jsonSaver(ReceiptScan.serializer().nullable)) { mutableStateOf<ReceiptScan?>(null) }
    var correctingFrom by rememberSaveable(stateSaver = jsonSaver(ReceiptScan.serializer().nullable)) { mutableStateOf<ReceiptScan?>(null) }
    var correctingId by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = correcting
    val draftId = correctingId
    if (draft != null && draftId != null) {
        ReceiptEditor(viewModel, draft, stores, original = correctingFrom, onChange = { correcting = it }, onDismiss = { correcting = null; correctingId = null }, onSave = { store ->
            viewModel.updatePurchase(draftId, draft, store)
            correcting = null; correctingId = null
        })
    }
    // Deleting asks once (it cannot be undone), also after the detail sheet has closed.
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    deletingId?.let { id ->
        ConfirmDialog(
            title = "Delete this receipt?",
            body = "Its lines disappear from your spending and from Orders.",
            confirmLabel = "Delete",
            onDismiss = { deletingId = null },
            onConfirm = { viewModel.deletePurchase(id) },
        )
    }
    val open = detail
    if (open != null && draft == null) {
        ModalBottomSheet(onDismissRequest = { viewModel.closePurchase() }) {
            SheetBody(
                title = "${storeName(open.purchase.store, stores)} · ${dateFormat.format(Date(open.purchase.purchasedAt))}",
                actions = {
                    TextButton(onClick = { deletingId = open.purchase.id; viewModel.closePurchase() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                    Button(onClick = { val start = open.asScan(); correcting = start; correctingFrom = start; correctingId = open.purchase.id; viewModel.closePurchase() }) { Text("Edit") }
                },
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(open.purchase.totalCents.euros(), style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(counted(open.lines.size, "line"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                }
                for (line in open.lines) {
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LineThumb(open.products[line.productId], size = 48)
                        Column(modifier = Modifier.weight(1f)) {
                            Text((if (line.quantity != 1.0) "${line.quantity.qty()}× " else "") + line.name, style = MaterialTheme.typography.bodyMedium)
                            open.products[line.productId]?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            line.dealText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        Text(line.totalPriceCents.euros(), style = MaterialTheme.typography.labelLargeEmphasized)
                    }
                }
            }
        }
    }
}

/** What was spent lately: the total, one plain bar per month, and the products most money went to. */
@Composable
private fun SpendCard(summary: nl.baskt.data.SpendSummary, stores: List<StoreInfo>) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(if (summary.months.size == 1) "Last month" else "Last ${counted(summary.months.size, "month")}")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(summary.totalCents.euros(), style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(counted(summary.purchases, "receipt"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            val max = summary.months.maxOfOrNull { it.totalCents }?.coerceAtLeast(1) ?: 1
            for (month in summary.months) MonthBar(month, max, stores)
            if (summary.topProducts.isNotEmpty()) {
                SectionHeader("Most spent on", Modifier.padding(top = 4.dp))
                for (top in summary.topProducts.take(5)) Text("${top.name} · ${top.times}× · ${top.totalCents.euros()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One month as a plain bar (it is an amount, not progress), with the stores apart when there was more than one. */
@Composable
private fun MonthBar(month: SpendMonth, max: Int, stores: List<StoreInfo>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(monthLabel(month.month), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
            Box(modifier = Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)) {
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth((month.totalCents.toFloat() / max).coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
            }
            Text(month.totalCents.euros(), style = MaterialTheme.typography.labelMedium)
        }
        if (month.perStore.size > 1) Text(
            month.perStore.entries.joinToString(" · ") { "${storeName(it.key, stores)} ${it.value.euros()}" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A saved purchase in the shape the editor works on. */
private fun PurchaseDetail.asScan(): ReceiptScan = ReceiptScan(
    store = purchase.store,
    purchasedAt = java.time.Instant.ofEpochMilli(purchase.purchasedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
    totalCents = purchase.totalCents,
    lines = lines.map { ReceiptLine(it.name, it.quantity, it.unitPriceCents, it.totalPriceCents, it.dealText, it.productId, it.productId?.let { id -> products[id] }, itemId = it.itemId) },
)

/** Reads shared or picked receipt files into memory with their real type, so PDFs reach the server as PDFs. */
fun readReceiptUris(context: android.content.Context, uris: List<Uri>): List<ReceiptUpload> = uris.mapIndexedNotNull { index, uri ->
    val type = context.contentResolver.getType(uri)
        ?: if (uri.toString().lowercase().endsWith(".pdf")) "application/pdf" else "image/jpeg"
    val extension = if (type == "application/pdf") "pdf" else "jpg"
    runCatching { context.contentResolver.openInputStream(uri)?.use { ReceiptUpload("receipt-$index.$extension", type, it.readBytes()) } }.getOrNull()
}
