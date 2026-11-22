package nl.baskt.ui.deals

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.LastChanceItem
import nl.baskt.data.Product
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.ProductSheet
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.rememberTranslation

/** Which last chance deals show: all, the ones close to their date, or the ones leaving the range. */
private enum class LastChanceFilter(val label: String, val reason: String?) { All("All", null), Expiring("Expiring", "EXPIRATION"), Leaving("Out of assortment", "OUT_OF_ASSORTMENT") }

private enum class LastChanceSort(val label: String) { Date("Soonest date"), Discount("Biggest discount"), Price("Lowest price"), Category("Category") }

private fun comparatorFor(by: LastChanceSort): Comparator<LastChanceItem> = when (by) {
    LastChanceSort.Date -> compareBy<LastChanceItem, String?>(nullsLast()) { it.date }.thenByDescending { it.discountPercent ?: 0 }
    LastChanceSort.Discount -> compareByDescending<LastChanceItem> { it.discountPercent ?: 0 }.thenBy(nullsLast()) { it.date }
    LastChanceSort.Price -> compareBy<LastChanceItem, Int?>(nullsLast()) { it.priceCents }.thenBy(nullsLast()) { it.date }
    LastChanceSort.Category -> compareBy<LastChanceItem, String?>(nullsLast()) { it.category }.thenBy(nullsLast()) { it.date }
}

/** "70% off", or "40% extra off" when AH stacks it on an earlier markdown. */
private fun LastChanceItem.discountLabel(): String? {
    val percent = discountPercent ?: return discountText
    return if (discountText?.contains("extra", ignoreCase = true) == true) "$percent% extra off" else "$percent% off"
}

/** "today", "tomorrow", or "22 Nov". */
private fun relativeDay(value: String): String {
    val date = runCatching { java.time.LocalDate.parse(value.take(10)) }.getOrNull() ?: return value
    val today = java.time.LocalDate.now()
    return when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> shortDate(value)
    }
}

private fun LastChanceItem.dateLine(): String? {
    val day = date?.let(::relativeDay)
    return when (reason) {
        "EXPIRATION" -> day?.let { "Best before $it" } ?: "Close to its date"
        "OUT_OF_ASSORTMENT" -> listOfNotNull("Out of assortment", day).joinToString(" · ")
        else -> day
    }
}

private fun LastChanceItem.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val words = query.trim().lowercase().split(Regex("\\s+"))
    val haystack = listOfNotNull(title, brand, category, quantityText).joinToString(" ").lowercase()
    return words.all { it in haystack }
}

/**
 * AH's "Laatste Kans Koopjes" at the account's favourite store: products marked down because their date is
 * close or they leave the range. Search, a reason filter and a sort; they are bought in the store only.
 */
@Composable
fun LastChanceTab(viewModel: AppViewModel, onLogin: () -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    val data by viewModel.lastChance.collectAsState()
    val loading by viewModel.loadingLastChance.collectAsState()
    val basketItems by viewModel.basket.items.collectAsState()
    var tried by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LastChanceFilter.All) }
    var sort by rememberSaveable { mutableStateOf(LastChanceSort.Date) }
    LaunchedEffect(Unit) { viewModel.loadAhAccount(); viewModel.loadLastChance().join(); tried = true }
    fun reload() { viewModel.loadAhAccount(); viewModel.loadLastChance(force = true) }

    val current = account
    val all = data?.items ?: emptyList()
    val searched = all.filter { it.matches(query) }
    val shown = searched.filter { filter.reason == null || it.reason == filter.reason }.sortedWith(comparatorFor(sort))
    val products = data?.products ?: emptyMap()
    val open = basketItems.filter { !it.isGroup && it.isOpen }
    val basketProducts = open.flatMap { item -> item.matches.mapNotNull { it.chosen?.id } }.toSet()

    PullToRefreshBox(isRefreshing = loading && data != null, onRefresh = { reload() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
            when {
                (current != null && !current.connected) || data?.connected == false -> item("login") {
                    EmptyState(
                        title = if (current?.error != null) "Your AH login expired" else "Log in to see last chance deals",
                        body = "Marked-down products at your AH store, close to their date or leaving the range",
                        icon = Icons.Default.AccountCircle,
                        modifier = Modifier.fillParentMaxSize(),
                    ) { FilledTonalButton(onClick = onLogin) { Text(if (current?.error != null) "Log in again" else "Log in to Albert Heijn") } }
                }
                data == null && (loading || !tried) -> item("loading") { CentredLoading("Loading last chance deals…") }
                data == null -> item("failed") { LoadFailed("Couldn’t load last chance deals") { reload() } }
                data?.store == null -> item("no-store") {
                    EmptyState(
                        "No favourite store",
                        "Pick your store in the Albert Heijn app, then pull to refresh",
                        icon = Icons.Default.Storefront,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
                else -> {
                    item("store") {
                        Text(
                            "AH ${data?.store?.name} · in store only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.Screen),
                        )
                    }
                    item("search") {
                        SearchField(value = query, onValueChange = { query = it }, placeholder = "Search last chance deals", onSearch = {}, modifier = Modifier.padding(horizontal = Spacing.Screen))
                    }
                    item("filters") {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.Screen), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SortChip(sort) { sort = it }
                            for (option in LastChanceFilter.entries) {
                                val count = searched.count { option.reason == null || it.reason == option.reason }
                                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text("${option.label} · $count") })
                            }
                        }
                    }
                    when {
                        all.isEmpty() -> item("empty") { EmptyState("No last chance deals right now", "AH adds new ones through the day", icon = Icons.Default.Timer) }
                        shown.isEmpty() -> item("none") {
                            EmptyState(if (query.isNotBlank()) "Nothing for “${query.trim()}”" else "No ${filter.label.lowercase()} deals right now", icon = Icons.Default.Timer)
                        }
                        else -> {
                            if (query.isNotBlank()) item("count") {
                                Text("${counted(shown.size, "deal")} for “${query.trim()}”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.Screen))
                            }
                            shown.forEachIndexed { index, deal ->
                                if (sort == LastChanceSort.Category && deal.category != shown.getOrNull(index - 1)?.category) {
                                    item("cat-${deal.category}") { SectionHeader(deal.category ?: "Other", Modifier.padding(horizontal = Spacing.Screen).padding(top = if (index == 0) 0.dp else 8.dp)) }
                                }
                                // The same product can be marked down twice (two dates), so the key carries the position too.
                                item("lc-${deal.productId}-${deal.date}-$index") {
                                    val product = products[deal.productId]
                                    LastChanceCard(deal, product, added = product?.id in basketProducts, onAdd = { product?.let(viewModel::addProductWithUndo) }, modifier = Modifier.padding(horizontal = Spacing.Screen))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortChip(sort: LastChanceSort, onPick: (LastChanceSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(sort.label) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in LastChanceSort.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { if (option == sort) Icon(Icons.Default.Check, contentDescription = "Selected") },
                    onClick = { onPick(option); open = false },
                )
            }
        }
    }
}

/** One marked-down product: discount, name, what is left, the price against the usual one, and its date. */
@Composable
private fun LastChanceCard(deal: LastChanceItem, product: Product?, added: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    var details by rememberSaveable(deal.productId) { mutableStateOf(false) }
    val texts = listOf(deal.title, deal.quantityText.orEmpty())
    val translated = rememberTranslation(texts)?.takeIf { it.translated && it.texts.size == texts.size }?.texts ?: texts
    val urgent = deal.date != null && relativeDay(deal.date) == "today"
    Card(onClick = { if (product != null) details = true }, enabled = product != null, modifier = modifier) {
        Row(modifier = Modifier.padding(Spacing.Card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ItemThumb(deal.imageUrl, contentDescription = null, size = 64)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                deal.discountLabel()?.let { Text(it, style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                Text(translated[0], style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(translated[1].ifBlank { null }, deal.left?.let { "$it left" }).joinToString(" · ")
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val price = listOfNotNull(deal.priceCents?.euros(), deal.regularPriceCents?.takeIf { it > (deal.priceCents ?: 0) }?.let { "was ${it.euros()}" }).joinToString(" · ")
                if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodyMedium)
                deal.dateLine()?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
            }
            if (product != null) AddButton(added, onAdd)
        }
    }
    if (details && product != null) ProductSheet(product) { details = false }
}
