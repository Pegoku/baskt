package nl.baskt.ui.shop

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Stepper
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted
import nl.baskt.ui.theme.BasktTheme

/** AH's messages are Dutch; shown in the app's language when they can be translated. */
@Composable
private fun translated(text: String?): String? {
    if (text.isNullOrBlank()) return text
    return nl.baskt.ui.common.rememberTranslation(listOf(text))?.takeIf { it.translated }?.texts?.firstOrNull() ?: text
}

/**
 * The self-scan strip at the bottom of AH shop mode: start it, what connecting is doing, how to get on the store
 * Wi-Fi, and once running the AH basket's live total. While it runs the whole strip is one button that opens the basket.
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
    val onColor = if (active) colors.onPrimaryContainer else colors.onSurface
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.phase) {
                    SelfScanPhase.CONNECTING -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    SelfScanPhase.NO_WIFI, SelfScanPhase.FAILED -> Icon(Icons.Default.WifiOff, contentDescription = null, tint = colors.error)
                    SelfScanPhase.ACTIVE -> Icon(if (state.connectionLost) Icons.Default.WifiOff else Icons.Default.ShoppingCartCheckout, contentDescription = null, tint = if (state.connectionLost) colors.error else onColor)
                    SelfScanPhase.OFF -> Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = colors.primary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    val basket = state.basket
                    val (title, detail) = when (state.phase) {
                        SelfScanPhase.OFF -> "AH self-scan" to (if (hasCard) "Scan into the AH basket, pay at a betaalpaal" else "Add your Bonuskaart to start")
                        SelfScanPhase.CONNECTING -> "AH self-scan" to (state.step ?: "Connecting…")
                        SelfScanPhase.NO_WIFI -> "Join the store Wi-Fi to continue" to null
                        SelfScanPhase.FAILED -> "Self-scan didn’t start" to (translated(state.error) ?: "Couldn’t reach the store")
                        SelfScanPhase.ACTIVE -> {
                            val issue = state.issues.lastOrNull()
                            "${counted(basket?.itemCount ?: 0, "item")} · ${(basket?.toPayCents ?: 0).euros()}" to when {
                                state.connectionLost -> "Reconnecting to the store…"
                                issue != null -> "Not added: ${translated(issue.title)}"
                                state.sending.isNotEmpty() -> "Sending to AH…"
                                (basket?.savedCents ?: 0) > 0 -> "You save ${basket!!.savedCents.euros()}"
                                else -> "AH basket"
                            }
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized, color = onColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail != null) Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active && (state.connectionLost || state.issues.isNotEmpty())) colors.error else if (active) onColor else colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                when (state.phase) {
                    SelfScanPhase.OFF -> if (hasCard) Button(onClick = onStart) { Text("Start") } else FilledTonalButton(onClick = onAddCard) { Text("Add card") }
                    SelfScanPhase.CONNECTING -> TextButton(onClick = onStop) { Text("Cancel") }
                    SelfScanPhase.ACTIVE -> {
                        if (state.sending.isNotEmpty()) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = onColor)
                        // The arrow says the strip opens upwards into the basket sheet.
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = onColor)
                    }
                    else -> Unit
                }
            }
            // Three actions do not fit beside the text, so a strip that needs the shopper puts them on their own line.
            if (state.phase == SelfScanPhase.NO_WIFI || state.phase == SelfScanPhase.FAILED) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onStop) { Text("Stop") }
                FilledTonalButton(onClick = onStart) { Text("Try again") }
                if (state.phase == SelfScanPhase.NO_WIFI) Button(onClick = onWifiSettings, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { IconLabel(Icons.Default.Wifi, "Wi-Fi") }
            }
        }
    }
    if (active) Surface(onClick = onOpenBasket, color = colors.primaryContainer, shape = BottomSheetDefaults.ExpandedShape, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }, content = content)
    else Surface(color = colors.surfaceContainerHigh, shape = BottomSheetDefaults.ExpandedShape, modifier = Modifier.fillMaxWidth(), content = content)
}

/** Where a line of the AH basket stands against the baskt basket. */
private sealed interface ListLink {
    data class OnList(val item: BasketItem) : ListLink
    data class Extra(val scan: TripScan) : ListLink
    data object Unknown : ListLink
}

/**
 * The live AH basket: each line with its quantity, price and Bonus, AH's refusals, the savings, what is still in
 * the baskt basket, and the way to the betaalpaal. Taking a line out asks in the row itself: the app's undo
 * message would sit behind this sheet, and a dialog must not stack on top of it. [onStop] only asks to stop; the
 * caller closes the sheet and confirms.
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
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    fun linkOf(line: SsItem): ListLink {
        val gtin = productGtin(line.barcode) ?: line.barcode
        tickedByBarcode[gtin]?.let { id -> listItems.firstOrNull { it.id == id }?.let { return ListLink.OnList(it) } }
        tripScans.lastOrNull { it.barcode == gtin }?.let { return if (it.synced) ListLink.Extra(it) else ListLink.Unknown }
        return ListLink.Unknown
    }
    val lines = basket?.products.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBody(
            title = "AH basket",
            actions = {
                TextButton(onClick = onStop, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text("Stop self-scan") }
                Button(onClick = onCheckout, enabled = lines.isNotEmpty() && !state.connectionLost, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    IconLabel(Icons.Default.ShoppingCartCheckout, "Pay ${(basket?.toPayCents ?: 0).euros()}")
                }
            },
        ) {
            Column {
                Text((basket?.toPayCents ?: 0).euros(), style = MaterialTheme.typography.displaySmallEmphasized)
                Text(
                    listOfNotNull(counted(basket?.itemCount ?: 0, "item"), basket?.savedCents?.takeIf { it > 0 }?.let { "${it.euros()} saved" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
                if (state.connectionLost) Text("No connection to the store", style = MaterialTheme.typography.bodyMedium, color = colors.error)
            }
            state.notice?.message?.takeIf { it.isNotBlank() }?.let { text ->
                Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Text(translated(text) ?: text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp))
                        IconButton(onClick = onDismissNotice) { Icon(Icons.Default.Close, contentDescription = "Dismiss message") }
                    }
                }
            }
            for (issue in state.issues) key("issue-${issue.id}") {
                val title = translated(issue.title) ?: issue.title
                Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(issue.barcode, issue.detail?.let { translated(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onDismissIssue(issue.id); onRescan() }, colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)) { Text("Scan again") }
                        IconButton(onClick = { onDismissIssue(issue.id) }) { Icon(Icons.Default.Close, contentDescription = "Dismiss “$title”") }
                    }
                }
            }
            if (lines.isEmpty()) EmptyState(title = "Nothing scanned yet", body = "Scanned products show here with AH’s price.", icon = Icons.Default.QrCodeScanner)
            else Column {
                for (line in lines) key(line.barcode) {
                    // A swipe asks the same question as the minus button at one: the row slides back and shows it.
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value -> if (value != SwipeToDismissBoxValue.Settled) removing = line.barcode; false })
                    SwipeToDismissBox(state = dismiss, backgroundContent = { RemoveLineBackground(dismiss.dismissDirection) }) {
                        Surface(color = BottomSheetDefaults.ContainerColor) {
                            SelfScanLine(
                                line, linkOf(line),
                                sending = line.barcode in state.sending,
                                removing = removing == line.barcode,
                                onQuantity = { quantity -> if (quantity <= 0) removing = line.barcode else onQuantity(line.barcode, quantity) },
                                onKeep = { removing = null },
                                onRemove = { removing = null; onQuantity(line.barcode, 0) },
                            )
                        }
                    }
                }
            }
            val discounts = basket?.discounts.orEmpty().filter { (it.discount ?: 0) > 0 }
            if (discounts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Your savings")
                for (discount in discounts) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tag(if (discount.icon?.contains("bonus") == true) "Bonus" else "Discount", color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
                    Text(discount.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("−${(discount.discount ?: 0).euros()}", style = MaterialTheme.typography.titleSmallEmphasized, color = colors.tertiary)
                }
            }
            if (remaining.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Still on your basket · ${remaining.size}")
                for (item in remaining) {
                    val product = item.match("AH")?.effective
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ItemThumb(product?.imageUrl, contentDescription = null)
                        Text((if (item.quantity > 1) "${item.quantity}× " else "") + (product?.title ?: item.text), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (product != null) Text((product.priceCents * item.quantity).euros(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * One line of the AH basket: picture, AH's name for it and its price on top, its tags and the quantity under
 * them. With [removing] the bottom line becomes the question whether to take it out.
 */
@Composable
private fun SelfScanLine(line: SsItem, link: ListLink, sending: Boolean, removing: Boolean, onQuantity: (Int) -> Unit, onKeep: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val quantity = line.quantity ?: 1
    val product = when (link) {
        is ListLink.OnList -> link.item.match("AH")?.effective
        is ListLink.Extra -> link.scan.product
        ListLink.Unknown -> null
    }
    val title = line.description?.trim()?.ifBlank { null } ?: line.barcode
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ItemThumb(product?.imageUrl, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(product?.title?.takeIf { !it.equals(line.description?.trim(), ignoreCase = true) }, line.weightText()).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text((line.price ?: 0).euros(), style = MaterialTheme.typography.titleMediumEmphasized)
        }
        if (removing) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Remove from the AH basket?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onKeep) { Text("Keep") }
            TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text("Remove") }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (tag in line.tags()) Tag(tag, modifier = Modifier.weight(1f, fill = false), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
                if (link is ListLink.Extra) Tag("Not on your basket", modifier = Modifier.weight(1f, fill = false))
            }
            // At one, minus asks whether to take the line out; a weighed line has no "one more".
            Stepper("$quantity", onMinus = { onQuantity(quantity - 1) }, onPlus = { onQuantity(quantity + 1) }, noun = "of $title", minusEnabled = !sending, plusEnabled = !sending && line.plusAllowed && line.weightText() == null)
        }
    }
}

@Composable
private fun RemoveLineBackground(direction: SwipeToDismissBoxValue) {
    val alignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = alignment) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text("Remove", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Checkout, full brightness on white: AH's instruction, the amount the betaalpaal will ask, and the Bonuskaart to
 * scan there. Once the betaalpaal reports paid, the same code opens the exit gate. Always in the light theme, so
 * every control is readable on the white a scanner needs.
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
        BasktTheme(darkTheme = false) {
            val colors = MaterialTheme.colorScheme
            Surface(color = Color.White, contentColor = colors.onSurface, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (checkout.paid) "Paid" else "Pay at a betaalpaal", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    when {
                        checkout.loading -> LoadingIndicator()
                        checkout.error != null && checkout.totals == null -> EmptyState(
                            title = "Couldn’t start the checkout",
                            body = translated(checkout.error) ?: checkout.error,
                            action = { FilledTonalButton(onClick = onRetry) { Text("Try again") } },
                        )
                        else -> {
                            val instruction = when {
                                checkout.paid -> checkout.gateMessage?.message ?: checkout.statusMessage?.message ?: "You’ve paid. Scan this code to open the gate."
                                checkout.status == "paying" -> checkout.statusMessage?.message ?: "Follow the instructions on the betaalpaal."
                                else -> checkout.message?.message ?: "Go to a betaalpaal and scan this barcode"
                            }
                            Text(translated(instruction) ?: instruction, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            checkout.totals?.totals?.total?.takeIf { !checkout.paid }?.let { total ->
                                Spacer(Modifier.height(12.dp))
                                Text(total.euros(), style = MaterialTheme.typography.displayMediumEmphasized)
                                checkout.totals.totals?.discount?.takeIf { it > 0 }?.let { Text("You save ${it.euros()}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                            }
                            val code = (if (checkout.paid) checkout.gateBarcode else null) ?: checkout.totals?.barcode ?: card
                            if (code != null) {
                                Spacer(Modifier.height(24.dp))
                                BarcodeImage(code, modifier = Modifier.fillMaxWidth().aspectRatio(2.6f))
                                Spacer(Modifier.height(12.dp))
                                Text(code.chunked(4).joinToString(" "), style = tillNumberStyle())
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // The one place that says who pays: the sheet's button only says "Pay".
                    if (!checkout.paid && checkout.status != "paying") Text("You pay at the betaalpaal with your own card; baskt never sees the payment.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    // The one way out: back to the trip before paying, done after.
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(if (checkout.paid) "Done" else "Back to scanning") }
                }
            }
        }
    }
}
