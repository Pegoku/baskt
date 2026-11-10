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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import nl.baskt.data.Product
import nl.baskt.data.ReceiptLine
import nl.baskt.data.ReceiptScan
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.storeName
import nl.baskt.ui.search.ScannerCamera
import nl.baskt.ui.theme.BasktTheme

internal fun Double.qty(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()

/** "1,29" / "1.29" / "129" (cents only when there is no separator and the number is big) → cents. */
internal fun parseEuros(text: String): Int? {
    val cleaned = text.trim().replace("€", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    val normalized = cleaned.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    return Math.round(value * 100).toInt()
}

private fun centsText(cents: Int?): String = cents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" } ?: ""

/** Keeps a serializable value across a rotation as JSON text (receipts and products are not parcelable). */
internal fun <T> jsonSaver(serializer: KSerializer<T>): Saver<T, String> = Saver(
    save = { value -> runCatching { Json.encodeToString(serializer, value) }.getOrNull() },
    restore = { text -> runCatching { Json.decodeFromString(serializer, text) }.getOrNull() },
)

/** "2026-11-09" (or a full timestamp starting with it) as a date; null when it is something else. */
private fun receiptDate(value: String?): java.time.LocalDate? = value?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }

/**
 * The receipt as it was read, open for correction before (or after) it is saved: fix a line, add what was
 * missed, remove what is not there, and link each line to the real product by searching or scanning its barcode
 * so the receipt carries pictures and prices. [original] is the saved receipt when one is being corrected (null
 * for a fresh scan); leaving only asks first when there is something to lose.
 */
@Composable
fun ReceiptEditor(
    viewModel: AppViewModel,
    scan: ReceiptScan,
    stores: List<StoreInfo>,
    original: ReceiptScan?,
    onChange: (ReceiptScan) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val correcting = original != null
    val firstStore = (original ?: scan).store ?: stores.firstOrNull()?.code ?: "AH"
    var store by rememberSaveable(scan.store) { mutableStateOf(scan.store ?: stores.firstOrNull()?.code ?: "AH") }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    val linesTotal = scan.lines.sumOf { it.totalPriceCents }
    // A fresh scan is itself the work that would be lost; a saved receipt only once something was changed.
    val hasWork = scan.lines.isNotEmpty()
    val losesWork by rememberUpdatedState(if (correcting) scan != original || store != firstStore else hasWork)
    // A swipe or scrim tap would throw the corrections away; ask first when there is something to lose.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        if (value == SheetValue.Hidden && losesWork) { discarding = true; false } else true
    })
    // Undo of a removed line is only offered while this editor is still the one on screen.
    val latestScan by rememberUpdatedState(scan)
    val latestChange by rememberUpdatedState(onChange)
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { onDispose { alive[0] = false } }
    fun removeLine(index: Int) {
        val line = scan.lines[index]
        onChange(scan.copy(lines = scan.lines.filterIndexed { i, _ -> i != index }))
        viewModel.notifyUndo("Removed “${line.name}”") {
            if (alive[0]) latestChange(latestScan.copy(lines = latestScan.lines.toMutableList().also { it.add(index.coerceAtMost(it.size), line) }))
        }
    }

    if (discarding) ConfirmDialog(
        title = if (correcting) "Discard your changes?" else "Discard this receipt?",
        body = if (correcting) "The receipt stays as it was saved." else "The scanned lines and your corrections are lost.",
        confirmLabel = "Discard",
        dismissLabel = "Keep editing",
        onDismiss = { discarding = false },
        onConfirm = onDismiss,
    )
    if (pickingDate) {
        // The picker works in UTC days; the receipt keeps a plain date.
        val picker = rememberDatePickerState(initialSelectedDateMillis = (receiptDate(scan.purchasedAt) ?: java.time.LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onChange(scan.copy(purchasedAt = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(
            title = if (correcting) "Correct the receipt" else "Check the receipt",
            actions = {
                TextButton(onClick = { if (losesWork) discarding = true else onDismiss() }) { Text("Discard") }
                Button(onClick = { onSave(store) }, enabled = hasWork) { Text(if (correcting) "Save changes" else "Save receipt") }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Store", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (code in (stores.map { it.code } + listOfNotNull(scan.store)).distinct()) FilterChip(selected = store == code, onClick = { store = code }, label = { Text(storeName(code, stores)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // A read-only field: the layer over it opens the date picker, so the keyboard never comes up.
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = receiptDate(scan.purchasedAt)?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault())) ?: "",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Date") },
                        placeholder = { Text("Not set") },
                        trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = "Choose the date") { pickingDate = true })
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(linesTotal.euros(), style = MaterialTheme.typography.titleMediumEmphasized)
                    val declared = scan.totalCents
                    if (declared != null && declared != linesTotal) Text("Receipt says ${declared.euros()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else Text(counted(scan.lines.size, "line"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Column {
                for ((index, line) in scan.lines.withIndex()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Edit line") { editing = index }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ItemThumb(line.product?.imageUrl, contentDescription = null)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text((if (line.quantity != 1.0) "${line.quantity.qty()}× " else "") + line.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            line.product?.let { product ->
                                Text(listOfNotNull(product.title, product.quantityText.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            line.dealText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (line.product == null || line.itemId != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (line.product == null) Tag("No product", color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                                if (line.itemId != null) Tag("On your basket")
                            }
                        }
                        Text(line.totalPriceCents.euros(), style = MaterialTheme.typography.labelLargeEmphasized)
                        IconButton(onClick = { removeLine(index) }) { Icon(Icons.Default.Delete, contentDescription = "Remove ${line.name}") }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { adding = true }) { IconLabel(Icons.Default.Add, "Add a line") }
            }
        }
    }
    editing?.let { index ->
        val line = scan.lines.getOrNull(index)
        if (line == null) editing = null
        else LineEditor(viewModel, line, isNew = false, store, stores, onDismiss = { editing = null }) { changed ->
            onChange(scan.copy(lines = scan.lines.mapIndexed { i, existing -> if (i == index) changed else existing }))
            editing = null
        }
    }
    if (adding) LineEditor(viewModel, ReceiptLine(name = "", totalPriceCents = 0), isNew = true, store, stores, onDismiss = { adding = false }) { line ->
        onChange(scan.copy(lines = scan.lines + line))
        adding = false
    }
}

/** A product picture when the line is linked (tap it for the product's details); the shared placeholder until then. */
@Composable
internal fun LineThumb(product: Product?, size: Int) {
    if (product != null) ProductThumb(product, size = size) else nl.baskt.ui.common.PhotoPlaceholder(size)
}

/** A tappable row of the line editor: a picture, a title, one line under it. */
@Composable
private fun PickRow(imageUrl: String?, title: String, subtitle: String?, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ItemThumb(imageUrl, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One line, full screen: name, quantity, total and discount, the basket item it pays for, and the product it
 * stands for (searched by name or scanned). The barcode scanner takes over this same screen instead of opening
 * on top of it.
 */
@Composable
private fun LineEditor(viewModel: AppViewModel, line: ReceiptLine, isNew: Boolean, store: String, stores: List<StoreInfo>, onDismiss: () -> Unit, onDone: (ReceiptLine) -> Unit) {
    var name by rememberSaveable { mutableStateOf(line.name) }
    var quantity by rememberSaveable { mutableStateOf(if (isNew) "1" else line.quantity.qty()) }
    var price by rememberSaveable { mutableStateOf(if (isNew) "" else centsText(line.totalPriceCents)) }
    var deal by rememberSaveable { mutableStateOf(line.dealText ?: "") }
    var product by rememberSaveable(stateSaver = jsonSaver(Product.serializer().nullable)) { mutableStateOf(line.product) }
    var itemId by rememberSaveable { mutableStateOf(line.itemId) }
    var itemText by rememberSaveable { mutableStateOf(line.itemText) }
    // The search starts from the line's own name; type over it to look for something else.
    var query by rememberSaveable { mutableStateOf(if (line.product == null) line.name else "") }
    var shownResults by rememberSaveable { mutableIntStateOf(6) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedFor by remember { mutableStateOf<String?>(null) }
    val basketItems = remember(query, name, store) { viewModel.openItemsForReceipt(store, query.ifBlank { name }) }
    val scope = rememberCoroutineScope()
    val storeLabel = storeName(store, stores)
    val cents = parseEuros(price)
    val qty = quantity.replace(',', '.').toDoubleOrNull()
    val nameError = attempted && name.isBlank()
    val quantityError = (quantity.isNotBlank() || attempted) && (qty == null || qty <= 0)
    val priceError = (price.isNotBlank() || attempted) && cents == null
    val valid = name.isNotBlank() && cents != null && qty != null && qty > 0
    fun link(candidate: Product) { product = candidate; scanMessage = null; if (name.isBlank()) name = candidate.title }

    // Search as you type, a beat after the last key; with an empty field the line's name is searched.
    val searchText = query.ifBlank { name }.trim()
    LaunchedEffect(searchText, product == null, store) {
        if (product != null || searchText.length < 2) { results = emptyList(); searchedFor = null; return@LaunchedEffect }
        delay(350)
        searching = true
        results = viewModel.productsForReceipt(searchText, store)
        searchedFor = searchText
        shownResults = 6
        searching = false
    }

    Dialog(onDismissRequest = { if (scanning) scanning = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (scanning) {
            BarcodePane(onClose = { scanning = false }) { code ->
                scope.launch {
                    val found = viewModel.productForBarcode(code, store)
                    if (found != null) {
                        link(found)
                        scanMessage = if (found.store != store) "Found at ${storeName(found.store, stores)}, not $storeLabel" else null
                    } else scanMessage = "No product found for this barcode"
                }
                scanning = false
            }
            return@Dialog
        }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(if (isNew) "Add line" else "Edit line") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") } },
                    actions = {
                        TextButton(onClick = {
                            if (!valid) { attempted = true; return@TextButton }
                            onDone(line.copy(
                                name = name.trim(),
                                quantity = qty ?: 1.0,
                                totalPriceCents = cents ?: 0,
                                unitPriceCents = if (qty != null && qty > 0 && cents != null) Math.round(cents / qty).toInt() else line.unitPriceCents,
                                dealText = deal.trim().ifBlank { null },
                                productId = product?.id,
                                product = product,
                                itemId = itemId,
                                itemText = itemText,
                            ))
                        }) { Text("Done") }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name on receipt") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = nameError, supportingText = if (nameError) { { Text("Enter the name") } } else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = quantity, onValueChange = { quantity = it }, label = { Text("Quantity") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        isError = quantityError, supportingText = if (quantityError) { { Text("A number above 0") } } else null,
                    )
                    OutlinedTextField(
                        value = price, onValueChange = { price = it }, label = { Text("Total") }, prefix = { Text("€") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        isError = priceError, supportingText = if (priceError) { { Text("An amount, e.g. 1,29") } } else null,
                    )
                }
                OutlinedTextField(value = deal, onValueChange = { deal = it }, label = { Text("Discount") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                if (itemId != null) {
                    SectionHeader("In your basket")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saving checks off “${itemText ?: "an item"}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { itemId = null; itemText = null }) { Text("Keep in basket") }
                    }
                } else if (basketItems.isNotEmpty()) {
                    SectionHeader("In your basket")
                    for ((item, itemProduct) in basketItems) {
                        PickRow(itemProduct?.imageUrl, (if (item.quantity > 1) "${item.quantity}× " else "") + item.text, itemProduct?.title, "This line pays for it") {
                            itemId = item.id; itemText = item.text
                            if (product == null && itemProduct != null) product = itemProduct
                            if (name.isBlank()) name = itemProduct?.title ?: item.text
                        }
                    }
                }

                SectionHeader("Product")
                val linked = product
                if (linked != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProductThumb(linked)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(linked.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(storeName(linked.store, stores), linked.quantityText.ifBlank { null }, linked.priceCents.euros()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { product = null }) { Icon(Icons.Default.LinkOff, contentDescription = "Unlink product") }
                    }
                    scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(value = query, onValueChange = { query = it }, placeholder = "Search $storeLabel", onSearch = {}, modifier = Modifier.weight(1f))
                        IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan the barcode") }
                    }
                    scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (searching) LoadingRow("Searching…")
                    else if (searchedFor == searchText && results.isEmpty()) Text("No products at $storeLabel for “$searchText”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (candidate in results.take(shownResults)) {
                        PickRow(candidate.imageUrl, candidate.title, listOfNotNull(candidate.quantityText.ifBlank { null }, candidate.priceCents.euros()).joinToString(" · "), "Link this product") { link(candidate) }
                    }
                    if (results.size > shownResults) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { shownResults += 6 }) { Text("Show more") }
                    }
                }
            }
        }
    }
}

/**
 * The camera, filling the line editor until it is sure about one barcode. It is dark whatever the app's theme
 * is, so it is wrapped in the dark theme and takes its colours from there.
 */
@Composable
private fun BarcodePane(onClose: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    BasktTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (granted) ScannerCamera(paused = false, cameraFraction = 1f) { code -> onCode(code); true }
                else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Allow the camera to scan a barcode", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") }
                }
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close scanner") }
                    Text("Scan the product’s barcode", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
