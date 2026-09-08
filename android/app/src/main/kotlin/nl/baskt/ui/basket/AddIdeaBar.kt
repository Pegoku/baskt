package nl.baskt.ui.basket

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.baskt.data.RecipeSuggestion

/**
 * The idea input with its autocomplete row.
 * Chips: tap fills the field, long-press starts multi-select; a recipe suggestion lists every ingredient.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddIdeaBar(
    suggestions: List<String>,
    recipe: RecipeSuggestion?,
    onTextChanged: (String) -> Unit,
    onAdd: (String, Int) -> Unit,
    onAddGroup: (String, List<String>?) -> Unit,
    onAddMany: (List<String>) -> Unit,
    placeholder: String = "Add an idea… e.g. halfvolle milk",
    allowFolders: Boolean = true,
    onVoice: (() -> Unit)? = null,
    focusRequest: kotlinx.coroutines.flow.MutableStateFlow<Boolean>? = null,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val wantsFocus = focusRequest?.collectAsState()?.value ?: false
    LaunchedEffect(wantsFocus) { if (wantsFocus) { focusRequester.requestFocus(); focusRequest?.value = false } }
    var quantity by remember { mutableIntStateOf(1) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val chips = if (recipe != null) recipe.ingredients else suggestions
    if (selected.isNotEmpty() && selected.any { it !in chips }) selected = selected.filter { it in chips }.toSet()

    fun reset() {
        text = ""
        quantity = 1
        selected = emptySet()
    }
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        // Typing "ingredients for X" and pressing add creates a folder from a recipe.
        if (recipe != null && allowFolders) onAddGroup(value, null) else onAdd(value, quantity)
        reset()
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
        Column {
            if (chips.isNotEmpty() && text.isNotBlank()) {
                if (recipe != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${recipe.title} · ${recipe.ingredients.size} ingredients",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.isEmpty() && allowFolders) {
                            TextButton(onClick = { onAddGroup(recipe.title, recipe.ingredients); reset() }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  Add all as folder")
                            }
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxLines = if (recipe != null) 5 else 2,
                ) {
                    for (chip in chips) {
                        SelectableChip(
                            text = chip,
                            selected = chip in selected,
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (chip in selected) selected - chip else selected + chip
                                else {
                                    text = chip
                                    onTextChanged(chip)
                                }
                            },
                            onLongClick = { selected = if (chip in selected) selected - chip else selected + chip },
                        )
                    }
                }
                if (selected.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val picked = chips.filter { it in selected }
                        if (allowFolders) {
                            FilledTonalButton(onClick = { onAddGroup(recipe?.title ?: text.trim(), picked); reset() }) { Text("Folder (${picked.size})") }
                        }
                        Button(onClick = { onAddMany(picked); reset() }) { Text("Add ${picked.size} separately") }
                        TextButton(onClick = { selected = emptySet() }) { Text("Cancel") }
                    }
                } else if (chips.isNotEmpty()) {
                    Text(
                        "Tap to use · hold to select several",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onTextChanged(it) },
                    // Enter adds the idea (Shift+Enter or a pasted list still gives new lines).
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) { submit(); true } else false
                    },
                    placeholder = { Text(placeholder) },
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { quantity += 1 }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, contentDescription = "More") }
                    Text("$quantity", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { if (quantity > 1) quantity -= 1 }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Remove, contentDescription = "Less") }
                }
                if (text.isBlank() && onVoice != null) {
                    FilledIconButton(onClick = onVoice) { Icon(Icons.Default.Mic, contentDescription = "Dictate") }
                } else {
                    FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                        Icon(if (recipe != null && allowFolders) Icons.Default.CreateNewFolder else Icons.Default.Add, contentDescription = "Add")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}
