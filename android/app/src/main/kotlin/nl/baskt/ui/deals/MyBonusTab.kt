package nl.baskt.ui.deals

import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import nl.baskt.data.PersonalOffer
import nl.baskt.data.StoreInfo
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow

/** The AH account's personal offers: Bonus Box picks to activate (up to the weekly limit) and AH's own personal Bonus. */
@Composable
fun MyBonusTab(viewModel: AppViewModel, stores: List<StoreInfo>, onLogin: () -> Unit, onAdded: (String) -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    val data by viewModel.ahOffers.collectAsState()
    val loading by viewModel.loadingAhOffers.collectAsState()
    val activating by viewModel.activating.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadAhAccount(); viewModel.loadAhOffers() }
    val current = account
    if (current != null && !current.connected) {
        nl.baskt.ui.common.EmptyState(
            title = if (current.error != null) "Your AH login expired" else "Your personal Bonus",
            body = "Log in with your Albert Heijn account to see your Bonus Box and personal offers, activate them, and have them count in your basket prices.",
            icon = Icons.Default.AccountCircle,
        ) { Button(onClick = onLogin) { Text(if (current.error != null) "Log in again" else "Log in to Albert Heijn") } }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (loading) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        val offers = data?.offers ?: emptyList()
        val box = offers.filter { it.source == "BONUS_BOX" }
        val personal = offers.filter { it.source != "BONUS_BOX" }
        val max = data?.maximumActivations
        val used = data?.activatedCount ?: 0
        val slotsLeft = max?.let { it - used }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "box-header") {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text("Bonus Box", style = MaterialTheme.typography.titleMedium)
                    val period = data?.period?.let { "${it.start.drop(5)} to ${it.end.drop(5)}" }
                    val summary = listOfNotNull(
                        max?.let { "$used of $it activated" },
                        period?.let { "week $it" },
                        if (current?.autoActivate == true) "matching offers activate automatically" else null,
                    ).joinToString(" · ")
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (data != null && box.isEmpty()) Text(
                        when (data?.bonusBoxError) {
                            "BONUS_BOX_NOT_FILLED" -> "AH has not filled your Bonus Box for this week yet."
                            null -> "No Bonus Box offers this week."
                            else -> "No Bonus Box this week (${data?.bonusBoxError})."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(box, key = { "box-${it.id}" }) { offer ->
                OfferCard(viewModel, offer, data?.products ?: emptyMap(), canActivate = slotsLeft == null || slotsLeft > 0, busy = offer.id in activating, onAdded = onAdded)
            }
            if (personal.isNotEmpty()) item(key = "personal-header") {
                Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp)) {
                    Text("Personal Bonus", style = MaterialTheme.typography.titleMedium)
                    Text("Already on your card this week.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(personal, key = { "personal-${it.id}" }) { offer ->
                OfferCard(viewModel, offer, data?.products ?: emptyMap(), canActivate = false, busy = false, onAdded = onAdded)
            }
        }
    }
}

@Composable
private fun OfferCard(viewModel: AppViewModel, offer: PersonalOffer, products: Map<String, nl.baskt.data.Product>, canActivate: Boolean, busy: Boolean, onAdded: (String) -> Unit) {
    var expanded by remember(offer.id) { mutableStateOf(false) }
    val offerProducts = offer.productIds.mapNotNull { products[it] }
    Card {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (offer.imageUrl != null) coil3.compose.AsyncImage(model = offer.imageUrl, contentDescription = null, modifier = Modifier.size(64.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (offer.dealText != null) Text(offer.dealText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(offer.title, style = MaterialTheme.typography.titleSmall)
                    if (offer.subtitle != null) Text(offer.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val price = listOfNotNull(offer.priceCents?.euros(), offer.regularPriceCents?.takeIf { it > (offer.priceCents ?: 0) }?.let { "was ${it.euros()}" }).joinToString(" · ")
                    if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    offer.status == "ACTIVATED" -> nl.baskt.ui.common.Tag(if (offer.autoActivated) "Activated for your basket" else "Activated")
                    offer.status == "ACTIVATABLE" && offer.hqId != null -> androidx.compose.material3.FilledTonalButton(enabled = canActivate && !busy, onClick = { viewModel.activateOffer(offer) }) {
                        Text(if (busy) "Activating…" else if (canActivate) "Activate" else "Weekly limit reached")
                    }
                    else -> Text(offer.status.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (offerProducts.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide products" else "${offerProducts.size} product" + if (offerProducts.size == 1) "" else "s") }
            }
            if (expanded) for (product in offerProducts) {
                ProductRow(product, trailing = { androidx.compose.material3.FilledTonalButton(onClick = { viewModel.addFromProduct(product); onAdded("Added “${product.title}” to your basket") }) { Text("Add") } })
            }
        }
    }
}
