package nl.baskt.ui.common

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.basket.DictationSheet
import nl.baskt.ui.basket.useServerDictation

/** One microphone flow for all screens; each caller decides what to do with the transcript. */
@Composable
fun rememberVoiceInput(viewModel: AppViewModel, prompt: String, onTranscript: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val online by viewModel.online.collectAsState()
    val serverStt by viewModel.serverStt.collectAsState()
    val deliver by rememberUpdatedState(onTranscript)
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val language = settings?.resolvedLanguage ?: java.util.Locale.getDefault().language
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) deliver(text)
        }
    }
    fun local() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { speech.launch(intent) }.onFailure {
            viewModel.notify("Dictation isn’t available on this phone")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = true
        else {
            // Say why the other recogniser shows up, instead of switching without a word.
            viewModel.notify("No microphone access: using the phone’s own dictation")
            local()
        }
    }
    // One sheet for both phases: it records, then stays open with a progress line while the clip is transcribed.
    if (recording || transcribing) DictationSheet(
        transcribing = transcribing,
        onCancel = { job?.cancel(); recording = false; transcribing = false },
        onUnavailable = { recording = false; local() },
        onRecorded = { audio ->
            transcribing = true
            recording = false
            job = scope.launch {
                val text = try { viewModel.container.api.transcribe(audio, language) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                finally { transcribing = false }
                if (text == null) {
                    viewModel.notify("Couldn’t transcribe: using the phone’s own dictation")
                    local()
                } else if (text.isNotBlank()) deliver(text)
                else viewModel.notify("No speech heard")
            }
        },
    )
    return {
        if (!recording && !transcribing) {
            viewModel.stopSpeaking()
            if (!useServerDictation(online, serverStt)) local()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = true
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
