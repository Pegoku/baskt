package nl.baskt.ui.shop

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.BasketItem
import nl.baskt.data.Product
import nl.baskt.data.TripScan
import nl.baskt.data.dealPricing
import nl.baskt.data.euros
import nl.baskt.data.freeExtra
import nl.baskt.data.shortDealText
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.ProductSheet
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.rememberTranslation
import nl.baskt.ui.common.storeName

/**
 * In-store mode: big checkboxes, items grouped by aisle (product category), screen stays on. Checking an item off
 * records it on today's trip (it moves to the orders page); skipping it (row menu or a sideways swipe) leaves it
 * out of this trip, crossed out in the basket, until it is unskipped from the skipped button; the scan button
 * adds things picked up that were not in the basket (its row menu or a swipe takes a scan back); the card button
 * shows the store's loyalty card for the checkout.
 */
@Composable
fun ShopModeScreen(viewModel: AppViewModel, store: String, onBack: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tripScans by viewModel.tripScans.collectAsState()
    val tillCodes by viewModel.tillCodes.collectAsState()
    val barcodeKinds by viewModel.barcodeKinds.collectAsState()
    val tripEnds by viewModel.tripEnds.collectAsState()
    val history by viewModel.history.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadHistory() }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // When the order step assigned stores, shop only those items; otherwise everything matched here. Checking an
    // item off assigns it to this store, so bought items never count as "the order step ran".
    val assigned = items.any { !it.isGroup && !it.isBought && it.assignedStore != null }
    val candidates = items.filter { !it.isGroup && !it.isSkipped && (it.isBought || !assigned || it.assignedStore == store) }
    // Items without a product here stay on the trip as well (in their own section at the end), so they can still be
    // looked for on the shelf and checked off.
    val shopping = candidates.filter { !it.isBought || it.assignedStore == store }.map { item -> item to item.match(store)?.effective }
    val remaining = shopping.filter { !it.first.isBought }
    // The bar pairs "N left" with what those N still cost, not with the whole trip.
    val remainingTotal = remaining.sumOf { (item, product) -> (product?.priceCents ?: 0) * item.quantity }
    val (found, notFound) = shopping.partition { it.second != null }
    val categories = found.groupBy { it.second?.category?.substringBefore(" / ")?.ifBlank { null } }.toList().sortedBy { it.first ?: "￿" }
    // The stores name their aisles in Dutch: shown in the app's language like the deal texts, when it can be translated.
    val categoryNames = categories.mapNotNull { it.first }
    val translatedNames = rememberTranslation(categoryNames, enabled = categoryNames.isNotEmpty())?.takeIf { it.translated }?.texts
    val sections = categories.map { (name, entries) -> Triple(name ?: "other", name?.let { translatedNames?.getOrNull(categoryNames.indexOf(it)) ?: it } ?: "Other", entries) } +
        listOfNotNull(notFound.takeIf { it.isNotEmpty() }?.let { Triple("not-found", "Not found at this store", it) })
    val card = settings?.loyaltyCards?.get(store)
    val relay = settings?.tillRelay == true
    LaunchedEffect(store, relay) { if (relay) viewModel.loadBarcodeKinds(store) }
    val tripWindow = 12 * 60 * 60 * 1000L
    val codesHere = if (relay) tillCodes.filter { it.store == store && System.currentTimeMillis() - it.at < tripWindow } else emptyList()
    val codedItems = codesHere.mapNotNull { it.itemId }.toSet()
    // A scan the phone already put on a basket item is shown on that item, not again as an extra.
    val scansHere = tripScans.filter { scan -> scan.store == store && System.currentTimeMillis() - scan.at < tripWindow && codesHere.none { it.scanId == scan.id && it.itemId != null } }
    // Items ticked off on an earlier visit still show here (bought at this store), but nothing about them is owed to
    // this trip's till: they are "previously bought", not "not scanned". A visit ends when it is paid for (Paid,
    // self-scan checkout, or a receipt that lists the item) or after a long pause.
    val paidAt = tripEnds[store] ?: 0L
    val paidItems = history?.purchases.orEmpty()
        .filter { it.purchase.store == store && it.purchase.source != "shop" }
        .flatMap { entry -> entry.lines.mapNotNull { it.itemId } }.toSet()
    val tripStart = maxOf(
        paidAt + 1,
        tripStartedAt(shopping.mapNotNull { it.first.boughtAt } + tripScans.filter { it.store == store }.map { it.at } + tillCodes.filter { it.store == store }.map { it.at }),
    ).coerceAtMost(System.currentTimeMillis())
    fun earlier(item: BasketItem) = item.isBought && (item.id in paidItems || (item.boughtAt ?: 0) < tripStart)
    fun kindOf(item: BasketItem, product: Product?) = barcodeKinds.firstOrNull { it.store == store && product != null && it.productId == product.id }?.kind
        ?: barcodeKinds.firstOrNull { it.store == store && it.itemText.equals(item.text, ignoreCase = true) }?.kind
    // Everything that is open on top of the list is saved, so turning the phone at the till keeps the barcode up.
    var linkingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTill by rememberSaveable { mutableStateOf(false) }
    if (showTill) TillRelayDialog(
        card = card,
        codes = nl.baskt.data.tillSequence(codesHere, items),
        unscanned = shopping.map { it.first }.filter { it.isBought && it.id !in codedItems && !earlier(it) },
        intervalMs = settings?.tillRelayIntervalMs ?: 1200,
        onInterval = { viewModel.setTillRelayInterval(it) },
        onNoBarcode = { viewModel.reportNoBarcode(store, it) },
        onPaid = { viewModel.clearTillCodes(store); viewModel.endTrip(store); showTill = false },
        onDismiss = { showTill = false },
    )
    scansHere.firstOrNull { it.id == linkingId }?.let { scan ->
        LinkScanSheet(scan, remaining.map { it.first }, onDismiss = { linkingId = null }) { item -> viewModel.linkScan(scan, item, store); linkingId = null }
    }
    val skipped = items.filter { !it.isGroup && it.isSkipped }
    var showSkipped by rememberSaveable { mutableStateOf(false) }

    val cardName = "${storeName(store, stores)} card"
    var showCard by rememberSaveable { mutableStateOf(false) }
    var editCard by rememberSaveable { mutableStateOf(false) }
    if (showCard && card != null) LoyaltyCardDialog(store, stores, card, onDismiss = { showCard = false }, onEdit = { showCard = false; editCard = true })
    if (editCard) LoyaltyCardEditor(store, stores, card, onDismiss = { editCard = false }) { number -> viewModel.saveLoyaltyCard(store, number); editCard = false; if (number != null) showCard = true }

    /** Skipping and unchecking are both one tap or swipe away, so both act at once and offer an undo. */
    // The product whose full sheet (large picture, price history, details) is open, from a row's picture or menu.
    var viewing by remember { mutableStateOf<Product?>(null) }
    viewing?.let { ProductSheet(it) { viewing = null } }

    fun skip(item: BasketItem) {
        viewModel.skip(item, true)
        viewModel.notifyUndo("Skipped “${item.text}”") { viewModel.restore(item, store) }
    }

    fun toggle(item: BasketItem, product: Product?) {
        if (item.isBought) {
            viewModel.unbuy(item)
            viewModel.notifyUndo("Unchecked “${item.text}”") { viewModel.markBought(item, store, product) }
        } else {
            viewModel.markBought(item, store, product)
        }
    }

    // AH self-scan (experimental): while a trip runs, every scan goes to the AH basket first, then to the baskt basket.
    val selfScanOn = store == "AH" && settings?.selfScan == true
    val selfScan by viewModel.selfScan.collectAsState()
    val tickedByBarcode by viewModel.tickedByBarcode.collectAsState()
    var showSelfScanBasket by rememberSaveable { mutableStateOf(false) }
    var confirmStopSelfScan by rememberSaveable { mutableStateOf(false) }
    val openScanner = nl.baskt.ui.common.rememberBarcodeScannerWithFormat { raw, format ->
        if (selfScanOn && selfScan.active) viewModel.selfScanItem(store, raw, format.takeIf { relay })
        else viewModel.scanItem(store, raw, format.takeIf { relay })
    }
    if (selfScanOn && showSelfScanBasket && selfScan.active) SelfScanBasketSheet(
        state = selfScan,
        listItems = items,
        remaining = remaining.map { it.first },
        tripScans = tripScans.filter { it.store == store && System.currentTimeMillis() - it.at < tripWindow },
        tickedByBarcode = tickedByBarcode,
        onQuantity = { barcode, quantity -> viewModel.setSelfScanQuantity(store, barcode, quantity) },
        onRescan = { openScanner() },
        onDismissIssue = { viewModel.dismissSelfScanIssue(it) },
        onDismissNotice = { viewModel.dismissSelfScanNotice() },
        onCheckout = { viewModel.selfScanCheckout() },
        // The question is asked after the sheet has closed, so a dialog never sits on top of a sheet.
        onStop = { showSelfScanBasket = false; confirmStopSelfScan = true },
        onDismiss = { showSelfScanBasket = false },
    )
    if (confirmStopSelfScan) ConfirmDialog(
        title = "Stop self-scan?",
        body = if ((selfScan.basket?.products?.size ?: 0) > 0) "Scanned items stay in the AH basket until you start again in this store." else "You can start again any time on the store Wi-Fi.",
        confirmLabel = "Stop",
        dismissLabel = "Keep scanning",
        onDismiss = { confirmStopSelfScan = false },
        onConfirm = { viewModel.stopSelfScan() },
    )
    if (selfScanOn) selfScan.checkout?.let { checkout ->
        SelfScanCheckoutDialog(checkout, card, onRetry = { viewModel.selfScanCheckout() }, onClose = { showSelfScanBasket = false; viewModel.closeSelfScanCheckout() })
    }

    fun removeScan(scan: TripScan) {
        viewModel.removeScan(scan)
        viewModel.notifyUndo("Removed “${scan.product?.title ?: scan.label?.title ?: scan.barcode}”") { viewModel.restoreScan(scan) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoreBadge(store, stores)
                        Text(
                            listOfNotNull("${remaining.size} left", remainingTotal.takeIf { it > 0 }?.euros()).joinToString(" · "),
                            style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (skipped.isNotEmpty() || showSkipped) {
                        val label = if (showSkipped) "Hide skipped" else "Show skipped"
                        Tip(label) {
                            IconToggleButton(checked = showSkipped, onCheckedChange = { showSkipped = it }) {
                                BadgedBox(badge = { if (skipped.isNotEmpty()) Badge { Text("${skipped.size}") } }) { Icon(Icons.Default.RemoveShoppingCart, contentDescription = label) }
                            }
                        }
                    }
                    if (relay) Tip("At the till") {
                        IconButton(onClick = { showTill = true }) {
                            BadgedBox(badge = { if (codesHere.isNotEmpty()) Badge { Text("${codesHere.size}") } }) { Icon(Icons.Default.PointOfSale, contentDescription = "Show codes at the till") }
                        }
                    }
                    Tip(cardName) {
                        IconButton(onClick = { if (card != null) showCard = true else editCard = true }) {
                            Icon(Icons.Default.CardMembership, contentDescription = if (card != null) "Show $cardName" else "Add $cardName")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selfScanOn) SelfScanBar(
                state = selfScan,
                hasCard = card != null,
                onStart = { viewModel.startSelfScan() },
                onAddCard = { editCard = true },
                onStop = { viewModel.stopSelfScan() },
                onWifiSettings = {
                    val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                    else android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    runCatching { context.startActivity(intent) }
                },
                onOpenBasket = { viewModel.refreshSelfScan(); showSelfScanBasket = true },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { openScanner() }, icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) }, text = { Text("Scan") })
        },
    ) { padding ->
        // The Scaffold's padding already ends above the self-scan bar; the list adds the room the Scan button takes.
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val done = shopping.size - remaining.size
            LinearWavyProgressIndicator(progress = { if (shopping.isEmpty()) 0f else done.toFloat() / shopping.size }, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen, vertical = 8.dp))
            if (!showSkipped && shopping.isEmpty() && scansHere.isEmpty()) EmptyState(
                title = "Nothing to buy here",
                body = "Scan anything you pick up to add it.",
                icon = Icons.Default.ShoppingCart,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            else LazyColumn(contentPadding = PaddingValues(bottom = Spacing.FabClearance)) {
                // Said once for the whole list, and only until the first scan shows the shopper knows how.
                if (relay && codesHere.isEmpty() && remaining.isNotEmpty()) item("scan-hint") {
                    Text("Scan each item as you pick it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.Screen))
                }
                if (showSkipped) {
                    item("h-skipped") { TripHeader("Skipped") }
                    if (skipped.isEmpty()) item("skipped-empty") {
                        Text("Nothing skipped", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.Screen, vertical = 8.dp))
                    }
                    items(skipped, key = { "skipped-${it.id}" }) { item ->
                        val product = item.match(store)?.effective
                        TripRow(
                            imageUrl = product?.imageUrl,
                            title = item.text,
                            subtitle = product?.quantityText,
                            struck = true,
                            modifier = Modifier.animateItem(),
                            trailing = { TextButton(onClick = { viewModel.restore(item, store) }) { Text("Unskip") } },
                        )
                    }
                }
                for ((sectionKey, section, entries) in sections) {
                    item("h-$sectionKey") { TripHeader(section) }
                    items(entries, key = { it.first.id }) { (item, product) ->
                        // Checking an item off records the purchase and puts the product in stock; checking it again undoes that.
                        // A sideways swipe (or the row menu) skips the item instead: off this trip, crossed out in the basket.
                        key(item.id) {
                            val dismiss = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.Settled) false else { skip(item); true }
                                },
                            )
                            SwipeToDismissBox(
                                state = dismiss,
                                modifier = Modifier.animateItem(),
                                enableDismissFromStartToEnd = !item.isBought,
                                enableDismissFromEndToStart = !item.isBought,
                                backgroundContent = { SwipeBackground(dismiss.dismissDirection, "Skip", Icons.Default.RemoveShoppingCart, destructive = false) },
                            ) {
                                Surface {
                                    val actions = listOfNotNull(
                                        product?.let { "View product" to { viewing = it } },
                                        if (item.isBought) null else "Skip" to { skip(item) },
                                    )
                                    TripRow(
                                        imageUrl = product?.imageUrl,
                                        title = (if (item.quantity > 1) "${item.quantity}× " else "") + (product?.title ?: item.text),
                                        subtitle = when {
                                            earlier(item) -> "Previously bought"
                                            item.isBought -> "Bought"
                                            product == null -> null
                                            differs(product.title, item.text) -> "${product.quantityText} · for “${item.text}”"
                                            else -> product.quantityText
                                        },
                                        checked = item.isBought,
                                        struck = item.isBought,
                                        onOpen = product?.let { { viewing = it } },
                                        price = product?.let { (it.priceCents * item.quantity).euros() },
                                        // The whole row is the checkbox: one target, one action, so a tap can never toggle twice.
                                        modifier = Modifier
                                            .toggleable(value = item.isBought, role = Role.Checkbox, onValueChange = { toggle(item, product) })
                                            .semantics { customActions = actions.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } } },
                                        tags = {
                                            // The deal on the shelf label (1+1 gratis, 15% korting), so it is taken at the till: one line, in the app's language.
                                            val dealText = product?.dealText?.let(::shortDealText)?.takeIf { it.isNotEmpty() && !item.isBought && !it.matches(PRICE_ONLY_DEAL) }
                                            if (dealText != null) {
                                                // "-35%" has no words to translate; "2 voor 3,50" and "1+1 gratis" do.
                                                val shown = rememberTranslation(listOf(dealText), enabled = dealText.any(Char::isLetter))?.takeIf { it.translated }?.texts?.firstOrNull() ?: dealText
                                                val free = product.freeExtra(item.quantity)
                                                val extra = if (free != null) "+$free free" else product.dealPricing()?.let { "${it.eachCents.euros()} each" }
                                                Tag(listOfNotNull(shown, extra).joinToString(" · "), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                                            }
                                            if (relay) TillTag(item, coded = item.id in codedItems, earlier = earlier(item), kind = kindOf(item, product))
                                        },
                                        trailing = if (actions.isEmpty()) null else ({ RowMenu("More for ${item.text}", actions) }),
                                    )
                                }
                            }
                        }
                    }
                }
                if (scansHere.isNotEmpty()) {
                    item("h-scanned") { TripHeader("Scanned here") }
                    // A sideways swipe or the row menu takes a scan back (wrong code, scanned twice); a code no store
                    // knows also offers a second try, since a crumpled or glared barcode often reads as something else.
                    items(scansHere, key = { it.id }) { scan ->
                        val dismiss = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.Settled) false else { removeScan(scan); true }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismiss,
                            modifier = Modifier.animateItem(),
                            backgroundContent = { SwipeBackground(dismiss.dismissDirection, "Remove", Icons.Default.Delete, destructive = true) },
                        ) {
                            Surface {
                                val product = scan.product
                                val label = scan.label
                                val title = product?.title ?: label?.title ?: scan.barcode
                                val actions = listOfNotNull(
                                    product?.let { "View product" to { viewing = it } },
                                    // The relay needs every code on its item: a weighed label no store knows is linked by hand.
                                    if (relay && remaining.isNotEmpty()) "Link to item" to { linkingId = scan.id } else null,
                                    if (product == null && scan.synced) "Scan again" to { removeScan(scan); openScanner() } else null,
                                    "Remove" to { removeScan(scan) },
                                )
                                TripRow(
                                    imageUrl = product?.imageUrl ?: label?.imageUrl,
                                    title = title,
                                    subtitle = when {
                                        product != null -> product.quantityText
                                        label != null && !scan.synced -> listOfNotNull(label.quantityText, "No price until you’re online").joinToString(" · ")
                                        label != null -> listOfNotNull(label.quantityText, "No price at this store").joinToString(" · ")
                                        scan.synced -> "Unknown product"
                                        else -> "Waiting for connection"
                                    },
                                    price = product?.priceCents?.euros(),
                                    onOpen = product?.let { { viewing = it } },
                                    modifier = Modifier.semantics { customActions = actions.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } } },
                                    trailing = { RowMenu("More for $title", actions) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A top-bar icon's name on long-press or hover, for the icons whose meaning is not obvious. */
@Composable
private fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(text) } }, state = rememberTooltipState(), content = content)
}

@Composable
private fun TripHeader(text: String) = SectionHeader(text, modifier = Modifier.padding(start = Spacing.Screen, end = Spacing.Screen, top = 16.dp, bottom = 4.dp))

/**
 * The one row of the trip list, for items, skipped items and scans alike: optional checkbox, picture, title with
 * a line of facts and tags under it, price, and the row's own action at the end. The checkbox only shows the
 * state; the caller makes the whole row the target through [modifier].
 */
@Composable
private fun TripRow(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    struck: Boolean = false,
    price: String? = null,
    onOpen: (() -> Unit)? = null,
    tags: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Spacing.Screen, end = if (trailing == null) Spacing.Screen else 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
        // The picture is its own button: it opens the product, while the rest of the row stays the checkbox.
        ItemThumb(
            imageUrl, contentDescription = if (onOpen != null) "View $title" else null, size = 48,
            modifier = if (onOpen != null) Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "View product", onClick = onOpen) else Modifier,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, textDecoration = if (struck) TextDecoration.LineThrough else null, color = if (struck) quiet else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            tags?.invoke()
        }
        if (price != null) Text(price, style = MaterialTheme.typography.titleMediumEmphasized, color = if (struck) quiet else MaterialTheme.colorScheme.onSurface)
        trailing?.invoke()
    }
}

/** The visible way to what a swipe does (skip an item, remove a scan), plus the row's other actions. */
@Composable
private fun RowMenu(label: String, actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((text, action) in actions) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() })
        }
    }
}

/** Follows the row as it slides away and names what the swipe does: skipping keeps the item, removing drops the scan. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean) {
    val colors = MaterialTheme.colorScheme
    val content = if (destructive) colors.onErrorContainer else colors.onSecondaryContainer
    Box(
        modifier = Modifier.fillMaxSize().background(if (destructive) colors.errorContainer else colors.secondaryContainer).padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = content)
            Text(label, color = content, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "VOOR 0.99": the deal price is already the price on the row, so the label would only repeat it. */
private val PRICE_ONLY_DEAL = Regex("""(?i)^(nu\s+)?voor\s*€?\s*\d+([.,]\d{2})?$""")

/**
 * Whether the product's name says something else than what was written in the basket ("AH halfvolle melk" for
 * "milk"): only then is the written text worth repeating under the product.
 */
private fun differs(productTitle: String, itemText: String): Boolean {
    val words = itemText.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
    return words.isNotEmpty() && words.none { productTitle.lowercase().contains(it) }
}

/**
 * What the till relay knows about this item, as a short fact: scanned already, checked off without a scan,
 * weighed (its label is scanned after weighing), or without a barcode. An ordinary pack says nothing: the
 * instruction to scan is given once above the list.
 */
@Composable
private fun TillTag(item: BasketItem, coded: Boolean, earlier: Boolean, kind: String?) {
    val colors = MaterialTheme.colorScheme
    when {
        coded -> Tag("Scanned", color = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
        earlier -> {}
        item.isBought -> Tag("Not scanned", color = colors.errorContainer, contentColor = colors.onErrorContainer)
        kind == "label" -> Tag("Weigh it, then scan the label")
        kind == "none" -> Tag("No barcode")
    }
}

/**
 * When the trip at this store began: the start of the latest run of activity (ticks, scans, till codes) with no
 * gap longer than [gap] in it, reaching up to now. With nothing recent the trip begins now, so everything bought
 * before counts as an earlier visit. Leaving the screen and coming back mid-trip keeps the same start.
 */
internal fun tripStartedAt(times: List<Long>, now: Long = System.currentTimeMillis(), gap: Long = 2 * 60 * 60 * 1000L): Long {
    var start = now
    for (t in times.filter { it <= now }.sortedDescending()) {
        if (start - t > gap) break
        start = t
    }
    return start
}

/** Says which basket item a scan stands for, typically a weighed label no store can look up. */
@Composable
private fun LinkScanSheet(scan: TripScan, open: List<BasketItem>, onDismiss: () -> Unit, onPick: (BasketItem) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBody(title = "Which item is this?", actions = { TextButton(onClick = onDismiss) { Text("Cancel") } }) {
            Text(scan.product?.title ?: scan.label?.title ?: scan.barcode, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                for (item in open) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(item) }.heightIn(min = 56.dp).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ItemThumb(item.match(scan.store)?.effective?.imageUrl, contentDescription = null)
                        Text((if (item.quantity > 1) "${item.quantity}× " else "") + item.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
