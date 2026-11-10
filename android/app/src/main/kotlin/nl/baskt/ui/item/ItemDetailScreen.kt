package nl.baskt.ui.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.PricePoint
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.StoreMatch
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.OriginalToggle
import nl.baskt.ui.common.PriceSparkline
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Stepper
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.TransferMenuItems
import nl.baskt.ui.common.rememberTransferPicker
import nl.baskt.ui.common.TranslatedText
import nl.baskt.ui.common.storeName

/**
 * One item: how many, what it is being searched as, and per store the product chosen for it or the options to
 * choose from. The one translation toggle in the top bar drives every translated text on the screen.
 */
@Composable
fun ItemDetailScreen(viewModel: AppViewModel, itemId: String, onBack: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val baskets by viewModel.basket.baskets.collectAsState()
    val currentBasketId by viewModel.basket.currentBasketId.collectAsState()
    val busyMatches by viewModel.busyMatches.collectAsState()
    val pendingRejections by viewModel.pendingRejections.collectAsState()
    val item = items.firstOrNull { it.id == itemId }

    var showOriginal by rememberSaveable(itemId) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable(itemId) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(itemId) { mutableStateOf(false) }

    if (renaming && item != null) RenameDialog(item.text, onDismiss = { renaming = false }) { name -> renaming = false; viewModel.rename(item, name) }
    // A deleted item cannot be put back (the server has no restore), so this one asks first instead of offering Undo.
    if (confirmDelete && item != null) ConfirmDialog(
        title = "Delete “${item.text}”?",
        body = "This can’t be undone.",
        confirmLabel = "Delete",
        onDismiss = { confirmDelete = false },
        onConfirm = { viewModel.delete(item); onBack() },
    )
    // Move and copy work as everywhere else: two overflow entries that open the shared list of baskets.
    val transfer = rememberTransferPicker(baskets, currentBasketId) { basketId, copy ->
        val current = item ?: return@rememberTransferPicker
        viewModel.transfer(current, basketId, copy)
        val target = baskets.firstOrNull { it.id == basketId }?.label ?: "the other basket"
        viewModel.notify(if (copy) "Copied “${current.text}” to $target" else "Moved “${current.text}” to $target")
        if (!copy) onBack()
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    // The name with a pencil after it: the pair is one button that opens the rename dialog.
                    if (item != null) Row(
                        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = "Rename") { renaming = true }.heightIn(min = 48.dp).padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (item != null) {
                        OriginalToggle(showOriginal) { showOriginal = !showOriginal }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }, onClick = { menuOpen = false; renaming = true })
                                DropdownMenuItem(text = { Text("Match again") }, leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) }, onClick = { menuOpen = false; viewModel.rematch(item) })
                                DropdownMenuItem(text = { Text("Add to stock") }, leadingIcon = { Icon(Icons.Default.Kitchen, contentDescription = null) }, onClick = {
                                    menuOpen = false
                                    viewModel.addToStockFromItem(item)
                                    viewModel.notify("Added “${item.text}” to stock")
                                })
                                TransferMenuItems(transfer) { menuOpen = false }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete…", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            EmptyState(
                "This item no longer exists",
                "It was deleted or moved to another basket.",
                icon = Icons.Default.SearchOff,
                modifier = Modifier.fillMaxSize().padding(padding),
                action = { FilledTonalButton(onClick = onBack) { Text("Back") } },
            )
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.ListGap),
            ) {
                item { Header(item, onQuantity = { viewModel.setQuantity(item, it) }) }
                if (item.isProcessing) item { LoadingRow("Finding products…", modifier = Modifier.padding(vertical = 8.dp)) }
                for (store in stores.filter { it.enabled }) {
                    val match = item.match(store.code)
                    item(key = store.code) {
                        val prefix = "${item.id}:${store.code}:"
                        StoreCard(
                            item = item,
                            store = store,
                            stores = stores,
                            match = match,
                            removing = pendingRejections.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet(),
                            onChoose = { productId -> viewModel.choose(item, store.code, productId) },
                            onReset = { viewModel.resetRejections(item, store.code) },
                            onReject = { viewModel.reject(item, store.code) },
                            onSearch = { query -> viewModel.searchMore(item, store.code, query) },
                            onThumbDown = { product -> viewModel.rejectWithUndo(item, store.code, product) },
                            onUnskip = { viewModel.unskip(item, store.code) },
                            loadHistory = { productId -> viewModel.priceHistory(productId) },
                            busy = "${item.id}:${store.code}" in busyMatches,
                            showOriginal = showOriginal,
                        )
                    }
                }
            }
        }
    }
}

/** The item's name in a dialog: the field is focused at once and Save only lights up for a real change. */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = draft.trim()
    val changed = name.isNotEmpty() && name != current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename item") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (changed) onSave(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = changed) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Header(item: BasketItem, onQuantity: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quantity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Stepper("${item.quantity}", onMinus = { onQuantity(item.quantity - 1) }, onPlus = { onQuantity(item.quantity + 1) }, minusEnabled = item.quantity > 1)
        }
        val parsed = item.parsed
        if (parsed != null) {
            Text("Searching for", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(parsed.canonicalName)
                for (attribute in parsed.attributes.take(3)) Tag(attribute, color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                parsed.sizeHint?.let { hint ->
                    val amount = hint.amount.toString().removeSuffix(".0")
                    val unit = if (hint.unit == "piece") (if (hint.amount == 1.0) "piece" else "pieces") else hint.unit
                    Tag("$amount $unit", color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * One store's side of the item. Chosen: the product, its price history and why it was picked. Otherwise the
 * options, each with a "Choose" button, and the ways out: none fit, search by hand, or not at this store.
 */
@Composable
private fun StoreCard(
    item: BasketItem,
    store: StoreInfo,
    stores: List<StoreInfo>,
    match: StoreMatch?,
    removing: Set<String>,
    onChoose: (String?) -> Unit,
    onReject: () -> Unit,
    onReset: () -> Unit,
    onSearch: (String) -> Unit,
    onThumbDown: (Product) -> Unit,
    onUnskip: () -> Unit,
    loadHistory: suspend (String) -> List<PricePoint>,
    busy: Boolean,
    showOriginal: Boolean,
) {
    var showOptions by rememberSaveable(match?.status, match?.updatedAt) { mutableStateOf(match?.status == "PENDING") }
    var searchText by rememberSaveable(store.code) { mutableStateOf("") }
    val name = storeName(store.code, stores)

    // The item is not bought at this store: say so, with the button that brings the store back.
    if (match?.status == "NONE") {
        Card {
            Row(modifier = Modifier.padding(start = Spacing.Card, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The name is in both texts already, so no badge here.
                Text("Not at $name", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (busy) BusyDot()
                TextButton(onClick = onUnskip, enabled = !busy) { Text("Use $name again", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        return
    }
    Card {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreBadge(store.code, stores)
                Text(
                    when {
                        busy -> "Searching…"
                        match == null -> if (item.isProcessing) "Searching…" else "No results yet"
                        match.status == "CHOSEN" -> when (match.chosenBy) {
                            "MEMORY" -> "Chosen from your earlier picks"
                            "USER" -> "Your choice"
                            else -> "Chosen"
                        }
                        match.status == "PENDING" -> "Choose a product"
                        match.status == "EXHAUSTED" -> "Nothing found yet"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                // The one busy signal of the card.
                if (busy) BusyDot()
                else if (match?.status == "CHOSEN" && !showOptions) TextButton(onClick = { showOptions = true }) { Text("Change") }
                if (match?.canResetSuggestions(showOptions) == true) TooltipIconButton(
                    text = "Reset suggestions",
                    icon = Icons.Default.RestartAlt,
                    enabled = !busy && !item.isProcessing,
                    onClick = onReset,
                )
            }
            if (match == null) return@Column

            val chosen = match.chosen
            if (match.status == "CHOSEN" && chosen != null) {
                val leaving = chosen.id in removing
                ProductRow(chosen, modifier = if (leaving) Modifier.alpha(0.5f) else Modifier, showOriginal = showOriginal, openable = !leaving) {
                    if (!leaving) TooltipIconButton(text = "Not a match", icon = Icons.Default.ThumbDown, onClick = { onThumbDown(chosen) })
                }
                PriceSparkline(chosen.id, loadHistory)
                if (match.reason != null && match.chosenBy != "USER") Reason(match.reason, showOriginal)
            }

            if (showOptions || match.status == "PENDING") {
                val options = match.options.filter { it.id != chosen?.id || match.status != "CHOSEN" }
                if (options.isNotEmpty()) {
                    if (match.page > 1) Text("Page ${match.page}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (product in options) {
                        OptionRow(
                            product,
                            match.equivalences[product.id],
                            selected = product.id == chosen?.id,
                            leaving = product.id in removing,
                            enabled = !busy,
                            showOriginal = showOriginal,
                            onChoose = { onChoose(product.id) },
                            onThumbDown = { onThumbDown(product) },
                        )
                    }
                    if (match.reason != null && match.status == "PENDING") Reason(match.reason, showOriginal)
                }
                // Short labels in a row that wraps, so a long store name never squeezes or clips a button.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onChoose(null) }, enabled = !busy) { Text("Not at $name") }
                    if (options.isNotEmpty()) FilledTonalButton(onClick = onReject, enabled = !busy) { Text("None fit") }
                }
            }

            if (match.status == "EXHAUSTED") {
                if (match.reason != null && !busy) Reason(match.reason, showOriginal)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onChoose(null) }, enabled = !busy) { Text("Not at $name") }
                    // Asks the server to understand the product and search for substitutes.
                    FilledTonalButton(onClick = onReject, enabled = !busy) { IconLabel(Icons.Default.Search, "Search alternatives") }
                }
            }
            if (match.status != "CHOSEN" || showOptions) {
                SearchField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = "Search $name",
                    onSearch = { if (searchText.isNotBlank()) { onSearch(searchText.trim()); searchText = "" } },
                    enabled = !busy,
                )
            }
        }
    }
}

/** Why this product was suggested or picked, in the app language unless the screen's toggle asks for the original. */
@Composable
private fun Reason(text: String, showOriginal: Boolean) {
    TranslatedText(text, style = MaterialTheme.typography.bodySmall, showOriginal = showOriginal, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Small progress mark for a card header. */
@Composable
private fun BusyDot() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)

/**
 * One option: the product, then on one line how close a match it is and its two actions. Choosing is the
 * labelled button; the thumb only says "not a match" (as in the similar products sheet) and can be undone.
 */
@Composable
private fun OptionRow(product: Product, equivalence: String?, selected: Boolean, leaving: Boolean, enabled: Boolean, showOriginal: Boolean, onChoose: () -> Unit, onThumbDown: () -> Unit) {
    Column(modifier = if (leaving) Modifier.alpha(0.5f) else Modifier) {
        ProductRow(product, showOriginal = showOriginal, openable = !leaving)
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val quality = when (equivalence) {
                null -> null
                "EXACT" -> "Same product"
                "EQUIVALENT" -> "Similar"
                else -> "Substitute"
            }
            if (quality != null) Tag(quality)
            Box(Modifier.weight(1f))
            when {
                leaving -> Text("Removed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                selected -> Tag("Chosen", color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                else -> {
                    TooltipIconButton(text = "Not a match", icon = Icons.Default.ThumbDown, enabled = enabled, onClick = onThumbDown)
                    FilledTonalButton(onClick = onChoose, enabled = enabled) { Text("Choose") }
                }
            }
        }
    }
}

@Composable
private fun TooltipIconButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = text) }
    }
}
