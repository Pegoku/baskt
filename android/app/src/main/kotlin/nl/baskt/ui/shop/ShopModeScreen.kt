package nl.baskt.ui.shop

import android.view.WindowManager
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductThumb
import nl.baskt.ui.common.StoreBadge

/** In-store mode: big checkboxes, items grouped by aisle (product category), screen stays on. */
@Composable
fun ShopModeScreen(viewModel: AppViewModel, store: String, onBack: () -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val shopping = items.filter { !it.isGroup }.mapNotNull { item -> item.match(store)?.effective?.let { item to it } }
    val remaining = shopping.filter { !it.first.checked }
    val total = shopping.sumOf { (item, product) -> product.priceCents * item.quantity }
    val sections = shopping.groupBy { it.second.category?.substringBefore(" / ")?.ifBlank { null } ?: "Other" }.toSortedMap()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { StoreBadge(store, stores); Text("${remaining.size} left · ${total.euros()}") } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val done = shopping.size - remaining.size
            LinearWavyProgressIndicator(progress = { if (shopping.isEmpty()) 0f else done.toFloat() / shopping.size }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                for ((section, entries) in sections) {
                    item("h-$section") {
                        Text(section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(entries, key = { it.first.id }) { (item, product) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleChecked(item) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = item.checked, onCheckedChange = { viewModel.toggleChecked(item) }, modifier = Modifier.padding(4.dp))
                            ProductThumb(product, size = 44)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (item.quantity > 1) "${item.quantity}× " else "") + product.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                                    color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                                Text("${product.quantityText} · for “${item.text}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text((product.priceCents * item.quantity).euros(), fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider()
                    }
                }
                val missing = items.filter { !it.isGroup && !it.checked && it.match(store)?.effective == null }
                if (missing.isNotEmpty()) {
                    item("missing") {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Not matched at this store", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (item in missing) Text("• ${item.text}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
