package nl.baskt.ui.basket

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoFixHigh
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
import nl.baskt.data.euros
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/**
 * Review what the magic wand wants to do: rewrite entries in the app language, merge duplicates nobody
 * has decided about (keeping one, or adding the amounts up) and take promotions. Every change starts
 * ticked and can be unticked; look-alikes that stay apart are shown for information. Nothing changes
 * until "Apply".
 */
@Composable
fun TidySheet(plan: TidyPlan?, loading: Boolean, stores: List<StoreInfo>, onDismiss: () -> Unit, onApply: (List<TidyRename>, List<MergeDecision>, List<Deal>) -> Unit) {
    val renames = plan?.renames ?: emptyList()
    val merges = plan?.merges ?: emptyList()
    val deals = plan?.deals ?: emptyList()
    var skippedRenames by remember(plan) { mutableStateOf(emptySet<String>()) }
    var skippedMerges by remember(plan) { mutableStateOf(emptySet<String>()) }
    // Adding the amounts up is the default only when the entries actually differ in quantity; otherwise it doubles by accident.
    var summed by remember(plan) { mutableStateOf(merges.filter { it.quantity > it.keepQuantity && it.items.any { item -> item.quantity > 1 } }.map { it.keepId }.toSet()) }
    var skippedDeals by remember(plan) { mutableStateOf(emptySet<String>()) }
    val dealKey = { deal: Deal -> "${deal.itemId}-${deal.store}" }
    val chosenRenames = renames.filter { it.id !in skippedRenames }
    val chosenMerges = merges.filter { it.keepId !in skippedMerges }.map { MergeDecision(it, sumQuantities = it.keepId in summed) }
    val chosenDeals = deals.filter { dealKey(it) !in skippedDeals }
    val changes = chosenRenames.size + chosenMerges.size + chosenDeals.size
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Tidy up", style = MaterialTheme.typography.titleLarge)
            }
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Waving the wand over your list…") }
                plan == null || plan.isEmpty -> Text(
                    if (plan?.distinct?.isNotEmpty() == true) "Nothing to change. The look-alikes below each have their own product, so they stay."
                    else "Your list is already tidy: every entry reads well" + (plan?.language?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: "") + ", nothing repeats and there are no new deals.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "Untick anything you want to keep as it is.",
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
                            ChangeCard(checked = active, onToggle = { skippedMerges = if (active) skippedMerges + merge.keepId else skippedMerges - merge.keepId }) {
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
                            ChangeCard(checked = change.id !in skippedRenames, onToggle = { skippedRenames = if (change.id in skippedRenames) skippedRenames - change.id else skippedRenames + change.id }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(change.from, style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(change.to, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                if (!change.reason.isNullOrBlank()) Text(change.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (deals.isNotEmpty()) {
                        item { SectionLabel(if (deals.size == 1) "Use a deal" else "Use ${deals.size} deals") }
                        items(deals, key = { "deal-" + dealKey(it) }) { deal ->
                            val key = dealKey(deal)
                            ChangeCard(checked = key !in skippedDeals, onToggle = { skippedDeals = if (key in skippedDeals) skippedDeals - key else skippedDeals + key }) {
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
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loading && plan != null && !plan.isEmpty) {
                    Button(onClick = { onApply(chosenRenames, chosenMerges, chosenDeals) }, enabled = changes > 0) {
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

@Composable
private fun ChangeCard(checked: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.size(40.dp))
            Column(modifier = Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    }
}
