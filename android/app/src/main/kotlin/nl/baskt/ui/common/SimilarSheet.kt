package nl.baskt.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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

/** The similar products of [product] as a sheet of their own. Inside the product sheet, [SimilarContent] is shown in place instead. */
@Composable
fun SimilarSheet(product: Product, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBody(title = "Similar products") { SimilarContent(product) }
    }
}

/**
 * "Similar products": the same or the closest product at every enabled store, best first. The product's own
 * store lists alternatives. Every result can be added to the basket as the pick for that store, and thumbs
 * up/down teach the matching: a rejected pairing disappears next time, a confirmed one comes first.
 * Goes inside a [SheetBody]. [onOpen] shows a result's details in the same sheet; without it rows open nothing,
 * so no sheet ever stacks on this one.
 */
@Composable
fun ColumnScope.SimilarContent(product: Product, onOpen: ((Product) -> Unit)? = null) {
    val container = (LocalContext.current.applicationContext as BasktApp).container
    val stores by container.basket.stores.collectAsState()
    val scope = rememberCoroutineScope()
    var response by remember(product.id) { mutableStateOf<SimilarResponse?>(null) }
    var loading by remember(product.id) { mutableStateOf(true) }
    var failed by remember(product.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var added by remember(product.id) { mutableStateOf(setOf<String>()) }
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
    /** A thumbs-down given just now stays on screen, dimmed, so it can be taken back; older ones are simply not listed. */
    fun listed(match: SimilarMatch) = verdictOf(match) != "DOWN" || match.product.id in verdicts

    fun rate(match: SimilarMatch, verdict: String?) {
        verdicts = verdicts + (match.product.id to verdict)
        scope.launch { container.basket.similarFeedback(product, match.product, verdict?.let { it == "UP" }) }
    }

    fun add(match: SimilarMatch) {
        added = added + match.product.id
        scope.launch {
            try { container.basket.addFromProduct(match.product) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                added = added - match.product.id
                container.basket.notices.tryEmit("Couldn’t add “${match.product.title}”")
            }
        }
    }

    Text(listOfNotNull(product.title, product.quantityText.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (loading) LoadingRow("Searching the stores…")
    else if (failed) EmptyState("Couldn’t search the stores", action = { FilledTonalButton(onClick = { retry++ }) { Text("Try again") } })

    val ordered = response?.results?.sortedBy { if (it.store == product.store) 1 else 0 } ?: emptyList()
    val nothing = !loading && !failed && ordered.isNotEmpty() && ordered.all { result -> result.matches.none(::listed) }
    if (nothing) EmptyState("Nothing similar found", icon = Icons.Default.SearchOff)
    for (result in ordered) {
        val visible = result.matches.filter(::listed)
        if (!nothing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            StoreBadge(result.store, stores)
            Text(
                when {
                    result.error != null -> "Couldn’t search this store"
                    visible.isEmpty() -> "Nothing similar"
                    result.store == product.store -> "Alternatives at the same store"
                    else -> counted(visible.size, "product")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (result.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                onAdd = { add(match) },
                onRate = { verdict -> rate(match, verdict) },
                onOpen = onOpen?.let { open -> { open(match.product) } },
            )
        }
        if (visible.size > shownCount) TextButton(onClick = { expanded = expanded + result.store }, modifier = Modifier.align(Alignment.End)) { Text("Show ${visible.size - shownCount} more") }
    }
}

/** One result: how close it is, the product, and on one line the two thumbs (which only rate) and the Add button. */
@Composable
private fun SimilarCard(match: SimilarMatch, note: String?, verdict: String?, added: Boolean, onAdd: () -> Unit, onRate: (String?) -> Unit, onOpen: (() -> Unit)?) {
    val rejected = verdict == "DOWN"
    Card {
        Column(Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A rejected result is dimmed instead of removed, so the thumbs-down can still be taken back.
            Column(modifier = if (rejected) Modifier.alpha(0.5f) else Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(
                        similarKindLabel(match.kind),
                        color = if (match.kind == "SAME") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (match.kind == "SAME") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                ProductRow(match.product, openable = onOpen != null && !rejected, onOpen = onOpen)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (rejected) {
                    Text("Marked as not a match", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRate(null) }) { Text("Undo") }
                } else {
                    IconToggleButton(checked = verdict == "UP", onCheckedChange = { onRate(if (it) "UP" else null) }) { Icon(Icons.Default.ThumbUp, contentDescription = "Good match") }
                    IconButton(onClick = { onRate("DOWN") }) { Icon(Icons.Default.ThumbDown, contentDescription = "Not a match") }
                    if (added) Tag("Added") else FilledTonalButton(onClick = onAdd, modifier = Modifier.padding(start = 4.dp)) { Text("Add") }
                }
            }
        }
    }
}
