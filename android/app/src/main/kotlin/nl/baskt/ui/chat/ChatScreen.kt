package nl.baskt.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.ChatMessage
import nl.baskt.data.ProposedChange
import nl.baskt.data.RecipeCard
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.recipes.RecipeSheet

/**
 * Assistant chat for the current basket. The assistant reads the basket, stock, stores and recipe sites and
 * proposes changes; nothing happens until you tick and apply them here.
 */
@Composable
fun ChatScreen(viewModel: AppViewModel, onBack: () -> Unit, onFolderAdded: () -> Unit) {
    val messages by viewModel.chat.collectAsState()
    val busy by viewModel.chatBusy.collectAsState()
    val online by viewModel.online.collectAsState()
    val steps by viewModel.chatSteps.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val recipeDetail by viewModel.recipeDetail.collectAsState()
    var input by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) input = spoken
        }
    }
    fun dictate() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, settings?.resolvedLanguage ?: java.util.Locale.getDefault().language)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "What would you like to ask?")
        }
        runCatching { speech.launch(intent) }.onFailure {
            android.widget.Toast.makeText(context, "Speech recognition is not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var openRecipe by remember { mutableStateOf<RecipeCard?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.loadChat() }
    LaunchedEffect(messages.size, busy, steps.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size + (if (busy) 1 else 0), scrollOffset = Int.MAX_VALUE / 2) }
    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy || !online) return
        input = ""
        viewModel.sendChat(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (messages.isNotEmpty()) IconButton(onClick = { viewModel.clearChat() }) { Icon(Icons.Default.DeleteSweep, contentDescription = "Clear chat") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) { send(); true } else false
                        },
                        placeholder = { Text("Ask or instruct… e.g. swap 1 kg bags for 500 g") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    val hasText = input.isNotBlank()
                    FilledIconButton(onClick = { if (hasText) send() else dictate() }, enabled = !busy && (!hasText || online)) {
                        Icon(if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic, contentDescription = if (hasText) "Send" else "Voice input")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            nl.baskt.ui.common.OfflineBanner(viewModel, needsServer = "The assistant needs the server.")
            if (messages.isEmpty() && !busy) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What can I do?", style = MaterialTheme.typography.titleMedium)
                    for (hint in listOf("Suggest three quick dinners for this week", "Swap the 1 kg bags for 500 g where you can", "What am I missing for pancakes?", "Remove everything that's already in stock")) {
                        AssistChip(onClick = { input = hint; send() }, label = { Text(hint) })
                    }
                    Text("The assistant looks things up (your basket, stock, the stores, recipe sites) and proposes changes. Nothing changes until you confirm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages, key = { it.id }) { message ->
                    if (message.role == "user") UserBubble(message.content)
                    else AssistantMessage(message, stores.map { it.code to it.name }.toMap(), onApply = { indices -> viewModel.applyProposal(message, indices) }, onOpenRecipe = { openRecipe = it; viewModel.openRecipe(it.url) }, onAddFolder = { viewModel.addRecipeFolder(it.url); onFolderAdded() })
                }
                if (busy) item("busy") {
                    // Live trace of the assistant's work: earlier steps dimmed, the current one with the spinner.
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (step in steps.dropLast(1).takeLast(4)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LoadingIndicator(modifier = Modifier.size(18.dp))
                                Text(steps.lastOrNull() ?: "Thinking…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    openRecipe?.let { card ->
        RecipeSheet(title = card.title, detail = recipeDetail, onDismiss = { openRecipe = null; viewModel.closeRecipe() }) {
            Button(onClick = { viewModel.addRecipeFolder(card.url); openRecipe = null; viewModel.closeRecipe(); onFolderAdded() }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Text("  Add as folder")
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)) {
            Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 300.dp))
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage, storeNames: Map<String, String>, onApply: (List<Int>) -> Unit, onOpenRecipe: (RecipeCard) -> Unit, onAddFolder: (RecipeCard) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.content.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)) {
                Text(message.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 320.dp))
            }
        }
        message.proposalJson?.let { proposal -> ProposalCard(message, proposal.summary, proposal.changes, proposal.applied ?: emptyList(), storeNames, onApply) }
        message.recipesJson?.takeIf { it.isNotEmpty() }?.let { cards ->
            for (card in cards) {
                Card(onClick = { onOpenRecipe(card) }) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (card.imageUrl != null) AsyncImage(model = card.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (card.source != null) Text(card.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onAddFolder(card) }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(message: ChatMessage, summary: String, changes: List<ProposedChange>, applied: List<Int>, storeNames: Map<String, String>, onApply: (List<Int>) -> Unit) {
    // First time: everything ticked except items already in stock. After applying some, start from nothing so the
    // remaining ones are a deliberate choice ("Apply 0").
    var selected by remember(message.id, applied.size) {
        mutableStateOf(if (applied.isEmpty()) changes.indices.filter { changes[it].inStock != true && changes[it].inList == null }.toSet() else emptySet())
    }
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(summary, style = MaterialTheme.typography.titleSmall)
            Text("Tick the changes you want, then apply.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((index, change) in changes.withIndex()) {
                val done = index in applied
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (done) Icon(Icons.Default.Check, contentDescription = "Applied", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    else Checkbox(checked = index in selected, onCheckedChange = { selected = if (it) selected + index else selected - index })
                    Icon(
                        when (change.type) { "add", "add_recipe_folder" -> Icons.Default.Add; "delete" -> Icons.Default.Remove; "replace" -> Icons.Default.SwapHoriz; "skip" -> Icons.Default.Remove; else -> Icons.Default.Edit },
                        contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(changeTitle(change), style = MaterialTheme.typography.bodyMedium, fontWeight = if (done) FontWeight.Normal else FontWeight.Medium)
                        changeDetail(change, storeNames)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    change.store?.let { StoreBadge(it, emptyList()) }
                }
            }
            val remaining = changes.indices.filter { it !in applied }
            if (remaining.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApply(selected.sorted()) }, enabled = selected.isNotEmpty()) { Text("Apply ${selected.size}") }
                    TextButton(onClick = { selected = if (selected.size == remaining.size) emptySet() else remaining.toSet() }) { Text(if (selected.size == remaining.size) "None" else "All") }
                }
            } else Text("All applied", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun changeTitle(change: ProposedChange): String = when (change.type) {
    "add" -> "Add “${change.text}”" + (change.quantity?.takeIf { it > 1 }?.let { " ×$it" } ?: "")
    "delete" -> "Delete “${change.text}”"
    "rename" -> "Rename “${change.from}” → “${change.to}”"
    "quantity" -> "Set “${change.text}” to ×${change.quantity}"
    "replace" -> "Replace product for “${change.text}”"
    "skip" -> "Skip “${change.text}” at this store"
    "add_recipe_folder" -> "Add recipe folder “${change.title}”"
    else -> change.type
}

private fun changeDetail(change: ProposedChange, storeNames: Map<String, String>): String? = when (change.type) {
    "add" -> when {
        change.inList != null -> "Already in your list as “${change.inList}”"
        change.inStock == true -> if (change.stockName != null) "Already in stock: “${change.stockName}”" else "Already in stock"
        else -> null
    }
    "replace" -> "${change.from ?: "current pick"} → ${change.to}"
    "add_recipe_folder" -> change.url
    else -> null
}
