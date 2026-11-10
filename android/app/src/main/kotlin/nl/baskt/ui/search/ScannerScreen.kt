package nl.baskt.ui.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.counted
import nl.baskt.ui.theme.BasktTheme

/**
 * Continuous capture with an explicit review mode; nothing is added until the user chooses it. The whole screen
 * is in the dark theme whatever the app's theme is: it is a camera, and every control on it must read on dark.
 */
@Composable
fun ScannerScreen(viewModel: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val stores by viewModel.basket.stores.collectAsState()
    val stock by viewModel.basket.stock.collectAsState()
    val scans by viewModel.scans.collectAsState()
    var paused by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackAt by remember { mutableLongStateOf(0L) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // The camera permission is asked from the "Allow camera" button, under a line that says what it is for.
    LaunchedEffect(Unit) { viewModel.refreshStock() }
    LaunchedEffect(feedbackAt) { delay(1800); feedback = null }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) viewModel.clearScans()
        }
    }
    // Leaving drops the scans, so anything that could still be added is asked about first (button and system back alike).
    val unapplied = scans.count { it.done == null && (it.loading || it.saving || it.listable) }
    fun close() { if (unapplied > 0) confirmClose = true else onClose() }
    BackHandler(enabled = unapplied > 0) { confirmClose = true }

    /** Taking things out of stock acts at once and can be undone: the entries come back as they were. */
    fun unstock(targets: List<AppViewModel.Scan>) {
        val removed = targets.mapNotNull { it.stockItem(stock) }
        viewModel.applyScans(targets.map { it.gtin }, "unstock")
        if (removed.isNotEmpty()) viewModel.notifyUndo(if (removed.size == 1) "Removed “${removed.first().text}” from stock" else "Removed ${removed.size} from stock") { removed.forEach { viewModel.restoreStock(it) } }
    }

    val cameraFraction = if (reviewing) .3f / 1.3f else 1.4f / 2.4f
    BasktTheme(darkTheme = true) {
        val colors = MaterialTheme.colorScheme
        if (confirmClose) ConfirmDialog(
            title = "Discard ${counted(unapplied, "scan")}?",
            body = "Scans you haven’t added are lost.",
            confirmLabel = "Discard",
            dismissLabel = "Keep scanning",
            onDismiss = { confirmClose = false },
            onConfirm = onClose,
        )
        Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (granted) ScannerCamera(paused || reviewing, cameraFraction) { code ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    val accepted = viewModel.onBarcodeSeen(code)
                    if (accepted) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        feedback = "Scanned"
                        feedbackAt = now
                    } else if (now - feedbackAt > 2500) {
                        feedback = "Already scanned"
                        feedbackAt = now
                    }
                    accepted
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(if (reviewing) .3f else 1.4f)) {
                        if (!granted) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("baskt uses the camera to read barcodes", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") }
                        }
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { close() }) { Icon(Icons.Default.Close, contentDescription = "Close") }
                                Text("Scan products", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                if (granted) TextButton(onClick = { paused = !paused }, enabled = !reviewing) { Text(if (paused) "Resume" else "Pause") }
                            }
                            // The one place that says the phone is offline; the cards only say what each scan still lacks.
                            OfflineBanner(viewModel)
                        }
                        // What the camera just did, also announced to a screen reader; otherwise the one-line how-to.
                        val hint = feedback ?: if (granted && !reviewing && !paused) "Point at a barcode" else ""
                        Text(hint, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        // See-through on purpose, so the camera stays visible behind the panel.
                        Surface(color = colors.surfaceContainerHigh.copy(alpha = .78f), shape = BottomSheetDefaults.ExpandedShape, modifier = Modifier.fillMaxSize()) {
                            Column(Modifier.navigationBarsPadding()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (scans.isEmpty()) "Scanned" else "Scanned · ${scans.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { reviewing = !reviewing }, enabled = scans.isNotEmpty() || reviewing) { Text(if (reviewing) "Scan more" else "Review") }
                                    // "Done" only exists once nothing is left to add; before that, Close asks about the rest.
                                    if (scans.isNotEmpty() && unapplied == 0) Button(onClick = onClose) { Text("Done") }
                                }
                                val ready = scans.filter { !it.loading && !it.saving && it.done == null && it.products.isNotEmpty() }
                                val listable = scans.filter { it.listable }
                                val toStock = ready.filter { it.stockItem(stock) == null }
                                val fromStock = ready.filter { it.stockItem(stock) != null }
                                if (reviewing && listable.isNotEmpty()) FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    if (fromStock.isNotEmpty()) OutlinedButton(onClick = { unstock(fromStock) }) { Text("Remove ${fromStock.size} from stock") }
                                    if (toStock.isNotEmpty()) OutlinedButton(onClick = { viewModel.applyScans(toStock.map { it.gtin }, "stock") }) { Text("Add ${toStock.size} to stock") }
                                    FilledTonalButton(onClick = { viewModel.applyScans(listable.map { it.gtin }, "list") }) { Text("Add ${listable.size} to basket") }
                                }
                                if (scans.isEmpty()) EmptyState(
                                    title = "Nothing scanned yet",
                                    body = "Scanned products collect here for review.",
                                    icon = Icons.Default.QrCodeScanner,
                                    modifier = Modifier.fillMaxSize(),
                                ) else {
                                    val listState = rememberLazyListState()
                                    LaunchedEffect(scans.firstOrNull()?.gtin) { if (!reviewing) listState.animateScrollToItem(0) }
                                    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = Spacing.ListGap), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
                                        items(scans, key = { it.gtin }) { scan ->
                                            ScanCard(
                                                scan = scan,
                                                stores = stores,
                                                inStock = scan.stockItem(stock) != null,
                                                onRemove = {
                                                    viewModel.dismissScan(scan.gtin)
                                                    feedback = null
                                                    // A scan that was not added yet comes back with one tap; a finished one is only tidied away.
                                                    if (scan.done == null) viewModel.notifyUndo("Removed the scan") { viewModel.onBarcodeSeen(scan.gtin) }
                                                },
                                                onRetry = { viewModel.retryScan(scan.gtin) },
                                                onPick = { viewModel.pickSuggestion(scan.gtin, it) },
                                                onAdd = { viewModel.applyScans(listOf(scan.gtin), "list") },
                                                onStock = { viewModel.applyScans(listOf(scan.gtin), "stock") },
                                                onUnstock = { unstock(listOf(scan)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One scanned code: what it is (the product, or what the phone knows about the code, or just the number), where
 * it stands (looking it up, saving, added), and the two things to do with it, right-aligned like on every card.
 */
@Composable
private fun ScanCard(
    scan: AppViewModel.Scan,
    stores: List<nl.baskt.data.StoreInfo>,
    inStock: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onPick: (nl.baskt.data.Product) -> Unit,
    onAdd: () -> Unit,
    onStock: () -> Unit,
    onUnstock: () -> Unit,
) {
    val product = scan.products.firstOrNull()
    Card {
        Column(Modifier.padding(start = Spacing.Card, end = 4.dp, top = 4.dp, bottom = Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The number is only the header while nothing better is known about the code.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (product != null) scan.products.distinctBy { it.store }.forEach { StoreBadge(it.store, stores) }
                    else Text(scan.gtin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRemove, enabled = !scan.saving) { Icon(Icons.Default.Close, contentDescription = "Remove ${product?.title ?: scan.label?.title ?: scan.gtin}") }
            }
            Column(Modifier.padding(end = Spacing.Card - 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    scan.loading -> Busy("Finding product…")
                    product == null && scan.offline -> {
                        ScanLabel(scan.label)
                        Text(if (scan.label != null) "No store price yet" else "Not looked up yet", style = MaterialTheme.typography.titleSmall)
                        when {
                            scan.done != null -> Applied("Added to basket as a barcode")
                            scan.saving -> Busy("Saving…")
                            else -> CardActions { FilledTonalButton(onClick = onAdd) { Text("Add") } }
                        }
                    }
                    product == null && scan.suggestions.isNotEmpty() -> {
                        ScanLabel(scan.label)
                        Text("Not found by barcode", style = MaterialTheme.typography.titleSmall)
                        Text("Pick the closest match", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        scan.suggestions.forEach { suggestion ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StoreBadge(suggestion.store, stores)
                                ProductRow(suggestion, trailing = { FilledTonalButton(onClick = { onPick(suggestion) }) { Text("This one") } })
                            }
                        }
                    }
                    product == null -> {
                        ScanLabel(scan.label)
                        Text("Product not found", style = MaterialTheme.typography.titleSmall)
                        CardActions { FilledTonalButton(onClick = onRetry) { Text("Try again") } }
                    }
                    else -> {
                        ProductRow(product)
                        when {
                            scan.done != null -> Applied(when (scan.done) { "list" -> "Added to basket"; "stock" -> "Added to stock"; else -> "Removed from stock" })
                            scan.saving -> Busy("Saving…")
                            else -> CardActions {
                                OutlinedButton(onClick = if (inStock) onUnstock else onStock) { Text(if (inStock) "Remove from stock" else "Add to stock") }
                                FilledTonalButton(onClick = onAdd) { Text("Add") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A card's actions: right-aligned, the secondary one first. */
@Composable
private fun CardActions(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = content)

/** Something is on its way for this card. */
@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** What was done with this card's scan. */
@Composable
private fun Applied(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * What the phone itself knows about a code: shown when no store product is available, so a scan is recognisable
 * instead of being a bare number. Never a price: this is not a store answer.
 */
@Composable
private fun ScanLabel(label: nl.baskt.data.ScannedLabel?) {
    if (label == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Product photos come cut out on white, so their tile is white in every theme.
        if (label.imageUrl != null) Surface(color = Color.White, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(56.dp)) {
            coil3.compose.AsyncImage(model = label.imageUrl, contentDescription = null, modifier = Modifier.padding(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label.title, style = MaterialTheme.typography.titleMedium)
            val facts = listOfNotNull(label.quantityText, if (label.source == "openfoodfacts") "From Open Food Facts" else null).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
