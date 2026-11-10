package nl.baskt.ui.stock

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.KebabDining
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.StockItem
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.DropdownPicker
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.SwitchRow
import nl.baskt.ui.common.counted
import java.text.Normalizer

/** The pantry groups, in the order the page shows them; keys match the server's stock categories. */
private data class StockGroup(val key: String, val label: String, val icon: ImageVector)

private val GROUPS = listOf(
    StockGroup("produce", "Vegetables & fruit", Icons.Default.Eco),
    StockGroup("dairy", "Dairy & eggs", Icons.Default.Egg),
    StockGroup("meat", "Meat, fish & vegetarian", Icons.Default.KebabDining),
    StockGroup("bakery", "Bread & bakery", Icons.Default.BakeryDining),
    StockGroup("breakfast", "Breakfast & spreads", Icons.Default.FreeBreakfast),
    StockGroup("pantry", "Pasta, rice & tins", Icons.Default.RiceBowl),
    StockGroup("spices", "Herbs, spices & oils", Icons.Default.Grass),
    StockGroup("snacks", "Snacks & sweets", Icons.Default.Cookie),
    StockGroup("drinks", "Drinks", Icons.Default.LocalDrink),
    StockGroup("frozen", "Frozen", Icons.Default.AcUnit),
    StockGroup("meals", "Ready meals", Icons.Default.LunchDining),
    StockGroup("household", "Household & care", Icons.Default.CleaningServices),
    StockGroup("other", "Other", Icons.Default.Category),
)

private fun groupOf(item: StockItem) = GROUPS.firstOrNull { it.key == item.category } ?: GROUPS.last()

/** A stock entry that is only a scanned code: no store knew the product. */
private fun StockItem.isBareCode() = text.all { it.isDigit() } && text.length >= 8
private fun StockItem.title() = if (isBareCode()) "Scanned item" else text
private fun StockItem.subtitle() = if (isBareCode()) text else quantityText

/** Lowercase without accents, so "tun" finds "atún" and "creme" finds "crème". */
private fun fold(text: String) =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Every word of the query has to appear in the name, the size, or the search words, so "tuna" finds atún. */
private fun StockItem.matches(words: List<String>): Boolean {
    if (words.isEmpty()) return true
    val haystack = fold(listOfNotNull(text, quantityText, searchTerms).joinToString(" "))
    return words.all { it in haystack }
}

/** What you already have at home, as a pantry of tiles grouped by aisle. Recipe folders skip these ingredients. */
@Composable
fun StockScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val stock by viewModel.basket.stock.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshStock() }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val words = fold(query).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val matching = stock.filter { it.matches(words) }
    val low = matching.filter { it.runningLow }
    val byGroup = matching.groupBy { groupOf(it).key }
    val present = GROUPS.filter { byGroup[it.key]?.isNotEmpty() == true }
    // A category the search has emptied no longer filters anything, so the grid is never blank because of it.
    val activeFilter = filter?.takeIf { key -> present.any { it.key == key } }
    val shown = if (activeFilter == null) present else present.filter { it.key == activeFilter }
    /** Adds what was typed (several when separated by commas) and says so. */
    fun addTyped(text: String) {
        val names = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        names.forEach { viewModel.addStock(it) }
        if (names.isNotEmpty()) viewModel.notify(if (names.size == 1) "Added “${names[0]}” to stock" else "Added ${counted(names.size, "item")} to stock")
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Stock") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Add to stock") })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (stock.isEmpty()) {
                EmptyState("Nothing in stock yet", "Recipes skip what you already have at home", icon = Icons.Default.Kitchen, modifier = Modifier.fillMaxSize())
                return@Column
            }
            SearchField(value = query, onValueChange = { query = it }, placeholder = "Search your stock", onSearch = {}, modifier = Modifier.padding(horizontal = Spacing.Screen, vertical = 8.dp))
            // Category chips: jump straight to one aisle.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.Screen), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = activeFilter == null, onClick = { filter = null }, label = { Text("All · ${matching.size}") })
                for (group in present) FilterChip(
                    selected = activeFilter == group.key,
                    onClick = { filter = if (activeFilter == group.key) null else group.key },
                    label = { Text("${group.label} · ${byGroup[group.key]?.size ?: 0}") },
                    leadingIcon = { Icon(group.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (matching.isEmpty()) {
                val typed = query.trim()
                EmptyState("Nothing called “$typed”", icon = Icons.Default.Search, modifier = Modifier.fillMaxSize()) {
                    FilledTonalButton(onClick = { addTyped(typed); query = "" }) { Text("Add “$typed” to stock", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                return@Column
            }
            // A "Running low" section that appears after a refresh lands above the anchored first header: show it.
            val grid = rememberLazyGridState()
            LaunchedEffect(low.isNotEmpty()) { if (low.isNotEmpty() && grid.firstVisibleItemIndex <= 1) grid.scrollToItem(0) }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(start = Spacing.Screen, end = Spacing.Screen, top = 8.dp, bottom = Spacing.FabClearance),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ListGap),
                verticalArrangement = Arrangement.spacedBy(Spacing.ListGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Without a category chosen the running-low things lead the page, and are then left out of their aisle below.
                val lowOnTop = low.isNotEmpty() && activeFilter == null
                if (lowOnTop) {
                    item("low-header", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Running low · ${low.size}", Modifier.padding(top = 8.dp)) }
                    items(low, key = { "low-${it.id}" }) { item -> StockTile(item, onOpen = { openId = item.id }) }
                }
                for (group in shown) {
                    val entries = byGroup[group.key].orEmpty().filter { !lowOnTop || !it.runningLow }.sortedBy { it.title().lowercase() }
                    if (entries.isEmpty()) continue
                    item("header-${group.key}", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("${group.label} · ${entries.size}", Modifier.padding(top = 8.dp)) }
                    items(entries, key = { it.id }) { item -> StockTile(item, onOpen = { openId = item.id }) }
                }
            }
        }
        if (adding) AddStockSheet(onDismiss = { adding = false }) { addTyped(it) }
        // Follow the live entry so a move or a running-low flip shows up in the open sheet.
        stock.firstOrNull { it.id == openId }?.let { live ->
            StockItemSheet(
                item = live,
                onDismiss = { openId = null },
                onRunningLow = { low ->
                    viewModel.setRunningLow(live, low)
                    viewModel.notify(if (low) "“${live.title()}” is running low and in your basket" else "“${live.title()}” is no longer running low")
                },
                onAddToBasket = { viewModel.add(live.text, 1); viewModel.notify("Added “${live.title()}” to your basket"); openId = null },
                onMove = { group -> viewModel.moveStock(live, group.key); viewModel.notify("Moved “${live.title()}” to ${group.label}") },
                onRemove = {
                    viewModel.removeStock(live)
                    viewModel.notifyUndo("Removed “${live.title()}” from stock") { viewModel.restoreStock(live) }
                    openId = null
                },
            )
        }
    }
}

/** One thing at home: its picture (or the shared placeholder), name and size. The whole tile is the button. */
@Composable
private fun StockTile(item: StockItem, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = if (item.runningLow) colors.tertiaryContainer else colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                ItemThumb(item.imageUrl, contentDescription = null, size = 88, modifier = Modifier.fillMaxSize())
                if (item.runningLow) Surface(
                    shape = MaterialTheme.shapes.small,
                    color = colors.tertiary,
                    contentColor = colors.onTertiary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) { Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = "Running low", modifier = Modifier.padding(2.dp).size(16.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                item.title(),
                style = MaterialTheme.typography.labelLarge,
                color = if (item.runningLow) colors.onTertiaryContainer else colors.onSurface,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle() ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.runningLow) colors.onTertiaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One stock entry: add it to the basket, mark it as running low (which also puts it in the basket and keeps it
 * here until it is bought again), move it to another category, or remove it (with Undo).
 */
@Composable
private fun StockItemSheet(
    item: StockItem,
    onDismiss: () -> Unit,
    onRunningLow: (Boolean) -> Unit,
    onAddToBasket: () -> Unit,
    onMove: (StockGroup) -> Unit,
    onRemove: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBody(
            title = item.title(),
            actions = {
                TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove from stock") }
                Button(onClick = onAddToBasket) { Text("Add to basket") }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ItemThumb(item.imageUrl, contentDescription = null, size = 88)
                item.subtitle()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
            }
            SwitchRow("Running low", "Puts it in your basket until you buy it again", checked = item.runningLow, onChange = onRunningLow)
            DropdownPicker("Category", GROUPS, groupOf(item), optionLabel = { it.label }, onPick = { if (it.key != groupOf(item).key) onMove(it) })
        }
    }
}

/** Quick entry: one thing, or several separated by commas or new lines. */
@Composable
private fun AddStockSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() { onAdd(text); text = "" }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBody(
            title = "Add to stock",
            modifier = Modifier.imePadding(),
            actions = {
                TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Add and keep open") }
                Button(onClick = { submit(); onDismiss() }, enabled = text.isNotBlank()) { Text("Add") }
            },
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("salt, olive oil, rice") },
                supportingText = { Text("Separate several with commas") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { submit(); onDismiss() } }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}
