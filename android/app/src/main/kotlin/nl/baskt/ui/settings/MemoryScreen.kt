package nl.baskt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every thumbs up/down and pick the app has learned from, newest first; wrong ones can be removed. */
@Composable
fun MemoryScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val memory by viewModel.memory.collectAsState()
    val names by viewModel.memoryNames.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val format = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    LaunchedEffect(Unit) { viewModel.loadMemory() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learned preferences") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (memory.isEmpty() && names.isEmpty()) {
            Text("Nothing learned yet. Picks, thumbs, \"none of these fit\" and wording corrections from comment mode end up here.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (names.isNotEmpty()) {
                item { Text("Wordings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                items(names, key = { "name-" + it.id }) { name ->
                    Card {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(name.preferred, style = MaterialTheme.typography.titleSmall)
                                Text("instead of “${name.source}” · ${format.format(Date(name.createdAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.deleteNameMemory(name.id) }) { Icon(Icons.Default.Delete, contentDescription = "Forget") }
                        }
                    }
                }
                if (memory.isNotEmpty()) item { Text("Picks and thumbs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
            items(memory, key = { it.id }) { choice ->
                Card {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoreBadge(choice.store, stores)
                                Text(choice.itemText, style = MaterialTheme.typography.titleSmall)
                                Text(format.format(Date(choice.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (choice.chosenTitle != null) Text("👍 ${choice.chosenTitle}", style = MaterialTheme.typography.bodySmall)
                            if (choice.rejectedTitles.isNotEmpty()) Text("👎 ${choice.rejectedTitles.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.deleteMemory(choice.id) }) { Icon(Icons.Default.Delete, contentDescription = "Forget") }
                    }
                }
            }
        }
    }
}
