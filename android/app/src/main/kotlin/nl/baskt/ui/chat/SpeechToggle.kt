package nl.baskt.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.baskt.data.ChatMessage
import nl.baskt.ui.AppViewModel

@Composable
fun SpeechToggle(viewModel: AppViewModel, message: ChatMessage) {
    val settings by viewModel.settings.collectAsState()
    val speaking by viewModel.speaking.collectAsState()
    val enabled = settings?.speakReplies == true
    val label = if (enabled) "Turn off spoken replies" else "Turn on spoken replies and read this message"
    Row {
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
            IconButton(onClick = { viewModel.toggleMessageSpeech(message) }) {
                Icon(if (enabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, label)
            }
        }
        if (speaking == message.id) TextButton(onClick = { viewModel.stopSpeaking() }) {
            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
            Text("Stop audio")
        } else if (enabled) TextButton(onClick = { viewModel.speakMessage(message) }) { Text("Read aloud") }
    }
}
