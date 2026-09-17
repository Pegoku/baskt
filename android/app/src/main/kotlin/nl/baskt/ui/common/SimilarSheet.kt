package nl.baskt.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nl.baskt.BasktApp
import nl.baskt.data.Product
import nl.baskt.data.SimilarMatch
import nl.baskt.data.SimilarResponse

/** Wording for the server's match labels. */
fun similarKindLabel(kind: String) = when (kind) {
    "SAME" -> "Same product"
    "EQUIVALENT" -> "Similar"
    else -> "Substitute"
}

/**
 * "Find similar": the same or the closest product at every enabled store, best first. The product's own
 * store lists alternatives. Every result can be added to the basket as a pinned pick for that store.
 */
@Composable
fun SimilarSheet(product: Product, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as BasktApp).container
    val stores by container.basket.stores.collectAsState()
    val scope = rememberCoroutineScope()
    var response by remember(product.id) { mutableStateOf<SimilarResponse?>(null) }
    var loading by remember(product.id) { mutableStateOf(true) }
    var failed by remember(product.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val added = remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(product.id, retry) {
        loading = true
        failed = false
        try {
            response = container.basket.similarProducts(product)
            failed = response == null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true } finally { loading = false }
    }
    val texts = remember(response) { response?.results?.flatMap { result -> result.matches.map { it.note.orEmpty() } } ?: emptyList() }
    val translation = rememberTranslation(texts)
    val notes = translation?.takeIf { it.translated }?.texts
    var noteIndex = 0

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(.92f).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Similar products", style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(product.title, product.quantityText.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(24.dp))
                Text("Looking for this product at the stores…")
            } else if (failed) {
                Text("The stores could not be searched right now.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text("Try again") }
            }
            val ordered = response?.results?.sortedBy { if (it.store == product.store) 1 else 0 } ?: emptyList()
            for (result in ordered) {
                val ownStore = result.store == product.store
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StoreBadge(result.store, stores)
                    Text(
                        when {
                            result.error != null -> result.error
                            ownStore -> "Alternatives at this store"
                            result.matches.isEmpty() -> "Nothing similar found"
                            else -> "${result.matches.size} found"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (result.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.matches.isEmpty() && result.error == null && result.queries.isNotEmpty()) {
                    Text("Searched for: ${result.queries.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (match in result.matches) {
                    val note = notes?.getOrNull(noteIndex) ?: match.note
                    noteIndex++
                    SimilarCard(match, note, added.value.contains(match.product.id)) {
                        added.value = added.value + match.product.id
                        scope.launch { container.basket.addFromProduct(match.product) }
                    }
                }
            }
            if (!loading && !failed && ordered.isNotEmpty() && ordered.all { it.matches.isEmpty() }) {
                Text("No store had anything close. Try searching by name from the scan or search screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
private fun SimilarCard(match: SimilarMatch, note: String?, added: Boolean, onAdd: () -> Unit) {
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    similarKindLabel(match.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (match.kind == "SAME") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            ProductRow(match.product) {
                FilledTonalIconButton(onClick = onAdd, enabled = !added) {
                    Icon(if (added) Icons.Default.Check else Icons.Default.AddShoppingCart, contentDescription = if (added) "Added to basket" else "Add to basket")
                }
            }
        }
    }
}
