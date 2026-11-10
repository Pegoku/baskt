package nl.baskt.ui.deals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.Deal
import nl.baskt.data.DealCard
import nl.baskt.data.PersonalOffer
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.dealPricing
import nl.baskt.data.euros
import nl.baskt.data.unitPriceLabel
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.SwitchRow
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.storeName

private val TABS = listOf("My items", "All", "My Bonus", "Prices")

/**
 * Deals in four views: the ones for the items in this basket (with one-tap switching), every deal at the
 * stores, the AH account's Bonus Box, and what got cheaper or more expensive. Every view reloads with a pull.
 */
@Composable
fun DealsScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit, onAhLogin: () -> Unit = {}) {
    val stores by viewModel.basket.stores.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Each tab keeps its own search text and filters while another one is shown.
    val tabState = rememberSaveableStateHolder()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Deals") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            tabState.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> MyItemsTab(viewModel, stores, onOpenItem)
                    1 -> AllDealsTab(viewModel, stores)
                    2 -> MyBonusTab(viewModel, onLogin = onAhLogin)
                    else -> PriceChangesTab(viewModel, stores)
                }
            }
        }
    }
}

/** The one loading look of the four tabs: the indicator and what is loading, in the middle of the page. */
@Composable
internal fun LazyItemScope.CentredLoading(text: String) {
    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { LoadingRow(text) }
}

/** The one "could not load" look of the four tabs, with the button that tries again. */
@Composable
internal fun LazyItemScope.LoadFailed(title: String, onRetry: () -> Unit) {
    EmptyState(title, "Check your connection", icon = Icons.Default.CloudOff, modifier = Modifier.fillParentMaxSize()) {
        FilledTonalButton(onClick = onRetry) { Text("Try again") }
    }
}

/** Asks once before a Bonus Box offer is activated, because AH has no way to take that back. */
@Composable
internal fun ActivateConfirm(offer: PersonalOffer, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Activate this offer?",
        body = "${offer.title}. AH doesn’t let you undo this.",
        confirmLabel = "Activate",
        destructive = false,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** Deals for the items in this basket; a card opens its item, and "Use this deal" switches the item to the deal's product. */
@Composable
private fun MyItemsTab(viewModel: AppViewModel, stores: List<StoreInfo>, onOpenItem: (String) -> Unit) {
    val deals by viewModel.deals.collectAsState()
    val loading by viewModel.loadingDeals.collectAsState()
    val offers by viewModel.ahOffers.collectAsState()
    val activating by viewModel.activating.collectAsState()
    var tried by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.findDeals(live = false).join(); tried = true }
    val list = deals?.deals ?: emptyList()
    // Bonus Box offers are only needed when a card can activate one.
    val needsOffers = list.any { it.product.personal?.status == "ACTIVATABLE" }
    LaunchedEffect(needsOffers) { if (needsOffers && viewModel.ahOffers.value == null) viewModel.loadAhOffers() }
    offers?.offers?.firstOrNull { it.id == confirming }?.let { offer ->
        ActivateConfirm(offer, onDismiss = { confirming = null }) { viewModel.activateOffer(offer) }
    }

    PullToRefreshBox(isRefreshing = loading && deals != null, onRefresh = { viewModel.findDeals(live = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
            when {
                deals == null && (loading || !tried) -> item("loading") { CentredLoading("Looking for deals…") }
                deals == null -> item("failed") { LoadFailed("Couldn’t load deals") { viewModel.findDeals(live = true) } }
                list.isEmpty() -> item("empty") {
                    EmptyState("No deals for your items", "Pull down to search the stores again", icon = Icons.Default.LocalOffer, modifier = Modifier.fillParentMaxSize())
                }
                else -> items(list, key = { "${it.itemId}-${it.store}-${it.product.id}" }) { deal ->
                    val personal = deal.product.personal
                    val offer = if (personal?.status == "ACTIVATABLE") offers?.offers?.firstOrNull { it.id == personal.offerId } else null
                    ItemDealCard(
                        deal = deal,
                        stores = stores,
                        activatable = personal?.status == "ACTIVATABLE",
                        activateEnabled = offer != null && offer.id !in activating,
                        activating = personal?.offerId in activating,
                        onOpen = { onOpenItem(deal.itemId) },
                        onActivate = { confirming = offer?.id },
                        onUse = { viewModel.takeDeal(deal) },
                    )
                }
            }
        }
    }
}

/** One deal for a basket item. The whole card opens the item; only a card that can do something gets a button row. */
@Composable
private fun ItemDealCard(deal: Deal, stores: List<StoreInfo>, activatable: Boolean, activateEnabled: Boolean, activating: Boolean, onOpen: () -> Unit, onActivate: () -> Unit, onUse: () -> Unit) {
    val isPick = deal.currentProductId == deal.product.id
    Card(onClick = onOpen) {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreBadge(deal.store, stores)
                Text(deal.itemText, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val saving = deal.savingCents
                if (saving != null && saving > 0) Text("Save ${saving.euros()}", style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary)
                if (isPick) Tag("Your pick")
            }
            DealProductLine(deal.product)
            if (activatable) Text("Bonus Box offer, not active yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (activatable || !isPick) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (activatable) OutlinedButton(enabled = activateEnabled, onClick = onActivate) { Text(if (activating) "Activating…" else "Activate") }
                if (!isPick) FilledTonalButton(onClick = onUse) { Text("Use this deal") }
            }
        }
    }
}

/**
 * The product of a deal inside a tappable card: the same facts as the shared product row, but only the picture
 * takes its own tap (it opens the product), so a tap anywhere else reaches the card.
 */
@Composable
private fun DealProductLine(product: Product) {
    val texts = listOf(product.title, product.quantityText, product.dealText.orEmpty())
    val shown = nl.baskt.ui.common.rememberTranslation(texts)?.takeIf { it.translated && it.texts.size == texts.size }?.texts ?: texts
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductThumb(product)
        Column(modifier = Modifier.weight(1f)) {
            Text(shown[0], style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(shown[1].ifBlank { null }, product.unitPriceLabel()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (product.dealText != null) Text(listOfNotNull(shown[2], product.dealPricing()?.label()).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(product.priceCents.euros(), style = MaterialTheme.typography.titleMediumEmphasized)
            if (product.regularPriceCents != null && product.regularPriceCents > product.priceCents) {
                Text(product.regularPriceCents.euros(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Products from the baskets whose price moved in the last week. */
@Composable
private fun PriceChangesTab(viewModel: AppViewModel, stores: List<StoreInfo>) {
    val data by viewModel.priceChanges.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    fun load() { scope.launch { loading = true; viewModel.loadPriceChanges().join(); loading = false } }
    LaunchedEffect(Unit) { load() }
    val changes = data?.changes
    PullToRefreshBox(isRefreshing = loading && data != null, onRefresh = { load() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
            when {
                changes == null && loading -> item("loading") { CentredLoading("Loading price changes…") }
                changes == null -> item("failed") { LoadFailed("Couldn’t load price changes") { load() } }
                changes.isEmpty() -> item("empty") {
                    EmptyState("No price changes this week", icon = Icons.AutoMirrored.Filled.TrendingFlat, modifier = Modifier.fillParentMaxSize())
                }
                else -> {
                    data?.scan?.lastRunAt?.let { at ->
                        item("updated") {
                            Text("Updated ${java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(changes, key = { it.product.id }) { change ->
                        val cheaper = change.diffCents < 0
                        val colour = if (cheaper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        Card {
                            Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreBadge(change.product.store, stores)
                                    Icon(if (cheaper) Icons.AutoMirrored.Filled.TrendingDown else Icons.AutoMirrored.Filled.TrendingUp, contentDescription = if (cheaper) "Cheaper" else "More expensive", tint = colour, modifier = Modifier.size(18.dp))
                                    Text(kotlin.math.abs(change.diffCents).euros(), style = MaterialTheme.typography.labelLargeEmphasized, color = colour)
                                    Text("Was ${change.previousCents.euros()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ProductRow(change.product)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which store-wide deals the All tab shows: everything, only this bonus week's, or the seasonal ones. */
private enum class DealPeriodFilter(val label: String, val period: String?) { All("Any time", null), Week("This week", "week"), Season("Seasonal", "season") }

/** Deal kinds the user can hide, in the order the sheet lists them; "online" is matched on the online-only flag. */
private val DEAL_KINDS = listOf(
    "volume" to "Multi-packs, e.g. a 6-pack for less",
    "free" to "Free items, e.g. 1+1",
    "half" to "Second one half price",
    "multibuy" to "Multi-buy, e.g. 2 for €5",
    "percent" to "Percentage off, e.g. 25% off",
    "price" to "Set price or money off",
    "gift" to "Free gifts",
    "other" to "Other Bonus deals",
    "online" to "Online only",
)

private fun DealCard.hiddenBy(hidden: Set<String>) = kind in hidden || (onlineOnly && "online" in hidden)

private fun DealCard.hasKind(key: String) = if (key == "online") onlineOnly else kind == key

/** "2026-12-27" as "27 Dec"; anything unparsable is shown as is. */
internal fun shortDate(value: String): String = runCatching {
    java.time.LocalDate.parse(value.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
}.getOrDefault(value)

/** "3 to 8 Nov", "28 Oct to 3 Nov", or "Until 8 Nov" / "From 3 Nov" when only one end is known. */
internal fun dateRange(from: String?, until: String?): String? {
    if (from == null || until == null) return until?.let { "Until ${shortDate(it)}" } ?: from?.let { "From ${shortDate(it)}" }
    val start = runCatching { java.time.LocalDate.parse(from.take(10)) }.getOrNull()
    val end = runCatching { java.time.LocalDate.parse(until.take(10)) }.getOrNull()
    val sameMonth = start != null && end != null && start.month == end.month && start.year == end.year
    return (if (sameMonth) start!!.dayOfMonth.toString() else shortDate(from)) + " to " + shortDate(until)
}

/** An icon-only button that says what it does when long-pressed or hovered. */
@Composable
private fun DealTypesButton(hiddenCount: Int, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Deal types") } }, state = rememberTooltipState()) {
        FilledTonalIconButton(onClick = onClick) {
            BadgedBox(badge = { if (hiddenCount > 0) Badge { Text("$hiddenCount") } }) {
                Icon(Icons.Default.FilterList, contentDescription = if (hiddenCount > 0) "Deal types, $hiddenCount hidden" else "Deal types")
            }
        }
    }
}

/** Every deal at the stores, with keyword search and period, store and type filters that scroll away with the list. */
@Composable
private fun AllDealsTab(viewModel: AppViewModel, stores: List<StoreInfo>) {
    val data by viewModel.allDeals.collectAsState()
    val loading by viewModel.loadingAllDeals.collectAsState()
    val basketItems by viewModel.basket.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // What the list on screen was searched with; the field can already hold something newer.
    var submitted by rememberSaveable { mutableStateOf("") }
    var store by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(DealPeriodFilter.All) }
    var showKinds by rememberSaveable { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val hidden = viewModel.settings.collectAsState().value?.hiddenDealKinds ?: emptySet()
    val enabled = stores.filter { it.enabled }
    fun search() { submitted = query.trim(); viewModel.loadAllDeals(submitted, store) }
    LaunchedEffect(store) { viewModel.loadAllDeals(submitted, store).join(); tried = true }

    val loaded = data?.results?.flatMap { result -> result.deals } ?: emptyList()
    val all = loaded.filterNot { it.hiddenBy(hidden) }
    // The store's own highlights of the week first; the sort is stable so relevance order stays within each part.
    val cards = all.filter { period.period == null || it.period == period.period }.sortedByDescending { it.featured }
    val failedStores = data?.results?.filter { it.error != null }?.map { storeName(it.store, stores) } ?: emptyList()
    // What is already in the basket, so its button reads "Added".
    val open = basketItems.filter { !it.isGroup && it.isOpen }
    val basketProducts = open.flatMap { item -> item.matches.mapNotNull { it.chosen?.id } }.toSet()
    val basketTexts = open.map { it.text.lowercase() }.toSet()
    fun isAdded(card: DealCard) = (card.product?.id ?: card.productId)?.let { it in basketProducts } == true || card.title.lowercase() in basketTexts

    if (showKinds) {
        ModalBottomSheet(onDismissRequest = { showKinds = false }) {
            SheetBody(title = "Deal types") {
                for ((key, label) in DEAL_KINDS) {
                    SwitchRow(label, if (data != null) counted(loaded.count { it.hasKind(key) }, "deal") else null, checked = key !in hidden) { viewModel.setDealKindHidden(key, !it) }
                }
            }
        }
    }

    PullToRefreshBox(isRefreshing = loading && data != null, onRefresh = { viewModel.loadAllDeals(submitted, store) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
            item("search") {
                SearchField(
                    value = query,
                    onValueChange = { query = it; if (it.isEmpty() && submitted.isNotEmpty()) search() },
                    placeholder = "Search deals",
                    onSearch = { search() },
                    modifier = Modifier.padding(horizontal = Spacing.Screen),
                )
            }
            item("period") {
                Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.Screen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = Spacing.Screen), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in DealPeriodFilter.entries) {
                            val count = if (option.period == null) all.size else all.count { it.period == option.period }
                            FilterChip(selected = period == option, onClick = { period = option }, label = { Text(if (data != null) "${option.label} · $count" else option.label) })
                        }
                    }
                    DealTypesButton(hidden.size) { showKinds = true }
                }
            }
            item("stores") {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.Screen), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = store == null, onClick = { store = null }, label = { Text("Any store") })
                    for (info in enabled) FilterChip(selected = store == info.code, onClick = { store = info.code }, label = { Text(info.name) })
                }
            }
            when {
                data == null && (loading || !tried) -> item("loading") { LoadingRow("Loading deals…", Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen, vertical = 32.dp)) }
                data == null || (cards.isEmpty() && failedStores.isNotEmpty() && loaded.isEmpty()) -> item("failed") {
                    EmptyState("Couldn’t load deals", "Check your connection", icon = Icons.Default.CloudOff) { FilledTonalButton(onClick = { viewModel.loadAllDeals(submitted, store) }) { Text("Try again") } }
                }
                cards.isEmpty() -> item("empty") {
                    if (loaded.isNotEmpty() && all.isEmpty()) EmptyState("All deals here are hidden", "Their deal types are turned off", icon = Icons.Default.LocalOffer) {
                        FilledTonalButton(onClick = { showKinds = true }) { Text("Deal types") }
                    } else EmptyState(
                        title = if (period == DealPeriodFilter.Season && all.isNotEmpty()) "No seasonal deals right now" else if (submitted.isNotBlank()) "No deals for “$submitted”" else "No deals found",
                        icon = Icons.Default.LocalOffer,
                    )
                }
                else -> {
                    if (failedStores.isNotEmpty()) item("store-errors") {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn’t load deals from ${failedStores.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.loadAllDeals(submitted, store) }) { Text("Try again") }
                        }
                    }
                    if (submitted.isNotBlank()) item("count") {
                        Text("${counted(cards.size, "deal")} for “$submitted”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.Screen))
                    }
                    items(cards, key = { "${it.store}-${it.id}" }) { card ->
                        DealCardView(card, stores, hidden, isAdded = ::isAdded, onAdd = { viewModel.addDeal(it) }, modifier = Modifier.padding(horizontal = Spacing.Screen))
                    }
                }
            }
        }
    }
}

/**
 * "€2,50 each · €3,19 single · until 8 Nov" for a multi-buy, "€2,73 each · €8,19 for 3" for a multi-pack:
 * the per-item price leads whenever the deal means taking more than one.
 */
private fun DealCard.priceLine(): String {
    val each = eachCents?.takeIf { packCount > 1 }
    val price = priceCents
    // A multi-pack lists the price of the whole pack; a multi-buy lists the single item's price.
    val isPack = each != null && price != null && kotlin.math.abs(each * packCount - price) <= packCount
    return listOfNotNull(
        each?.let { "${it.euros()} each" } ?: price?.euros(),
        if (each != null && price != null) (if (isPack) "${price.euros()} for $packCount" else "${price.euros()} single") else null,
        regularPriceCents?.let { "was ${it.euros()}" },
        validUntil?.let { "until ${shortDate(it)}" },
    ).joinToString(" · ")
}

/** The Add button of a deal or product row; it reads "Added" once the thing is in the basket. */
@Composable
internal fun AddButton(added: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = !added) { Text(if (added) "Added" else "Add") }
}

/** A short text button with a chevron that folds a list out under a card. */
@Composable
internal fun Expander(label: String, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = modifier) {
        Text(label)
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand", modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/** One store-wide deal: tap opens the product (or the deal itself), and multi-packs of the same item fold out below. */
@Composable
private fun DealCardView(card: DealCard, stores: List<StoreInfo>, hidden: Set<String>, isAdded: (DealCard) -> Boolean, onAdd: (DealCard) -> Unit, modifier: Modifier = Modifier) {
    val variants = card.variants.filterNot { it.hiddenBy(hidden) }
    // The open sheet is remembered by the deal's id so it comes back after a rotation.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Title, subtitle and deal text in the app language, plus each pack's deal text; the store's wording until it arrives.
    val texts = listOf(card.title, card.subtitle.orEmpty(), card.dealText.orEmpty()) + variants.map { it.dealText.orEmpty() }
    val shown = nl.baskt.ui.common.rememberTranslation(texts)?.takeIf { it.translated && it.texts.size == texts.size }?.texts ?: texts
    Card(onClick = { openId = card.id }, modifier = modifier) {
        Row(modifier = Modifier.padding(Spacing.Card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ItemThumb(card.imageUrl ?: card.product?.imageUrl, contentDescription = null, size = 64)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoreBadge(card.store, stores)
                    if (card.dealText != null) Text(shown[2], style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(shown[0], style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (card.subtitle != null) Text(shown[1], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val tags = listOfNotNull("In this week’s flyer".takeIf { card.featured }, "Seasonal".takeIf { card.period == "season" })
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                val price = card.priceLine()
                if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodySmall)
            }
            AddButton(isAdded(card)) { onAdd(card) }
        }
        if (variants.isNotEmpty()) {
            Expander(counted(variants.size, "pack deal"), expanded, Modifier.padding(start = 4.dp)) { expanded = !expanded }
            if (expanded) Column(modifier = Modifier.padding(start = Spacing.Card, end = Spacing.Card, bottom = 8.dp)) {
                for ((index, variant) in variants.withIndex()) {
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { openId = variant.id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${variant.packCount}-pack" + (variant.dealText?.let { " · ${shown[3 + index]}" } ?: ""), style = MaterialTheme.typography.labelLarge)
                            Text(listOfNotNull(variant.priceCents?.euros(), variant.eachCents?.let { "${it.euros()} each" }, variant.regularPriceCents?.let { "was ${it.euros()}" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AddButton(isAdded(variant)) { onAdd(variant) }
                    }
                }
            }
        }
    }
    (listOf(card) + variants).firstOrNull { it.id == openId }?.let { opened ->
        val product = opened.product
        if (product != null) nl.baskt.ui.common.ProductSheet(product) { openId = null }
        else DealSheet(opened, storeName(opened.store, stores), added = isAdded(opened), onAdd = { onAdd(opened); openId = null }) { openId = null }
    }
}

/** Details of a deal that is not a single product (Jumbo promotions, AH bonus groups, Bonus Box offers). */
@Composable
internal fun DealSheet(card: DealCard, store: String, added: Boolean, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var original by rememberSaveable(card.id) { mutableStateOf(false) }
    val texts = listOf(card.title, card.subtitle.orEmpty(), card.dealText.orEmpty())
    val translation = nl.baskt.ui.common.rememberTranslation(texts, !original)
    val shown = translation?.takeIf { !original && it.translated && it.texts.size == texts.size }?.texts ?: texts
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBody(
            title = shown[0],
            titleTrailing = { nl.baskt.ui.common.OriginalToggle(original) { original = !original } },
            actions = {
                if (card.url != null) TextButton(onClick = { uri.openUri(card.url) }) { Text("View at $store") }
                Button(onClick = onAdd, enabled = !added) { Text(if (added) "Added" else "Add to basket") }
            },
        ) {
            // The toggle already says which version is shown; only a pending or failed translation needs words.
            if (!original && translation?.translated != true) Text(if (translation == null) "Translating…" else "Couldn’t translate, showing the original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.imageUrl != null) nl.baskt.ui.common.ZoomableImage(card.imageUrl, shown[0], Modifier.fillMaxWidth().height(220.dp))
            if (card.dealText != null) Text(shown[2], style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
            if (card.subtitle != null) Text(shown[1], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val price = listOfNotNull(card.priceCents?.euros(), card.regularPriceCents?.let { "was ${it.euros()}" }).joinToString(" · ")
            if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodyLarge)
            val period = listOfNotNull("Seasonal".takeIf { card.period == "season" }, dateRange(card.validFrom, card.validUntil)).joinToString(" · ")
            if (period.isNotEmpty()) Text(period, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
