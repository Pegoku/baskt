package nl.baskt.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** "1 item", "3 items": every count in the app goes through here so none reads "1 items". */
fun counted(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"

/** The spacing every screen shares, so cards, sheets and lists line up the same. */
object Spacing {
    /** Side margin of a screen and of everything in a list. */
    val Screen = 16.dp
    /** Padding inside a card. */
    val Card = 12.dp
    /** Gap between cards or rows in a list. */
    val ListGap = 8.dp
    /** Side padding inside a bottom sheet. */
    val SheetSide = 20.dp
    /** Space under the last control of a bottom sheet. */
    val SheetBottom = 32.dp
    /** Bottom padding of a list that scrolls under a floating action button. */
    val FabClearance = 96.dp
}

/** The one section header: a short coloured label above a group of rows or cards. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/**
 * The one quantity stepper: minus, value, plus, each button a full 48dp target. [value] is what sits between
 * the buttons ("3", "as written"); [noun] names what is counted so a screen reader hears "Fewer servings".
 */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    noun: String = "",
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    valueWidth: Dp = 40.dp,
) {
    val of = if (noun.isBlank()) "" else " $noun"
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus, enabled = minusEnabled) { Icon(Icons.Default.Remove, contentDescription = "Fewer$of") }
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.widthIn(min = valueWidth))
        IconButton(onClick = onPlus, enabled = plusEnabled) { Icon(Icons.Default.Add, contentDescription = "More$of") }
    }
}

/**
 * The one search field: a leading magnifier, a clear button once there is text, and the keyboard's Search key
 * runs [onSearch] and puts the keyboard away.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Search,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus(); onSearch() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The one single-choice control: a connected group of toggle buttons (the M3 Expressive replacement for
 * segmented buttons). Use it for two to four short options that switch a view or a setting.
 */
@Composable
fun ChoiceGroup(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, fill: Boolean = true) {
    Row(modifier = if (fill) modifier.fillMaxWidth() else modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, label ->
            ToggleButton(
                checked = index == selected,
                onCheckedChange = { if (index != selected) onSelect(index) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = (if (fill) Modifier.weight(1f) else Modifier).semantics { role = Role.RadioButton },
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The one dropdown picker: a labelled read-only field with an arrow that opens the choices, the current one marked. */
@Composable
fun <T> DropdownPicker(label: String, options: List<T>, selected: T?, optionLabel: (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, placeholder: String = "") {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(if (open) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The field only shows the value; this layer over it takes the tap, so the keyboard never opens.
        androidx.compose.foundation.layout.Box(Modifier.matchParentSize().clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = "Choose $label") { open = true })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { open = false; onPick(option) },
                    trailingIcon = { if (option == selected) Icon(Icons.Default.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}

/** A setting or option that is on or off: the whole row toggles, the switch only shows the state. */
@Composable
fun SwitchRow(title: String, body: String?, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A row that goes somewhere or opens something: title, optional one-line summary, optional trailing content. */
@Composable
fun NavRow(title: String, body: String? = null, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** "Something is happening": the expressive indicator at a size it is drawn for, next to a short label. */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingIndicator(modifier = Modifier.size(32.dp).semantics { contentDescription = text })
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The body of every bottom sheet: an optional title, scrolling content, and the actions pinned under it so
 * they never scroll out of reach. Sides are 20dp and the bottom 32dp. Put the dismissing action first and the
 * confirming one last in [actions]; they are laid out right-aligned.
 */
@Composable
fun SheetBody(
    title: String? = null,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(start = Spacing.SheetSide, end = Spacing.SheetSide, bottom = Spacing.SheetBottom)) {
        if (title != null || titleTrailing != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                titleTrailing?.invoke(this)
            }
        }
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        if (actions != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
