package nl.baskt.ui.basket

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Stepper
import nl.baskt.ui.common.TransferMenuItems
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.rememberTransferPicker

/**
 * A folder (e.g. a recipe) showing its items; each behaves like a normal basket item. The folder itself is
 * renamed, moved, copied and deleted from the menu in the top bar; its items can be reordered by their
 * handles and selected (menu entry or long press) to skip, move, copy or delete several at once.
 */
@Composable
fun GroupScreen(viewModel: AppViewModel, groupId: String, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val recipe by viewModel.recipeSuggestion.collectAsState()
    val baskets by viewModel.basket.baskets.collectAsState()
    val currentBasketId by viewModel.basket.currentBasketId.collectAsState()
    val recipeDetail by viewModel.recipeDetail.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val group = items.firstOrNull { it.id == groupId }
    val children = items.filter { it.parentId == groupId }
    val enabledStores = stores.filter { it.enabled }
    val context = LocalContext.current

    // Open sheets and dialogs are plain flags, so they survive a rotation.
    var showRecipe by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val selectedItems = children.filter { it.id in selection }
    val selectionMode = selecting || selectedItems.isNotEmpty()
    fun endSelection() { selecting = false; viewModel.clearSelection() }
    // The selection is shared with the basket screen; what was ticked here must not follow the user back there.
    DisposableEffect(Unit) { onDispose { viewModel.clearSelection() } }
    androidx.activity.compose.BackHandler(enabled = selectionMode) { endSelection() }

    val transferFolder = rememberTransferPicker(baskets, currentBasketId) { basketId, copy -> group?.let { viewModel.transfer(it, basketId, copy); if (!copy) onBack() } }
    val transferSelected = rememberTransferPicker(baskets, currentBasketId) { basketId, copy -> selecting = false; viewModel.transferSelected(basketId, copy) }

    if (showRecipe && group != null) {
        nl.baskt.ui.recipes.RecipeSheet(title = group.text, detail = recipeDetail, onDismiss = { showRecipe = false; viewModel.closeRecipe() }) {}
    }
    if (renaming && group != null) {
        NameDialog(title = "Rename folder", initial = group.text, onDismiss = { renaming = false }) { viewModel.rename(group, it); renaming = false }
    }
    // Deleting cannot be undone (the picks made for the items are gone with them), so both deletes ask first.
    if (confirmDelete && group != null) ConfirmDialog(
        title = "Delete “${group.text}”?",
        body = if (children.isEmpty()) "This also deletes it for everyone who shares this basket." else "This also deletes its ${counted(children.size, "item")} for everyone who shares this basket.",
        confirmLabel = "Delete",
        onDismiss = { confirmDelete = false },
        onConfirm = { viewModel.delete(group); onBack() },
    )
    if (confirmDeleteSelected) ConfirmDialog(
        title = if (selectedItems.size == 1) "Delete “${selectedItems.first().text}”?" else "Delete ${counted(selectedItems.size, "item")}?",
        body = deleteItemsBody(selectedItems.size),
        confirmLabel = "Delete",
        onDismiss = { confirmDeleteSelected = false },
        onConfirm = { selecting = false; viewModel.deleteSelected() },
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selectionMode) TopAppBar(
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
                                TransferMenuItems(transferSelected) { menu = false }
                                if (transferSelected.available) HorizontalDivider()
                                DeleteMenuItem("Delete…") { menu = false; confirmDeleteSelected = true }
                            }
                        }
                    }
                },
            ) else MediumFlexibleTopAppBar(
                title = { Text(group?.text ?: "Folder", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (group != null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }, onClick = { menu = false; renaming = true })
                                DropdownMenuItem(text = { Text("Select items") }, leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) }, enabled = children.isNotEmpty(), onClick = { menu = false; selecting = true })
                                TransferMenuItems(transferFolder) { menu = false }
                                // The recipe itself opens from the "Recipe & steps" button below; this is the page it came from.
                                group.recipe?.sourceUrl?.let { url ->
                                    DropdownMenuItem(text = { Text("Open recipe website") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) }, onClick = {
                                        menu = false
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }.onFailure { viewModel.notify("Couldn’t open the recipe website") }
                                    })
                                }
                                HorizontalDivider()
                                DeleteMenuItem("Delete folder…") { menu = false; confirmDelete = true }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (group != null && !selectionMode) AddIdeaBar(
                suggestions = suggestions,
                recipe = recipe,
                onTextChanged = { viewModel.onIdeaTextChanged(it) },
                onAdd = { text, quantity -> viewModel.clearSuggestions(); viewModel.addToGroup(groupId, text, quantity) },
                onAddGroup = { _, picked -> viewModel.clearSuggestions(); picked?.forEach { viewModel.addToGroup(groupId, it, 1) } },
                onAddMany = { picked -> viewModel.clearSuggestions(); picked.forEach { viewModel.addToGroup(groupId, it, 1) } },
                placeholder = "Add to this folder",
                allowFolders = false,
            )
        },
    ) { padding ->
        if (group == null) {
            EmptyState(
                "This folder no longer exists",
                "It was deleted or moved to another basket.",
                icon = Icons.Default.FolderOff,
                modifier = Modifier.fillMaxSize().padding(padding),
                action = { FilledTonalButton(onClick = onBack) { Text("Back to basket") } },
            )
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            val listState = rememberLazyListState()
            // The header card is the first list entry, so the rows start one position later.
            val reorder = rememberRowReorder(listState, indexOffset = 1) { viewModel.reorder(it) }
            val shown = reorder.order ?: children
            val canReorder = !selectionMode && children.size > 1
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = Spacing.Card), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
                item("header") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when {
                                group.isProcessing -> LoadingRow("Looking up the recipe…")
                                group.status == "ERROR" -> Text(group.error ?: "Couldn’t build this folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                else -> {
                                    val info = group.recipe
                                    Text(
                                        listOfNotNull(
                                            counted(children.size, "item"),
                                            info?.servings?.let { servings -> servings.trim().toIntOrNull()?.let { counted(it, "serving") } ?: "$servings servings" },
                                            info?.sourceUrl?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }?.let { "Recipe from $it" },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            val info = group.recipe
                            if (info != null && !group.isProcessing && group.status != "ERROR") {
                                FilledTonalButton(onClick = { showRecipe = true; viewModel.openGroupRecipe(group.id) }) {
                                    IconLabel(Icons.AutoMirrored.Filled.MenuBook, "Recipe & steps")
                                }
                                val current = (info.currentServings ?: info.baseServings)?.toInt()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Servings", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    Stepper(
                                        value = current?.toString() ?: "As written",
                                        onMinus = { viewModel.setGroupServings(group, (current ?: 2) - 1) },
                                        onPlus = { viewModel.setGroupServings(group, (current ?: 2) + 1) },
                                        noun = "servings",
                                        minusEnabled = (current ?: 2) > 1,
                                    )
                                }
                                if (info.baseServings != null && current != null && info.baseServings.toInt() != current) {
                                    Text("Scaled from ${counted(info.baseServings.toInt(), "serving")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val leftOut = group.recipe?.skipped ?: emptyList()
                            if (leftOut.isNotEmpty()) {
                                Text("In stock, so left out: ${leftOut.joinToString { it.text }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { viewModel.addSkipped(group) }, modifier = Modifier.align(Alignment.End)) { Text("Add anyway") }
                            }
                            val open = children.filter { !it.checked }
                            if (open.isNotEmpty()) FolderTotals(open, enabledStores)
                        }
                    }
                }
                if (children.isEmpty() && !group.isProcessing) item("empty") {
                    EmptyState("This folder is empty", "Add items below.", icon = Icons.Outlined.FolderOpen)
                }
                items(shown, key = { it.id }) { child ->
                    val handle: (@Composable () -> Unit)? = if (canReorder) ({ ReorderHandle(reorder, children, child.id) }) else null
                    Box(modifier = reorderRow(reorder, child.id)) {
                        BasketItemCard(
                            child,
                            enabledStores,
                            onClick = { if (selectionMode) viewModel.toggleSelected(child.id) else onOpenItem(child.id) },
                            onLongClick = { selecting = true; viewModel.toggleSelected(child.id) },
                            onToggle = { viewModel.toggleChecked(child) },
                            modifier = if (canReorder) Modifier.moveActions(reorder, children, child.id) else Modifier,
                            selected = if (selectionMode) child.id in selection else null,
                            trailing = handle,
                        )
                    }
                }
            }
        }
    }
}
