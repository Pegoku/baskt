package nl.baskt.ui.basket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import nl.baskt.data.VoiceItem

/**
 * Review what was understood from dictation. Retracted items ("never mind, I have rice") stay in the
 * list unchecked so nothing mentioned is silently lost.
 */
@Composable
fun VoiceConfirmSheet(items: List<VoiceItem>, loading: Boolean, onDismiss: () -> Unit, onConfirm: (List<VoiceItem>) -> Unit) {
    var selected by remember(items) { mutableStateOf(items.filter { it.wanted }.map { it.text }.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("I understood", style = MaterialTheme.typography.titleLarge)
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Listening to what you said…") }
            } else if (items.isEmpty()) {
                Text("Nothing that sounded like groceries. Try again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val ordered = items.filter { it.wanted } + items.filter { !it.wanted }
            for (item in ordered) {
                val checked = item.text in selected
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { selected = if (it) selected + item.text else selected - item.text })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            (if (item.kind == "recipe") "📁 " else "") + item.text + if (item.quantity > 1) "  ×${item.quantity}" else "",
                            textDecoration = if (!checked) TextDecoration.LineThrough else null,
                            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.note != null) Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(ordered.filter { it.text in selected }) }, enabled = !loading && selected.isNotEmpty()) { Text("Add ${selected.size}") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}
