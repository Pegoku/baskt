package nl.baskt.ui.basket

import androidx.compose.foundation.clickable
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
import nl.baskt.ui.common.StoreBadge

@Composable
fun BasketScreen(viewModel: AppViewModel, onOpenItem: (String) -> Unit, onCompare: () -> Unit, onSettings: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val loading by viewModel.basket.loading.collectAsState()
    val error by viewModel.basket.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val enabledStores = stores.filter { it.enabled }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "Settings")
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onSettings()
        viewModel.basket.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("baskt", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    if (items.any { it.checked }) {
                        IconButton(onClick = { viewModel.clearChecked() }) { Icon(Icons.Default.Delete, contentDescription = "Clear checked") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
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
            AddIdeaBar(
                suggestions = suggestions,
                onTextChanged = { viewModel.onIdeaTextChanged(it) },
                onAdd = { text, quantity -> viewModel.clearSuggestions(); viewModel.add(text, quantity) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { item ->
                        DismissibleItem(item, onDelete = { viewModel.delete(item) }) {
                            BasketItemCard(item, enabledStores, onClick = { onOpenItem(item.id) }, onToggle = { viewModel.toggleChecked(item) })
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
fun BasketItemCard(item: BasketItem, stores: List<StoreInfo>, onClick: () -> Unit, onToggle: () -> Unit) {
    Card(
        onClick = onClick,
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

@Composable
private fun AddIdeaBar(suggestions: List<String>, onTextChanged: (String) -> Unit, onAdd: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var quantity by remember { mutableIntStateOf(1) }
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        onAdd(value, quantity)
        text = ""
        quantity = 1
    }
    // safeDrawing = system bars + keyboard, so the bar rises above the IME without double padding.
    Surface(tonalElevation = 3.dp, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
      Column {
        if (suggestions.isNotEmpty() && text.isNotBlank()) {
            // Autocomplete row: history matches and AI interpretations of what was typed.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (suggestion in suggestions) {
                    SuggestionChip(onClick = { text = suggestion; onTextChanged(suggestion) }, label = { Text(suggestion) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onTextChanged(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add an idea… e.g. halfvolle milk") },
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { quantity += 1 }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, contentDescription = "More") }
                Text("$quantity", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { if (quantity > 1) quantity -= 1 }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Remove, contentDescription = "Less") }
            }
            FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank()) { Icon(Icons.Default.Add, contentDescription = "Add") }
        }
      }
    }
}
