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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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

/** Results shown per store before "Show more". */
private const val INITIAL_PER_STORE = 4

/**
 * "Find similar": the same or the closest product at every enabled store, best first. The product's own
 * store lists alternatives. Every result can be added to the basket as a pinned pick for that store, and
 * thumbs up/down teach the server: a rejected pairing disappears next time, a confirmed one comes first.
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
    var added by remember { mutableStateOf(setOf<String>()) }
    /** Thumbs given in this sheet, layered over what the server sent. */
    var verdicts by remember(product.id) { mutableStateOf(mapOf<String, String?>()) }
    var expanded by remember(product.id) { mutableStateOf(setOf<String>()) }
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

    fun verdictOf(match: SimilarMatch) = if (match.product.id in verdicts) verdicts[match.product.id] else match.feedback

    fun rate(match: SimilarMatch, up: Boolean) {
        val current = verdictOf(match)
        val next: Boolean? = if ((current == "UP") == up && current != null) null else up
        verdicts = verdicts + (match.product.id to next?.let { if (it) "UP" else "DOWN" })
        scope.launch { container.basket.similarFeedback(product, match.product, next) }
    }

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
            if (ordered.isNotEmpty()) {
                val vision = ordered.any { it.vision }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (vision) "Packaging pictures were compared. Thumbs teach it what fits." else "Compared by name and size only; no picture model is configured on the server. Thumbs teach it what fits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (result in ordered) {
                val ownStore = result.store == product.store
                val visible = result.matches.filter { verdictOf(it) != "DOWN" }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StoreBadge(result.store, stores)
                    Text(
                        when {
                            result.error != null -> result.error
                            ownStore -> "Alternatives at this store"
                            visible.isEmpty() -> "Nothing similar found"
                            else -> "${visible.size} found"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (result.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (visible.isEmpty() && result.error == null && result.queries.isNotEmpty()) {
                    Text("Searched for: ${result.queries.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val shownCount = if (result.store in expanded) visible.size else minOf(INITIAL_PER_STORE, visible.size)
                for (match in result.matches) {
                    val note = notes?.getOrNull(noteIndex) ?: match.note
                    noteIndex++
                    val position = visible.indexOf(match)
                    if (position < 0 || position >= shownCount) continue
                    SimilarCard(
                        match = match,
                        note = note,
                        verdict = verdictOf(match),
                        added = match.product.id in added,
                        onAdd = { added = added + match.product.id; scope.launch { container.basket.addFromProduct(match.product) } },
                        onRate = { up -> rate(match, up) },
                    )
                }
                if (visible.size > shownCount) TextButton(onClick = { expanded = expanded + result.store }) { Text("Show ${visible.size - shownCount} more") }
            }
            if (!loading && !failed && ordered.isNotEmpty() && ordered.all { result -> result.matches.none { verdictOf(it) != "DOWN" } }) {
                Text("No store had anything close. Try searching by name from the scan or search screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
private fun SimilarCard(match: SimilarMatch, note: String?, verdict: String?, added: Boolean, onAdd: () -> Unit, onRate: (Boolean) -> Unit) {
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (verdict == "UP") "Confirmed · ${similarKindLabel(match.kind)}" else similarKindLabel(match.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (match.kind == "SAME" || verdict == "UP") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            ProductRow(match.product) {
                FilledTonalIconButton(onClick = onAdd, enabled = !added) {
                    Icon(if (added) Icons.Default.Check else Icons.Default.AddShoppingCart, contentDescription = if (added) "Added to basket" else "Add to basket")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Does this fit?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onRate(true) }, colors = IconButtonDefaults.iconButtonColors(contentColor = if (verdict == "UP") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Icon(Icons.Default.ThumbUp, contentDescription = if (verdict == "UP") "Confirmed as a match; tap to undo" else "Yes, this fits")
                }
                IconButton(onClick = { onRate(false) }, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Icon(Icons.Default.ThumbDown, contentDescription = "No, this does not fit")
                }
            }
        }
    }
}
