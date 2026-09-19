package nl.baskt.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.PurchaseLine
import nl.baskt.data.PurchaseWithLines
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.StoreLogo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private fun dayOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Previous orders, one day at a time: what was ticked off or scanned in the store (and scanned receipts), grouped
 * by store. The arrows and the calendar only land on days something was bought.
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
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(days) { if (selected == null || selected !in days) selected = days.lastOrNull() }
    val day = selected
    val index = days.indexOf(day)
    val previous = days.getOrNull(index - 1)
    val next = if (index >= 0) days.getOrNull(index + 1) else null
    var picking by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val dayTitle = when (day) {
        null -> "No orders yet"
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
    }
    val dayPurchases = purchases.filter { day != null && dayOf(it.purchase.purchasedAt) == day }.sortedBy { it.purchase.purchasedAt }
    val dayScans = pendingScans.filter { day != null && dayOf(it.at) == day }
    val dayTotal = dayPurchases.sumOf { it.purchase.totalCents }

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
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { selected = LocalDate.ofEpochDay(it / 86_400_000L) }; picking = false }) { Text("Show") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state, title = { Text("Days with orders", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Previous orders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the list") } },
                actions = { IconButton(onClick = { picking = true }, enabled = day != null) { Icon(Icons.Default.CalendarMonth, contentDescription = "Pick a day") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            nl.baskt.ui.common.OfflineBanner(viewModel, needsServer = "Showing the orders known so far.")
            // Day stepper: only days with an order are reachable, so the arrows never land on an empty day.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = previous }, enabled = previous != null) { Icon(Icons.Default.ChevronLeft, contentDescription = "Earlier order day") }
                TextButton(onClick = { picking = true }, enabled = day != null, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dayTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (day != null) Text(
                            listOfNotNull(if (dayTotal > 0) dayTotal.euros() else null, "${dayPurchases.sumOf { it.lines.size } + dayScans.size} items", if (days.size > 1) "${index + 1} of ${days.size} days" else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { selected = next }, enabled = next != null) { Icon(Icons.Default.ChevronRight, contentDescription = "Later order day") }
            }
            HorizontalDivider()
            if (day == null) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nothing bought yet", style = MaterialTheme.typography.titleMedium)
                        Text("Items you tick off or scan in the in-store checklist land here, grouped by day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (entry in dayPurchases) {
                        item(entry.purchase.id) { PurchaseCard(entry, products, stores) }
                    }
                    if (dayScans.isNotEmpty()) {
                        item("pending") {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Scanned, waiting for connection", style = MaterialTheme.typography.titleSmall)
                                    }
                                    for (scan in dayScans) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StoreLogo(scan.store, stores, size = 20)
                                        Text(scan.barcode, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    item("footer") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PurchaseCard(entry: PurchaseWithLines, products: Map<String, nl.baskt.data.Product>, stores: List<nl.baskt.data.StoreInfo>) {
    val purchase = entry.purchase
    val time = Instant.ofEpochMilli(purchase.purchasedAt).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StoreLogo(purchase.store, stores, size = 36)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stores.firstOrNull { it.code == purchase.store }?.name ?: purchase.store, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(if (purchase.source == "shop") Icons.Default.ShoppingBag else Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((if (purchase.source == "shop") "In store" else "Receipt") + " · $time · ${entry.lines.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(purchase.totalCents.euros(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
            for (line in entry.lines) LineRow(line, products[line.productId])
        }
    }
}

@Composable
private fun LineRow(line: PurchaseLine, product: nl.baskt.data.Product?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (product != null) ProductThumb(product, size = 40)
        else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(modifier = Modifier.weight(1f)) {
            val qty = if (line.quantity > 1.0) "${line.quantity.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}× " else ""
            Text(qty + line.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(product?.quantityText, line.dealText, if (line.barcode != null && line.name == line.barcode) "Unknown product · barcode only" else null).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (line.totalPriceCents > 0) line.totalPriceCents.euros() else "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
