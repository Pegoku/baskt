package nl.baskt.ui.basket

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.TransferMenu

/** A folder (e.g. a recipe) showing its child items; each child behaves like a normal basket item. */
@Composable
fun GroupScreen(viewModel: AppViewModel, groupId: String, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val items by viewModel.basket.items.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val recipe by viewModel.recipeSuggestion.collectAsState()
    val baskets by viewModel.basket.baskets.collectAsState()
    val currentBasketId by viewModel.basket.currentBasketId.collectAsState()
    val group = items.firstOrNull { it.id == groupId }
    val children = items.filter { it.parentId == groupId }
    val enabledStores = stores.filter { it.enabled }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.text ?: "Folder", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    val url = group?.recipe?.sourceUrl
                    if (url != null) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open recipe")
                        }
                    }
                    if (group != null) {
                        IconButton(onClick = { viewModel.delete(group); onBack() }) { Icon(Icons.Default.Delete, contentDescription = "Delete folder") }
                        TransferMenu(baskets, currentBasketId) { basketId, copy -> viewModel.transfer(group, basketId, copy); if (!copy) onBack() }
                    }
                },
            )
        },
        bottomBar = {
            AddIdeaBar(
                suggestions = suggestions,
                recipe = recipe,
                onTextChanged = { viewModel.onIdeaTextChanged(it) },
                onAdd = { text, quantity -> viewModel.clearSuggestions(); viewModel.addToGroup(groupId, text, quantity) },
                onAddGroup = { _, picked -> viewModel.clearSuggestions(); picked?.forEach { viewModel.addToGroup(groupId, it, 1) } },
                onAddMany = { picked -> viewModel.clearSuggestions(); picked.forEach { viewModel.addToGroup(groupId, it, 1) } },
                placeholder = "Add to this folder…",
                allowFolders = false,
            )
        },
    ) { padding ->
        if (group == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text("This folder no longer exists.") }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("header") {
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            group.isProcessing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LoadingIndicator()
                                Text("Looking up a recipe and its ingredients…")
                            }
                            group.status == "ERROR" -> Text(group.error ?: "Could not build this folder", color = MaterialTheme.colorScheme.error)
                            else -> {
                                val info = group.recipe
                                Text(
                                    listOfNotNull("${children.size} items", info?.servings?.let { "$it servings" }, info?.sourceUrl?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }?.let { "recipe from $it" })
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        val skipped = group.recipe?.skipped ?: emptyList()
                        if (skipped.isNotEmpty()) {
                            Text(
                                "Left out because in stock: ${skipped.joinToString { it.text }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            androidx.compose.material3.TextButton(onClick = { viewModel.addSkipped(group) }) { Text("Add them anyway") }
                        }
                        if (children.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                for (store in enabledStores) {
                                    val total = children.filter { !it.checked }.sumOf { child -> (child.match(store.code)?.effective?.priceCents ?: 0) * child.quantity }
                                    val missing = children.count { !it.checked && it.match(store.code)?.effective == null }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        StoreBadge(store.code, stores)
                                        Text(total.euros() + if (missing > 0) " (−$missing)" else "", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(children, key = { it.id }) { child ->
                BasketItemCard(child, enabledStores, onClick = { onOpenItem(child.id) }, onToggle = { viewModel.toggleChecked(child) })
            }
        }
    }
}
