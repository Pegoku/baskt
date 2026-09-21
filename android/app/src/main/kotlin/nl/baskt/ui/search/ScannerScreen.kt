package nl.baskt.ui.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.theme.BasktTheme

/** Continuous capture with an explicit review mode; nothing is added until the user chooses it. */
@Composable
fun ScannerScreen(viewModel: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val stores by viewModel.basket.stores.collectAsState()
    val stock by viewModel.basket.stock.collectAsState()
    val scans by viewModel.scans.collectAsState()
    val online by viewModel.online.collectAsState()
    var paused by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackAt by remember { mutableLongStateOf(0L) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { viewModel.refreshStock(); if (!granted) permission.launch(Manifest.permission.CAMERA) }
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
    val cameraFraction = if (reviewing) .3f / 1.3f else 1.4f / 2.4f
    Surface(color = Color(0xFF383838), modifier = Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize()) {
        if (granted) ScannerCamera(paused || reviewing, cameraFraction) { code ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    val accepted = viewModel.onBarcodeSeen(code)
                    if (accepted) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        feedback = "Captured · ready for the next item"
                        feedbackAt = now
                    } else if (now - feedbackAt > 2500) {
                        feedback = "Already in your batch · scan another item"
                        feedbackAt = now
                    }
                    accepted

                }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(if (reviewing) .3f else 1.4f)) {
                if (!granted) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow camera access to scan products", color = Color.White)
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") }
                }
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close scanner", tint = Color.White) }
                    Text("Scan products", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { paused = !paused }, enabled = !reviewing) { Text(if (paused) "Resume" else "Pause", color = Color.White) }
                }
                if (!online) Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(16.dp))
                        Text("Offline · known products load from your phone, new codes are looked up later", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(feedback ?: if (reviewing) "Review your batch below" else if (paused) "Resume when you’re ready" else if (!online) "Scan as usual · the barcode goes on your list and becomes the product when you’re back online" else "Point at a barcode · above or below the frame works too",
                    color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
              BasktTheme(darkTheme = true) {
               Surface(color = Color(0xFF484848).copy(alpha = .78f), contentColor = Color.White, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your batch · ${scans.size}", style = MaterialTheme.typography.titleMedium)
                            Text("${scans.count { it.done != null }} added or updated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { reviewing = !reviewing }, enabled = scans.isNotEmpty() || reviewing) { Text(if (reviewing) "Scan more" else "Review") }
                        TextButton(onClick = onClose) { Text("Done") }
                    }
                    val ready = scans.filter { !it.loading && !it.saving && it.done == null && it.products.isNotEmpty() }
                    val listable = scans.filter { it.listable }
                    val toStock = ready.filter { it.stockItem(stock) == null }
                    val fromStock = ready.filter { it.stockItem(stock) != null }
                    if (reviewing && listable.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { viewModel.applyScans(listable.map { it.gtin }, "list") }, modifier = Modifier.fillMaxWidth()) { Text("Add ${listable.size} to list") }
                        if (listable.size > ready.size) Text("${listable.size - ready.size} not looked up yet · added as barcodes, resolved when you’re back online", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (toStock.isNotEmpty()) FilledTonalButton(onClick = { viewModel.applyScans(toStock.map { it.gtin }, "stock") }, modifier = Modifier.weight(1f)) { Text("Add ${toStock.size} to stock") }
                            if (fromStock.isNotEmpty()) OutlinedButton(onClick = { viewModel.applyScans(fromStock.map { it.gtin }, "unstock") }, modifier = Modifier.weight(1f)) { Text("Remove ${fromStock.size} from stock") }
                        }
                    }
                    if (scans.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scan a few. Review together.", style = MaterialTheme.typography.titleLarge)
                        Text("Keep scanning, then review your list and stock. To scan an item again, move its barcode away from the camera and back.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(scans.firstOrNull()?.gtin) { if (!reviewing) listState.animateScrollToItem(0) }
                        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(scans, key = { it.gtin }) { scan ->
                                Card {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(scan.gtin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { viewModel.dismissScan(scan.gtin); feedback = null }, enabled = !scan.saving) { Icon(Icons.Default.Close, "Dismiss ${scan.gtin} from batch") }
                                        }
                                        val product = scan.products.firstOrNull()
                                        when {
                                            scan.loading -> Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(28.dp)); Text("Finding product…") }
                                            product == null && scan.offline -> {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error)
                                                    Text("Offline · not looked up yet", style = MaterialTheme.typography.titleMedium)
                                                }
                                                if (scan.done != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    Column {
                                                        Text("Added to list as a barcode")
                                                        Text("It turns into the product when you’re back online", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                } else if (scan.saving) {
                                                    Text("Saving…")
                                                } else {
                                                    Text("The barcode goes on your list now and becomes the product once the server is reachable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Button(onClick = { viewModel.applyScans(listOf(scan.gtin), "list") }, modifier = Modifier.fillMaxWidth()) { Text("Add barcode to list") }
                                                }
                                            }
                                            product == null -> {
                                                Text("Product not found", style = MaterialTheme.typography.titleMedium)
                                                Text("Check the barcode or retry when connected. You can keep scanning.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                TextButton(onClick = { viewModel.retryScan(scan.gtin) }) { Text("Retry lookup") }
                                            }
                                            else -> {
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { scan.products.distinctBy { it.store }.forEach { StoreBadge(it.store, stores) } }
                                                ProductRow(product)
                                                if (scan.done != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    Column {
                                                        Text(when (scan.done) { "list" -> "Added to list"; "stock" -> "Added to stock"; else -> "Removed from stock" })
                                                        Text("Scan again to use this item again", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                } else if (scan.saving) {
                                                    Text("Saving…")
                                                } else {
                                                    val inStock = scan.stockItem(stock)
                                                    Button(onClick = { viewModel.applyScans(listOf(scan.gtin), "list") }, modifier = Modifier.fillMaxWidth()) { Text("Add to list") }
                                                    FilledTonalButton(onClick = {
                                                        viewModel.applyScans(listOf(scan.gtin), if (inStock != null) "unstock" else "stock")
                                                    }, modifier = Modifier.fillMaxWidth()) { Text(if (inStock != null) "Remove from stock" else "Add to stock") }
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
            }
        }
      }
    }
}
