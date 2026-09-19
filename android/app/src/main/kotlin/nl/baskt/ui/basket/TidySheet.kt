package nl.baskt.ui.basket

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RadioButton
import nl.baskt.data.Deal
import nl.baskt.data.MergeDecision
import nl.baskt.data.StoreInfo
import nl.baskt.data.TidyPlan
import nl.baskt.data.TidyRename
import nl.baskt.data.TidySelection
import nl.baskt.data.euros
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/**
 * Review what the magic wand wants to do: rewrite entries in the app language, merge duplicates nobody
 * has decided about (keeping one, or adding the amounts up) and take promotions. Every change starts
 * ticked and can be unticked; look-alikes that stay apart are shown for information. Nothing changes
 * until "Apply".
 *
 * Comment mode (toggle in the top-right corner, on by default): hold a proposal to select it, select all
 * from the header, then write what should change ("double the cookies amount", "it should be bolsa de
 * lechugas"). The assistant revises the selected proposals and remembers wording corrections.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TidySheet(
    plan: TidyPlan?,
    loading: Boolean,
    revising: Boolean,
    commentMode: Boolean,
    stores: List<StoreInfo>,
    onToggleCommentMode: () -> Unit,
    onComment: (TidySelection, String) -> Unit,
    onDismiss: () -> Unit,
    onApply: (List<TidyRename>, List<MergeDecision>, List<Deal>) -> Unit,
) {
    val renames = plan?.renames ?: emptyList()
    val merges = plan?.merges ?: emptyList()
    val deals = plan?.deals ?: emptyList()
    var skippedRenames by remember(plan) { mutableStateOf(emptySet<String>()) }
    var skippedMerges by remember(plan) { mutableStateOf(emptySet<String>()) }
    // Adding the amounts up is the default only when the entries actually differ in quantity; otherwise it doubles by accident.
    var summed by remember(plan) { mutableStateOf(merges.filter { it.quantity > it.keepQuantity && it.items.any { item -> item.quantity > 1 } }.map { it.keepId }.toSet()) }
    var skippedDeals by remember(plan) { mutableStateOf(emptySet<String>()) }
    val dealKey = { deal: Deal -> "${deal.itemId}:${deal.store}" }
    val chosenRenames = renames.filter { it.id !in skippedRenames }
    val chosenMerges = merges.filter { it.keepId !in skippedMerges }.map { MergeDecision(it, sumQuantities = it.keepId in summed) }
    val chosenDeals = deals.filter { dealKey(it) !in skippedDeals }
    val changes = chosenRenames.size + chosenMerges.size + chosenDeals.size

    // Comment mode selection: proposal keys ("rename-id", "merge-keepId", "deal-item:store"). Survives a revision, since keys are stable.
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    val allKeys = renames.map { "rename-${it.id}" } + merges.map { "merge-${it.keepId}" } + deals.map { "deal-" + dealKey(it) }
    val selection = selectedKeys.intersect(allKeys.toSet())
    val allSelected = allKeys.isNotEmpty() && selection.size == allKeys.size
    fun toggleKey(key: String) { selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key }
    val commenting = commentMode && !loading && plan != null && allKeys.isNotEmpty()
    // In comment mode a tap selects; a long press selects in either mode (and turns comment mode on when it was off).
    fun cardPress(key: String, onTick: () -> Unit) { if (commenting) toggleKey(key) else onTick() }
    fun cardHold(key: String) { if (!commentMode) onToggleCommentMode(); selectedKeys = selectedKeys + key }
    fun sendComment(comment: String) {
        onComment(
            TidySelection(
                renames = renames.map { it.id }.filter { "rename-$it" in selection },
                merges = merges.map { it.keepId }.filter { "merge-$it" in selection },
                deals = deals.map { dealKey(it) }.filter { "deal-$it" in selection },
            ),
            comment,
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Tidy up", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (commenting) {
                    IconButton(onClick = { selectedKeys = if (allSelected) emptySet() else allKeys.toSet() }) {
                        Icon(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll, contentDescription = if (allSelected) "Unselect all" else "Select all")
                    }
                }
                if (!loading && plan != null && allKeys.isNotEmpty()) {
                    IconButton(onClick = onToggleCommentMode) {
                        Icon(
                            if (commentMode) Icons.AutoMirrored.Filled.Comment else Icons.Default.CommentsDisabled,
                            contentDescription = if (commentMode) "Turn comment mode off" else "Turn comment mode on",
                            tint = if (commentMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val reply = plan?.reply
            if (!reply.isNullOrBlank()) Text(reply, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Waving the wand over your list…") }
                plan == null || plan.isEmpty -> Text(
                    if (plan?.distinct?.isNotEmpty() == true) "Nothing to change. The look-alikes below each have their own product, so they stay."
                    else "Your list is already tidy: every entry reads well" + (plan?.language?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: "") + ", nothing repeats and there are no new deals.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    if (commenting) "Tap proposals to select them, then say what should change. Untick what you want to keep as it is." else "Untick anything you want to keep as it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!loading && plan != null && (renames.isNotEmpty() || merges.isNotEmpty() || deals.isNotEmpty() || plan.distinct.isNotEmpty())) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (merges.isNotEmpty()) {
                        item { SectionLabel(if (merges.size == 1) "Merge duplicates" else "Merge ${merges.size} sets of duplicates") }
                        items(merges, key = { "merge-${it.keepId}" }) { merge ->
                            val active = merge.keepId !in skippedMerges
                            val key = "merge-${merge.keepId}"
                            val tick = { skippedMerges = if (active) skippedMerges + merge.keepId else skippedMerges - merge.keepId }
                            ChangeCard(checked = active, selected = key in selection, onToggle = tick, onPress = { cardPress(key, tick) }, onHold = { cardHold(key) }) {
                                Text(merge.items.joinToString(" · ") { it.text + if (it.quantity > 1) " ×${it.quantity}" else "" }, style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(merge.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (merge.reason.isNotBlank()) Text(merge.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (active) {
                                    Spacer(Modifier.height(2.dp))
                                    QuantityChoice(selected = merge.keepId !in summed, label = "Keep one, ×${merge.keepQuantity}") { summed = summed - merge.keepId }
                                    QuantityChoice(selected = merge.keepId in summed, label = "Add the amounts up, ×${merge.quantity}") { summed = summed + merge.keepId }
                                }
                            }
                        }
                    }
                    if (renames.isNotEmpty()) {
                        item { SectionLabel(if (renames.size == 1) "Rename" else "Rename ${renames.size} entries") }
                        items(renames, key = { "rename-${it.id}" }) { change ->
                            val key = "rename-${change.id}"
                            val tick = { skippedRenames = if (change.id in skippedRenames) skippedRenames - change.id else skippedRenames + change.id }
                            ChangeCard(checked = change.id !in skippedRenames, selected = key in selection, onToggle = tick, onPress = { cardPress(key, tick) }, onHold = { cardHold(key) }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (change.from != change.to) Text(change.from, style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(change.to + (change.quantity?.let { "  ×$it" } ?: ""), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                if (!change.reason.isNullOrBlank()) Text(change.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (deals.isNotEmpty()) {
                        item { SectionLabel(if (deals.size == 1) "Use a deal" else "Use ${deals.size} deals") }
                        items(deals, key = { "deal-" + dealKey(it) }) { deal ->
                            val key = dealKey(deal)
                            val tick = { skippedDeals = if (key in skippedDeals) skippedDeals - key else skippedDeals + key }
                            ChangeCard(checked = key !in skippedDeals, selected = "deal-$key" in selection, onToggle = tick, onPress = { cardPress("deal-$key", tick) }, onHold = { cardHold("deal-$key") }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreBadge(deal.store, stores)
                                    Text(deal.itemText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    val saving = deal.savingCents
                                    if (saving != null && saving > 0) Text("save ${saving.euros()}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                }
                                ProductRow(deal.product)
                            }
                        }
                    }
                    if (plan.distinct.isNotEmpty()) {
                        item { SectionLabel("Kept apart") }
                        items(plan.distinct, key = { "distinct-" + it.items.joinToString("+") { item -> item.id } }) { entry ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (item in entry.items) {
                                        Text(item.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        if (item.product != null) Text(item.product, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text("Look alike, but each has its own chosen product.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (commenting) CommentBar(selectedCount = selection.size, busy = revising, onSend = { sendComment(it) })
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loading && plan != null && !plan.isEmpty) {
                    Button(onClick = { onApply(chosenRenames, chosenMerges, chosenDeals) }, enabled = changes > 0 && !revising) {
                        Text(if (changes == 0) "Nothing ticked" else if (changes == 1) "Apply 1 change" else "Apply $changes changes")
                    }
                }
                TextButton(onClick = onDismiss) { Text(if (!loading && (plan == null || plan.isEmpty)) "Close" else "Cancel") }
            }
        }
    }
}

@Composable
private fun QuantityChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(36.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChangeCard(checked: Boolean, selected: Boolean, onToggle: () -> Unit, onPress: () -> Unit, onHold: () -> Unit, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
            .combinedClickable(onClick = onPress, onLongClick = onHold),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.size(40.dp))
            Column(modifier = Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    }
}
