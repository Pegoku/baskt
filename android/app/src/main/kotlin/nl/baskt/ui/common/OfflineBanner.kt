package nl.baskt.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.baskt.ui.AppViewModel

/** Shown while the server is unreachable: saved data is displayed, edits wait in a queue. */
@Composable
fun OfflineBanner(viewModel: AppViewModel, needsServer: String? = null) {
    val online by viewModel.online.collectAsState()
    val pending by viewModel.pending.collectAsState()
    if (online && pending.isEmpty()) return
    Surface(color = if (online) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        !online && pending.isNotEmpty() -> "Offline — showing saved data. ${pending.size} change${if (pending.size == 1) "" else "s"} will be sent when the server is back."
                        !online -> "Offline — showing saved data." + (needsServer?.let { " $it" } ?: "")
                        else -> "Sending ${pending.size} queued change${if (pending.size == 1) "" else "s"}…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!online) TextButton(onClick = { viewModel.retryConnection() }) { Text("Retry") }
        }
    }
}
