package nl.baskt.ui.basket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import nl.baskt.data.DuplicateGroup
import nl.baskt.data.DuplicateResolution

/**
 * Review the duplicates the server found. Each group gets one decision: merge into a single entry
 * (default), keep exactly one of them, or leave them alone. Nothing changes until "Apply".
 */
@Composable
fun DuplicatesSheet(groups: List<DuplicateGroup>, loading: Boolean, onDismiss: () -> Unit, onApply: (List<Pair<DuplicateGroup, DuplicateResolution>>) -> Unit) {
    var decisions by remember(groups) { mutableStateOf<Map<Int, DuplicateResolution>>(groups.indices.associateWith { DuplicateResolution.Merge }) }
    val changes = decisions.values.count { it != DuplicateResolution.KeepAll }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Duplicates", style = MaterialTheme.typography.titleLarge)
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Checking your list for repeats…") }
                groups.isEmpty() -> Text("No duplicates found. Every open item is a different product.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(
                    if (groups.size == 1) "One group of entries means the same thing." else "${groups.size} groups of entries mean the same thing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!loading && groups.isNotEmpty()) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(groups) { index, group ->
                        DuplicateGroupCard(group, decisions[index] ?: DuplicateResolution.Merge) { decisions = decisions + (index to it) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loading && groups.isNotEmpty()) {
                    Button(onClick = { onApply(groups.mapIndexed { index, group -> group to (decisions[index] ?: DuplicateResolution.Merge) }) }, enabled = changes > 0) {
                        Text(if (changes == 0) "Nothing to change" else "Apply")
                    }
                }
                TextButton(onClick = onDismiss) { Text(if (groups.isEmpty() && !loading) "Close" else "Cancel") }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup, resolution: DuplicateResolution, onChange: (DuplicateResolution) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(group.items.joinToString(" · ") { it.text }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (group.reason.isNotBlank()) Text(group.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Option(
                selected = resolution == DuplicateResolution.Merge,
                title = "Merge into one",
                detail = "Keeps the entry with the best product picks, ×${group.mergedQuantity} in total",
                onClick = { onChange(DuplicateResolution.Merge) },
            )
            for (item in group.items) {
                Option(
                    selected = resolution is DuplicateResolution.Keep && resolution.id == item.id,
                    title = "Keep only “${item.text}”" + if (item.quantity > 1) " ×${item.quantity}" else "",
                    detail = item.folder?.let { "In folder $it" },
                    onClick = { onChange(DuplicateResolution.Keep(item.id)) },
                )
            }
            Option(selected = resolution == DuplicateResolution.KeepAll, title = "Keep all, they are different", detail = null, onClick = { onChange(DuplicateResolution.KeepAll) })
        }
    }
}

@Composable
private fun Option(selected: Boolean, title: String, detail: String?, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
