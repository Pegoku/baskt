package nl.baskt.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.baskt.ui.AppViewModel

/** A queue that is still draining after this long while online is stuck, and that is worth a word. */
private const val STALLED_AFTER_MS = 10_000L

/**
 * A short inset card, shaped like the list rows below it, for the states the shopper must know about:
 * offline (saved data is shown, edits wait in a queue), a change that failed to sync, or a queue that has
 * been draining for too long. Ordinary edits pass through the queue in a blink and say nothing.
 */
@Composable
fun OfflineBanner(viewModel: AppViewModel) {
    val online by viewModel.online.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val failed = pending.count { it.failure != null }
    val draining = online && pending.isNotEmpty() && failed == 0
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(draining) {
        stalled = false
        if (draining) { delay(STALLED_AFTER_MS); stalled = true }
    }
    if (online && pending.isEmpty()) return
    if (draining && !stalled) return
    val changes = { count: Int -> if (count == 1) "1 change" else "$count changes" }
    val message = when {
        failed > 0 -> "${changes(failed)} didn't sync"
        !online && pending.isNotEmpty() -> "Offline · ${changes(pending.size)} waiting"
        !online -> "Offline · showing saved data"
        else -> "Still sending ${changes(pending.size)}…"
    }
    val retry = !online || failed > 0
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (failed > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = if (retry) 4.dp else 12.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (online) Icons.Default.Sync else Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (retry) TextButton(onClick = { viewModel.retryConnection() }) { Text("Retry") }
        }
    }
}
