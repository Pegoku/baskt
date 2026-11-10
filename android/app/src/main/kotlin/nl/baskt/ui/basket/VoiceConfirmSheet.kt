package nl.baskt.ui.basket

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import nl.baskt.data.VoiceItem
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.counted

/**
 * Review what was understood from dictation. Retracted items ("never mind, I have rice") stay in the
 * sheet unticked so nothing mentioned is silently lost. When nothing was understood, [onRetry] starts
 * another dictation straight from here.
 */
@Composable
fun VoiceConfirmSheet(items: List<VoiceItem>, loading: Boolean, transcript: String?, onDismiss: () -> Unit, onRetry: () -> Unit, onConfirm: (List<VoiceItem>) -> Unit) {
    val ordered = items.filter { it.wanted } + items.filter { !it.wanted }
    // Ticks are kept by position, so the same word said twice stays two rows, and they survive a rotation.
    var selected by rememberSaveable(items, stateSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })) {
        mutableStateOf(ordered.indices.filter { ordered[it].wanted }.toSet())
    }
    val nothing = !loading && items.isEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBody(
            title = when {
                loading -> "Reading your request…"
                nothing -> "Nothing understood"
                else -> "Add to basket?"
            },
            actions = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (nothing) Button(onClick = onRetry) { Text("Try again") }
                else Button(onClick = { onConfirm(ordered.filterIndexed { index, _ -> index in selected }) }, enabled = !loading && selected.isNotEmpty()) {
                    Text(if (selected.isEmpty()) "Add" else "Add ${counted(selected.size, "item")}")
                }
            },
        ) {
            // What was heard, so a misheard word explains a surprising item instead of looking like a bug.
            if (transcript != null) Text("“$transcript”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LoadingRow("Finding the items in it…")
            else if (nothing) Text("That didn’t sound like groceries.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                ordered.forEachIndexed { index, item ->
                    val checked = index in selected
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { selected = if (it) selected + index else selected - index })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.kind == "recipe") Icon(Icons.Default.Folder, contentDescription = "Folder", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                                Text(
                                    item.text + if (item.quantity > 1) " ×${item.quantity}" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (!checked) TextDecoration.LineThrough else null,
                                    color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.note != null) Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
