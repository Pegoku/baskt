package nl.baskt.ui.basket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The comment input on the tidy screen. It only shows once the Comment button of a change was tapped;
 * [label] names what the remark is about ("Comment on “melk”"), and the remark itself is free text
 * ("double the amount", "call it bolsa de lechugas"). The changes commented on are then revised.
 */
@Composable
fun CommentBar(label: String, busy: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit, onVoice: (() -> Unit)? = null) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        val instruction = text.trim()
        if (instruction.isEmpty() || busy) return
        onSend(instruction)
        text = ""
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onCancel, enabled = !busy) { Icon(Icons.Default.Close, contentDescription = "Cancel comment") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. double the amount", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = if (onVoice == null) null else ({ IconButton(onClick = onVoice, enabled = !busy) { Icon(Icons.Default.Mic, contentDescription = "Dictate") } }),
            )
            if (busy) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            else FilledIconButton(onClick = { send() }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send comment") }
        }
    }
}
