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

/** Continuous capture with an explicit review mode; nothing is added until the user chooses it. */
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
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column {
            Box(Modifier.fillMaxWidth().weight(if (reviewing) .3f else 1.4f)) {
                if (granted) ScannerCamera(paused || reviewing) { code ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (viewModel.onBarcodeSeen(code)) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        feedback = "Captured · ready for the next item"
                        feedbackAt = now
                    } else if (now - feedbackAt > 2500) {
                        feedback = "Already in your batch · scan another item"
                        feedbackAt = now
                    }
                } else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow camera access to scan products", color = Color.White)
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") }
                }
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close scanner", tint = Color.White) }
                    Text("Scan products", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { paused = !paused }, enabled = !reviewing) { Text(if (paused) "Resume" else "Pause", color = Color.White) }
                }
                Text(feedback ?: if (reviewing) "Review your batch below" else if (paused) "Resume when you’re ready" else "Fit one barcode in the frame · hold steady",
                    color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                    if (reviewing && ready.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.applyScans(ready.map { it.gtin }, "list") }, modifier = Modifier.weight(1f)) { Text("Add ${ready.size} to list") }
                        FilledTonalButton(onClick = { viewModel.applyScans(ready.map { it.gtin }, "stock") }, modifier = Modifier.weight(1f)) { Text("Add ${ready.size} to stock") }
                    }
                    if (scans.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scan a few. Review together.", style = MaterialTheme.typography.titleLarge)
                        Text("Each product appears here once. Keep scanning, then add products to your list or stock.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(scans.firstOrNull()?.gtin) { if (!reviewing) listState.animateScrollToItem(0) }
                        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(scans, key = { it.gtin }) { scan ->
                                Card {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(scan.gtin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { viewModel.dismissScan(scan.gtin) }, enabled = !scan.saving) { Icon(Icons.Default.Close, "Dismiss ${scan.gtin} from batch") }
                                        }
                                        val product = scan.products.firstOrNull()
                                        when {
                                            scan.loading -> Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(28.dp)); Text("Finding product…") }
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
                                                    Text(when (scan.done) { "list" -> "Added to list"; "stock" -> "Added to stock"; else -> "Removed from stock" })
                                                } else if (scan.saving) {
                                                    Text("Saving…")
                                                } else {
                                                    val inStock = stock.firstOrNull { it.productId == product.id || it.barcode == scan.gtin }
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
