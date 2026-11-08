package nl.baskt.ui.stock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.StockItem
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.IconLabel

/** The pantry groups, in the order the page shows them; keys match the server's stock categories. */
private data class StockGroup(val key: String, val label: String, val icon: ImageVector)

private val GROUPS = listOf(
    StockGroup("produce", "Vegetables & fruit", Icons.Default.Eco),
    StockGroup("dairy", "Dairy & eggs", Icons.Default.Egg),
    StockGroup("meat", "Meat, fish & veggie", Icons.Default.KebabDining),
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

/** A stock line that is only a scanned code: no store knew the product. */
private fun StockItem.isBareCode() = text.all { it.isDigit() } && text.length >= 8
private fun StockItem.title() = if (isBareCode()) "Scanned item" else text
private fun StockItem.subtitle() = if (isBareCode()) text else quantityText

/** What you already have at home, as a pantry of tiles grouped by aisle. Recipe folders skip these ingredients. */
@Composable
fun StockScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val stock by viewModel.basket.stock.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshStock() }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<StockItem?>(null) }

    val matching = stock.filter { query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) || it.quantityText?.contains(query.trim(), ignoreCase = true) == true }
    val low = matching.filter { it.runningLow }
    val byGroup = matching.groupBy { groupOf(it).key }
    val present = GROUPS.filter { byGroup[it.key]?.isNotEmpty() == true }
    val shown = if (filter == null) present else present.filter { it.key == filter }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Stock") },
                subtitle = { if (stock.isNotEmpty()) Text(if (stock.size == 1) "1 thing at home" else "${stock.size} things at home") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (stock.isNotEmpty()) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = if (searching) "Close search" else "Search stock")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Add") })
        },
    ) { padding ->
        if (stock.isEmpty()) {
            EmptyState(
                "Nothing in stock yet",
                "Add staples you always have (salt, pepper, oil, rice). Recipe folders will not ask you to buy them again, and things you tick off in the store land here by themselves.",
                icon = Icons.Default.Kitchen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { FilledTonalButton(onClick = { adding = true }) { IconLabel(Icons.Default.Add, "Add staples") } }
            if (adding) AddStockSheet(onDismiss = { adding = false }) { viewModel.addStock(it) }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search your stock") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                )
            }
            // Category chips: jump straight to one aisle.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                for (group in present) FilterChip(
                    selected = filter == group.key,
                    onClick = { filter = if (filter == group.key) null else group.key },
                    label = { Text("${group.label} · ${byGroup[group.key]?.size ?: 0}") },
                    leadingIcon = { Icon(group.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (matching.isEmpty()) {
                EmptyState("Nothing called “${query.trim()}”", "Check the spelling, or add it to your stock.", icon = Icons.Default.Search)
                return@Column
            }
            // A "Running low" section that appears after a refresh lands above the anchored first header: show it.
            val grid = rememberLazyGridState()
            LaunchedEffect(low.isNotEmpty()) { if (low.isNotEmpty() && grid.firstVisibleItemIndex <= 1) grid.scrollToItem(0) }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (low.isNotEmpty() && filter == null) {
                    item("low-header", span = { GridItemSpan(maxLineSpan) }) {
                        GroupHeader("Running low", Icons.Default.TrendingDown, low.size, color = MaterialTheme.colorScheme.tertiary)
                    }
                    items(low, key = { "low-${it.id}" }) { item -> StockTile(item, onOpen = { open = item }) }
                }
                for (group in shown) {
                    val entries = byGroup[group.key].orEmpty().sortedBy { it.title().lowercase() }
                    item("header-${group.key}", span = { GridItemSpan(maxLineSpan) }) { GroupHeader(group.label, group.icon, entries.size) }
                    items(entries, key = { it.id }) { item -> StockTile(item, onOpen = { open = item }) }
                }
            }
        }
        if (adding) AddStockSheet(onDismiss = { adding = false }) { viewModel.addStock(it) }
        open?.let { current ->
            // Follow the live entry so a move or a running-low flip shows up in the open sheet.
            val live = stock.firstOrNull { it.id == current.id } ?: current
            StockItemSheet(
                item = live,
                onDismiss = { open = null },
                onRunningLow = { viewModel.setRunningLow(live, it) },
                onAddToBasket = { viewModel.add(live.text, 1); open = null },
                onMove = { viewModel.moveStock(live, it) },
                onRemove = { viewModel.removeStock(live); open = null },
            )
        }
    }
}

@Composable
private fun GroupHeader(label: String, icon: ImageVector, count: Int, color: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.titleSmallEmphasized, color = color, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StockTile(item: StockItem, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (item.runningLow) colors.tertiaryContainer else colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onOpen),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                ProductPicture(item, Modifier.fillMaxSize())
                if (item.runningLow) Surface(
                    shape = MaterialTheme.shapes.small,
                    color = colors.tertiary,
                    contentColor = colors.onTertiary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) { Icon(Icons.Default.TrendingDown, contentDescription = "Running low", modifier = Modifier.padding(2.dp).size(16.dp)) }
            }
            Spacer(Modifier.height(6.dp))
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

/** Product photos are cut out on white; entries without one get the aisle's icon on a quiet tile. */
@Composable
private fun ProductPicture(item: StockItem, modifier: Modifier) {
    if (item.imageUrl != null) {
        Box(modifier.background(Color.White, MaterialTheme.shapes.medium).padding(8.dp)) {
            AsyncImage(model = item.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
            Icon(groupOf(item).icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun StockItemSheet(
    item: StockItem,
    onDismiss: () -> Unit,
    onRunningLow: (Boolean) -> Unit,
    onAddToBasket: () -> Unit,
    onMove: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var moving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val current = groupOf(item)
    if (removing) ConfirmDialog(
        title = "Remove “${item.text}”?",
        body = "It leaves your stock, so recipes and running low stop counting it.",
        confirmLabel = "Remove",
        onDismiss = { removing = false },
        onConfirm = onRemove,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProductPicture(item, Modifier.size(88.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title(), style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    item.subtitle()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Icon(current.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(current.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (item.runningLow) {
                Text("Running low: it is on the basket and stays here until you buy it again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                OutlinedButton(onClick = { onRunningLow(false) }, modifier = Modifier.fillMaxWidth()) { Text("I still have enough") }
            } else {
                FilledTonalButton(onClick = { onRunningLow(true) }, modifier = Modifier.fillMaxWidth()) { IconLabel(Icons.Default.TrendingDown, "Running low · add to basket") }
                OutlinedButton(onClick = onAddToBasket, modifier = Modifier.fillMaxWidth()) { IconLabel(Icons.Default.AddShoppingCart, "Add to basket") }
            }
            TextButton(onClick = { moving = !moving }) { IconLabel(Icons.Default.Category, if (moving) "Keep it in ${current.label}" else "Move to another category") }
            if (moving) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (group in GROUPS) FilterChip(
                        selected = group.key == current.key,
                        onClick = { onMove(group.key); moving = false },
                        label = { Text(group.label) },
                        leadingIcon = { Icon(group.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            TextButton(onClick = { removing = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                IconLabel(Icons.Default.Delete, "Remove from stock")
            }
        }
    }
}

/** Quick entry: one thing, or several separated by commas or new lines. */
@Composable
private fun AddStockSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach(onAdd)
        text = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add to stock", style = MaterialTheme.typography.titleLarge)
            Text("Separate several with commas. Each one is sorted into its category by itself.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("salt, olive oil, rice…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(); onDismiss() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Add another") }
                androidx.compose.material3.Button(onClick = { submit(); onDismiss() }, enabled = text.isNotBlank()) { Text("Add") }
            }
        }
    }
}
