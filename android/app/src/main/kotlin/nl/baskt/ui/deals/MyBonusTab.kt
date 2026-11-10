package nl.baskt.ui.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import nl.baskt.data.DealCard
import nl.baskt.data.PersonalOffer
import nl.baskt.data.Product
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted

/** The AH account's personal offers: Bonus Box picks to activate (up to the weekly limit) and AH's own personal Bonus. */
@Composable
fun MyBonusTab(viewModel: AppViewModel, onLogin: () -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    val data by viewModel.ahOffers.collectAsState()
    val loading by viewModel.loadingAhOffers.collectAsState()
    val activating by viewModel.activating.collectAsState()
    val basketItems by viewModel.basket.items.collectAsState()
    var tried by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.loadAhAccount(); viewModel.loadAhOffers().join(); tried = true }
    fun reload() { viewModel.loadAhAccount(); viewModel.loadAhOffers(force = true) }

    val current = account
    val offers = data?.offers ?: emptyList()
    val box = offers.filter { it.source == "BONUS_BOX" }
    val personal = offers.filter { it.source != "BONUS_BOX" }
    val max = data?.maximumActivations
    val used = data?.activatedCount ?: 0
    val limitReached = max != null && used >= max
    val products = data?.products ?: emptyMap()
    val open = basketItems.filter { !it.isGroup && it.isOpen }
    val basketProducts = open.flatMap { item -> item.matches.mapNotNull { it.chosen?.id } }.toSet()
    val basketTexts = open.map { it.text.lowercase() }.toSet()
    offers.firstOrNull { it.id == confirming }?.let { offer ->
        ActivateConfirm(offer, onDismiss = { confirming = null }) { viewModel.activateOffer(offer) }
    }

    PullToRefreshBox(isRefreshing = loading && data != null, onRefresh = { reload() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
            when {
                current != null && !current.connected -> item("login") {
                    EmptyState(
                        title = if (current.error != null) "Your AH login expired" else "Log in to see your Bonus Box",
                        body = "Activate offers and see them in your basket prices",
                        icon = Icons.Default.AccountCircle,
                        modifier = Modifier.fillParentMaxSize(),
                    ) { FilledTonalButton(onClick = onLogin) { Text(if (current.error != null) "Log in again" else "Log in to Albert Heijn") } }
                }
                data == null && (loading || !tried) -> item("loading") { CentredLoading("Loading your Bonus Box…") }
                data == null -> item("failed") { LoadFailed("Couldn’t load your Bonus Box") { reload() } }
                else -> {
                    item("box-header") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SectionHeader("Bonus Box")
                            val period = data?.period?.let { dateRange(it.start, it.end) }
                            val facts = listOfNotNull(max?.let { "$used of $it activated" }, period, "Weekly limit reached".takeIf { limitReached }).joinToString(" · ")
                            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (current?.autoActivate == true && box.isNotEmpty()) Text("Offers that match your basket activate automatically", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (box.isEmpty()) Text(
                                if (data?.bonusBoxError == "BONUS_BOX_NOT_FILLED") "AH hasn’t filled your Bonus Box for this week yet" else "No Bonus Box offers this week",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    items(box, key = { "box-${it.id}" }) { offer ->
                        OfferCard(viewModel, offer, products, basketProducts, basketTexts, canActivate = !limitReached, busy = offer.id in activating) { confirming = offer.id }
                    }
                    if (personal.isNotEmpty()) item("personal-header") { SectionHeader("Personal Bonus", Modifier.padding(top = 12.dp)) }
                    items(personal, key = { "personal-${it.id}" }) { offer ->
                        OfferCard(viewModel, offer, products, basketProducts, basketTexts, canActivate = false, busy = false) {}
                    }
                }
            }
        }
    }
}

/** The offer in the shape the deal sheet shows, so a Bonus Box offer opens exactly like any other deal. */
private fun PersonalOffer.asDealCard() = DealCard(store = "AH", id = id, title = title, subtitle = subtitle, dealText = dealText, imageUrl = imageUrl, priceCents = priceCents, regularPriceCents = regularPriceCents, validFrom = periodStart, validUntil = periodEnd)

/**
 * One personal offer, laid out like a deal card: tap opens it (the product when it is a single one), the
 * trailing slot holds "Activate" or the "Activated" tag, and its products fold out below.
 */
@Composable
private fun OfferCard(viewModel: AppViewModel, offer: PersonalOffer, products: Map<String, Product>, basketProducts: Set<String>, basketTexts: Set<String>, canActivate: Boolean, busy: Boolean, onActivate: () -> Unit) {
    var expanded by rememberSaveable(offer.id) { mutableStateOf(false) }
    var open by rememberSaveable(offer.id) { mutableStateOf(false) }
    val offerProducts = offer.productIds.mapNotNull { products[it] }
    val texts = listOf(offer.title, offer.subtitle.orEmpty(), offer.dealText.orEmpty())
    val shown = nl.baskt.ui.common.rememberTranslation(texts)?.takeIf { it.translated && it.texts.size == texts.size }?.texts ?: texts
    Card(onClick = { open = true }) {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ItemThumb(offer.imageUrl, contentDescription = null, size = 64)
                Column(modifier = Modifier.weight(1f)) {
                    if (offer.dealText != null) Text(shown[2], style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shown[0], style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (offer.subtitle != null) Text(shown[1], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val price = listOfNotNull(offer.priceCents?.euros(), offer.regularPriceCents?.takeIf { it > (offer.priceCents ?: 0) }?.let { "was ${it.euros()}" }).joinToString(" · ")
                    if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodySmall)
                }
                when {
                    offer.status == "ACTIVATED" -> Tag("Activated")
                    // Past the weekly limit the header says so once; the offers then simply have no button.
                    offer.status == "ACTIVATABLE" && offer.hqId != null && canActivate -> FilledTonalButton(enabled = !busy, onClick = onActivate) { Text(if (busy) "Activating…" else "Activate") }
                }
            }
            if (offerProducts.isNotEmpty()) {
                Expander(counted(offerProducts.size, "product"), expanded) { expanded = !expanded }
                if (expanded) for (product in offerProducts) {
                    ProductRow(product, trailing = { AddButton(product.id in basketProducts) { viewModel.addProductWithUndo(product) } })
                }
            }
        }
    }
    if (open) {
        val single = offerProducts.singleOrNull()
        if (single != null) nl.baskt.ui.common.ProductSheet(single) { open = false }
        else DealSheet(offer.asDealCard(), "Albert Heijn", added = offer.title.lowercase() in basketTexts, onAdd = { viewModel.addDeal(offer.asDealCard()); open = false }) { open = false }
    }
}
