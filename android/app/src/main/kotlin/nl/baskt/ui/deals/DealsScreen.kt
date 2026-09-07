package nl.baskt.ui.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/** Promotions (bonus, 1+1, x%) for the items in the current basket, with one-tap switching. */
@Composable
fun DealsScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val deals by viewModel.deals.collectAsState()
    val loading by viewModel.loadingDeals.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    LaunchedEffect(Unit) { viewModel.findDeals(live = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deals") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { viewModel.findDeals(live = true) }) { Icon(Icons.Default.Refresh, contentDescription = "Search the stores again") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator()
                    Text("Looking for promotions…")
                }
            }
            val list = deals?.deals ?: emptyList()
            if (!loading && list.isEmpty()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No promotions found for your open items", style = MaterialTheme.typography.titleMedium)
                    Text("Tap the refresh icon to search the stores again for bonus packs, 1+1 and discounts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.findDeals(live = true) }) { Text("Search the stores") }
                }
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (deal.currentProductId != deal.product.id) {
                                    Button(onClick = { viewModel.takeDeal(deal) }) { Text("Use this deal") }
                                } else {
                                    Text("Already your pick", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
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
