@file:OptIn(ExperimentalFoundationApi::class)

package nl.baskt.ui.basket

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import nl.baskt.ui.common.buildShareText
import nl.baskt.ui.common.shareText
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.BasketSwitcherTitle
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.storeName

@Composable
fun BasketScreen(viewModel: AppViewModel, onOpenItem: (String) -> Unit, onOpenGroup: (String) -> Unit, onCompare: () -> Unit, onSettings: () -> Unit, onStock: () -> Unit, onSearch: () -> Unit, onDeals: () -> Unit, onRecipes: () -> Unit, onShop: (String) -> Unit, onPurchases: () -> Unit, onScan: () -> Unit = {}) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val baskets by viewModel.basket.baskets.collectAsState()
    val currentBasketId by viewModel.basket.currentBasketId.collectAsState()
    val loading by viewModel.basket.loading.collectAsState()
    val error by viewModel.basket.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val enabledStores = stores.filter { it.enabled }

    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) viewModel.interpret(spoken)
    }
    fun startDictation() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, settings?.resolvedLanguage ?: java.util.Locale.getDefault().language)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "What do you need?")
        }
        runCatching { speech.launch(intent) }.onFailure { android.widget.Toast.makeText(context, "Speech recognition is not available", android.widget.Toast.LENGTH_SHORT).show() }
    }
    val dictateRequest by viewModel.dictateRequest.collectAsState()
    LaunchedEffect(dictateRequest) { if (dictateRequest) { viewModel.dictateRequest.value = false; startDictation() } }
    val selection by viewModel.selection.collectAsState()
    val selectionMode = selection.isNotEmpty()
    var renaming by remember { mutableStateOf<BasketItem?>(null) }
    var naming by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun shareLink(whatsApp: Boolean) = scope.launch {
        val link = viewModel.shareLink() ?: return@launch
        val basketName = baskets.firstOrNull { it.id == currentBasketId }?.name ?: "baskt"
        shareText(context, "🧺 $basketName — open the list, tick things off, add ideas:\n$link", whatsApp)
    }
    if (renaming != null) {
        val target = renaming!!
        NameDialog(title = "Rename", initial = target.text, onDismiss = { renaming = null }) { viewModel.rename(target, it); renaming = null; viewModel.clearSelection() }
    }
    if (naming) {
        NameDialog(title = "New folder", initial = "", onDismiss = { naming = false }) { viewModel.groupSelected(it); naming = false }
    }
    val whatsapp by viewModel.whatsapp.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadWhatsApp() }
    /** Opens the Google code scanner straight away; the result shows in the scan sheet below. */
    fun startScan(context: android.content.Context) {
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
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { viewModel.lookupBarcode(it); viewModel.refreshStock() } }
            .addOnFailureListener { error ->
                val cancelled = error is com.google.mlkit.common.MlKitException && error.errorCode == com.google.mlkit.common.MlKitException.CODE_SCANNER_CANCELLED
                if (!cancelled) android.widget.Toast.makeText(context, error.message ?: "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
    val scanResult by viewModel.scanResult.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val stock by viewModel.basket.stock.collectAsState()
    if (scanResult != null || searching) {
        ScanResultSheet(
            result = scanResult,
            searching = searching,
            stores = enabledStores,
            stockEntryFor = { product -> stock.firstOrNull { it.productId == product.id || (scanResult?.gtin != null && it.barcode == scanResult?.gtin) } },
            onDismiss = { viewModel.clearSearch() },
            onAddToList = { product -> viewModel.addFromProduct(product); viewModel.clearSearch(); scope.launch { snackbar.showSnackbar("Added ${product.title} to the list") } },
            onAddToStock = { product -> viewModel.addProductToStock(product, scanResult?.gtin); viewModel.clearSearch(); scope.launch { snackbar.showSnackbar("${product.title} is now in stock") } },
            onRemoveFromStock = { entry -> viewModel.removeStock(entry); viewModel.clearSearch(); scope.launch { snackbar.showSnackbar("Removed ${entry.text} from stock") } },
            onSearchInstead = { viewModel.clearSearch(); onSearch() },
        )
    }
    fun sendBot() = scope.launch {
        viewModel.sendToWhatsApp().fold(
            { count -> snackbar.showSnackbar("Sent $count items to WhatsApp — react ✅ to check them off") },
            { error -> snackbar.showSnackbar(error.message ?: "WhatsApp bridge failed", actionLabel = "Settings").also { if (it == androidx.compose.material3.SnackbarResult.ActionPerformed) onSettings() } },
        )
    }
    val proposal by viewModel.voiceProposal.collectAsState()
    val interpreting by viewModel.interpreting.collectAsState()
    if (proposal != null || interpreting) {
        VoiceConfirmSheet(proposal ?: emptyList(), interpreting, onDismiss = { viewModel.dismissProposal() }, onConfirm = { viewModel.confirmProposal(it) })
    }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "Settings")
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onSettings()
        viewModel.basket.clearError()
    }

    androidx.activity.compose.BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    Scaffold(
        topBar = {
            if (selectionMode) {
                val selectedItems = items.filter { it.id in selection }
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = { viewModel.clearSelection() }) { Icon(Icons.Default.Close, contentDescription = "Cancel") } },
                    actions = {
                        if (selectedItems.size == 1) IconButton(onClick = { renaming = selectedItems.first() }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                        if (selectedItems.any { !it.isGroup }) IconButton(onClick = { naming = true }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Folder from selection") }
                        IconButton(onClick = { viewModel.checkSelected(selectedItems.any { !it.checked }) }) { Icon(Icons.Default.CheckCircle, contentDescription = "Check / uncheck") }
                        nl.baskt.ui.common.TransferMenu(baskets, currentBasketId) { basketId, copy -> viewModel.transferSelected(basketId, copy) }
                        IconButton(onClick = { viewModel.deleteSelected() }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
                    },
                )
            } else TopAppBar(
                title = {
                    // The server's counts lag behind local edits; show live numbers for the open basket.
                    val liveBaskets = baskets.map { basket ->
                        if (basket.id != currentBasketId) basket
                        else basket.copy(itemCount = items.count { !it.isGroup }, openCount = items.count { !it.isGroup && !it.checked })
                    }
                    BasketSwitcherTitle(
                        baskets = liveBaskets,
                        currentId = currentBasketId,
                        onSwitch = { viewModel.switchBasket(it) },
                        onCreate = { name, emoji -> viewModel.createBasket(name, emoji) },
                        onRename = { basket, name, emoji -> viewModel.renameBasket(basket.id, name, emoji) },
                        onDelete = { viewModel.deleteBasket(it.id) },
                    )
                },
                actions = {
                    IconButton(onClick = onScan) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcodes") }
                    IconButton(onClick = onRecipes) { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Recipes") }
                    if (items.any { !it.checked && !it.isGroup }) IconButton(onClick = onDeals) { Icon(Icons.Default.LocalOffer, contentDescription = "Find deals") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            for (store in enabledStores) {
                                DropdownMenuItem(text = { Text("Shop at ${store.name}") }, leadingIcon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) }, onClick = { menu = false; onShop(store.code) })
                            }
                            if (whatsapp?.enabled == true) {
                                DropdownMenuItem(text = { Text("Send items to WhatsApp bot") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = { menu = false; sendBot() })
                            }
                            DropdownMenuItem(text = { Text("Share link (live list)") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = { menu = false; shareLink(whatsApp = false) })
                            DropdownMenuItem(text = { Text("Send link to WhatsApp") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = { menu = false; shareLink(whatsApp = true) })
                            DropdownMenuItem(text = { Text("Share as text") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = { menu = false; shareText(context, buildShareText(baskets.firstOrNull { it.id == currentBasketId }, items, enabledStores), whatsApp = false) })
                            DropdownMenuItem(text = { Text("Search products") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, onClick = { menu = false; onSearch() })
                            DropdownMenuItem(text = { Text("Stock") }, leadingIcon = { Icon(Icons.Default.Kitchen, contentDescription = null) }, onClick = { menu = false; onStock() })
                            DropdownMenuItem(text = { Text("Receipts & spending") }, leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null) }, onClick = { menu = false; onPurchases() })
                            DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }, onClick = { menu = false; viewModel.reload() })
                            if (items.any { it.checked }) {
                                DropdownMenuItem(text = { Text("Clear checked") }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }, onClick = { menu = false; viewModel.clearChecked() })
                            }
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }, onClick = { menu = false; onSettings() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (items.any { !it.checked }) {
                ExtendedFloatingActionButton(
                    onClick = onCompare,
                    icon = { Icon(Icons.Default.Balance, contentDescription = null) },
                    text = { Text("Compare") },
                )
            }
        },
        bottomBar = {
            val suggestions by viewModel.suggestions.collectAsState()
            val recipe by viewModel.recipeSuggestion.collectAsState()
            AddIdeaBar(
                suggestions = suggestions,
                recipe = recipe,
                onTextChanged = { viewModel.onIdeaTextChanged(it) },
                onAdd = { text, quantity -> viewModel.clearSuggestions(); viewModel.add(text, quantity) },
                onAddGroup = { title, picked -> viewModel.addGroup(title, picked) },
                onAddMany = { picked -> viewModel.addMany(picked) },
                onVoice = { startDictation() },
                focusRequest = viewModel.focusInputRequest,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        nl.baskt.ui.common.OfflineBanner(viewModel, needsServer = "New ideas are matched once you're back online.")
        Box(modifier = Modifier.fillMaxSize()) {
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loading) {
                        LoadingIndicator()
                    } else {
                        Text("Your basket of ideas is empty", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Type what you need, like \"halfvolle milk\" or \"something for pasta sauce\". baskt finds matching products at each supermarket.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (enabledStores.isEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = onSettings) { Text("Connect to your baskt server") }
                        }
                    }
                }
            } else {
                val topLevel = items.filter { it.parentId == null }
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(topLevel, key = { it.id }) { item ->
                        val selected = item.id in selection
                        val open: () -> Unit = { if (selectionMode) viewModel.toggleSelected(item.id) else if (item.isGroup) onOpenGroup(item.id) else onOpenItem(item.id) }
                        val longPress: () -> Unit = { viewModel.toggleSelected(item.id) }
                        val content: @Composable () -> Unit = {
                            Box(modifier = if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier) {
                                if (item.isGroup) {
                                    GroupCard(item, items.filter { it.parentId == item.id }, enabledStores, onClick = open, onLongClick = longPress, onToggle = { viewModel.toggleChecked(item) })
                                } else {
                                    BasketItemCard(item, enabledStores, onClick = open, onLongClick = longPress, onToggle = { viewModel.toggleChecked(item) })
                                }
                            }
                        }
                        if (selectionMode) content() else DismissibleItem(item, onDelete = { viewModel.delete(item) }) { content() }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DismissibleItem(item: BasketItem, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            true
        } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        },
    ) { content() }
    // Keep the key stable even if the same item id re-enters the list.
    LaunchedEffect(item.id) {}
}

@Composable
fun BasketItemCard(item: BasketItem, stores: List<StoreInfo>, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = if (item.checked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.quantity > 1) Text("×${item.quantity}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                when {
                    item.isQueued -> Text("Waiting for connection — will be matched later", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    item.isProcessing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                        Text("Finding products…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item.status == "ERROR" -> Text(item.error ?: "Matching failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (store in stores) {
                            val match = item.match(store.code)
                            val product = match?.effective
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                StoreBadge(store.code, stores)
                                Text(
                                    when {
                                        match == null -> "…"
                                        match.status == "NONE" -> "skip"
                                        match.status == "EXHAUSTED" -> "none found"
                                        product != null -> (product.priceCents * item.quantity).euros() + if (match.status == "PENDING") "?" else ""
                                        else -> "—"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (match?.status == "CHOSEN") FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (match?.status == "PENDING") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (item.needsChoice) {
                    Text("Tap to choose products", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

/** A folder row: title, child count and per-store totals summed over the children. */
@Composable
fun GroupCard(group: BasketItem, children: List<BasketItem>, stores: List<StoreInfo>, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onToggle: () -> Unit) {
    val allChecked = children.isNotEmpty() && children.all { it.checked }
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = if (allChecked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allChecked, onCheckedChange = { onToggle() }, enabled = children.isNotEmpty())
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        group.text,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (allChecked) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                when {
                    group.isProcessing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                        Text("Looking up the recipe…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    group.status == "ERROR" -> Text(group.error ?: "Could not build this folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {
                        val open = children.filter { !it.checked }
                        val busy = children.count { it.isProcessing }
                        Text(
                            listOfNotNull("${children.size} items", if (busy > 0) "$busy searching" else null, if (group.needsChoice) "choices to make" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (group.needsChoice) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (store in stores) {
                                val total = open.sumOf { child -> (child.match(store.code)?.effective?.priceCents ?: 0) * child.quantity }
                                val missing = open.count { it.match(store.code)?.effective == null }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StoreBadge(store.code, stores)
                                    Text(
                                        if (open.isEmpty()) "—" else total.euros() + if (missing > 0) " (−$missing)" else "",
                                        style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** After a barcode scan: what was found per store, and what to do with it. */
@Composable
private fun ScanResultSheet(
    result: nl.baskt.data.BarcodeResponse?,
    searching: Boolean,
    stores: List<StoreInfo>,
    stockEntryFor: (nl.baskt.data.Product) -> nl.baskt.data.StockItem?,
    onDismiss: () -> Unit,
    onAddToList: (nl.baskt.data.Product) -> Unit,
    onAddToStock: (nl.baskt.data.Product) -> Unit,
    onRemoveFromStock: (nl.baskt.data.StockItem) -> Unit,
    onSearchInstead: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val found = result?.results?.mapNotNull { it.product } ?: emptyList()
            when {
                searching || result == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Looking up the barcode…") }
                found.isEmpty() -> {
                    Text("No store knows barcode ${result.gtin}", style = MaterialTheme.typography.titleMedium)
                    Text("You can still search the product by name.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onSearchInstead) { Text("Search products") }
                }
                else -> {
                    val primary = found.first()
                    Text("Scanned", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (product in found) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StoreBadge(product.store, stores)
                            Text(product.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                    nl.baskt.ui.common.ProductRow(primary)
                    val inStock = stockEntryFor(primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Button(onClick = { onAddToList(primary) }, modifier = Modifier.weight(1f)) { Text("Add to list") }
                        if (inStock != null) {
                            androidx.compose.material3.OutlinedButton(onClick = { onRemoveFromStock(inStock) }, modifier = Modifier.weight(1f)) { Text("Remove from stock") }
                        } else {
                            androidx.compose.material3.FilledTonalButton(onClick = { onAddToStock(primary) }, modifier = Modifier.weight(1f)) { Text("Add to stock") }
                        }
                    }
                    if (inStock != null) Text("Already in your stock since you added “${inStock.text}”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Adding to the list pins ${storeName(primary.store, stores)} to this product and matches the other stores.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
