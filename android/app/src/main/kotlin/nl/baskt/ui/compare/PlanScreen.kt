package nl.baskt.ui.compare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.Comparison
import nl.baskt.data.ComparisonItem
import nl.baskt.data.OrderStoreTotal
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.StoreSummary
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ChoiceGroup
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.storeName

/** One size for every "this one is selected" check mark on the page. */
private val CheckSize = 18.dp
/** The narrowest a store card or a store option may get before the row scrolls sideways instead of squeezing. */
private val MinStoreCard = 148.dp
private val SkipWidth = 72.dp

/**
 * The plan: one page that answers "where do I buy this?" Ranked store totals at the top; tapping one sends the
 * whole basket there (with Undo), and the Shop button under them starts shopping the plan that is chosen.
 * Below, every item with what it costs at each store (cheapest marked), so single items can go elsewhere or be skipped.
 */
@Composable
fun PlanScreen(viewModel: AppViewModel, active: Boolean = true, onBack: () -> Unit, onOpenItem: (String) -> Unit, onShop: (String) -> Unit) {
    val comparison by viewModel.comparison.collectAsState()
    val comparing by viewModel.comparing.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val items by viewModel.basket.items.collectAsState()
    val enabled = stores.filter { it.enabled }
    val listed = items.filter { !it.isGroup && !it.isBought }
    val open = listed.filter { it.isOpen }

    // Composed next to the basket ahead of a swipe; only compare once it is actually being looked at.
    LaunchedEffect(items, active) { if (active) viewModel.compare() }

    val data = comparison

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Plan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { viewModel.compare(refreshPrices = true) }, enabled = open.isNotEmpty()) { Icon(Icons.Default.Refresh, contentDescription = "Refresh prices") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            if (comparing && data != null) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen))
            when {
                listed.isEmpty() -> EmptyPlan("Nothing to plan yet", "Add items to your basket to compare the stores.")
                open.isEmpty() -> EmptyPlan("Everything is skipped", "Unskip an item to compare the stores.") {
                    FilledTonalButton(onClick = { viewModel.unskipAll() }) { Text("Unskip all") }
                }
                data == null && comparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                data == null -> EmptyPlan("Couldn’t compare the stores", null) {
                    FilledTonalButton(onClick = { viewModel.compare(force = true) }) { Text("Try again") }
                }
                else -> PlanList(data, listed, open, enabled, stores, viewModel, onOpenItem, onShop)
            }
        }
    }
}

@Composable
private fun EmptyPlan(title: String, body: String?, action: (@Composable () -> Unit)? = null) {
    EmptyState(title, body, icon = Icons.Default.Storefront, modifier = Modifier.fillMaxSize(), action = action)
}

@Composable
private fun PlanList(
    data: Comparison,
    listed: List<BasketItem>,
    open: List<BasketItem>,
    enabled: List<StoreInfo>,
    stores: List<StoreInfo>,
    viewModel: AppViewModel,
    onOpenItem: (String) -> Unit,
    onShop: (String) -> Unit,
) {
    val ranked = data.stores.sortedBy { it.rank }
    val rows = data.items.associateBy { it.itemId }
    val byUnit = data.rankBy == "unitPrice"
    // "Everything at X" is the current plan when every open item that X stocks is assigned there.
    fun allAt(store: String) = open.isNotEmpty() && open.all { it.assignedStore == store || it.match(store)?.effective == null }
    LazyColumn(contentPadding = PaddingValues(start = Spacing.Screen, end = Spacing.Screen, top = 8.dp, bottom = Spacing.Screen), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
        item("h-stores") { SectionHeader("Where to shop", modifier = Modifier.padding(vertical = 4.dp)) }
        val perStore = data.order.perStore
        val cheapestRow = data.items.associate { it.itemId to it.cheapestStore }
        // The mix is in use once every open item sits at its cheapest store (items found nowhere stay unassigned).
        val mixActive = ranked.size > 1 && open.isNotEmpty() && open.all { cheapestRow[it.id] == null || it.assignedStore == cheapestRow[it.id] } && open.any { it.assignedStore != null }
        // Only offered when splitting the basket actually saves something over the cheapest single store.
        val mixSaving = if (ranked.size > 1) ranked.first().fullTotalCents - data.mixAndMatchTotalCents else 0
        if (mixSaving > 0) item("mix") {
            MixCard(total = data.mixAndMatchTotalCents, saving = mixSaving, versus = storeName(ranked.first().store, stores), active = mixActive) {
                viewModel.assignWithUndo("mix", "Each item is now at its cheapest store")
            }
        }
        item("stores") {
            ScrollingRow(count = ranked.size, reserved = 0.dp, modifier = Modifier.height(IntrinsicSize.Max)) { cell ->
                for (summary in ranked) StoreCard(
                    summary = summary,
                    stores = stores,
                    itemCount = data.items.size,
                    best = summary.rank == 1 && ranked.size > 1,
                    complete = summary.missingItemIds.isEmpty(),
                    chosen = allAt(summary.store),
                    onChoose = { viewModel.assignWithUndo("store:${summary.store}", "Everything is now at ${storeName(summary.store, stores)}") },
                    modifier = cell.fillMaxHeight(),
                )
            }
        }
        // The one primary action of the page: shop the plan as it stands, one button per store that has items.
        val shopping = ranked.mapNotNull { summary -> (perStore[summary.store] ?: OrderStoreTotal()).takeIf { it.count > 0 }?.let { summary.store to it } }.sortedByDescending { it.second.count }
        if (shopping.isNotEmpty()) item("shop") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
                shopping.forEachIndexed { index, (store, total) ->
                    val label = "Shop at ${storeName(store, stores)} · ${counted(total.count, "item")} · ${total.totalCents.euros()}"
                    if (index == 0) Button(onClick = { onShop(store) }, modifier = Modifier.fillMaxWidth()) { IconLabel(Icons.Default.ShoppingCart, label) }
                    else FilledTonalButton(onClick = { onShop(store) }, modifier = Modifier.fillMaxWidth()) { IconLabel(Icons.Default.ShoppingCart, label) }
                }
            }
        }
        val unassigned = open.count { it.assignedStore == null }
        if (unassigned > 0 && open.any { it.assignedStore != null }) item("unassigned") {
            Text(
                "${counted(unassigned, "item")} ${if (unassigned == 1) "has" else "have"} no store yet",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        item("h-items") {
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader("Item by item")
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compare by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ChoiceGroup(listOf("Pack price", "Unit price"), selected = if (byUnit) 1 else 0, onSelect = { viewModel.setRankBy(if (it == 1) "unitPrice" else "price") }, fill = false)
                }
            }
        }
        items(listed, key = { it.id }) { item ->
            PlanItemCard(
                item = item,
                row = rows[item.id],
                byUnit = byUnit,
                enabled = enabled,
                stores = stores,
                onPick = { viewModel.buyAt(item, it) },
                onSkip = { viewModel.skip(item, !item.isSkipped) },
                onOpen = { onOpenItem(item.id) },
            )
        }
    }
}

/**
 * A row of [count] equal cells (plus [reserved] width for a fixed extra one). The cells share the width while
 * each keeps at least [MinStoreCard]; with more stores than fit, the row scrolls sideways instead of squeezing them.
 */
@Composable
private fun ScrollingRow(count: Int, reserved: Dp, modifier: Modifier = Modifier, content: @Composable RowScope.(Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gaps = Spacing.ListGap * ((count - 1).coerceAtLeast(0) + if (reserved > 0.dp) 1 else 0)
        val each = if (count > 0) (maxWidth - reserved - gaps) / count else maxWidth
        if (each >= MinStoreCard) Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.ListGap)) { content(Modifier.weight(1f)) }
        else Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.ListGap)) { content(Modifier.width(MinStoreCard)) }
    }
}

/** A card that is one choice out of several: tapping it selects it, and a screen reader hears it as such. */
@Composable
private fun ChoiceCard(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, nested: Boolean = false, accent: Boolean = false, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected && accent -> scheme.tertiaryContainer
                selected -> scheme.primaryContainer
                nested -> scheme.surface
                else -> scheme.surfaceContainer
            },
        ),
        // The border only marks the selected one; unselected cards have none.
        border = if (selected) BorderStroke(2.dp, if (accent) scheme.tertiary else scheme.primary) else null,
        modifier = modifier.semantics { this.selected = selected; role = Role.RadioButton },
    ) { content() }
}

/** One store's total for the whole basket, side by side with the others. Tapping it sends everything there. */
@Composable
private fun StoreCard(
    summary: StoreSummary,
    stores: List<StoreInfo>,
    itemCount: Int,
    best: Boolean,
    complete: Boolean,
    chosen: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceCard(selected = chosen, onClick = onChoose, modifier = modifier) {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreLogo(summary.store, stores, size = 28)
                Text(storeName(summary.store, stores), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (chosen) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(CheckSize), tint = MaterialTheme.colorScheme.primary)
            }
            Text(summary.fullTotalCents.euros(), style = MaterialTheme.typography.headlineSmallEmphasized, color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(
                when {
                    best && complete -> "Cheapest · all ${counted(itemCount, "item")}"
                    complete -> "All ${counted(itemCount, "item")}"
                    else -> "${summary.matchedCount} of ${counted(itemCount, "item")}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (best && complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.unconfirmedCount > 0) Text(
                "${counted(summary.unconfirmedCount, "match", "matches")} to confirm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Cheapest store for every item, above the stores: the plan that splits the basket. */
@Composable
private fun MixCard(total: Int, saving: Int, versus: String, active: Boolean, onChoose: () -> Unit) {
    ChoiceCard(selected = active, onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(Spacing.Card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mix & match", style = MaterialTheme.typography.titleSmall)
                    Text(total.euros(), style = MaterialTheme.typography.titleSmallEmphasized)
                }
                Text("Each item at its cheapest store · saves ${saving.euros()} vs all at $versus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(CheckSize), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One item: the product you would get at each store with its line price, the cheapest marked, and Skip. The title row opens the item to change its products. */
@Composable
private fun PlanItemCard(
    item: BasketItem,
    row: ComparisonItem?,
    byUnit: Boolean,
    enabled: List<StoreInfo>,
    stores: List<StoreInfo>,
    onPick: (String) -> Unit,
    onSkip: () -> Unit,
    onOpen: () -> Unit,
) {
    val best = row?.let { if (byUnit) it.cheapestByUnitPriceStore ?: it.cheapestStore else it.cheapestStore }
    Card(colors = CardDefaults.cardColors(containerColor = if (item.isSkipped) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(start = Spacing.Card, end = Spacing.Card, bottom = Spacing.Card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = "Change products", onClick = onOpen).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    (if (item.quantity > 1) "${item.quantity}× " else "") + item.text,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (item.isSkipped) TextDecoration.LineThrough else null,
                    color = if (item.isSkipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScrollingRow(count = enabled.size, reserved = SkipWidth, modifier = Modifier.height(IntrinsicSize.Max)) { cell ->
                for (store in enabled) {
                    val match = item.match(store.code)
                    val product = match?.effective
                    val line = row?.perStore?.get(store.code)
                    StoreOption(
                        store = store,
                        stores = stores,
                        product = product,
                        lineCents = line?.lineCents ?: product?.let { it.priceCents * item.quantity },
                        unitsToBuy = line?.unitsToBuy ?: item.quantity,
                        unconfirmed = match?.status == "PENDING",
                        cheapest = best == store.code && enabled.size > 1,
                        selected = item.isOpen && item.assignedStore == store.code,
                        onClick = { if (product != null) onPick(store.code) },
                        modifier = cell.fillMaxHeight(),
                    )
                }
                SkipOption(selected = item.isSkipped, onClick = onSkip, modifier = Modifier.width(SkipWidth).fillMaxHeight())
            }
            if (row != null && row.packSizeDiffers && row.cheapestByUnitPriceStore != null && row.cheapestByUnitPriceStore != row.cheapestStore) {
                Text(
                    "Different pack sizes: ${storeName(row.cheapestByUnitPriceStore, stores)} is cheaper per unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** What you would get at this store: picture, pack and price. A store with nothing for the item says so and cannot be picked. */
@Composable
private fun StoreOption(
    store: StoreInfo,
    stores: List<StoreInfo>,
    product: Product?,
    lineCents: Int?,
    unitsToBuy: Int,
    unconfirmed: Boolean,
    cheapest: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    ChoiceCard(selected = selected, onClick = onClick, enabled = product != null, nested = true, modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StoreLogo(store.code, stores, size = 18)
                Text(store.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(CheckSize), tint = MaterialTheme.colorScheme.primary)
            }
            if (product == null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    Text("Not found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Only a picture here: the whole card picks the store, so the thumb must not open anything.
                    ProductThumb(product, size = 56, openable = false)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(product.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(if (unitsToBuy > 1) "$unitsToBuy×" else null, product.quantityText).joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val price = (lineCents ?: product.priceCents).euros()
                    // "~" marks a price from a match that is not confirmed yet, the same way the basket rows do.
                    Text(
                        if (unconfirmed) "~$price" else price,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = if (selected || cheapest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = if (unconfirmed) Modifier.semantics { contentDescription = "$price, estimated" } else Modifier,
                    )
                    if (cheapest) Text("Cheapest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Leaves the item out of this plan; tapping it again unskips. Selection alone shows the state. */
@Composable
private fun SkipOption(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    ChoiceCard(selected = selected, onClick = onClick, nested = true, accent = true, modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(28.dp), tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Skip", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
