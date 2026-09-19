package nl.baskt.ui.basket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Comment mode's input in the tidy sheet: one free-text remark about the selected proposals ("double the
 * cookies amount", "it should be bolsa de lechugas"). The assistant revises those proposals.
 */
@Composable
fun CommentBar(selectedCount: Int, busy: Boolean, onSend: (String) -> Unit, onVoice: (() -> Unit)? = null) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        val instruction = text.trim()
        if (instruction.isEmpty() || busy) return
        onSend(instruction)
        text = ""
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (selectedCount) {
                    0 -> "Hold a proposal to select it, then tell me what should change."
                    1 -> "What should change about this proposal?"
                    else -> "What should change about these $selectedCount proposals?"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("e.g. double the amount, or “call it bolsa de lechugas”") },
                    singleLine = true,
                    enabled = !busy && selectedCount > 0,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    trailingIcon = if (onVoice == null) null else ({ IconButton(onClick = onVoice, enabled = !busy) { Icon(Icons.Default.Mic, contentDescription = "Dictate") } }),
                )
                if (busy) LoadingIndicator(modifier = Modifier.size(40.dp))
                else FilledIconButton(onClick = { send() }, enabled = text.isNotBlank() && selectedCount > 0) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Apply") }
            }
        }
    }
}
