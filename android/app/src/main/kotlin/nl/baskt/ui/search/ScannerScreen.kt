package nl.baskt.ui.search

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import nl.baskt.data.Product
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge
import java.util.concurrent.Executors

/**
 * Continuous barcode scanner: the camera keeps running, every new code is looked up and appears in the
 * list below with "Add to list" / "Add to stock". Closes only via the X.
 */
@Composable
fun ScannerScreen(viewModel: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stores by viewModel.basket.stores.collectAsState()
    val stock by viewModel.basket.stock.collectAsState()
    val scans by viewModel.scans.collectAsState()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { viewModel.refreshStock(); if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); viewModel.clearScans() } }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (granted) {
                    AndroidView(
                        factory = { ctx ->
                            val view = PreviewView(ctx)
                            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build())
                            ProcessCameraProvider.getInstance(ctx).also { future ->
                                future.addListener({
                                    val provider = future.get()
                                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                    analysis.setAnalyzer(executor) { proxy ->
                                        val media = proxy.image
                                        if (media == null) { proxy.close(); return@setAnalyzer }
                                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                            .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let { viewModel.onBarcodeSeen(it) } }
                                            .addOnCompleteListener { proxy.close() }
                                    }
                                    provider.unbindAll()
                                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                                }, ContextCompat.getMainExecutor(ctx))
                            }
                            view
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Aiming frame.
                    Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(180.dp).border(3.dp, Color.White.copy(alpha = 0.8f), MaterialTheme.shapes.large))
                } else {
                    Column(modifier = Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera permission is needed to scan", color = Color.White)
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White) }
                Text(
                    if (scans.isEmpty()) "Point at a barcode — keep scanning as many as you like" else "${scans.size} scanned · keep going or close",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (scans.isEmpty()) {
                    Text("Scanned products appear here.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = true) {
                        items(scans, key = { it.gtin }) { scan ->
                            Card {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val product: Product? = scan.products.firstOrNull()
                                    when {
                                        scan.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { LoadingIndicator(modifier = Modifier.height(22.dp)); Text("Looking up ${scan.gtin}…") }
                                        product == null -> Text("No store knows ${scan.gtin}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        else -> {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in scan.products) StoreBadge(p.store, stores) }
                                            ProductRow(product)
                                            val inStock = stock.firstOrNull { it.productId == product.id || it.barcode == scan.gtin }
                                            when (scan.done) {
                                                "list" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Text("Added to the list") }
                                                "stock" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Text("Added to stock") }
                                                "unstock" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Text("Removed from stock") }
                                                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                    Button(onClick = { viewModel.addFromProduct(product); viewModel.markScan(scan.gtin, "list") }, modifier = Modifier.weight(1f)) { Text("Add to list") }
                                                    if (inStock != null) OutlinedButton(onClick = { viewModel.removeStock(inStock); viewModel.markScan(scan.gtin, "unstock") }, modifier = Modifier.weight(1f)) { Text("Remove from stock") }
                                                    else FilledTonalButton(onClick = { viewModel.addProductToStock(product, scan.gtin); viewModel.markScan(scan.gtin, "stock") }, modifier = Modifier.weight(1f)) { Text("Add to stock") }
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
