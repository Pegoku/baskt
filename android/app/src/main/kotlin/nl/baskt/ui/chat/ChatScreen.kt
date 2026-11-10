package nl.baskt.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.ChatMessage
import nl.baskt.data.Product
import nl.baskt.data.ProposedChange
import nl.baskt.data.RecipeCard
import nl.baskt.data.StoreInfo
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.counted
import nl.baskt.ui.common.storeName
import nl.baskt.ui.recipes.RecipeSheet

/** Starters for an empty chat. Tapping one fills the field, so it can be edited before it is sent. */
private val SUGGESTIONS = listOf("Suggest three quick dinners for this week", "Swap the 1 kg bags for 500 g", "What’s missing for pancakes?", "Remove what’s already in stock")

/** No bubble is wider than this, whoever speaks. */
private val BubbleMaxWidth = 320.dp

/** The one bubble shape: the theme's large corners, with the corner on the speaker's side pulled in. */
@Composable
private fun bubbleShape(mine: Boolean): Shape {
    val large = MaterialTheme.shapes.large
    val tail = MaterialTheme.shapes.extraSmall.topStart
    return if (mine) large.copy(bottomEnd = tail) else large.copy(bottomStart = tail)
}

@Composable
private fun Bubble(mine: Boolean, content: @Composable () -> Unit) {
    Surface(color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = bubbleShape(mine)) {
        Box(modifier = Modifier.widthIn(max = BubbleMaxWidth).padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

/**
 * Assistant chat for the current basket. The assistant reads the basket, stock, stores and recipe sites and
 * proposes changes; nothing happens until you tick and apply them here. [onViewBasket] is the "View" action of
 * the notice shown after a recipe was added: adding one does not leave the conversation by itself.
 */
@Composable
fun ChatScreen(viewModel: AppViewModel, onBack: () -> Unit, onViewBasket: () -> Unit) {
    val messages by viewModel.chat.collectAsState()
    val busy by viewModel.chatBusy.collectAsState()
    val steps by viewModel.chatSteps.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val recipeDetail by viewModel.recipeDetail.collectAsState()
    val favourites by viewModel.recipeFavourites.collectAsState()
    val items by viewModel.basket.items.collectAsState()
    // Products already picked for an item in this basket show as added instead of offering a second copy.
    val inBasket = remember(items) { items.flatMap { item -> item.matches.mapNotNull { it.chosen?.id } }.toSet() }
    var input by rememberSaveable { mutableStateOf("") }
    // A message that failed or was stopped comes back to the field instead of being lost.
    val unsent by viewModel.chatUnsent.collectAsState()
    LaunchedEffect(unsent) {
        val text = unsent ?: return@LaunchedEffect
        if (input.isBlank()) input = text
        viewModel.chatUnsent.value = null
    }
    val dictate = nl.baskt.ui.common.rememberVoiceInput(viewModel, "Ask the assistant") { input = ""; viewModel.sendChat(it, voiceInput = true) }
    // The open recipe is kept by its address, so the sheet survives a rotation.
    var openRecipeUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val openRecipe = remember(openRecipeUrl, messages) { messages.flatMap { it.recipesJson.orEmpty() }.firstOrNull { it.url == openRecipeUrl } }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.loadChat(); viewModel.loadRecipeFavourites() }
    LaunchedEffect(messages.size, busy, steps.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + (if (busy) 1 else 0), scrollOffset = Int.MAX_VALUE / 2) }
    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        viewModel.sendChat(text)
    }
    fun addRecipe(card: RecipeCard) {
        viewModel.addRecipeFolder(card.url)
        viewModel.notify("Added “${card.title}” to your basket", "View") { onViewBasket() }
    }

    var confirmClear by rememberSaveable { mutableStateOf(false) }
    if (confirmClear) ConfirmDialog(
        title = "Clear chat?",
        body = "The assistant forgets this conversation. Your basket and learned preferences stay as they are.",
        confirmLabel = "Clear chat",
        onDismiss = { confirmClear = false },
        onConfirm = { viewModel.clearChat() },
    )
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    SpeakRepliesToggle(viewModel)
                    if (messages.isNotEmpty()) TipIconButton("Clear chat", Icons.Default.DeleteSweep) { confirmClear = true }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                // Navigation bar and keyboard insets both: the field rides on top of whichever is showing.
                Row(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = Spacing.Screen, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) { send(); true } else false
                        },
                        placeholder = { Text("Ask the assistant", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    val hasText = input.isNotBlank()
                    // While the assistant works the button stops it; otherwise it sends what is typed, or dictates.
                    FilledIconButton(onClick = { if (busy) viewModel.cancelChat() else if (hasText) send() else dictate() }) {
                        when {
                            busy -> Icon(Icons.Default.Stop, contentDescription = "Stop")
                            hasText -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            else -> Icon(Icons.Default.Mic, contentDescription = "Dictate")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            if (messages.isEmpty() && !busy) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState("Ask the assistant", "It proposes changes, and nothing changes until you apply them", icon = Icons.Default.AutoAwesome)
                    Column(modifier = Modifier.padding(horizontal = Spacing.Screen), horizontalAlignment = Alignment.CenterHorizontally) {
                        for (hint in SUGGESTIONS) SuggestionChip(onClick = { input = hint }, label = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Spacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(messages, key = { it.id }) { message ->
                        if (message.role == "user") UserBubble(message.content)
                        else AssistantMessage(viewModel, message, stores, inBasket, onApply = { indices -> viewModel.applyProposal(message, indices) }, onOpenRecipe = { openRecipeUrl = it.url; viewModel.openRecipe(nl.baskt.data.RecipeSummary(it.title, it.url, it.imageUrl, source = it.source)) }, onAddRecipe = { addRecipe(it) })
                    }
                    if (busy) item("busy") {
                        // Live trace of the assistant's work: earlier steps dimmed, the current one with the spinner.
                        Bubble(mine = false) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (step in steps.dropLast(1).takeLast(4)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(steps.lastOrNull() ?: "Thinking…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    openRecipe?.let { card ->
        val recipe = nl.baskt.data.RecipeSummary(card.title, card.url, card.imageUrl, source = card.source)
        RecipeSheet(title = card.title, detail = recipeDetail, onDismiss = { openRecipeUrl = null; viewModel.closeRecipe() }) {
            if (!card.url.startsWith("baskt://")) {
                val favourite = favourites.any { it.url == card.url }
                IconToggleButton(checked = favourite, onCheckedChange = { viewModel.toggleRecipeFavourite(recipe) }) {
                    Icon(if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = if (favourite) "Remove from favourites" else "Add to favourites")
                }
            }
            Button(onClick = { addRecipe(card); openRecipeUrl = null; viewModel.closeRecipe() }) { IconLabel(Icons.Default.AddShoppingCart, "Add to basket") }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Bubble(mine = true) { SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) } }
    }
}

@Composable
private fun AssistantMessage(viewModel: AppViewModel, message: ChatMessage, stores: List<StoreInfo>, inBasket: Set<String>, onApply: (List<Int>) -> Unit, onOpenRecipe: (RecipeCard) -> Unit, onAddRecipe: (RecipeCard) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
        if (message.content.isNotBlank()) Bubble(mine = false) { SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodyLarge) } }
        for (product in message.products) ProductCard(product, stores, product.id in inBasket) { viewModel.addFromProduct(product) }
        message.proposalJson?.let { proposal -> ProposalCard(message, proposal.summary, proposal.changes, proposal.applied ?: emptyList(), stores, onApply) }
        message.recipesJson?.takeIf { it.isNotEmpty() }?.let { cards ->
            for (card in cards) {
                Card(onClick = { onOpenRecipe(card) }) {
                    Row(modifier = Modifier.padding(start = Spacing.Card, top = Spacing.Card, bottom = Spacing.Card, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (card.imageUrl != null) AsyncImage(model = card.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium))
                        else ItemThumb(null, null, size = 64)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (card.source != null) Text(card.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TipIconButton("Add to basket", Icons.Default.AddShoppingCart) { onAddRecipe(card) }
                    }
                }
            }
        }
        // One "Read aloud" per message that has something to read; the global setting lives in the top bar.
        if (message.content.isNotBlank() || !message.proposalJson?.summary.isNullOrBlank()) ReadAloudButton(viewModel, message)
    }
}

/** A product the answer names: tap the card for its details, or add it straight to the basket as this exact pick. */
@Composable
private fun ProductCard(product: Product, stores: List<StoreInfo>, added: Boolean, onAdd: () -> Unit) {
    // The card is the one tap target for the details, so the row inside it does not open them a second time.
    var details by rememberSaveable(product.id) { mutableStateOf(false) }
    if (details) nl.baskt.ui.common.ProductSheet(product) { details = false }
    Card(onClick = { details = true }) {
        ProductRow(product, Modifier.padding(Spacing.Card), openable = false, trailing = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StoreLogo(product.store, stores, size = 20)
                FilledTonalButton(onClick = onAdd, enabled = !added) { Text(if (added) "Added" else "Add") }
            }
        })
    }
}

@Composable
private fun ProposalCard(message: ChatMessage, summary: String, changes: List<ProposedChange>, applied: List<Int>, stores: List<StoreInfo>, onApply: (List<Int>) -> Unit) {
    // First time: everything ticked except items already in stock or in the basket. After applying some, what was
    // left unticked was left out on purpose, so it stays unticked until the user ticks it.
    var selected by rememberSaveable(message.id, applied.size, stateSaver = listSaver<Set<Int>, Int>({ it.toList() }, { it.toSet() })) {
        mutableStateOf(if (applied.isEmpty()) changes.indices.filter { changes[it].inStock != true && changes[it].inList == null }.toSet() else emptySet())
    }
    Card {
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary, style = MaterialTheme.typography.titleSmall)
            for ((index, change) in changes.withIndex()) {
                val done = index in applied
                val rowModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                // The whole row is the checkbox; an applied row keeps the same indent with a tick in the box's place.
                Row(
                    modifier = if (done) rowModifier else rowModifier.toggleable(value = index in selected, role = Role.Checkbox, onValueChange = { selected = if (it) selected + index else selected - index }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Default.Check, contentDescription = "Applied", tint = MaterialTheme.colorScheme.primary)
                        else Checkbox(checked = index in selected, onCheckedChange = null)
                    }
                    Icon(changeIcon(change.type), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(changeTitle(change, stores), style = if (done) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyMediumEmphasized)
                        changeDetail(change)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (change.type == "add_recipe_folder") 1 else 2, overflow = TextOverflow.Ellipsis) }
                    }
                    change.store?.let { StoreBadge(it, stores) }
                }
            }
            val remaining = changes.indices.filter { it !in applied }
            if (remaining.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    val all = selected.size == remaining.size
                    TextButton(onClick = { selected = if (all) emptySet() else remaining.toSet() }) { Text(if (all) "None" else "All") }
                    Button(onClick = { onApply(selected.sorted()) }, enabled = selected.isNotEmpty()) { Text(if (selected.isEmpty()) "Apply changes" else "Apply ${counted(selected.size, "change")}") }
                }
            } else Text("All applied", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One icon per kind of change, so a minus never means both "delete" and "not at this store". */
private fun changeIcon(type: String) = when (type) {
    "add" -> Icons.Default.Add
    "add_recipe_folder" -> Icons.Default.AddShoppingCart
    "add_skipped" -> Icons.AutoMirrored.Filled.PlaylistAdd
    "stock_add" -> Icons.Default.Inventory2
    "delete" -> Icons.Default.Delete
    "stock_remove" -> Icons.Default.Remove
    "replace" -> Icons.Default.SwapHoriz
    "skip" -> Icons.Default.Block
    "quantity" -> Icons.Default.Numbers
    "servings" -> Icons.Default.Groups
    else -> Icons.Default.Edit
}

/** The item or recipe a change is about, quoted; a change that names nothing says "this item". */
private fun subject(change: ProposedChange): String = (change.text ?: change.title)?.let { "“$it”" } ?: "this item"

private fun changeTitle(change: ProposedChange, stores: List<StoreInfo>): String = when (change.type) {
    "add" -> "Add " + (change.quantity?.takeIf { it > 1 }?.let { "$it× " } ?: "") + subject(change)
    "delete" -> "Delete ${subject(change)}"
    "rename" -> "Rename " + (change.from?.let { "“$it”" } ?: subject(change)) + (change.to?.let { " to “$it”" } ?: "")
    "quantity" -> "Set ${subject(change)}" + (change.quantity?.let { " to $it×" } ?: "")
    "replace" -> "Replace the product for ${subject(change)}"
    "skip" -> "Not at ${change.store?.let { storeName(it, stores) } ?: "this store"}: ${subject(change)}"
    "add_recipe_folder" -> "Add recipe folder ${subject(change)}"
    "stock_add" -> "Mark ${subject(change)} as in stock"
    "stock_remove" -> "Remove ${subject(change)} from stock"
    "servings" -> "Make ${subject(change)}" + (change.servings?.let { " for $it" } ?: "")
    "add_skipped" -> "Add skipped ingredients to ${subject(change)}"
    // A kind of change this version does not know: never show its internal name.
    else -> "Change ${subject(change)}"
}

private fun changeDetail(change: ProposedChange): String? = when (change.type) {
    "add" -> when {
        change.inList != null -> "Already in your basket as “${change.inList}”"
        change.inStock == true -> if (change.stockName != null) "Already in stock: “${change.stockName}”" else "Already in stock"
        else -> null
    }
    "replace" -> listOfNotNull(change.from ?: "Current pick", change.to).joinToString(" → ")
    // The site's name says where the recipe is from; the full address would only wrap.
    "add_recipe_folder" -> change.url?.let { url -> runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url }
    "stock_add" -> change.quantityText
    "servings" -> if (change.from != null && change.servings != null) "${change.from} → ${counted(change.servings, "serving")}" else null
    "add_skipped" -> change.items?.joinToString(", ")
    else -> null
}
