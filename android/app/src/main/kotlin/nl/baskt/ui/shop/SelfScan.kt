package nl.baskt.ui.shop

import nl.baskt.ui.common.IconLabel
import androidx.compose.material3.ButtonDefaults
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nl.baskt.data.BasketItem
import nl.baskt.data.SelfScanCheckout
import nl.baskt.data.SelfScanPhase
import nl.baskt.data.SelfScanState
import nl.baskt.data.SsItem
import nl.baskt.data.TripScan
import nl.baskt.data.euros
import nl.baskt.data.productGtin
import nl.baskt.data.tags
import nl.baskt.data.weightText
import nl.baskt.ui.common.ProductThumb

/** AH's messages are Dutch; shown in the app's language when the server can translate them. */
@Composable
private fun translated(text: String?): String? {
    if (text.isNullOrBlank()) return text
    return nl.baskt.ui.common.rememberTranslation(listOf(text))?.takeIf { it.translated }?.texts?.firstOrNull() ?: text
}

/**
 * The self-scan strip at the bottom of AH shop mode: start it, what connecting is doing, how to get on the store
 * Wi-Fi, and once running the AH basket's live total (tap for the basket).
 */
@Composable
fun SelfScanBar(
    state: SelfScanState,
    hasCard: Boolean,
    onStart: () -> Unit,
    onAddCard: () -> Unit,
    onStop: () -> Unit,
    onWifiSettings: () -> Unit,
    onOpenBasket: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val active = state.active
    Surface(
        color = if (active) colors.primaryContainer else colors.surfaceContainerHigh,
        shape = androidx.compose.material3.BottomSheetDefaults.ExpandedShape,
        modifier = Modifier.fillMaxWidth().then(if (active) Modifier.clickable(onClick = onOpenBasket) else Modifier),
    ) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val onColor = if (active) colors.onPrimaryContainer else colors.onSurface
            when (state.phase) {
                SelfScanPhase.CONNECTING -> LoadingIndicator(modifier = Modifier.size(24.dp))
                SelfScanPhase.NO_WIFI, SelfScanPhase.FAILED -> Icon(Icons.Default.WifiOff, contentDescription = null, tint = colors.error)
                SelfScanPhase.ACTIVE -> Icon(if (state.connectionLost) Icons.Default.WifiOff else Icons.Default.ShoppingCartCheckout, contentDescription = null, tint = if (state.connectionLost) colors.error else onColor)
                SelfScanPhase.OFF -> Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = colors.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                val basket = state.basket
                val (title, detail) = when (state.phase) {
                    SelfScanPhase.OFF -> "AH Zelfscan" to (if (hasCard) "Scan into the AH basket, pay at a betaalpaal" else "Add your Bonuskaart to start")
                    SelfScanPhase.CONNECTING -> "AH Zelfscan" to (state.step ?: "Connecting")
                    SelfScanPhase.NO_WIFI -> "Join the store Wi-Fi" to "The AH server is only reachable on this store's Wi-Fi. baskt carries on as soon as you're on it."
                    SelfScanPhase.FAILED -> "Self-scan did not start" to (translated(state.error) ?: "No answer from the store server")
                    SelfScanPhase.ACTIVE -> {
                        val issue = state.issues.lastOrNull()
                        val head = "${basket?.itemCount ?: 0} items · ${(basket?.toPayCents ?: 0).euros()}"
                        head to when {
                            state.connectionLost -> "No connection to the store server · reconnecting"
                            issue != null -> "Not added: ${translated(issue.title)}"
                            state.sending.isNotEmpty() -> "Sending to AH…"
                            (basket?.savedCents ?: 0) > 0 -> "You save ${basket!!.savedCents.euros()} · tap for the basket"
                            else -> "AH basket${state.store?.storeNumber?.let { " · store $it" } ?: ""} · tap to see it"
                        }
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, color = onColor)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active && (state.connectionLost || state.issues.isNotEmpty())) colors.error else if (active) onColor else colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            when (state.phase) {
                SelfScanPhase.OFF -> if (hasCard) Button(onClick = onStart) { Text("Start") } else FilledTonalButton(onClick = onAddCard) { Text("Add card") }
                SelfScanPhase.CONNECTING -> TextButton(onClick = onStop) { Text("Cancel") }
                SelfScanPhase.NO_WIFI -> {
                    FilledTonalButton(onClick = onWifiSettings, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { IconLabel(Icons.Default.Wifi, "Wi-Fi") }
                    IconButton(onClick = onStart) { Icon(Icons.Default.Refresh, contentDescription = "Try again") }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Close, contentDescription = "Stop self-scan") }
                }
                SelfScanPhase.FAILED -> {
                    Button(onClick = onStart) { Text("Try again") }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Close, contentDescription = "Stop self-scan") }
                }
                SelfScanPhase.ACTIVE -> if (state.sending.isNotEmpty()) LoadingIndicator(modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Where a line of the AH basket stands against the baskt list. */
private sealed interface ListLink {
    data class OnList(val item: BasketItem) : ListLink
    data class Extra(val scan: TripScan) : ListLink
    data object Unknown : ListLink
}

/**
 * The live AH basket: each line with its quantity, price and Bonus, AH's refusals, the savings, what is still on
 * the baskt list, and the way to the betaalpaal.
 */
@Composable
fun SelfScanBasketSheet(
    state: SelfScanState,
    listItems: List<BasketItem>,
    remaining: List<BasketItem>,
    tripScans: List<TripScan>,
    tickedByBarcode: Map<String, String>,
    onQuantity: (String, Int) -> Unit,
    onRescan: () -> Unit,
    onDismissIssue: (Long) -> Unit,
    onDismissNotice: () -> Unit,
    onCheckout: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val basket = state.basket
    val colors = MaterialTheme.colorScheme
    var confirmStop by remember { mutableStateOf(false) }
    if (confirmStop) nl.baskt.ui.common.ConfirmDialog(
        title = "Stop self-scan?",
        body = if ((basket?.products?.size ?: 0) > 0) "The AH basket keeps its items on the store server. Start self-scan again in this store to carry on, or hand the phone to staff if you leave it." else "You can start it again any time on the store Wi-Fi.",
        confirmLabel = "Stop",
        dismissLabel = "Keep scanning",
        onDismiss = { confirmStop = false },
        onConfirm = onStop,
    )
    fun linkOf(line: SsItem): ListLink {
        val gtin = productGtin(line.barcode) ?: line.barcode
        tickedByBarcode[gtin]?.let { id -> listItems.firstOrNull { it.id == id }?.let { return ListLink.OnList(it) } }
        tripScans.lastOrNull { it.barcode == gtin }?.let { return if (it.synced) ListLink.Extra(it) else ListLink.Unknown }
        return ListLink.Unknown
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item("totals") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text("AH basket" + (state.store?.storeNumber?.let { " · store $it" } ?: ""), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Text((basket?.toPayCents ?: 0).euros(), style = MaterialTheme.typography.displaySmallEmphasized)
                    Text(
                        listOfNotNull("${basket?.itemCount ?: 0} items", basket?.savedCents?.takeIf { it > 0 }?.let { "you save ${it.euros()}" }, if (state.connectionLost) "no connection" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = if (state.connectionLost) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
            state.notice?.message?.takeIf { it.isNotBlank() }?.let { text ->
                item("notice") {
                    Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = colors.onSecondaryContainer)
                            Text(translated(text) ?: text, color = colors.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp))
                            IconButton(onClick = onDismissNotice) { Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.onSecondaryContainer) }
                        }
                    }
                }
            }
            items(state.issues, key = { "issue-${it.id}" }) { issue ->
                Surface(color = colors.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = colors.onErrorContainer)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(translated(issue.title) ?: issue.title, color = colors.onErrorContainer, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(issue.barcode, issue.detail?.let { translated(it) }).joinToString(" · "), color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onDismissIssue(issue.id); onRescan() }) { Text("Scan again", color = colors.onErrorContainer) }
                        IconButton(onClick = { onDismissIssue(issue.id) }) { Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.onErrorContainer) }
                    }
                }
            }
            val lines = basket?.products.orEmpty()
            if (lines.isEmpty()) item("empty") {
                Text("Nothing scanned yet. Scan a product and it shows here with AH's price and Bonus.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
            items(lines, key = { "line-${it.barcode}" }) { line ->
                key(line.barcode) {
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value -> if (value == SwipeToDismissBoxValue.Settled) false else { onQuantity(line.barcode, 0); true } })
                    SwipeToDismissBox(state = dismiss, modifier = Modifier.animateItem(), backgroundContent = { RemoveLineBackground(dismiss.dismissDirection) }) {
                        Surface(color = colors.surfaceContainerLow) {
                            SelfScanLine(line, linkOf(line), sending = line.barcode in state.sending, onQuantity = { onQuantity(line.barcode, it) })
                        }
                    }
                }
                HorizontalDivider()
            }
            val discounts = basket?.discounts.orEmpty().filter { (it.discount ?: 0) > 0 }
            if (discounts.isNotEmpty()) {
                item("h-savings") { SectionLabel("Your savings") }
                items(discounts.size, key = { "discount-$it" }) { index ->
                    val discount = discounts[index]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = colors.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(if (discount.icon?.contains("bonus") == true) "Bonus" else "Korting", color = colors.onTertiaryContainer, style = MaterialTheme.typography.labelMediumEmphasized, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Text(discount.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("−${(discount.discount ?: 0).euros()}", fontWeight = FontWeight.SemiBold, color = colors.tertiary)
                    }
                }
            }
            if (remaining.isNotEmpty()) {
                item("h-remaining") { SectionLabel("Still on your list · ${remaining.size}") }
                items(remaining, key = { "open-${it.id}" }) { item ->
                    val product = item.match("AH")?.effective
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (product != null) ProductThumb(product, size = 36) else Box(Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if (item.quantity > 1) "${item.quantity}× " else "") + (product?.title ?: item.text), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Not in the AH basket yet", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (product != null) Text((product.priceCents * item.quantity).euros(), color = colors.onSurfaceVariant)
                    }
                }
            }
            item("actions") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCheckout, enabled = lines.isNotEmpty() && !state.connectionLost, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        nl.baskt.ui.common.IconLabel(Icons.Default.ShoppingCartCheckout, "Pay at a betaalpaal · ${(basket?.toPayCents ?: 0).euros()}")
                    }
                    Text("You pay at the betaalpaal with your own card; baskt only shows your Bonuskaart there.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) { Text("Stop self-scan", color = colors.error) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SelfScanLine(line: SsItem, link: ListLink, sending: Boolean, onQuantity: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val quantity = line.quantity ?: 1
    val product = when (link) {
        is ListLink.OnList -> link.item.match("AH")?.effective
        is ListLink.Extra -> link.scan.product
        ListLink.Unknown -> null
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (product != null) ProductThumb(product, size = 44)
        else Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = colors.onSurfaceVariant) }
        }
        Column(Modifier.weight(1f)) {
            Text(line.description?.trim()?.ifBlank { null } ?: line.barcode, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(product?.title?.takeIf { !it.equals(line.description?.trim(), ignoreCase = true) }, line.weightText()).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = line.tags()
            val (listText, listWarn) = when (link) {
                is ListLink.OnList -> "For “${link.item.text}”" to false
                is ListLink.Extra -> "Not on your list" to true
                ListLink.Unknown -> null to false
            }
            if (tags.isNotEmpty() || listText != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                for (tag in tags) Chip(tag, colors.tertiaryContainer, colors.onTertiaryContainer)
                if (listText != null) Chip(listText, if (listWarn) colors.secondaryContainer else colors.surfaceContainerHighest, if (listWarn) colors.onSecondaryContainer else colors.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((line.price ?: 0).euros(), fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onQuantity(quantity - 1) }, enabled = !sending) {
                    Icon(if (quantity <= 1) Icons.Default.Delete else Icons.Default.Remove, contentDescription = if (quantity <= 1) "Remove" else "One less")
                }
                if (sending) LoadingIndicator(modifier = Modifier.size(16.dp))
                else Text("$quantity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
                IconButton(onClick = { onQuantity(quantity + 1) }, enabled = !sending && line.plusAllowed && line.weightText() == null) {
                    Icon(Icons.Default.Add, contentDescription = "One more")
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(text, color = content, style = MaterialTheme.typography.labelMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun RemoveLineBackground(direction: SwipeToDismissBoxValue) {
    val alignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = alignment) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text("Take out of the AH basket", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Checkout, full brightness on white: AH's instruction, the amount the betaalpaal will ask, and the Bonuskaart to
 * scan there. Once the betaalpaal reports paid, the same code opens the exit gate.
 */
@Composable
fun SelfScanCheckoutDialog(checkout: SelfScanCheckout, card: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose { window?.let { it.attributes = it.attributes.apply { screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (checkout.paid) "Paid" else "Pay at a betaalpaal", style = MaterialTheme.typography.titleLarge, color = Color.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black) }
            }
            Spacer(Modifier.weight(1f))
            when {
                checkout.loading -> LoadingIndicator()
                checkout.error != null && checkout.totals == null -> {
                    Text(translated(checkout.error) ?: checkout.error, color = Color.Black, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRetry) { Text("Try again", color = Color.Black) }
                }
                else -> {
                    val instruction = when {
                        checkout.paid -> checkout.gateMessage?.message ?: checkout.statusMessage?.message ?: "Je hebt betaald. Scan onderstaande code om het hekje te openen."
                        checkout.status == "paying" -> checkout.statusMessage?.message ?: "Volg de instructies op de betaalpaal."
                        else -> checkout.message?.message ?: "Ga naar een betaalpaal en scan onderstaande streepjescode"
                    }
                    Text(translated(instruction) ?: instruction, color = Color.Black, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    checkout.totals?.totals?.total?.takeIf { !checkout.paid }?.let { total ->
                        Spacer(Modifier.height(12.dp))
                        Text(total.euros(), color = Color.Black, style = MaterialTheme.typography.displayMediumEmphasized)
                        checkout.totals?.totals?.discount?.takeIf { it > 0 }?.let { Text("You save ${it.euros()}", color = Color.DarkGray) }
                    }
                    val code = (if (checkout.paid) checkout.gateBarcode else null) ?: checkout.totals?.barcode ?: card
                    if (code != null) {
                        Spacer(Modifier.height(24.dp))
                        BarcodeImage(code, modifier = Modifier.fillMaxWidth().aspectRatio(2.6f))
                        Spacer(Modifier.height(12.dp))
                        Text(code.chunked(4).joinToString(" "), color = Color.Black, style = tillNumberStyle())
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (!checkout.paid && checkout.status != "paying") Text("Pay at the betaalpaal with your own card. baskt never pays and never sees the payment.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose) { Text(if (checkout.paid) "Done · end the trip" else "Back to scanning", color = Color.Black) }
        }
    }
}
