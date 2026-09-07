package nl.baskt.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import nl.baskt.data.Basket

/** Top-bar title that switches between baskets and manages them. */
@Composable
fun BasketSwitcherTitle(
    baskets: List<Basket>,
    currentId: String,
    onSwitch: (String) -> Unit,
    onCreate: (String, String?) -> Unit,
    onRename: (Basket, String, String?) -> Unit,
    onDelete: (Basket) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Basket?>(null) }
    var creating by remember { mutableStateOf(false) }
    val current = baskets.firstOrNull { it.id == currentId }

    Box {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { open = true }) {
                Text(current?.label ?: "baskt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch basket", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (basket in baskets) {
                DropdownMenuItem(
                    text = { Text(basket.label + if (basket.openCount > 0) "  ·  ${basket.openCount}" else "", fontWeight = if (basket.id == currentId) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSwitch(basket.id) },
                    trailingIcon = {
                        if (basket.id == currentId) {
                            IconButton(onClick = { open = false; editing = basket }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New basket…") }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }, onClick = { open = false; creating = true })
            if (current != null && baskets.size > 1) {
                DropdownMenuItem(
                    text = { Text("Delete “${current.name}”", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; onDelete(current) },
                )
            }
        }
    }
    if (creating) {
        BasketDialog(title = "New basket", initialName = "", initialEmoji = "", onDismiss = { creating = false }) { name, emoji -> creating = false; onCreate(name, emoji) }
    }
    editing?.let { basket ->
        BasketDialog(title = "Rename basket", initialName = basket.name, initialEmoji = basket.emoji ?: "", onDismiss = { editing = null }) { name, emoji -> editing = null; onRename(basket, name, emoji) }
    }
}

@Composable
private fun BasketDialog(title: String, initialName: String, initialEmoji: String, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, label = { Text("Icon") }, singleLine = true, modifier = Modifier.width(80.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), emoji.trim().ifBlank { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Overflow menu offering "Move to" / "Copy to" for every other basket. */
@Composable
fun TransferMenu(baskets: List<Basket>, currentId: String, onTransfer: (String, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var copyMode by remember { mutableStateOf(false) }
    val others = baskets.filter { it.id != currentId }
    if (others.isEmpty()) return
    Box {
        IconButton(onClick = { open = true; copyMode = false }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(if (copyMode) "Copy to" else "Move to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for (basket in others) {
                DropdownMenuItem(
                    text = { Text(basket.label) },
                    leadingIcon = { Icon(if (copyMode) Icons.Default.FileCopy else Icons.Default.DriveFileMove, contentDescription = null) },
                    onClick = { open = false; onTransfer(basket.id, copyMode) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(if (copyMode) "Move instead…" else "Copy instead…") }, onClick = { copyMode = !copyMode })
        }
    }
}
