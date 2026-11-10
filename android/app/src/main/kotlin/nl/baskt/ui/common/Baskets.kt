package nl.baskt.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.Basket

/**
 * Top-bar title that switches between baskets. The menu lists the baskets, then "New basket…" and
 * "Manage baskets…"; the manage sheet is where any basket, not only the open one, is renamed or deleted.
 */
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
    // Ids, not objects, so an open sheet or dialog survives a rotation.
    var managing by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = baskets.firstOrNull { it.id == currentId }
    val editing = baskets.firstOrNull { it.id == editingId }
    val deleting = baskets.firstOrNull { it.id == deletingId }

    Box {
        TextButton(onClick = { open = true }) {
            Text(current?.label ?: "baskt", style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch basket", tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (basket in baskets) {
                DropdownMenuItem(
                    text = { Text(basket.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { open = false; onSwitch(basket.id) },
                    // The open basket carries a check mark; the number is how many items are still open in each.
                    leadingIcon = { if (basket.id == currentId) Icon(Icons.Default.Check, contentDescription = "Open now") else Box(Modifier.width(24.dp)) },
                    trailingIcon = { if (basket.openCount > 0) Text("${basket.openCount}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New basket…") }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }, onClick = { open = false; creating = true })
            DropdownMenuItem(text = { Text("Manage baskets…") }, leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }, onClick = { open = false; managing = true })
        }
    }
    // The sheet steps aside while a rename or delete dialog is up (never a dialog on top of a sheet) and comes back after.
    if (managing && editing == null && deleting == null) {
        ModalBottomSheet(onDismissRequest = { managing = false }) {
            SheetBody(title = "Manage baskets", actions = { TextButton(onClick = { managing = false }) { Text("Done") } }) {
                Column {
                    for (basket in baskets) {
                        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(basket.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(counted(basket.itemCount, "item"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editingId = basket.id }) { Icon(Icons.Default.Edit, contentDescription = "Rename ${basket.name}") }
                            // The last basket cannot go: there must always be one to add to.
                            IconButton(onClick = { deletingId = basket.id }, enabled = baskets.size > 1) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${basket.name}", tint = if (baskets.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        BasketDialog(title = "New basket", initialName = "", initialEmoji = "", onDismiss = { creating = false }) { name, emoji -> creating = false; onCreate(name, emoji) }
    }
    deleting?.let { basket ->
        ConfirmDialog(
            title = "Delete “${basket.name}”?",
            body = "All its items are deleted too. This can’t be undone.",
            confirmLabel = "Delete",
            onDismiss = { deletingId = null },
        ) { onDelete(basket) }
    }
    editing?.let { basket ->
        BasketDialog(title = "Edit basket", initialName = basket.name, initialEmoji = basket.emoji ?: "", onDismiss = { editingId = null }) { name, emoji -> editingId = null; onRename(basket, name, emoji) }
    }
}

/** Keeps only the last emoji (or letter) typed, whole: cutting by character count would split a composite emoji. */
private fun lastGrapheme(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val breaks = java.text.BreakIterator.getCharacterInstance().apply { setText(trimmed) }
    breaks.last()
    val start = breaks.previous()
    return if (start == java.text.BreakIterator.DONE) trimmed else trimmed.substring(start)
}

@Composable
private fun BasketDialog(title: String, initialName: String, initialEmoji: String, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var emoji by rememberSaveable { mutableStateOf(initialEmoji) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val changed = name.trim() != initialName || emoji.trim() != initialEmoji
    val canSave = name.isNotBlank() && changed
    fun save() { if (canSave) onSave(name.trim(), emoji.trim().ifBlank { null }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = emoji, onValueChange = { emoji = lastGrapheme(it) }, label = { Text("Emoji") }, singleLine = true, modifier = Modifier.width(88.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Moving or copying items to another basket, the same way everywhere (basket selection, folder, item detail).
 * A screen creates one with [rememberTransferPicker], puts [TransferMenuItems] in its overflow menu, and gets
 * the chosen basket back through its callback.
 */
@Stable
class TransferPicker internal constructor(
    /** False when there is no other basket to move or copy to; the menu entries then leave themselves out. */
    val available: Boolean,
    private val show: (copy: Boolean) -> Unit,
) {
    /** Opens the list of target baskets, for a copy or a move. */
    fun open(copy: Boolean) = show(copy)
}

/**
 * Hosts the "Move to" / "Copy to" sheet that lists the other baskets. [onTransfer] gets the picked basket id and
 * whether it is a copy. Call it once per screen, outside the menu, so the sheet outlives the closing menu.
 */
@Composable
fun rememberTransferPicker(baskets: List<Basket>, currentId: String, onTransfer: (basketId: String, copy: Boolean) -> Unit): TransferPicker {
    // null = closed, false = moving, true = copying.
    var copyMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val others = baskets.filter { it.id != currentId }
    val transfer by rememberUpdatedState(onTransfer)
    copyMode?.let { copy ->
        ModalBottomSheet(onDismissRequest = { copyMode = null }) {
            SheetBody(title = if (copy) "Copy to" else "Move to", actions = { TextButton(onClick = { copyMode = null }) { Text("Cancel") } }) {
                Column {
                    for (basket in others) {
                        NavRow(title = basket.label, body = counted(basket.itemCount, "item")) { copyMode = null; transfer(basket.id, copy) }
                    }
                }
            }
        }
    }
    val available = others.isNotEmpty()
    return remember(available) { TransferPicker(available) { copyMode = it } }
}

/**
 * The two overflow entries, "Move to…" and "Copy to…". Each opens the target list of [picker]; [onClick]
 * runs first so the caller can close its menu.
 */
@Composable
fun TransferMenuItems(picker: TransferPicker, onClick: () -> Unit) {
    if (!picker.available) return
    DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) }, onClick = { onClick(); picker.open(copy = false) })
    DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }, onClick = { onClick(); picker.open(copy = true) })
}

/** A "More" button holding only the two transfer entries, for a top bar that has no other overflow menu. */
@Composable
fun TransferMenu(baskets: List<Basket>, currentId: String, onTransfer: (String, Boolean) -> Unit) {
    val picker = rememberTransferPicker(baskets, currentId, onTransfer)
    if (!picker.available) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { TransferMenuItems(picker) { open = false } }
    }
}
