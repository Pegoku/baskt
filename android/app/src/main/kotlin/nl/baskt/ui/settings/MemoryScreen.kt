package nl.baskt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the app has learned from picks and rejections, newest first; wrong ones can be removed. */
@Composable
fun MemoryScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val memory by viewModel.memory.collectAsState()
    val names by viewModel.memoryNames.collectAsState()
    val loading by viewModel.memoryLoading.collectAsState()
    val failed by viewModel.memoryFailed.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val format = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    LaunchedEffect(Unit) { viewModel.loadMemory() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Learned preferences") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val empty = memory.isEmpty() && names.isEmpty()
        when {
            empty && loading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingRow("Loading…") }
            empty && failed -> EmptyState(
                "Couldn’t load what was learned",
                "Check the connection and try again",
                icon = Icons.Default.CloudOff,
                modifier = Modifier.fillMaxSize().padding(padding),
                action = { FilledTonalButton(onClick = { viewModel.loadMemory() }) { Text("Try again") } },
            )
            empty -> EmptyState(
                "Nothing learned yet",
                "What you pick and reject shows up here",
                icon = Icons.Default.Psychology,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
                if (names.isNotEmpty()) {
                    item("names-header") { SectionHeader("Wordings") }
                    items(names, key = { "name-" + it.id }) { name ->
                        Card(modifier = Modifier.animateItem()) {
                            Row(modifier = Modifier.padding(start = Spacing.Card, top = Spacing.Card, bottom = Spacing.Card, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(name.preferred, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Instead of “${name.source}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(format.format(Date(name.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { viewModel.deleteNameMemory(name.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                            }
                        }
                    }
                }
                if (memory.isNotEmpty()) item("choices-header") { SectionHeader("Picks and rejections", modifier = Modifier.padding(top = if (names.isNotEmpty()) 8.dp else 0.dp)) }
                items(memory, key = { it.id }) { choice ->
                    Card(modifier = Modifier.animateItem()) {
                        Row(modifier = Modifier.padding(start = Spacing.Card, top = Spacing.Card, bottom = Spacing.Card, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StoreBadge(choice.store, stores)
                                    Text(choice.itemText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                                Text(format.format(Date(choice.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (choice.chosenTitle != null) FeedbackLine(Icons.Default.ThumbUp, "Chosen", choice.chosenTitle, MaterialTheme.colorScheme.onSurface)
                                if (choice.rejectedTitles.isNotEmpty()) FeedbackLine(Icons.Default.ThumbDown, "Rejected", choice.rejectedTitles.joinToString(", "), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.deleteMemory(choice.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                        }
                    }
                }
            }
        }
    }
}

/** A product the matching was told was right (thumb up, "Chosen") or wrong (thumb down, "Rejected"). */
@Composable
private fun FeedbackLine(icon: ImageVector, meaning: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = meaning, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
