package nl.baskt.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.Product
import nl.baskt.data.PurchaseLine
import nl.baskt.data.PurchaseWithLines
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.PhotoPlaceholder
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.storeName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private fun dayOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Orders, one day at a time: what was checked off or scanned in the store (and scanned receipts), grouped
 * by store. Swiping, the arrows and the calendar only land on days something was bought. Every line can go straight
 * back in the basket, one at a time or a whole order at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val history by viewModel.history.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val tripScans by viewModel.tripScans.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadHistory() }

    val purchases = history?.purchases ?: emptyList()
    val products = history?.products ?: emptyMap()
    val pendingScans = tripScans.filter { !it.synced }
    val days = (purchases.map { dayOf(it.purchase.purchasedAt) } + pendingScans.map { dayOf(it.at) }).distinct().sorted()
    // Saved as plain values (a day number, line ids) so the day on show and an open question survive rotation.
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val day = selectedDay?.let(LocalDate::ofEpochDay)
    fun select(on: LocalDate?) { selectedDay = on?.toEpochDay() }
    LaunchedEffect(days) { if (day == null || day !in days) select(days.lastOrNull()) }
    val index = days.indexOf(day)
    val previous = days.getOrNull(index - 1)
    val next = if (index >= 0) days.getOrNull(index + 1) else null
    var picking by rememberSaveable { mutableStateOf(false) }
    // Ids of the lines waiting on the "already in your basket" question.
    var duplicateAsk by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    fun alreadyIn(lines: List<PurchaseLine>) = lines.mapNotNull { line -> viewModel.basketItemFor(line, products)?.let { line to it } }
    fun addLines(lines: List<PurchaseLine>) {
        if (alreadyIn(lines).isEmpty()) viewModel.addPurchaseLines(lines, products) else duplicateAsk = lines.map { it.id }
    }

    // One page per order day. Past the newest day the swipe carries on to the basket, as before.
    val pager = rememberPagerState(initialPage = (days.size - 1).coerceAtLeast(0)) { days.size }
    val currentDays by rememberUpdatedState(days)
    LaunchedEffect(index) {
        if (index < 0 || pager.currentPage == index) return@LaunchedEffect
        if ((pager.currentPage - index).let { it * it } > 1) pager.scrollToPage(index) else pager.animateScrollToPage(index)
    }
    // currentPage flips halfway through a swipe, so the arrows and calendar follow the finger, not the settle.
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { page -> currentDays.getOrNull(page)?.let { select(it) } } }

    val today = LocalDate.now()
    fun titleOf(on: LocalDate) = when (on) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> on.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
    }
    fun purchasesOn(on: LocalDate) = purchases.filter { dayOf(it.purchase.purchasedAt) == on }.sortedBy { it.purchase.purchasedAt }
    fun scansOn(on: LocalDate) = pendingScans.filter { dayOf(it.at) == on }
    fun summaryOf(page: Int): String {
        val on = days[page]
        val dayPurchases = purchasesOn(on)
        val total = dayPurchases.sumOf { it.purchase.totalCents }
        return listOfNotNull(if (total > 0) total.euros() else null, counted(dayPurchases.sumOf { it.lines.size } + scansOn(on).size, "item")).joinToString(" · ")
    }

    if (picking && day != null) {
        val selectable = days.map { it.toEpochDay() }.toSet()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = (utcTimeMillis / 86_400_000L) in selectable
                override fun isSelectableYear(year: Int) = days.any { it.year == year }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { select(LocalDate.ofEpochDay(it / 86_400_000L)) }; picking = false }) { Text("Show") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state, title = { Text("Days with orders", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }

    if (duplicateAsk.isNotEmpty()) {
        val lines = purchases.flatMap { it.lines }.filter { it.id in duplicateAsk }
        val already = alreadyIn(lines)
        // The basket changed under the question (or the lines are gone): nothing left to ask.
        if (already.isEmpty()) LaunchedEffect(Unit) { duplicateAsk = emptyList() }
        else DuplicateSheet(
            lines = lines,
            already = already,
            more = lines.singleOrNull()?.let { viewModel.purchaseQuantity(it) },
            onDismiss = { duplicateAsk = emptyList() },
            onAddNew = { duplicateAsk = emptyList(); viewModel.addPurchaseLines(lines, products) },
            onAddMore = { duplicateAsk = emptyList(); viewModel.addPurchaseLines(lines, products, bumpExisting = true) },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Orders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    val account by viewModel.ahAccount.collectAsState()
                    LaunchedEffect(Unit) { if (account == null) viewModel.loadAhAccount() }
                    val importing by viewModel.importingReceipts.collectAsState()
                    if (account?.connected == true) TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text("Get AH receipts") } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = { viewModel.importAhReceipts() }, enabled = !importing) {
                            if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "Get AH receipts")
                        }
                    }
                    if (day != null) IconButton(onClick = { picking = true }) { Icon(Icons.Default.CalendarMonth, contentDescription = "Pick a day") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            if (day == null) {
                EmptyState("No orders yet", "What you check off or scan in a store shows up here, by day.", icon = Icons.Default.ShoppingBag, modifier = Modifier.fillMaxSize())
                return@Column
            }
            // Day stepper: only days with an order are reachable, so the arrows never land on an empty day.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { select(previous) }, enabled = previous != null) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier day") }
                // The day labels ride along with the pages: each slides and fades by its distance from the scroll position.
                var labelWidth by remember { mutableIntStateOf(0) }
                Box(modifier = Modifier.weight(1f).height(56.dp).clipToBounds().onSizeChanged { labelWidth = it.width }, contentAlignment = Alignment.Center) {
                    for (page in (pager.currentPage - 1)..(pager.currentPage + 1)) {
                        if (page !in days.indices) continue
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().graphicsLayer {
                                val distance = page - (pager.currentPage + pager.currentPageOffsetFraction)
                                translationX = distance * labelWidth * 0.6f
                                alpha = (1f - abs(distance) * 1.6f).coerceIn(0f, 1f)
                            },
                        ) {
                            Text(titleOf(days[page]), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(summaryOf(page), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = { select(next) }, enabled = next != null) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later day") }
            }
            HorizontalDivider()
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { days[it].toEpochDay() }, verticalAlignment = Alignment.Top) { page ->
                val pageDay = days[page]
                val pageScans = scansOn(pageDay)
                LazyColumn(contentPadding = PaddingValues(start = Spacing.Screen, end = Spacing.Screen, top = 12.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
                    for (entry in purchasesOn(pageDay)) {
                        item(entry.purchase.id) { PurchaseCard(entry, products, stores, onAdd = { lines -> addLines(lines) }) }
                    }
                    if (pageScans.isNotEmpty()) {
                        item("pending") {
                            Card {
                                Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Scanned, waiting to sync", style = MaterialTheme.typography.titleSmall)
                                    }
                                    for (scan in pageScans) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StoreLogo(scan.store, stores, size = 20)
                                        Text(scan.product?.title ?: scan.label?.title ?: "Barcode ${scan.barcode}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asked when lines going back in the basket are already there: two choices, each named for what it does.
 * Swiping the sheet away is "neither".
 */
@Composable
private fun DuplicateSheet(lines: List<PurchaseLine>, already: List<Pair<PurchaseLine, BasketItem>>, more: Int?, onDismiss: () -> Unit, onAddNew: () -> Unit, onAddMore: () -> Unit) {
    val single = already.singleOrNull()?.takeIf { lines.size == 1 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBody(
            title = "Already in your basket",
            actions = {
                OutlinedButton(onClick = onAddNew) { Text(if (single != null) "Add as new item" else "Add as new items") }
                Button(onClick = onAddMore) { Text(if (single != null && more != null) "Add $more more" else "Add to existing") }
            },
        ) {
            Text(
                if (single != null) "“${single.second.text}” is in your basket ${single.second.quantity} ${if (single.second.quantity == 1) "time" else "times"}."
                else "${already.size} of these ${counted(lines.size, "item")} are in your basket: ${already.joinToString { it.second.text }}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PurchaseCard(entry: PurchaseWithLines, products: Map<String, Product>, stores: List<StoreInfo>, onAdd: (List<PurchaseLine>) -> Unit) {
    val purchase = entry.purchase
    // The device's own 12 or 24 hour setting.
    val time = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(purchase.purchasedAt))
    Card {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StoreLogo(purchase.store, stores, size = 36)
                Column(modifier = Modifier.weight(1f)) {
                    Text(storeName(purchase.store, stores), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(if (purchase.source == "shop") Icons.Default.ShoppingBag else Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((when (purchase.source) { "shop" -> "In store"; "ah" -> "AH receipt"; else -> "Receipt" }) + " · $time · ${counted(entry.lines.size, "item")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(purchase.totalCents.euros(), style = MaterialTheme.typography.titleMediumEmphasized)
            }
            HorizontalDivider()
            for (line in entry.lines) LineRow(line, products[line.productId], onAdd = { onAdd(listOf(line)) })
            if (entry.lines.size > 1) FilledTonalButton(onClick = { onAdd(entry.lines) }, modifier = Modifier.align(Alignment.End)) {
                IconLabel(Icons.Default.AddShoppingCart, "Add all to basket")
            }
        }
    }
}

@Composable
private fun LineRow(line: PurchaseLine, product: Product?, onAdd: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (product != null) ProductThumb(product, size = 40) else PhotoPlaceholder(40)
        Column(modifier = Modifier.weight(1f)) {
            // A line that only knows its barcode gets a name a person can read, with the code as the detail.
            val unknown = line.barcode != null && line.name == line.barcode
            val qty = if (line.quantity > 1.0) "${line.quantity.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}× " else ""
            Text(qty + if (unknown) "Unknown product" else line.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(product?.quantityText, line.dealText, if (unknown) "Barcode ${line.barcode}" else null).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (line.totalPriceCents > 0) Text(line.totalPriceCents.euros(), style = MaterialTheme.typography.bodyMediumEmphasized)
        FilledTonalButton(onClick = onAdd) { Text("Add") }
    }
}
