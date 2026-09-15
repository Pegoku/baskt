package nl.baskt.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import nl.baskt.data.SpeechModels
import nl.baskt.ui.AppViewModel

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
    val selectedVoice = settings?.ttsVoice?.takeIf { it in chosen?.voices.orEmpty() } ?: chosen?.voices?.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice conversations", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak replies")
                Text("Read assistant replies aloud after you send a voice message.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings?.speakReplies == true, onCheckedChange = { viewModel.setSpeakReplies(it) })
        }
        Text("Voice messages send automatically. You can also turn spoken replies on or off below any assistant message.", style = MaterialTheme.typography.bodySmall)
        when {
            error -> TextButton(onClick = { retry++ }) { Text("Retry loading voices") }
            catalogue == null -> Text("Loading voices…")
            catalogue?.configured != true -> Text("Speech is not configured on this server.")
            chosen == null -> Text("No voice supports your selected language.")
            else -> {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(chosen.name) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        supported.forEach { model ->
                            DropdownMenuItem(text = { Column { Text(model.name); Text(model.description, style = MaterialTheme.typography.bodySmall) } },
                                onClick = { expanded = false; viewModel.setSpeechVoice(model.id, model.voices.first()) })
                        }
                    }
                }
                Text(chosen.description, style = MaterialTheme.typography.bodySmall)
                if (chosen.id != settings?.ttsModel) Text("Using a model that supports your language.", style = MaterialTheme.typography.bodySmall)
                var voiceMenu by remember(chosen.id, language) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { voiceMenu = true }) {
                            Text(chosen.voiceNames[selectedVoice] ?: selectedVoice.orEmpty())
                        }
                        DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            chosen.voices.forEach { voice ->
                                DropdownMenuItem(text = { Text(chosen.voiceNames[voice] ?: voice) },
                                    onClick = { voiceMenu = false; viewModel.setSpeechVoice(chosen.id, voice) })
                            }
                        }
                    }
                    TextButton(onClick = {
                        if (speaking == "preview") viewModel.stopSpeaking()
                        else selectedVoice?.let { viewModel.previewSpeech(chosen.id, it) }
                    }) { Text(if (speaking == "preview") "Stop preview" else "Test voice") }
                }
                Text("Samples are generated when played and cached for later.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
