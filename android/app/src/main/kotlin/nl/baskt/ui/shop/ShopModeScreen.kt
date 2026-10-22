package nl.baskt.ui.shop

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.BasketItem
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.StoreBadge

/**
 * In-store mode: big checkboxes, items grouped by aisle (product category), screen stays on. Ticking records the
 * item on today's trip (it moves to the orders page); swiping a row sideways archives it for this trip (it is
 * crossed out on the main list and can be brought back from the archive button); the scan button adds things
 * picked up that were not on the list; the card button shows the store's loyalty card for the checkout.
 */
@Composable
fun ShopModeScreen(viewModel: AppViewModel, store: String, onBack: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tripScans by viewModel.tripScans.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // When the order step assigned stores, shop only those items; otherwise everything matched here. Ticking an
    // item assigns it to this store, so bought items never count as "the order step ran".
    val assigned = items.any { !it.isGroup && !it.isBought && it.assignedStore != null }
    val candidates = items.filter { !it.isGroup && !it.isSkipped && (it.isBought || !assigned || it.assignedStore == store) }
    // Items without a product here stay on the trip as well (in their own section at the end), so they can still be
    // looked for on the shelf and ticked off.
    val shopping = candidates.filter { !it.isBought || it.assignedStore == store }.map { item -> item to item.match(store)?.effective }
    val remaining = shopping.filter { !it.first.isBought }
    val total = shopping.sumOf { (item, product) -> (product?.priceCents ?: 0) * item.quantity }
    val (found, notFound) = shopping.partition { it.second != null }
    val sections = found.groupBy { it.second?.category?.substringBefore(" / ")?.ifBlank { null } ?: "Other" }.toSortedMap().toList() +
        listOfNotNull(notFound.takeIf { it.isNotEmpty() }?.let { "Not found at this store" to it })
    val scansHere = tripScans.filter { it.store == store && System.currentTimeMillis() - it.at < 12 * 60 * 60 * 1000 }
    val archived = items.filter { !it.isGroup && it.isSkipped }
    var showArchived by remember { mutableStateOf(false) }

    val card = settings?.loyaltyCards?.get(store)
    var showCard by remember { mutableStateOf(false) }
    var editCard by remember { mutableStateOf(false) }
    if (showCard && card != null) LoyaltyCardDialog(store, stores, card, onDismiss = { showCard = false }, onEdit = { showCard = false; editCard = true })
    if (editCard) LoyaltyCardEditor(store, stores, card, onDismiss = { editCard = false }) { number -> viewModel.saveLoyaltyCard(store, number); editCard = false; if (number != null) showCard = true }

    /** Archiving and un-ticking are both one swipe or tap away, so both offer an immediate undo. */
    fun undoable(message: String, undo: () -> Unit) = scope.launch {
        val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short, withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) undo()
    }

    fun archive(item: BasketItem) {
        viewModel.skip(item, true)
        undoable("Archived “${item.text}”") { viewModel.restore(item, store) }
    }

    // One tap can reach both the checkbox and the row underneath it; without this an item could be ticked and
    // immediately un-ticked again, which silently drops it off the trip.
    val lastToggle = remember { mutableMapOf<String, Long>() }
    fun toggle(item: BasketItem, product: nl.baskt.data.Product?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastToggle[item.id] ?: 0L) < 700) return
        lastToggle[item.id] = now
        if (item.isBought) {
            viewModel.unbuy(item)
            undoable("Unticked “${item.text}”") { viewModel.markBought(item, store, product) }
        } else {
            viewModel.markBought(item, store, product)
        }
    }

    val scan = nl.baskt.ui.common.rememberBarcodeScanner { viewModel.scanItem(store, it) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreBadge(store, stores); Text("${remaining.size} left · ${total.euros()}") } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (archived.isNotEmpty() || showArchived) IconButton(onClick = { showArchived = !showArchived }) {
                        BadgedBox(badge = { if (archived.isNotEmpty()) Badge { Text("${archived.size}") } }) {
                            Icon(
                                if (showArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (showArchived) "Hide archived items" else "Show ${archived.size} archived items",
                                tint = if (showArchived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { if (card != null) showCard = true else editCard = true }) {
                        Icon(Icons.Default.CardMembership, contentDescription = if (card != null) "Show loyalty card" else "Add loyalty card", tint = if (card != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { scan() }, icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) }, text = { Text("Scan") })
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val done = shopping.size - remaining.size
            LinearWavyProgressIndicator(progress = { if (shopping.isEmpty()) 0f else done.toFloat() / shopping.size }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (showArchived) {
                    item("h-archived") {
                        Text("Archived · not this trip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    if (archived.isEmpty()) item("archived-empty") {
                        Text("Swipe an item sideways to archive it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    items(archived, key = { "archived-${it.id}" }) { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(item.text, style = MaterialTheme.typography.titleMedium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.match(store)?.effective?.quantityText ?: "Archived this trip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewModel.restore(item, store) }) { Text("Restore") }
                        }
                        HorizontalDivider()
                    }
                }
                for ((section, entries) in sections) {
                    item("h-$section") {
                        Text(section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(entries, key = { it.first.id }) { (item, product) ->
                        // Buying (ticking) here records the purchase and puts the product in stock; ticking again undoes it.
                        // A sideways swipe archives the item instead: off this trip, crossed out on the main list.
                        key(item.id) {
                            val dismiss = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.Settled) false else { archive(item); true }
                                },
                            )
                            SwipeToDismissBox(
                                state = dismiss,
                                modifier = Modifier.animateItem(),
                                enableDismissFromStartToEnd = !item.isBought,
                                enableDismissFromEndToStart = !item.isBought,
                                backgroundContent = { ArchiveBackground(dismiss.dismissDirection) },
                            ) {
                                Surface {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { toggle(item, product) }.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Checkbox(checked = item.isBought, onCheckedChange = { toggle(item, product) }, modifier = Modifier.padding(4.dp))
                                        if (product != null) ProductThumb(product, size = 44)
                                        else Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(44.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                (if (item.quantity > 1) "${item.quantity}× " else "") + (product?.title ?: item.text),
                                                style = MaterialTheme.typography.titleMedium,
                                                textDecoration = if (item.isBought) TextDecoration.LineThrough else null,
                                                color = if (item.isBought) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (item.isBought) Icon(Icons.Default.Kitchen, contentDescription = "In stock", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    when {
                                                        item.isBought -> "Bought · in stock · in today's order"
                                                        product == null -> "No product matched here · tick it if you find it"
                                                        else -> "${product.quantityText} · for “${item.text}”"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (product != null) Text((product.priceCents * item.quantity).euros(), fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (scansHere.isNotEmpty()) {
                    item("h-scanned") {
                        Text("Scanned here", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(scansHere, key = { it.id }) { scan ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val product = scan.product
                            val label = scan.label
                            if (product != null) ProductThumb(product, size = 44)
                            else if (label?.imageUrl != null) Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(44.dp)) {
                                coil3.compose.AsyncImage(model = label.imageUrl, contentDescription = label.title, modifier = Modifier.padding(4.dp))
                            }
                            else Icon(if (scan.synced) Icons.Default.QrCodeScanner else Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product?.title ?: label?.title ?: scan.barcode, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        product != null -> "${product.quantityText} · in today's order and in stock"
                                        label != null && !scan.synced -> listOfNotNull(label.quantityText, "recognised by your phone · price follows when online").joinToString(" · ")
                                        label != null -> listOfNotNull(label.quantityText, "no store price for this code").joinToString(" · ")
                                        scan.synced -> "Unknown product · saved by its barcode"
                                        else -> "Waiting for connection · stocked once online"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (product != null) Text(product.priceCents.euros(), fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Follows the row as it slides away, so it is obvious the swipe archives instead of deleting. */
@Composable
private fun ArchiveBackground(direction: SwipeToDismissBoxValue) {
    val alignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("Archive", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
        }
    }
}
