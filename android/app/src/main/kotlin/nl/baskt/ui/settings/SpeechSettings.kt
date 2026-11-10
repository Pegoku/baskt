package nl.baskt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import nl.baskt.data.SpeechModels
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.DropdownPicker
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SwitchRow

/** The voice rows of "Language & voice": whether replies are spoken, and with which voice. */
@Composable
fun SpeechSettings(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val speaking by viewModel.speaking.collectAsState()
    var catalogue by remember { mutableStateOf<SpeechModels?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val language = settings?.resolvedLanguage ?: "en"
    LaunchedEffect(settings?.baseUrl, settings?.token, language, retry) {
        error = false
        catalogue = null
        try { catalogue = viewModel.container.api.speechModels(language) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    val supported = catalogue?.models.orEmpty().filter { language in it.languages }
    val chosen = supported.firstOrNull { it.id == settings?.ttsModel } ?: supported.firstOrNull()
    val sortedVoices = remember(chosen, language) {
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag(language))
        chosen?.voices.orEmpty().sortedWith { first, second ->
            collator.compare(chosen?.voiceNames?.get(first) ?: first, chosen?.voiceNames?.get(second) ?: second)
        }
    }
    val selectedVoice = settings?.ttsVoice?.takeIf { it in chosen?.voices.orEmpty() } ?: chosen?.voices?.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow("Speak replies", "Reads the assistant’s reply aloud after a voice message", checked = settings?.speakReplies == true, modifier = Modifier.heightIn(min = 48.dp)) { viewModel.setSpeakReplies(it) }
        when {
            error -> {
                Text("Couldn’t load the voices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text("Try again") }
            }
            catalogue == null -> LoadingRow("Loading voices…", modifier = Modifier.heightIn(min = 48.dp))
            catalogue?.configured != true -> Text("Voice replies aren’t available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            chosen == null -> Text("No voice speaks your suggestion language", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                DropdownPicker(
                    label = "Voice model",
                    options = supported,
                    selected = chosen,
                    optionLabel = { it.name },
                    onPick = { viewModel.setSpeechVoice(it.id, it.voices.first()) },
                    modifier = Modifier.padding(top = 4.dp),
                )
                DropdownPicker(
                    label = "Voice",
                    options = sortedVoices,
                    selected = selectedVoice,
                    optionLabel = { chosen.voiceNames[it] ?: it },
                    onPick = { viewModel.setSpeechVoice(chosen.id, it) },
                    modifier = Modifier.padding(top = 4.dp),
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    val playing = speaking == "preview"
                    TextButton(onClick = { if (playing) viewModel.stopSpeaking() else selectedVoice?.let { viewModel.previewSpeech(chosen.id, it) } }) {
                        IconLabel(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing) "Stop" else "Test voice")
                    }
                }
            }
        }
    }
}
