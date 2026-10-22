package nl.baskt.ui.compare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
    val perStore = data?.order?.perStore ?: emptyMap()
    val shoppable = enabled.filter { (perStore[it.code]?.count ?: 0) > 0 }
    val unassigned = open.count { it.assignedStore == null }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Plan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the list") } },
                actions = { IconButton(onClick = { viewModel.compare(refreshPrices = true) }, enabled = open.isNotEmpty()) { Icon(Icons.Default.Refresh, contentDescription = "Refresh prices") } },
            )
        },
        bottomBar = {
            if (open.isNotEmpty()) ShopBar(shoppable, perStore, unassigned, onShop)
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
        items(ranked, key = { "store-${it.store}" }) { summary ->
            StoreCard(
                summary = summary,
                stores = stores,
                itemCount = data.items.size,
                best = summary.rank == 1 && ranked.size > 1,
                missing = data.items.filter { it.itemId in summary.missingItemIds }.map { it.text },
                chosen = allAt(summary.store),
                onChoose = { viewModel.assign("store:${summary.store}") },
                onShop = { onShop(summary.store) },
            )
        }
        // Only worth a second trip when splitting the list actually saves something.
        val cheapest = ranked.firstOrNull()
        val mixSaving = if (cheapest != null) cheapest.fullTotalCents - data.mixAndMatchTotalCents else 0
        if (ranked.size > 1 && cheapest != null && mixSaving > 0) item("mix") {
            MixCard(total = data.mixAndMatchTotalCents, saving = mixSaving, versus = storeName(cheapest.store, stores)) { viewModel.assign("mix") }
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

/** One store's total for the whole list, and the button that sends everything there. */
@Composable
private fun StoreCard(summary: StoreSummary, stores: List<StoreInfo>, itemCount: Int, best: Boolean, missing: List<String>, chosen: Boolean, onChoose: () -> Unit, onShop: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (best) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StoreLogo(summary.store, stores, size = 32)
                Column(modifier = Modifier.weight(1f)) {
                    Text(storeName(summary.store, stores), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (missing.isEmpty()) "Everything on the list · $itemCount items" else "${summary.matchedCount} of $itemCount items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(summary.fullTotalCents.euros(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (best) Text("cheapest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (missing.isNotEmpty()) Text(
                "Missing: ${missing.joinToString(", ")} · shared items only ${summary.comparableTotalCents.euros()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.unconfirmedCount > 0) Text(
                if (summary.unconfirmedCount == 1) "1 item still uses the suggested product" else "${summary.unconfirmedCount} items still use the suggested product",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            // Once the whole list is assigned here, the same spot takes you into the store.
            if (chosen) Button(onClick = onShop, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Start shopping at ${storeName(summary.store, stores)}")
            } else Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text("Everything at ${storeName(summary.store, stores)}") }
        }
    }
}

@Composable
private fun MixCard(total: Int, saving: Int, versus: String, onChoose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mix & match", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cheapest store per item · saves ${saving.euros()} vs all at $versus · more than one trip",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(total.euros(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            FilledTonalButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text("Use the cheapest mix") }
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

/** Sticky bar: one button per store that has items, each opening shopping mode with that store's share of the list. */
@Composable
private fun ShopBar(shoppable: List<StoreInfo>, perStore: Map<String, OrderStoreTotal>, unassigned: Int, onShop: (String) -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (shoppable.isEmpty()) {
                Text("Pick a store above, or choose per item, to start shopping.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                if (unassigned > 0) Text(
                    if (unassigned == 1) "1 item has no store yet" else "$unassigned items have no store yet",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (store in shoppable) {
                        val total = perStore[store.code] ?: continue
                        Button(onClick = { onShop(store.code) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Column {
                                Text("Shop at ${store.name}", style = MaterialTheme.typography.labelLarge)
                                Text((if (total.count == 1) "1 item" else "${total.count} items") + " · ${total.totalCents.euros()}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
