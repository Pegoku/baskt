package nl.baskt.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import nl.baskt.data.ChatMessage
import nl.baskt.ui.AppViewModel

/** An icon-only button whose label shows as a tooltip on long-press and is read by a screen reader. */
@Composable
internal fun TipIconButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

/** Reads this one assistant message aloud; while it plays, the same button stops it. */
@Composable
fun ReadAloudButton(viewModel: AppViewModel, message: ChatMessage) {
    val speaking by viewModel.speaking.collectAsState()
    val playing = speaking == message.id
    TipIconButton(if (playing) "Stop" else "Read aloud", if (playing) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp) {
        if (playing) viewModel.stopSpeaking() else viewModel.speakMessage(message)
    }
}

/** The "Speak replies" setting, once, in the chat's top bar (it is also in Settings). */
@Composable
fun SpeakRepliesToggle(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text("Speak replies") } }, state = rememberTooltipState()) {
        IconToggleButton(checked = settings?.speakReplies == true, onCheckedChange = { viewModel.setSpeakReplies(it) }) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = "Speak replies")
        }
    }
}
