package nl.baskt.ui.basket

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.baskt.data.VoiceRecorder

/** Recordings stop here even if the phone is left on the counter; Whisper bills by audio length. */
private const val MAX_RECORDING_MS = 60_000L

/**
 * Records a dictation for server-side transcription. It listens until the speaker goes quiet (or they tap
 * Done) and hands the clip to [onRecorded]; [onUnavailable] means the microphone could not be opened at
 * all, which sends the caller to Android's own recogniser.
 */
@Composable
fun DictationSheet(onCancel: () -> Unit, onRecorded: (ByteArray) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val recorder = remember { VoiceRecorder(context) }
    var loudness by remember { mutableStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    val level by animateFloatAsState(loudness, animationSpec = tween(120), label = "level")

    fun finish() {
        if (finished) return
        finished = true
        val clip = recorder.stop()
        if (clip == null) onCancel() else onRecorded(clip)
    }

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    LaunchedEffect(Unit) {
        if (!recorder.start()) {
            onUnavailable()
            return@LaunchedEffect
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val silence = SilenceDetector()
        val started = System.currentTimeMillis()
        while (recorder.recording) {
            delay(100)
            val amplitude = recorder.amplitude()
            elapsedMs = System.currentTimeMillis() - started
            // Decay slowly so the ring follows the voice instead of flickering between syllables.
            loudness = maxOf(loudness * 0.75f, (amplitude / 18_000f).coerceIn(0f, 1f))
            if (silence.update(amplitude, elapsedMs) || elapsedMs >= MAX_RECORDING_MS) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                finish()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { recorder.cancel(); onCancel() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Listening…", style = MaterialTheme.typography.titleLarge)
            Text(
                "Say everything you need. It stops on its own when you go quiet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
                val primary = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.size(180.dp)) {
                    val base = size.minDimension / 5f
                    drawCircle(color = primary.copy(alpha = 0.10f), radius = base * (1.7f + level * 1.1f))
                    drawCircle(color = primary.copy(alpha = 0.18f), radius = base * (1.3f + level * 0.7f))
                    drawCircle(color = primary, radius = base)
                }
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(
                "%d:%02d".format(elapsedMs / 60_000, (elapsedMs / 1_000) % 60),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { recorder.cancel(); onCancel() }) { Text("Cancel") }
                Button(onClick = { finish() }) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Done")
                }
            }
        }
    }
}
