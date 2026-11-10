package nl.baskt.ui.basket

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.data.RecipeSuggestion
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Stepper
import nl.baskt.ui.common.counted

/**
 * The add field with its suggestions.
 *
 * A suggestion chip fills the field when tapped. "Select several" above the chips turns them into tick
 * chips, so a few suggestions (or the ingredients of a recipe) can be added in one go; the buttons for that
 * take the place of the header, so the chips never move under the finger. The quantity stepper only shows
 * once something is typed, on its own row, so the field keeps the full width.
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
    placeholder: String = "Add an idea, e.g. halfvolle melk",
    allowFolders: Boolean = true,
    onVoice: (() -> Unit)? = null,
    focusRequest: kotlinx.coroutines.flow.MutableStateFlow<Boolean>? = null,
) {
    // Saved across rotation: a half-typed idea, its quantity and the ticked suggestions must not vanish.
    var text by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableIntStateOf(1) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(setOf()) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val wantsFocus = focusRequest?.collectAsState()?.value ?: false
    LaunchedEffect(wantsFocus) { if (wantsFocus) { focusRequester.requestFocus(); focusRequest.value = false } }
    val chips = if (recipe != null) recipe.ingredients else suggestions
    if (selected.isNotEmpty() && selected.any { it !in chips }) selected = selected.filter { it in chips }.toSet()
    val showChips = chips.isNotEmpty() && text.isNotBlank()
    if (!showChips && selecting) selecting = false

    fun reset() {
        text = ""
        quantity = 1
        selected = emptySet()
        selecting = false
    }
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        onAdd(value, quantity)
        reset()
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            if (showChips) {
                // One row of fixed height above the chips: a caption and "Select several", or the actions for the ticked chips.
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Spacing.Screen),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (selecting) {
                        val picked = chips.filter { it in selected }
                        TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Cancel", maxLines = 1) }
                        if (allowFolders) {
                            FilledTonalButton(onClick = { onAddGroup(recipe?.title ?: text.trim(), picked); reset() }, enabled = picked.isNotEmpty()) { Text("Add as folder", maxLines = 1) }
                        }
                        Button(onClick = { onAddMany(picked); reset() }, enabled = picked.isNotEmpty()) {
                            Text(if (picked.isEmpty()) "Add" else if (allowFolders) "Add ${picked.size}" else "Add ${counted(picked.size, "item")}", maxLines = 1)
                        }
                    } else {
                        Text(
                            if (recipe != null) "${recipe.title} · ${counted(recipe.ingredients.size, "ingredient")}" else "Suggestions",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (chips.size > 1) TextButton(onClick = { selecting = true }) { Text("Select several", maxLines = 1) }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    maxLines = if (recipe != null) 4 else 2,
                ) {
                    for (chip in chips) {
                        if (selecting) {
                            FilterChip(
                                selected = chip in selected,
                                onClick = { selected = if (chip in selected) selected - chip else selected + chip },
                                label = { Text(chip, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                // The check mark's place is always there, so ticking a chip cannot reflow the rows.
                                leadingIcon = { Box(Modifier.size(FilterChipDefaults.IconSize)) { if (chip in selected) Icon(Icons.Default.Check, contentDescription = null) } },
                            )
                        } else {
                            SuggestionChip(onClick = { text = chip; onTextChanged(chip) }, label = { Text(chip, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { changed ->
                        // The field wraps long ideas, so soft keyboards show Enter instead of Done. A single typed
                        // line break therefore adds the idea; a pasted list (several characters at once) keeps its lines.
                        val typedEnter = changed.length == text.length + 1 && changed.count { it == '\n' } == text.count { it == '\n' } + 1
                        if (typedEnter) submit() else { text = changed; onTextChanged(changed) }
                    },
                    // A hardware Enter adds the idea too (Shift+Enter still gives a new line).
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) { submit(); true } else false
                    },
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = false,
                    maxLines = 3,
                )
                if (text.isBlank() && onVoice != null) {
                    FilledIconButton(onClick = onVoice) { Icon(Icons.Default.Mic, contentDescription = "Dictate") }
                } else {
                    FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
            }
            // Only once there is something to count, so an empty bar is just the field.
            AnimatedVisibility(visible = text.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = Spacing.Screen, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Stepper(value = "$quantity", onMinus = { if (quantity > 1) quantity -= 1 }, onPlus = { quantity += 1 }, minusEnabled = quantity > 1)
                }
            }
        }
    }
}
