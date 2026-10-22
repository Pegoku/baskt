package nl.baskt.ui.compare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.storeName

/**
 * The plan: one page that answers "where do I buy this?" Ranked store totals at the top, each with a button that
 * sends the whole list there; below, every item with what it costs at each store (cheapest marked) so single
 * items can go elsewhere or stay home. The bar at the bottom starts shopping mode for every store that has items.
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the list") } },
                actions = { IconButton(onClick = { viewModel.compare(refreshPrices = true) }, enabled = open.isNotEmpty()) { Icon(Icons.Default.Refresh, contentDescription = "Refresh prices") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            if (comparing && data != null) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            when {
                listed.isEmpty() -> EmptyPlan("Nothing to plan yet", "Add a few ideas to the list and the stores get compared here.")
                open.isEmpty() -> EmptyPlan("Everything is skipped", "Put something back on this trip to compare the stores.")
                data == null && comparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                data == null -> EmptyPlan("No comparison available", "The stores could not be compared. Pull prices again once you are online.")
                else -> PlanList(data, listed, open, enabled, stores, viewModel, onOpenItem, onShop)
            }
        }
    }
}

@Composable
private fun EmptyPlan(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
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
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("h-stores") { SectionTitle("Where to shop") }
        val perStore = data.order.perStore
        val cheapestRow = data.items.associate { it.itemId to it.cheapestStore }
        // The mix is in use once every open item sits at its cheapest store (items found nowhere stay unassigned).
        val mixActive = ranked.size > 1 && open.isNotEmpty() && open.all { cheapestRow[it.id] == null || it.assignedStore == cheapestRow[it.id] } && open.any { it.assignedStore != null }
        if (ranked.size > 1) item("mix") {
            val cheapest = ranked.first()
            MixCard(total = data.mixAndMatchTotalCents, saving = cheapest.fullTotalCents - data.mixAndMatchTotalCents, versus = storeName(cheapest.store, stores), active = mixActive) { viewModel.assign("mix") }
        }
        item("stores") {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (summary in ranked) StoreCard(
                    summary = summary,
                    stores = stores,
                    itemCount = data.items.size,
                    best = summary.rank == 1 && ranked.size > 1,
                    missing = data.items.filter { it.itemId in summary.missingItemIds }.map { it.text },
                    chosen = allAt(summary.store),
                    assigned = perStore[summary.store] ?: OrderStoreTotal(),
                    onChoose = { viewModel.assign("store:${summary.store}") },
                    onShop = { onShop(summary.store) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        val unassigned = open.count { it.assignedStore == null }
        if (unassigned > 0 && open.any { it.assignedStore != null }) item("unassigned") {
            Text(
                if (unassigned == 1) "1 item has no store yet · pick one below" else "$unassigned items have no store yet · pick them below",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (data.missingEverywhere.isNotEmpty()) item("missing") {
            Text(
                "Not found anywhere: ${data.items.filter { it.itemId in data.missingEverywhere }.joinToString { it.text }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item("h-items") {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Item by item", modifier = Modifier.weight(1f))
                Text("Cheapest by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = !byUnit, onClick = { viewModel.setRankBy("price") }, label = { Text("pack") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = byUnit, onClick = { viewModel.setRankBy("unitPrice") }, label = { Text("per kg / l") })
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

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

/**
 * One store's total for the whole list, side by side with the others. The button sends everything there; once the
 * whole list (or this store's share of a mix) is assigned here, it starts shopping instead.
 */
@Composable
private fun StoreCard(
    summary: StoreSummary,
    stores: List<StoreInfo>,
    itemCount: Int,
    best: Boolean,
    missing: List<String>,
    chosen: Boolean,
    assigned: OrderStoreTotal,
    onChoose: () -> Unit,
    onShop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = storeName(summary.store, stores)
    // Tapping the card is the choice itself; the selected one wears the same border as a chosen item option.
    Card(
        onClick = onChoose,
        colors = CardDefaults.cardColors(containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreLogo(summary.store, stores, size = 28)
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (chosen) Icon(Icons.Default.Check, contentDescription = "Everything here", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(summary.fullTotalCents.euros(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(
                when {
                    best && missing.isEmpty() -> "cheapest · all $itemCount items"
                    missing.isEmpty() -> "all $itemCount items"
                    else -> "${summary.matchedCount} of $itemCount items"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (best && missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (missing.isNotEmpty()) Text(
                "Missing: ${missing.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.unconfirmedCount > 0) Text(
                if (summary.unconfirmedCount == 1) "1 suggested pick" else "${summary.unconfirmedCount} suggested picks",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.weight(1f))
            // One fixed-height slot for the button or the hint, so choosing a store never resizes the cards.
            Box(modifier = Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                if (assigned.count > 0) Button(onClick = onShop, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (chosen) "Shop" else "Shop ${assigned.count} · ${assigned.totalCents.euros()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Text("Tap to shop everything here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Cheapest store for every item, above the stores: the plan that splits the list. */
@Composable
private fun MixCard(total: Int, saving: Int, versus: String, active: Boolean, onChoose: () -> Unit) {
    Card(
        onClick = onChoose,
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mix & match", style = MaterialTheme.typography.titleSmall)
                    Text(total.euros(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (saving > 0) "Cheapest store per item · saves ${saving.euros()} vs all at $versus" else "Cheapest store per item · no cheaper than all at $versus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) Icon(Icons.Default.Check, contentDescription = "Mix in use", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One item: the product you would get at each store with its line price, the cheapest marked; Skip leaves it home. */
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
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (if (item.quantity > 1) "${item.quantity}× " else "") + item.text,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (item.isSkipped) TextDecoration.LineThrough else null,
                    color = if (item.isSkipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Change products") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        modifier = Modifier.weight(1f),
                    )
                }
                SkipOption(selected = item.isSkipped, onClick = onSkip, modifier = Modifier.width(72.dp))
            }
            if (row != null && row.packSizeDiffers && row.cheapestByUnitPriceStore != null && row.cheapestByUnitPriceStore != row.cheapestStore) {
                Text(
                    "Different pack sizes: ${storeName(row.cheapestByUnitPriceStore, stores)} is cheaper per unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** What you would get at this store: picture, pack and price; dimmed when the store has nothing for the item. */
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
    Card(
        onClick = onClick,
        enabled = product != null,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StoreLogo(store.code, stores, size = 18)
                Text(store.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Default.Check, contentDescription = "Buying here", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (product == null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { Text("—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Not found", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductThumb(product, size = 56)
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
                    Text(
                        (lineCents ?: product.priceCents).euros() + if (unconfirmed) "?" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected || cheapest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (cheapest) Text("cheapest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SkipOption(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.height(2.dp))
            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(28.dp), tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Skip", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            Text(if (selected) "Not this trip" else "Leave home", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
