package nl.baskt.ui.deals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.DealCard
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/** Promotions (bonus, 1+1, x%) for the items in the current basket, with one-tap switching. */
@Composable
fun DealsScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit, onAhLogin: () -> Unit = {}) {
    val deals by viewModel.deals.collectAsState()
    val loading by viewModel.loadingDeals.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val changes by viewModel.priceChanges.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.findDeals(live = false); viewModel.loadPriceChanges() }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message, withDismissAction = true) } }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Deals") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (tab == 0) IconButton(onClick = { viewModel.findDeals(live = true) }) { Icon(Icons.Default.Refresh, contentDescription = "Search the stores again") }
                    if (tab == 2) IconButton(onClick = { viewModel.loadAhOffers(force = true) }) { Icon(Icons.Default.Refresh, contentDescription = "Reload my offers") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            nl.baskt.ui.common.OfflineBanner(viewModel)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 4)) { Text("My items", maxLines = 1) }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 4)) { Text("All", maxLines = 1) }
                SegmentedButton(selected = tab == 2, onClick = { tab = 2 }, shape = SegmentedButtonDefaults.itemShape(2, 4)) { Text("My Bonus", maxLines = 1) }
                SegmentedButton(selected = tab == 3, onClick = { tab = 3 }, shape = SegmentedButtonDefaults.itemShape(3, 4)) { Text("Prices", maxLines = 1) }
            }
            if (tab == 2) {
                MyBonusTab(viewModel, stores, onLogin = onAhLogin, onAdded = { notify(it) })
                return@Column
            }
            if (tab == 1) {
                AllDealsTab(viewModel, stores, onAdded = { notify(it) })
                return@Column
            }
            if (tab == 3) {
                val data = changes
                val format = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                Text(
                    data?.scan?.let { scan -> "Nightly scan: " + (scan.lastRunAt?.let { "last ${format.format(java.util.Date(it))}" } ?: "not run yet") + (scan.nextRunAt?.let { ", next ${format.format(java.util.Date(it))}" } ?: "") } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (data != null && data.changes.isEmpty()) {
                    nl.baskt.ui.common.EmptyState("No price changes this week", "Nothing in your baskets got cheaper or dearer in the last week.", icon = Icons.AutoMirrored.Filled.TrendingFlat)
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data?.changes ?: emptyList(), key = { it.product.id }) { change ->
                        Card {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreBadge(change.product.store, stores)
                                    Text(
                                        (if (change.diffCents < 0) "↓ " else "↑ ") + kotlin.math.abs(change.diffCents).euros(),
                                        color = if (change.diffCents < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text("was ${change.previousCents.euros()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ProductRow(change.product)
                            }
                        }
                    }
                }
                return@Column
            }
            if (loading) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator()
                    Text("Looking for promotions…")
                }
            }
            val list = deals?.deals ?: emptyList()
            if (!loading && list.isEmpty()) {
                nl.baskt.ui.common.EmptyState(
                    "No promotions for your open items",
                    "Search the stores again for bonus packs, 1+1 and discounts.",
                    icon = Icons.Default.LocalOffer,
                ) { FilledTonalButton(onClick = { viewModel.findDeals(live = true) }) { Text("Search the stores") } }
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { "${it.itemId}-${it.store}-${it.product.id}" }) { deal ->
                    Card {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoreBadge(deal.store, stores)
                                Text(deal.itemText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                val saving = deal.savingCents
                                if (saving != null && saving > 0) Text("save ${saving.euros()}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            ProductRow(deal.product)
                            val personal = deal.product.personal
                            if (personal?.status == "ACTIVATABLE") {
                                val offer = viewModel.ahOffers.collectAsState().value?.offers?.firstOrNull { it.id == personal.offerId }
                                val busy = personal.offerId in viewModel.activating.collectAsState().value
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Bonus Box offer, not active yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.OutlinedButton(enabled = !busy && offer != null, onClick = { offer?.let { viewModel.activateOffer(it) } }) { Text(if (busy) "Activating…" else "Activate") }
                                }
                                LaunchedEffect(Unit) { if (viewModel.ahOffers.value == null) viewModel.loadAhOffers() }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (deal.currentProductId != deal.product.id) {
                                    Button(onClick = { viewModel.takeDeal(deal); notify("Switched “${deal.itemText}” to ${deal.product.title}") }) { Text("Use this deal") }
                                } else {
                                    Text("Already your pick", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onOpenItem(deal.itemId) }) { Text("Open item") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which store-wide deals the All tab shows: everything, only this bonus week's, or the seasonal ones. */
private enum class DealPeriodFilter(val label: String, val period: String?) { All("All deals", null), Week("This week", "week"), Season("Seasonal", "season") }

/** Deal kinds the user can hide, in the order the sheet lists them; "online" is matched on the online-only flag. */
private val DEAL_KINDS = listOf(
    "volume" to "Volume deals (multi-packs)",
    "free" to "Free items (1+1, 2e gratis)",
    "half" to "Second item half price",
    "multibuy" to "Multi-buy (2 voor 5,00)",
    "percent" to "Percentage off",
    "price" to "Fixed price or amount off",
    "gift" to "Free gifts",
    "other" to "Plain Bonus and other",
    "online" to "Online only",
)

private fun DealCard.hiddenBy(hidden: Set<String>) = kind in hidden || (onlineOnly && "online" in hidden)

private fun DealCard.hasKind(key: String) = if (key == "online") onlineOnly else kind == key

/** "2026-12-27" as "27 Dec"; anything unparsable is shown as is. */
private fun shortDate(value: String): String = runCatching {
    java.time.LocalDate.parse(value.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
}.getOrDefault(value)

/** Store-wide promotions with period and store filters and keyword search; each card can be added to the basket. */
@Composable
private fun AllDealsTab(viewModel: AppViewModel, stores: List<nl.baskt.data.StoreInfo>, onAdded: (String) -> Unit) {
    val data by viewModel.allDeals.collectAsState()
    val loading by viewModel.loadingAllDeals.collectAsState()
    var query by remember { androidx.compose.runtime.mutableStateOf("") }
    var store by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var period by remember { androidx.compose.runtime.mutableStateOf(DealPeriodFilter.All) }
    var showKinds by remember { androidx.compose.runtime.mutableStateOf(false) }
    val hidden = viewModel.settings.collectAsState().value?.hiddenDealKinds ?: emptySet()
    val enabled = stores.filter { it.enabled }
    LaunchedEffect(store) { viewModel.loadAllDeals(query, store) }
    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search the deals, e.g. kaas") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.loadAllDeals(query.trim(), store) }),
            trailingIcon = { IconButton(onClick = { viewModel.loadAllDeals(query.trim(), store) }) { Icon(Icons.Default.Search, contentDescription = "Search") } },
        )
        val loaded = data?.results?.flatMap { result -> result.deals } ?: emptyList()
        val all = loaded.filterNot { it.hiddenBy(hidden) }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in DealPeriodFilter.entries) {
                val count = if (option.period == null) all.size else all.count { it.period == option.period }
                androidx.compose.material3.FilterChip(selected = period == option, onClick = { period = option }, label = { Text(if (data != null) "${option.label} ($count)" else option.label) })
            }
            androidx.compose.material3.FilterChip(
                selected = hidden.isNotEmpty(),
                onClick = { showKinds = true },
                label = { Text(if (hidden.isEmpty()) "Hide types" else "${hidden.size} hidden") },
                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (showKinds) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showKinds = false }) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text("Show these deals", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    for ((key, label) in DEAL_KINDS) {
                        val count = loaded.count { it.hasKind(key) }
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(if (data != null) "$count in this list" else "") },
                            trailingContent = { androidx.compose.material3.Switch(checked = key !in hidden, onCheckedChange = { viewModel.setDealKindHidden(key, !it) }) },
                            modifier = Modifier.clickable { viewModel.setDealKindHidden(key, key !in hidden) },
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = store == null, onClick = { store = null }, label = { Text("All stores") })
            for (info in enabled) androidx.compose.material3.FilterChip(selected = store == info.code, onClick = { store = info.code }, label = { Text(info.name) })
        }
        if (loading) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        // The store's own highlights of the week first; the sort is stable so relevance order stays within each part.
        val cards = all.filter { period.period == null || it.period == period.period }.sortedByDescending { it.featured }
        val errors = data?.results?.mapNotNull { it.error } ?: emptyList()
        if (errors.isNotEmpty()) Text(errors.joinToString("; "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (!loading && data != null && cards.isEmpty()) nl.baskt.ui.common.EmptyState(
            title = if (loaded.isNotEmpty() && all.isEmpty()) "All deals here are of a hidden type" else if (period == DealPeriodFilter.Season && all.isNotEmpty()) "No seasonal deals running right now" else "No deals found",
            icon = Icons.Default.LocalOffer,
        )
        if (!loading && data != null && cards.isNotEmpty() && query.isNotBlank()) Text("${cards.size} deals for “$query”" + (data?.terms?.takeIf { it.size > 1 }?.let { "  (also searched: ${it.drop(1).joinToString(", ")})" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cards, key = { "${it.store}-${it.id}" }) { card ->
                DealCardView(card, stores, hidden, onAdd = { deal -> viewModel.addDeal(deal); onAdded("Added “${deal.title}” to your basket") })
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

/** One store-wide deal: tap opens the product (or the deal itself), and multi-packs of the same item fold out below. */
@Composable
private fun DealCardView(card: DealCard, stores: List<nl.baskt.data.StoreInfo>, hidden: Set<String>, onAdd: (DealCard) -> Unit) {
    var open by remember { androidx.compose.runtime.mutableStateOf<DealCard?>(null) }
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    val variants = card.variants.filterNot { it.hiddenBy(hidden) }
    // Title, subtitle and deal text in the app language, plus each pack's deal text; the store's wording until it arrives.
    val texts = listOf(card.title, card.subtitle.orEmpty(), card.dealText.orEmpty()) + variants.map { it.dealText.orEmpty() }
    val shown = nl.baskt.ui.common.rememberTranslation(texts)?.takeIf { it.translated && it.texts.size == texts.size }?.texts ?: texts
    Card(onClick = { open = card }) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val image = card.imageUrl ?: card.product?.imageUrl
            if (image != null) coil3.compose.AsyncImage(model = image, contentDescription = null, modifier = Modifier.padding(2.dp).size(64.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoreBadge(card.store, stores)
                    if (card.dealText != null) Text(shown[2], style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary)
                }
                Text(shown[0], style = MaterialTheme.typography.titleSmall)
                if (card.subtitle != null) Text(shown[1], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tags = listOfNotNull("In the folder".takeIf { card.featured }, "Seasonal".takeIf { card.period == "season" })
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                val price = card.priceLine()
                if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onAdd(card) }) { Text("Add") }
        }
        if (variants.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (expanded) "Hide pack deals" else "More pack deals of this item (${variants.size})")
            }
            if (expanded) Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                for ((index, variant) in variants.withIndex()) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { open = variant }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${variant.packCount}× pack" + (variant.dealText?.let { " · ${shown[3 + index]}" } ?: ""), style = MaterialTheme.typography.labelLarge)
                            Text(listOfNotNull(variant.priceCents?.euros(), variant.eachCents?.let { "${it.euros()} each" }, variant.regularPriceCents?.let { "was ${it.euros()}" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onAdd(variant) }) { Text("Add") }
                    }
                }
            }
        }
    }
    open?.let { shown ->
        val product = shown.product
        if (product != null) nl.baskt.ui.common.ProductSheet(product) { open = null }
        else DealSheet(shown, onAdd = { onAdd(shown); open = null }) { open = null }
    }
}

/** Details of a promotion that is not a single product (Jumbo promotions, AH bonus groups). */
@Composable
private fun DealSheet(card: DealCard, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var original by androidx.compose.runtime.saveable.rememberSaveable(card.id) { androidx.compose.runtime.mutableStateOf(false) }
    val texts = listOf(card.title, card.subtitle.orEmpty(), card.dealText.orEmpty())
    val translation = nl.baskt.ui.common.rememberTranslation(texts, !original)
    val shown = translation?.takeIf { !original && it.translated && it.texts.size == texts.size }?.texts ?: texts
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (original) "Original" else if (translation?.translated == true) "Translated" else if (translation == null) "Translating…" else "Translation unavailable · showing original", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                nl.baskt.ui.common.OriginalToggle(original) { original = !original }
            }
            if (card.imageUrl != null) nl.baskt.ui.common.ZoomableImage(card.imageUrl, shown[0], Modifier.fillMaxWidth().height(220.dp))
            if (card.dealText != null) Text(shown[2], style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
            Text(shown[0], style = MaterialTheme.typography.headlineSmall)
            if (card.subtitle != null) Text(shown[1], color = MaterialTheme.colorScheme.onSurfaceVariant)
            val period = listOfNotNull(card.validFrom?.let { "from ${shortDate(it)}" }, card.validUntil?.let { "until ${shortDate(it)}" }).joinToString(" ")
            val price = listOfNotNull(card.priceCents?.euros(), card.regularPriceCents?.let { "was ${it.euros()}" }).joinToString(" · ")
            if (price.isNotEmpty()) Text(price)
            if (period.isNotEmpty()) Text((if (card.period == "season") "Seasonal, " else "") + period, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd) { Text("Add to basket") }
                if (card.url != null) androidx.compose.material3.OutlinedButton(onClick = { uri.openUri(card.url) }) { Text("Open at the store") }
            }
        }
    }
}
