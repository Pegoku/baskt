package nl.baskt.ui.purchases

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.baskt.data.Product
import nl.baskt.data.ReceiptLine
import nl.baskt.data.ReceiptScan
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.search.ScannerCamera

private fun Double.qty(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()

/** "1,29" / "1.29" / "129" (cents only when there is no separator and the number is big) → cents. */
internal fun parseEuros(text: String): Int? {
    val cleaned = text.trim().replace("€", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    val normalized = cleaned.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    return Math.round(value * 100).toInt()
}

private fun centsText(cents: Int?): String = cents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" } ?: ""

/**
 * The receipt as the model read it, open for correction before (or after) it is saved: fix a line, add what it
 * missed, drop what it invented, and link each line to the real product by searching or scanning its barcode so
 * the purchase carries pictures and prices.
 */
@Composable
fun ReceiptEditor(
    viewModel: AppViewModel,
    scan: ReceiptScan,
    storeCodes: List<String>,
    title: String,
    saveLabel: String,
    onChange: (ReceiptScan) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var store by remember(scan.store) { mutableStateOf(scan.store ?: storeCodes.firstOrNull() ?: "AH") }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Int?>(null) }
    var dateText by remember(scan.purchasedAt) { mutableStateOf(scan.purchasedAt ?: "") }
    val linesTotal = scan.lines.sumOf { it.totalPriceCents }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().rememberHorizontalScroll()) {
                for (code in (storeCodes + listOfNotNull(scan.store)).distinct()) FilterChip(selected = store == code, onClick = { store = code }, label = { Text(code) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it; if (it.isBlank() || Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)) onChange(scan.copy(purchasedAt = it.ifBlank { null })) },
                    label = { Text("Date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(linesTotal.euros(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val declared = scan.totalCents
                    if (declared != null && declared != linesTotal) Text("receipt says ${declared.euros()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else Text("${scan.lines.size} lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            scan.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(scan.lines) { index, line ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { editing = index }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LineThumb(line.product, size = 44)
                        Column(modifier = Modifier.weight(1f)) {
                            Text((if (line.quantity != 1.0) "${line.quantity.qty()}× " else "") + line.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val product = line.product
                            Text(
                                if (product != null) listOfNotNull(product.title, product.quantityText.ifBlank { null }).joinToString(" · ") else "No product yet · tap to link",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (product != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            line.dealText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        Text(line.totalPriceCents.euros(), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { removing = index }) { Icon(Icons.Default.Delete, contentDescription = "Remove ${line.name}") }
                    }
                }
            }
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a line the receipt missed")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(store) }, enabled = scan.lines.isNotEmpty()) { Text(saveLabel) }
                TextButton(onClick = onDismiss) { Text("Discard") }
            }
        }
    }
    editing?.let { index ->
        LineDialog(viewModel, scan.lines[index], store, onDismiss = { editing = null }) { line ->
            onChange(scan.copy(lines = scan.lines.mapIndexed { i, existing -> if (i == index) line else existing }))
            editing = null
        }
    }
    if (adding) LineDialog(viewModel, ReceiptLine(name = "", totalPriceCents = 0), store, onDismiss = { adding = false }) { line ->
        onChange(scan.copy(lines = scan.lines + line))
        adding = false
    }
    removing?.let { index ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this line?") },
            text = { Text(scan.lines[index].name) },
            confirmButton = { TextButton(onClick = { onChange(scan.copy(lines = scan.lines.filterIndexed { i, _ -> i != index })); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Modifier.rememberHorizontalScroll(): Modifier = this.horizontalScroll(rememberScrollState())

/** A product picture when the line is linked; a receipt glyph until then. */
@Composable
internal fun LineThumb(product: Product?, size: Int) {
    if (product != null) ProductThumb(product, size = size)
    else Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** One line: name, quantity, price and deal, plus the product it stands for (searched by name or scanned). */
@Composable
private fun LineDialog(viewModel: AppViewModel, line: ReceiptLine, store: String, onDismiss: () -> Unit, onDone: (ReceiptLine) -> Unit) {
    var name by remember { mutableStateOf(line.name) }
    var quantity by remember { mutableStateOf(if (line.quantity == 1.0 && line.name.isEmpty()) "1" else line.quantity.qty()) }
    var price by remember { mutableStateOf(if (line.name.isEmpty()) "" else centsText(line.totalPriceCents)) }
    var deal by remember { mutableStateOf(line.dealText ?: "") }
    var product by remember { mutableStateOf(line.product) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cents = parseEuros(price)
    val qty = quantity.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && cents != null && qty != null && qty > 0

    // Search as you type, a beat after the last key; the line's own name is the first query.
    LaunchedEffect(query) {
        val text = query.ifBlank { if (product == null) name else "" }.trim()
        if (text.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = viewModel.productsForReceipt(text, store)
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (line.name.isEmpty()) "Add a line" else "Edit line") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("As printed") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = quantity, onValueChange = { quantity = it }, label = { Text("Qty") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Total €") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1.4f), isError = price.isNotBlank() && cents == null)
                }
                OutlinedTextField(value = deal, onValueChange = { deal = it }, label = { Text("Bonus / discount") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                val linked = product
                if (linked != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProductThumb(linked, size = 48)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(linked.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(linked.store, linked.quantityText.ifBlank { null }, linked.priceCents.euros()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { product = null }) { Icon(Icons.Default.LinkOff, contentDescription = "Unlink product") }
                    }
                } else {
                    Text("Link the product", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(if (name.isBlank()) "Search $store" else name) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan the barcode") }
                    }
                    scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (searching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { LoadingIndicator(modifier = Modifier.size(20.dp)); Text("Searching…", style = MaterialTheme.typography.bodySmall) }
                    for (candidate in results.take(6)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { product = candidate; scanMessage = null; if (name.isBlank()) name = candidate.title }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ProductThumb(candidate, size = 40, onOpen = { product = candidate; scanMessage = null; if (name.isBlank()) name = candidate.title })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(candidate.quantityText.ifBlank { null }, candidate.priceCents.euros()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Link, contentDescription = "Link", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onDone(line.copy(
                        name = name.trim(),
                        quantity = qty ?: 1.0,
                        totalPriceCents = cents ?: 0,
                        unitPriceCents = if (qty != null && qty > 0 && cents != null) Math.round(cents / qty).toInt() else line.unitPriceCents,
                        dealText = deal.trim().ifBlank { null },
                        productId = product?.id,
                        product = product,
                    ))
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (scanning) BarcodeDialog(onClose = { scanning = false }) { code ->
        scope.launch {
            val found = viewModel.productForBarcode(code, store)
            if (found != null) {
                product = found
                scanMessage = if (found.store != store) "Found at ${found.store}, not $store" else null
                if (name.isBlank()) name = found.title
            } else scanMessage = "No product found for $code"
        }
        scanning = false
    }
}

/** Full-screen camera that closes on the first barcode it is sure about. */
@Composable
private fun BarcodeDialog(onClose: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF383838), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (granted) ScannerCamera(paused = false, cameraFraction = 1f) { code -> onCode(code); true }
                else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow camera access to scan the barcode", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White) }
                    Text("Scan the product's barcode", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text("Cancel", color = Color.White) }
            }
        }
    }
}
