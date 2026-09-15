package nl.baskt.ui.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.StoreMatch
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.PriceSparkline
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.storeName

@Composable
fun ItemDetailScreen(viewModel: AppViewModel, itemId: String, onBack: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val baskets by viewModel.basket.baskets.collectAsState()
    val currentBasketId by viewModel.basket.currentBasketId.collectAsState()
    val busyMatches by viewModel.busyMatches.collectAsState()
    val item = items.firstOrNull { it.id == itemId }

    // TextFieldValue so the cursor can be placed at the end when editing starts.
    var titleDraft by remember(item?.text) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(item?.text ?: "")) }
    var editingTitle by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val titleInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    fun commitTitle() {
        val current = item ?: return
        if (!editingTitle) return
        editingTitle = false
        val value = titleDraft.text.trim()
        if (value.isNotEmpty() && value != current.text) viewModel.rename(current, value) else titleDraft = androidx.compose.ui.text.input.TextFieldValue(current.text)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    // One field that is read-only until tapped: avoids focus glitches from swapping Text and TextField.
                    androidx.compose.foundation.text.BasicTextField(
                        value = titleDraft,
                        onValueChange = { if (editingTitle) titleDraft = it },
                        readOnly = !editingTitle,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (editingTitle) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitTitle(); focusManager.clearFocus(force = true); keyboard?.hide() }),
                        interactionSource = titleInteraction,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { state ->
                            if (state.isFocused) {
                                if (!editingTitle && item != null) {
                                    val text = item.text
                                    titleDraft = androidx.compose.ui.text.input.TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
                                    editingTitle = true
                                }
                            } else if (editingTitle) commitTitle()
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (item != null) {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Match again") }, leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) }, onClick = { menuOpen = false; viewModel.rematch(item) })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Add to stock") }, leadingIcon = { Icon(Icons.Default.Kitchen, contentDescription = null) }, onClick = { menuOpen = false; viewModel.addToStockFromItem(item) })
                                for (basket in baskets.filter { it.id != currentBasketId }) {
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("Move to ${basket.label}") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) }, onClick = { menuOpen = false; viewModel.transfer(item, basket.id, copy = false); onBack() })
                                }
                                androidx.compose.material3.HorizontalDivider()
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; viewModel.delete(item); onBack() },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text("This item no longer exists.") }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).pointerInput(Unit) {
                // Tapping anywhere outside the title ends editing and hides the keyboard.
                detectTapGestures(onTap = { focusManager.clearFocus(force = true); keyboard?.hide() })
            },
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(item, onQuantity = { viewModel.setQuantity(item, it) }) }
            if (item.isProcessing) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                        LoadingIndicator()
                        Text("Searching the supermarkets…")
                    }
                }
            }
            for (store in stores.filter { it.enabled }) {
                val match = item.match(store.code)
                item(key = store.code) {
                    StoreCard(
                        item = item,
                        store = store,
                        stores = stores,
                        match = match,
                        onChoose = { productId -> viewModel.choose(item, store.code, productId) },
                        onReset = { viewModel.resetRejections(item, store.code) },
                        onReject = { viewModel.reject(item, store.code) },
                        onSearch = { query -> viewModel.searchMore(item, store.code, query) },
                        onFeedback = { productId, up -> viewModel.feedback(item, store.code, productId, up) },
                        onUnskip = { viewModel.unskip(item, store.code) },
                        loadHistory = { productId -> viewModel.priceHistory(productId) },
                        busy = "${item.id}:${store.code}" in busyMatches,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(item: BasketItem, onQuantity: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quantity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (item.quantity > 1) onQuantity(item.quantity - 1) }) { Icon(Icons.Default.Remove, contentDescription = "Less") }
            Text("${item.quantity}", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onQuantity(item.quantity + 1) }) { Icon(Icons.Default.Add, contentDescription = "More") }
        }
        val parsed = item.parsed
        if (parsed != null) {
            Text(
                "Understood as",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestionChip(onClick = {}, label = { Text(parsed.canonicalName) })
                for (attribute in parsed.attributes.take(3)) SuggestionChip(onClick = {}, label = { Text(attribute) })
                parsed.sizeHint?.let { SuggestionChip(onClick = {}, label = { Text("${it.amount.toString().removeSuffix(".0")} ${if (it.unit == "piece") "stuks" else it.unit}") }) }
            }
            Text(
                "The product name and the properties the AI read from your idea. It searches the stores with these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StoreCard(
    item: BasketItem,
    store: StoreInfo,
    stores: List<StoreInfo>,
    match: StoreMatch?,
    onChoose: (String?) -> Unit,
    onReject: () -> Unit,
    onReset: () -> Unit,
    onSearch: (String) -> Unit,
    onFeedback: (String, Boolean) -> Unit,
    onUnskip: () -> Unit = {},
    loadHistory: suspend (String) -> List<nl.baskt.data.PricePoint> = { emptyList() },
    busy: Boolean = false,
) {
    var showOptions by remember(match?.status, match?.updatedAt) { mutableStateOf(match?.status == "PENDING") }
    var searchText by remember { mutableStateOf("") }

    // A skipped store is shown dimmed and collapsed; tapping the card re-enables it.
    if (match?.status == "NONE") {
        Card(onClick = onUnskip, modifier = Modifier.alpha(0.55f)) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreBadge(store.code, stores)
                Text("Skipped at ${storeName(store.code, stores)} — tap to enable again", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (busy) LoadingIndicator(modifier = Modifier.size(22.dp))
            }
        }
        return
    }
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreBadge(store.code, stores)
                Text(
                    when (match?.status) {
                        null -> if (item.isProcessing) "Searching…" else "No results yet"
                        "CHOSEN" -> when (match.chosenBy) {
                            "MEMORY" -> "Chosen from your earlier picks"
                            "USER" -> "Your choice"
                            else -> "Chosen"
                        }
                        "PENDING" -> "Pick the product you mean"
                        "NONE" -> "Not buying this here"
                        "EXHAUSTED" -> "Nothing fitting found yet"
                        else -> match.status
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (busy) LoadingIndicator(modifier = Modifier.size(22.dp))
                else if (match?.status == "CHOSEN" && !showOptions) TextButton(onClick = { showOptions = true }) { Text("Change") }
                if (match?.canResetSuggestions(showOptions) == true) TooltipIconButton(
                    text = "Reset suggestions",
                    icon = Icons.Default.Autorenew,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = !busy && !item.isProcessing,
                    onClick = onReset,
                )
            }
            if (match == null) return@Column
            if (busy) Text("Looking for alternatives…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

            val chosen = match.chosen
            if (match.status == "CHOSEN" && chosen != null) {
                ProductRow(chosen) {
                    TooltipIconButton(text = "Not what I meant", icon = Icons.Default.ThumbDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onFeedback(chosen.id, false) })
                }
                PriceSparkline(chosen.id, loadHistory)
                if (match.reason != null && match.chosenBy != "USER") nl.baskt.ui.common.TranslatedText(match.reason, style = MaterialTheme.typography.bodySmall)
            }

            if (showOptions || match.status == "PENDING") {
                run {
                    val options = match.options.filter { it.id != chosen?.id || match.status != "CHOSEN" }
                    if (options.isNotEmpty()) {
                        Text("Option ${match.page} of suggestions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (product in options) {
                            OptionRow(
                                product,
                                match.equivalences[product.id],
                                selected = product.id == chosen?.id,
                                onChoose = { onChoose(product.id) },
                                onFeedback = { up -> onFeedback(product.id, up) },
                            )
                        }
                        if (match.reason != null && match.status == "PENDING") {
                            nl.baskt.ui.common.TranslatedText(match.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (options.isNotEmpty()) {
                            FilledTonalButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text("None of these fit") }
                        }
                        if (match.status != "NONE") {
                            OutlinedButton(onClick = { onChoose(null) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Skip at ${storeName(store.code, stores)}") }
                        }
                    }
                }
            }

            if (match.status == "EXHAUSTED") {
                if (match.reason != null && !busy) nl.baskt.ui.common.TranslatedText(match.reason, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    // Asks the server to understand the product and search for substitutes.
                    FilledTonalButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) {
                        if (busy) LoadingIndicator(modifier = Modifier.size(18.dp)) else Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (busy) "  Searching…" else "  Search alternatives")
                    }
                    OutlinedButton(onClick = { onChoose(null) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Skip at ${storeName(store.code, stores)}") }
                }
            }
            val showSearch = match.status != "CHOSEN" || showOptions
            if (showSearch) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search ${storeName(store.code, stores)} yourself, e.g. halfvolle melk") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (searchText.isNotBlank()) { onSearch(searchText.trim()); searchText = "" } }),
                    trailingIcon = {
                        IconButton(onClick = { if (searchText.isNotBlank()) { onSearch(searchText.trim()); searchText = "" } }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OptionRow(product: Product, equivalence: String?, selected: Boolean, onChoose: () -> Unit, onFeedback: (Boolean) -> Unit) {
    Column {
        ProductRow(product) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
            } else {
                Row {
                    // Thumbs up = "this is what I want" (chooses it); thumbs down = "not what I meant" (removes it, teaches memory).
                    TooltipIconButton(text = "This is what I want", icon = Icons.Default.ThumbUp, tint = MaterialTheme.colorScheme.primary, onClick = onChoose)
                    TooltipIconButton(text = "Not what I meant", icon = Icons.Default.ThumbDown, tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onFeedback(false) })
                }
            }
        }
        if (equivalence != null && equivalence != "EQUIVALENT") {
            Spacer(Modifier.height(2.dp))
            AssistChip(
                onClick = {},
                label = { Text(if (equivalence == "EXACT") "exact match" else "substitute", style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun TooltipIconButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = text, tint = tint) }
    }
}


@Suppress("unused")
private fun StoreMatch.priceLabel(quantity: Int) = effective?.let { (it.priceCents * quantity).euros() } ?: "—"
