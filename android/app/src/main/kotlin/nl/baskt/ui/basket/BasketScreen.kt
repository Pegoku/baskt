@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package nl.baskt.ui.basket

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.RemoveShoppingCart
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.baskt.data.BasketItem
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.BasketSwitcherTitle
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.NavRow
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.TransferMenuItems
import nl.baskt.ui.common.buildShareText
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.rememberTransferPicker
import nl.baskt.ui.common.shareText

/** The one icon that means "skip" everywhere on this screen: the row toggle, the selection bar and the menu. */
internal val SkipIcon = Icons.Outlined.RemoveShoppingCart

@Composable
fun BasketScreen(viewModel: AppViewModel, onOpenItem: (String) -> Unit, onOpenGroup: (String) -> Unit, onPlan: () -> Unit, onSettings: () -> Unit, onStock: () -> Unit, onSearch: () -> Unit, onDeals: () -> Unit, onRecipes: () -> Unit, onShop: (String) -> Unit, onPurchases: () -> Unit, onScan: () -> Unit = {}, onChat: () -> Unit = {}, onTidy: () -> Unit = {}, onOrders: () -> Unit = {}) {
    // Items bought in the store live on the orders page now; the basket only shows what is still to decide on.
    val allItems by viewModel.basket.items.collectAsState()
    val items = allItems.filter { !it.isBought }
    val stores by viewModel.basket.stores.collectAsState()
    val baskets by viewModel.basket.baskets.collectAsState()
    val currentBasketId by viewModel.basket.currentBasketId.collectAsState()
    val loading by viewModel.basket.loading.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val enabledStores = stores.filter { it.enabled }

    val context = LocalContext.current
    val startDictation = nl.baskt.ui.common.rememberVoiceInput(viewModel, "What do you need?") { viewModel.interpret(it) }
    val dictateRequest by viewModel.dictateRequest.collectAsState()
    LaunchedEffect(dictateRequest) { if (dictateRequest) { viewModel.dictateRequest.value = false; startDictation() } }
    val selection by viewModel.selection.collectAsState()
    // Selection mode starts from "Select items" in the menu or from a long press, and lasts until it is closed
    // or an action was taken, even while nothing is ticked yet.
    var selecting by rememberSaveable { mutableStateOf(false) }
    val selectionMode = selecting || selection.isNotEmpty()
    fun endSelection() { selecting = false; viewModel.clearSelection() }
    // Open dialogs and sheets are kept by id or flag, so they survive a rotation.
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSkipped by rememberSaveable { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var choosingStore by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val basketName = baskets.firstOrNull { it.id == currentBasketId }?.name ?: "baskt"
    fun shareLink(whatsApp: Boolean) = scope.launch {
        val link = viewModel.shareLink() ?: return@launch
        shareText(context, "🧺 $basketName\nOpen the basket, check items off and add your own:\n$link", whatsApp)
    }
    fun sendBot() = scope.launch {
        viewModel.sendToWhatsApp().fold(
            { count -> viewModel.notify("Sent ${counted(count, "item")} to WhatsApp") },
            { error -> viewModel.notify("Couldn’t send to WhatsApp" + (error.message?.let { ": $it" } ?: ""), "Settings") { onSettings() } },
        )
    }
    val transfer = rememberTransferPicker(baskets, currentBasketId) { basketId, copy -> selecting = false; viewModel.transferSelected(basketId, copy) }

    items.firstOrNull { it.id == renamingId }?.let { target ->
        NameDialog(title = if (target.isGroup) "Rename folder" else "Rename item", initial = target.text, onDismiss = { renamingId = null }) { viewModel.rename(target, it); renamingId = null; endSelection() }
    }
    if (naming) {
        NameDialog(title = "New folder", initial = "", onDismiss = { naming = false }) { viewModel.groupSelected(it); naming = false; selecting = false }
    }
    // Deleting cannot be undone (the picks made for an item are gone with it), so it asks first.
    if (confirmDeleteSelected) {
        val chosen = items.filter { it.id in selection }
        ConfirmDialog(
            title = if (chosen.size == 1) "Delete “${chosen.first().text}”?" else "Delete ${counted(chosen.size, "item")}?",
            body = deleteItemsBody(chosen.size),
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteSelected = false },
        ) { selecting = false; viewModel.deleteSelected() }
    }
    if (confirmDeleteSkipped) {
        val count = items.count { it.isSkipped && !it.isGroup }
        ConfirmDialog(
            title = "Delete ${counted(count, "skipped item")}?",
            body = deleteItemsBody(count),
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteSkipped = false },
        ) { viewModel.clearChecked() }
    }
    val whatsapp by viewModel.whatsapp.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadWhatsApp() }
    if (sharing) ShareSheet(
        showBot = whatsapp?.enabled == true,
        onDismiss = { sharing = false },
        onLink = { shareLink(whatsApp = false) },
        onLinkWhatsApp = { shareLink(whatsApp = true) },
        onText = { shareText(context, buildShareText(baskets.firstOrNull { it.id == currentBasketId }, items, enabledStores), whatsApp = false) },
        onBot = { sendBot() },
    )
    if (choosingStore) ModalBottomSheet(onDismissRequest = { choosingStore = false }) {
        SheetBody(title = "Shop at", actions = { TextButton(onClick = { choosingStore = false }) { Text("Cancel") } }) {
            Column { for (store in enabledStores) NavRow(title = store.name, leading = { StoreLogo(store.code, stores) }) { choosingStore = false; onShop(store.code) } }
        }
    }
    val lastAdded by viewModel.lastAdded.collectAsState()
    LaunchedEffect(lastAdded?.id) {
        val added = lastAdded ?: return@LaunchedEffect
        viewModel.consumeLastAdded()
        if (added.inStock) viewModel.notify("“${added.text}” is in stock, so it’s skipped", "Unskip") { viewModel.skip(added, false) }
    }
    val proposal by viewModel.voiceProposal.collectAsState()
    val interpreting by viewModel.interpreting.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    if (proposal != null || interpreting) {
        VoiceConfirmSheet(proposal ?: emptyList(), interpreting, transcript, onDismiss = { viewModel.dismissProposal() }, onRetry = { viewModel.dismissProposal(); startDictation() }, onConfirm = { viewModel.confirmProposal(it) })
    }
    androidx.activity.compose.BackHandler(enabled = selectionMode) { endSelection() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (selectionMode) {
                val selectedItems = items.filter { it.id in selection }
                TopAppBar(
                    title = { Text(if (selectedItems.isEmpty()) "Select items" else "${selectedItems.size} selected") },
                    navigationIcon = { IconButton(onClick = { endSelection() }) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") } },
                    actions = {
                        if (selectedItems.isNotEmpty()) {
                            val skip = selectedItems.any { !it.checked }
                            Tooltip(if (skip) "Skip" else "Unskip") {
                                IconButton(onClick = { selecting = false; viewModel.checkSelected(skip) }) { Icon(SkipIcon, contentDescription = if (skip) "Skip" else "Unskip") }
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (selectedItems.size == 1) DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }, onClick = { menu = false; renamingId = selectedItems.first().id })
                                    if (selectedItems.any { !it.isGroup }) DropdownMenuItem(text = { Text("New folder…") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) }, onClick = { menu = false; naming = true })
                                    TransferMenuItems(transfer) { menu = false }
                                    HorizontalDivider()
                                    DeleteMenuItem("Delete…") { menu = false; confirmDeleteSelected = true }
                                }
                            }
                        }
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
                    // Always the same three icons in the same place; Deals is greyed out while there is nothing to find deals for.
                    val openItems = items.any { !it.checked && !it.isGroup }
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search products") }
                    IconButton(onClick = onRecipes) { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Recipes") }
                    IconButton(onClick = onDeals, enabled = openItems) { Icon(Icons.Default.LocalOffer, contentDescription = "Deals") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // Places to go, then things to do with this basket, then Settings.
                            DropdownMenuItem(text = { Text("Orders") }, leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }, onClick = { menu = false; onOrders() })
                            DropdownMenuItem(text = { Text("Tidy up") }, leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) }, enabled = openItems, onClick = { menu = false; viewModel.dismissTidy(); onTidy() })
                            DropdownMenuItem(text = { Text("Stock") }, leadingIcon = { Icon(Icons.Default.Kitchen, contentDescription = null) }, onClick = { menu = false; onStock() })
                            DropdownMenuItem(text = { Text("Receipts") }, leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null) }, onClick = { menu = false; onPurchases() })
                            // One store goes straight to it; several open a short list to pick from.
                            if (enabledStores.size == 1) {
                                val store = enabledStores.first()
                                DropdownMenuItem(text = { Text("Shop at ${store.name}") }, leadingIcon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) }, onClick = { menu = false; onShop(store.code) })
                            } else if (enabledStores.size > 1) {
                                DropdownMenuItem(text = { Text("Shop at…") }, leadingIcon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) }, onClick = { menu = false; choosingStore = true })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Share…") }, leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }, onClick = { menu = false; sharing = true })
                            DropdownMenuItem(text = { Text("Select items") }, leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) }, enabled = items.isNotEmpty(), onClick = { menu = false; selecting = true })
                            if (items.any { it.isSkipped }) {
                                DropdownMenuItem(text = { Text("Unskip all") }, leadingIcon = { Icon(SkipIcon, contentDescription = null) }, onClick = { menu = false; viewModel.unskipAll() })
                                DeleteMenuItem("Delete skipped items…") { menu = false; confirmDeleteSkipped = true }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }, onClick = { menu = false; onSettings() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selectionMode) ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                text = { Text("Scan") },
            )
        },
        bottomBar = {
            // Selecting is its own task: the price strip and the add bar step aside until it is done.
            if (!selectionMode) {
                val suggestions by viewModel.suggestions.collectAsState()
                val recipe by viewModel.recipeSuggestion.collectAsState()
                Column {
                    // While typing, the keyboard and the suggestions need the room more than the totals do.
                    if (!WindowInsets.isImeVisible) TripStrip(items, enabledStores, onPlan)
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
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            var refreshing by remember { mutableStateOf(false) }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; scope.launch { try { viewModel.reload().join() } finally { refreshing = false } } },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Assistant button: bottom-left, level with the Scan button. Both leave while items are being selected.
                if (!selectionMode) FloatingActionButton(
                    onClick = onChat,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).zIndex(1f),
                ) { Icon(Icons.Default.AutoAwesome, contentDescription = "Assistant") }
                if (items.isEmpty()) {
                    // A one-row list, so pulling down to refresh also works on an empty basket.
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                when {
                                    loading -> LoadingIndicator()
                                    settings?.configured == false -> EmptyState(
                                        title = "Not connected yet",
                                        body = "Add your baskt address in Settings to start.",
                                        icon = Icons.Outlined.ShoppingBasket,
                                        action = { FilledTonalButton(onClick = onSettings) { Text("Open Settings") } },
                                    )
                                    enabledStores.isEmpty() -> EmptyState(
                                        title = "No stores chosen",
                                        body = "Choose where you shop in Settings.",
                                        icon = Icons.Outlined.ShoppingBasket,
                                        action = { FilledTonalButton(onClick = onSettings) { Text("Open Settings") } },
                                    )
                                    else -> EmptyState(title = "Your basket is empty", body = "Add what you need below.", icon = Icons.Outlined.ShoppingBasket)
                                }
                            }
                        }
                    }
                } else {
                    val topLevel = items.filter { it.parentId == null }
                    val listState = rememberLazyListState()
                    val reorder = rememberRowReorder(listState) { viewModel.reorder(it) }
                    val shown = reorder.order ?: topLevel
                    val canReorder = !selectionMode && topLevel.size > 1
                    // The bottom padding keeps the last row and the skipped note clear of the two floating buttons.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Spacing.Screen, end = Spacing.Screen, top = Spacing.ListGap, bottom = Spacing.FabClearance),
                        verticalArrangement = Arrangement.spacedBy(Spacing.ListGap),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            val open: () -> Unit = { if (selectionMode) viewModel.toggleSelected(item.id) else if (item.isGroup) onOpenGroup(item.id) else onOpenItem(item.id) }
                            val longPress: () -> Unit = { selecting = true; viewModel.toggleSelected(item.id) }
                            val handle: (@Composable () -> Unit)? = if (canReorder) ({ ReorderHandle(reorder, topLevel, item.id) }) else null
                            val rowModifier = if (canReorder) Modifier.moveActions(reorder, topLevel, item.id) else Modifier
                            val selected = if (selectionMode) item.id in selection else null
                            Box(modifier = reorderRow(reorder, item.id)) {
                                if (item.isGroup) {
                                    GroupCard(item, items.filter { it.parentId == item.id }, enabledStores, onClick = open, onLongClick = longPress, onToggle = { viewModel.toggleChecked(item) }, modifier = rowModifier, selected = selected, trailing = handle)
                                } else {
                                    BasketItemCard(item, enabledStores, onClick = open, onLongClick = longPress, onToggle = { viewModel.skip(item, !item.checked) }, modifier = rowModifier, selected = selected, trailing = handle)
                                }
                            }
                            // No swipe-to-dismiss on rows: horizontal swipes belong to the pager (delete via selection).
                        }
                        // Skipped items stay in the basket until they are unskipped here (or one by one), so nothing is forgotten.
                        // The note lives at the foot of the list so the rows above never shift when something is skipped.
                        val skippedCount = items.count { it.isSkipped && !it.isGroup }
                        if (skippedCount > 0 && !selectionMode) item(key = "skipped-note") {
                            Row(modifier = Modifier.fillMaxWidth().animateItem().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${counted(skippedCount, "item")} skipped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.unskipAll() }) { Text("Unskip all") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One sentence for every "delete items" question on the basket and folder screens. */
internal fun deleteItemsBody(count: Int) = if (count == 1) "This also deletes it for everyone who shares this basket." else "This also deletes them for everyone who shares this basket."

/** A destructive menu entry: red label and bin, always the last one in its group. */
@Composable
internal fun DeleteMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = MaterialTheme.colorScheme.error) },
        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        onClick = onClick,
    )
}

/** Names an icon-only button on long press or hover. */
@Composable
internal fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(text) } }, state = rememberTooltipState()) { content() }
}

/**
 * Drag-to-reorder for a lazy column of basket rows: the handle drags a row along the finger and it swaps with a
 * neighbour as it passes that neighbour's midpoint. The order is kept here while dragging and handed to [onDrop]
 * on release. [indexOffset] is how many list entries (a header, say) come before the first row.
 */
internal class RowReorder(private val listState: LazyListState, private val scope: CoroutineScope, private val rowGap: Int, private val indexOffset: Int, private val onDrop: (List<String>) -> Unit) {
    var order by mutableStateOf<List<BasketItem>?>(null)
        private set
    var draggingId by mutableStateOf<String?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    val heights = mutableMapOf<String, Int>()

    fun start(rows: List<BasketItem>, id: String) { order = rows; draggingId = id; offset = 0f }

    fun finish() {
        val dropped = order
        draggingId = null
        offset = 0f
        if (dropped != null) onDrop(dropped.map { it.id })
        order = null
    }

    fun drag(id: String, amount: Float) {
        val rows = order ?: return
        val current = rows.indexOfFirst { it.id == draggingId }
        if (current < 0) return
        offset += amount
        val below = rows.getOrNull(current + 1)?.let { heights[it.id] }?.plus(rowGap)
        val above = rows.getOrNull(current - 1)?.let { heights[it.id] }?.plus(rowGap)
        fun swapWith(other: Int) {
            // The list anchors its scroll position to the first visible row's key; when a swap moves that row
            // the list would scroll along with it. Pin the current position while the swap is laid out.
            val first = listState.firstVisibleItemIndex
            if (current + indexOffset == first || other + indexOffset == first) listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
            order = rows.toMutableList().also { java.util.Collections.swap(it, current, other) }
        }
        if (below != null && offset > below / 2f) {
            swapWith(current + 1)
            offset -= below
        } else if (above != null && offset < -above / 2f) {
            swapWith(current - 1)
            offset += above
        }
        // Keep the list moving when the row is dragged past the visible edge.
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        val top = row.offset + offset
        val bottom = top + row.size
        val overshoot = when {
            top < info.viewportStartOffset -> top - info.viewportStartOffset
            bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
            else -> 0f
        }
        if (overshoot != 0f) scope.launch { offset += listState.scrollBy(overshoot * 0.3f) }
    }

    /** One step up or down without dragging: what the screen reader's "Move up" and "Move down" actions do. */
    fun move(rows: List<BasketItem>, id: String, delta: Int) {
        val from = rows.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in rows.indices) return
        onDrop(rows.map { it.id }.toMutableList().also { java.util.Collections.swap(it, from, to) })
    }
}

@Composable
internal fun rememberRowReorder(listState: LazyListState, indexOffset: Int = 0, onDrop: (List<String>) -> Unit): RowReorder {
    val scope = rememberCoroutineScope()
    val rowGap = with(LocalDensity.current) { Spacing.ListGap.roundToPx() }
    val drop by rememberUpdatedState(onDrop)
    return remember(listState, indexOffset, rowGap) { RowReorder(listState, scope, rowGap, indexOffset) { drop(it) } }
}

/** What a reorderable row needs on its outer box: its measured height, and the lift and offset while it is dragged. */
@Composable
internal fun LazyItemScope.reorderRow(reorder: RowReorder, id: String): Modifier {
    val dragged = reorder.draggingId == id
    val shape = MaterialTheme.shapes.medium
    val lift = with(LocalDensity.current) { 6.dp.toPx() }
    return (if (dragged) Modifier else Modifier.animateItem())
        .onSizeChanged { reorder.heights[id] = it.height }
        .graphicsLayer { translationY = if (dragged) reorder.offset else 0f; shadowElevation = if (dragged) lift else 0f; this.shape = shape; clip = false }
        .zIndex(if (dragged) 1f else 0f)
}

/** The grip at the end of a row. It is for the finger only; a screen reader gets "Move up" and "Move down" on the row instead. */
@Composable
internal fun ReorderHandle(reorder: RowReorder, rows: List<BasketItem>, id: String) {
    val current by rememberUpdatedState(rows)
    Icon(
        Icons.Default.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).pointerInput(id) {
            detectDragGestures(
                onDragStart = { reorder.start(current, id) },
                onDragEnd = { reorder.finish() },
                onDragCancel = { reorder.finish() },
            ) { change, amount ->
                change.consume()
                reorder.drag(id, amount.y)
            }
        }.minimumInteractiveComponentSize(),
    )
}

/** Reordering without the drag gesture: "Move up" and "Move down" as accessibility actions of the row. */
internal fun Modifier.moveActions(reorder: RowReorder, rows: List<BasketItem>, id: String): Modifier {
    val index = rows.indexOfFirst { it.id == id }
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction("Move up") { reorder.move(rows, id, -1); true })
        if (index in 0 until rows.lastIndex) add(CustomAccessibilityAction("Move down") { reorder.move(rows, id, 1); true })
    }
    return if (actions.isEmpty()) this else semantics { customActions = actions }
}

/**
 * Leaves an item out of this shopping trip without deleting it. One icon, a crossed-out cart, lit while the
 * item is skipped; the tooltip and the screen reader say "Skip" or "Unskip". (It is not a "done" tick:
 * items are checked off in shop mode.)
 */
@Composable
private fun SkipToggle(skipped: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    val label = if (skipped) "Unskip" else "Skip"
    Tooltip(label) {
        IconToggleButton(checked = skipped, onCheckedChange = { onToggle() }, enabled = enabled) { Icon(SkipIcon, contentDescription = label) }
    }
}

/** The start of a row: the skip toggle, or a tick box while items are being selected ([selected] is then not null). */
@Composable
private fun RowLeading(selected: Boolean?, skipped: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    if (selected == null) SkipToggle(skipped, onToggle, enabled)
    else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Checkbox(checked = selected, onCheckedChange = null) }
}

/** The modifier both row cards share: the ripple stays inside the card's shape, and a screen reader hears a button (and whether it is selected). */
@Composable
private fun Modifier.rowClick(selected: Boolean?, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    clip(CardDefaults.shape)
        .combinedClickable(role = Role.Button, onLongClickLabel = if (onLongClick != null) "Select" else null, onLongClick = onLongClick, onClick = onClick)
        .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)

@Composable
fun BasketItemCard(item: BasketItem, stores: List<StoreInfo>, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onToggle: () -> Unit, modifier: Modifier = Modifier, selected: Boolean? = null, trailing: (@Composable () -> Unit)? = null) {
    Card(
        modifier = modifier.rowClick(selected, onClick, onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected == true -> MaterialTheme.colorScheme.primaryContainer
                item.checked -> MaterialTheme.colorScheme.surfaceContainerLow
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp, end = Spacing.Card, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RowLeading(selected, skipped = item.checked, onToggle = onToggle)
            ItemThumb(item.anyProduct?.imageUrl, null, modifier = Modifier.padding(end = 10.dp).alpha(if (item.checked) 0.5f else 1f))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                        color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.quantity > 1) Text("×${item.quantity}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                // Every state of this line is one line of the same text style and at least as tall as the store
                // badges, so a row keeps its height when it is skipped or waits to sync and the rows below never shift.
                Box(modifier = Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.CenterStart) {
                    when {
                        item.inStock -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Kitchen, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            StatusText("In stock · skipped")
                        }
                        item.isQueued && item.barcode != null -> StatusText("Scanned offline · waiting to sync")
                        item.isQueued -> StatusText("Waiting to sync")
                        item.isProcessing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            StatusText("Searching…")
                        }
                        item.status == "ERROR" -> StatusText(item.error ?: "Couldn’t match this item", color = MaterialTheme.colorScheme.error)
                        else -> StorePrices(item, stores)
                    }
                }
                // Skipped rows say nothing more; a row that still needs a pick says so with a tag, not an instruction.
                if (item.needsChoice && !item.checked) Tag("Choose product", modifier = Modifier.padding(top = 4.dp))
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * What the item costs at each store, wrapping onto a second line when there are many stores. A price that is
 * only the first guess is marked "~" (read out as "estimated"), a store still being searched shows a small
 * spinner, "Not here" means this store was ruled out for the item and "No match" that nothing fitting was found.
 */
@Composable
private fun StorePrices(item: BasketItem, stores: List<StoreInfo>) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (store in stores) {
            val match = item.match(store.code)
            val product = match?.effective
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StoreBadge(store.code, stores)
                when {
                    match == null -> CircularProgressIndicator(Modifier.size(12.dp).semantics { contentDescription = "Searching" }, strokeWidth = 2.dp)
                    match.status == "NONE" -> Text("Not here", style = MaterialTheme.typography.bodySmall, color = quiet)
                    match.status == "EXHAUSTED" || product == null -> Text("No match", style = MaterialTheme.typography.bodySmall, color = quiet)
                    else -> {
                        val price = (product.priceCents * item.quantity).euros()
                        val estimated = match.status == "PENDING"
                        Text(
                            if (estimated) "~$price" else price,
                            style = if (match.status == "CHOSEN") MaterialTheme.typography.bodySmallEmphasized else MaterialTheme.typography.bodySmall,
                            color = if (estimated || item.isSkipped) quiet else MaterialTheme.colorScheme.onSurface,
                            modifier = if (estimated) Modifier.semantics { contentDescription = "estimated $price" } else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** A folder row: title, child count and per-store totals summed over the children. */
@Composable
fun GroupCard(group: BasketItem, children: List<BasketItem>, stores: List<StoreInfo>, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onToggle: () -> Unit, modifier: Modifier = Modifier, selected: Boolean? = null, trailing: (@Composable () -> Unit)? = null) {
    val allChecked = children.isNotEmpty() && children.all { it.checked }
    Card(
        modifier = modifier.rowClick(selected, onClick, onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected == true -> MaterialTheme.colorScheme.primaryContainer
                allChecked -> MaterialTheme.colorScheme.surfaceContainerLow
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp, end = Spacing.Card, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RowLeading(selected, skipped = allChecked, onToggle = onToggle, enabled = children.isNotEmpty())
            // A folder shows the dish it came from; plain folders keep the icon in the title alone.
            val cover = group.recipe?.imageUrl ?: children.firstNotNullOfOrNull { it.anyProduct?.imageUrl }
            if (cover != null) ItemThumb(cover, null, modifier = Modifier.padding(end = 10.dp).alpha(if (allChecked) 0.5f else 1f))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = "Folder", modifier = Modifier.size(18.dp))
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
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Looking up the recipe…", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    group.status == "ERROR" -> Text(group.error ?: "Couldn’t build this folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> {
                        val open = children.filter { !it.checked }
                        val busy = children.count { it.isProcessing }
                        Text(
                            listOfNotNull(counted(children.size, "item"), if (busy > 0) "$busy searching" else null, if (allChecked) "skipped" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (open.isNotEmpty()) FolderTotals(open, stores, modifier = Modifier.padding(top = 2.dp))
                        if (group.needsChoice && !allChecked) Tag("Choose products", modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            trailing?.invoke()
        }
    }
}

/** What the open items of a folder add up to per store, with "2 missing" where a store lacks some of them. */
@Composable
internal fun FolderTotals(open: List<BasketItem>, stores: List<StoreInfo>, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (store in stores) {
            val total = open.sumOf { child -> (child.match(store.code)?.effective?.priceCents ?: 0) * child.quantity }
            val missing = open.count { it.match(store.code)?.effective == null }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StoreBadge(store.code, stores)
                Text(
                    when {
                        missing == open.size -> "No match"
                        missing > 0 -> "${total.euros()} · $missing missing"
                        else -> total.euros()
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Asks for one name (an item, a folder). The field is focused at once, the keyboard's Done saves, and Save waits for a real change. */
@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val canSave = text.isNotBlank() && text.trim() != initial
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

