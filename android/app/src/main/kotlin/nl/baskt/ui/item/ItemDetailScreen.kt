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
    val item = items.firstOrNull { it.id == itemId }

    // TextFieldValue so the cursor can be placed at the end when editing starts.
    var titleDraft by remember(item?.text) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(item?.text ?: "")) }
    var editingTitle by remember { mutableStateOf(false) }
    var titleHadFocus by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    fun commitTitle() {
        val current = item ?: return
        editingTitle = false
        val value = titleDraft.text.trim()
        if (value.isNotEmpty() && value != current.text) viewModel.rename(current, value) else titleDraft = androidx.compose.ui.text.input.TextFieldValue(current.text)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    if (editingTitle) {
                        // Inline rename: the title turns into a text field, saved on Done or when focus leaves.
                        LaunchedEffect(Unit) { titleHadFocus = false; focusRequester.requestFocus() }
                        androidx.compose.foundation.text.BasicTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { commitTitle() }),
                            // Only commit on a real blur: the field reports "not focused" once before it receives focus.
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { state ->
                                if (state.isFocused) titleHadFocus = true else if (titleHadFocus && editingTitle) commitTitle()
                            },
                        )
                    } else {
                        Text(
                            item?.text ?: "Item",
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = item != null) {
                                val text = item?.text ?: ""
                                titleDraft = androidx.compose.ui.text.input.TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
                                editingTitle = true
                            },
                        )
                    }
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
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        onReject = { viewModel.reject(item, store.code) },
                        onSearch = { query -> viewModel.searchMore(item, store.code, query) },
                        onFeedback = { productId, up -> viewModel.feedback(item, store.code, productId, up) },
                        loadHistory = { productId -> viewModel.priceHistory(productId) },
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
    onSearch: (String) -> Unit,
    onFeedback: (String, Boolean) -> Unit,
    loadHistory: suspend (String) -> List<nl.baskt.data.PricePoint> = { emptyList() },
) {
    var showOptions by remember(match?.status, match?.updatedAt) { mutableStateOf(match?.status == "PENDING") }
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember(match?.status) { mutableStateOf(match?.status == "EXHAUSTED") }

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
                if (match?.status == "CHOSEN" && !showOptions) TextButton(onClick = { showOptions = true }) { Text("Change") }
            }
            if (match == null) return@Column

            val chosen = match.chosen
            if (match.status == "CHOSEN" && chosen != null) {
                ProductRow(chosen) {
                    IconButton(onClick = { onFeedback(chosen.id, false) }) { Icon(Icons.Default.ThumbDown, contentDescription = "Not this one", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                PriceSparkline(chosen.id, loadHistory)
                if (match.reason != null && match.chosenBy != "USER") Text(match.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (showOptions || match.status == "PENDING" || match.status == "NONE") {
                if (match.status == "NONE" && !showOptions) {
                    TextButton(onClick = { showOptions = true }) { Text("Pick a product instead") }
                } else {
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
                            Text(match.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (options.isNotEmpty()) {
                            FilledTonalButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("None of these fit") }
                        }
                        if (match.status != "NONE") {
                            OutlinedButton(onClick = { onChoose(null) }, modifier = Modifier.weight(1f)) { Text("Skip at ${storeName(store.code, stores)}") }
                        }
                    }
                    TextButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Search ${storeName(store.code, stores)} myself")
                    }
                }
            }

            if (match.status == "EXHAUSTED") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    // Asks the server for synonyms / broader terms and searches the store again.
                    FilledTonalButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Search alternatives")
                    }
                    OutlinedButton(onClick = { showSearch = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Search myself")
                    }
                }
            }
            if (showSearch) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search term in Dutch, e.g. halfvolle melk") },
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = onChoose, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("This") }
                }
                Row {
                    IconButton(onClick = { onFeedback(true) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ThumbUp, contentDescription = "Good match", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { onFeedback(false) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ThumbDown, contentDescription = "Bad match", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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


@Suppress("unused")
private fun StoreMatch.priceLabel(quantity: Int) = effective?.let { (it.priceCents * quantity).euros() } ?: "—"
