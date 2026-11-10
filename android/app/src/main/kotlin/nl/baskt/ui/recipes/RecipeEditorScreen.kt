package nl.baskt.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import nl.baskt.data.RecipeDraft
import nl.baskt.data.RecipeStep
import nl.baskt.data.RecipeSummary
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing

/**
 * Write a recipe by hand, or describe it: baskt first looks for matching recipes on the sites (open one to
 * check it and save it, or say none fits), then drafts one for you to review before saving. Everything typed
 * survives a rotation, and leaving with unsaved text asks first.
 */
@Composable
fun RecipeEditorScreen(viewModel: AppViewModel, recipeId: String?, onBack: () -> Unit) {
    val mine by viewModel.myRecipes.collectAsState()
    val generate by viewModel.generate.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val online by viewModel.online.collectAsState()
    val recipeDetail by viewModel.recipeDetail.collectAsState()
    val scope = rememberCoroutineScope()
    val existing = mine.firstOrNull { it.id == recipeId }
    val startIngredients = existing?.ingredientLines?.joinToString("\n") ?: ""
    val startSteps = existing?.steps?.map { it.text }?.ifEmpty { listOf("") } ?: listOf("")
    var description by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var servings by rememberSaveable(existing?.id) { mutableStateOf(existing?.servings ?: "") }
    var ingredients by rememberSaveable(existing?.id) { mutableStateOf(startIngredients) }
    var steps by rememberSaveable(existing?.id) { mutableStateOf(startSteps) }
    var origin by rememberSaveable(existing?.id) { mutableStateOf(existing?.origin ?: "manual") }
    var showEditor by rememberSaveable(existing?.id) { mutableStateOf(existing != null) }
    var noneFit by rememberSaveable { mutableStateOf(false) }
    // The search for matches is reset once per visit, and a draft is filled in once: a rotation must not redo either.
    var started by rememberSaveable { mutableStateOf(false) }
    var draftFilled by rememberSaveable { mutableStateOf(false) }
    // Save was tapped with something missing: the empty fields now say so.
    var attempted by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var previewUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!started) { viewModel.clearGenerate(); started = true }
        if (recipeId != null && existing == null) viewModel.loadMyRecipes()
    }
    LaunchedEffect(generate?.draft) {
        val draft = generate?.draft ?: return@LaunchedEffect
        if (draftFilled) return@LaunchedEffect
        title = draft.title; servings = draft.servings ?: ""; ingredients = draft.ingredientLines.joinToString("\n"); steps = draft.steps.map { it.text }.ifEmpty { listOf("") }
        origin = "ai"; showEditor = true; draftFilled = true
    }
    fun currentDraft() = RecipeDraft(
        title = title.trim(),
        description = description.trim().ifBlank { existing?.description },
        servings = servings.trim().ifBlank { null },
        ingredientLines = ingredients.lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.isNotEmpty() },
        steps = steps.map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotEmpty() }.map { RecipeStep(it) },
    )
    fun write() { noneFit = true; draftFilled = false; viewModel.draftRecipe(description.trim()) }
    fun moveStep(from: Int, to: Int) { if (to in steps.indices) steps = steps.toMutableList().also { java.util.Collections.swap(it, from, to) } }
    fun removeStep(index: Int) {
        val removed = steps[index]
        steps = if (steps.size == 1) listOf("") else steps.filterIndexed { i, _ -> i != index }
        // An empty step has nothing to bring back.
        if (removed.isNotBlank()) viewModel.notifyUndo("Removed step ${index + 1}") {
            steps = if (steps == listOf("")) listOf(removed) else steps.toMutableList().also { it.add(index.coerceAtMost(it.size), removed) }
        }
    }

    val missingTitle = title.isBlank()
    val missingIngredients = ingredients.isBlank()
    val missingSteps = steps.none { it.isNotBlank() }
    val dirty = title != (existing?.title ?: "") || servings != (existing?.servings ?: "") || ingredients != startIngredients || steps != startSteps || (existing == null && description.isNotBlank())
    fun leave() { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    if (confirmDiscard) ConfirmDialog(
        title = if (existing != null) "Discard your changes?" else "Discard this recipe?",
        body = "What you typed won’t be saved.",
        confirmLabel = "Discard",
        dismissLabel = "Keep editing",
        onDismiss = { confirmDiscard = false },
        onConfirm = onBack,
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (existing != null) "Edit recipe" else "New recipe") },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                // Save lives in the bar so it stays reachable however long the steps get.
                actions = {
                    if (showEditor) Button(
                        onClick = {
                            if (missingTitle || missingIngredients || missingSteps) attempted = true
                            else scope.launch { if (viewModel.saveRecipe(currentDraft(), origin, existing?.id) != null) onBack() }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.Screen), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (existing == null && !showEditor) {
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Describe the dish") },
                    placeholder = { Text("e.g. arroz cubano: rice with a fried egg and tomato sauce") },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { showEditor = true; origin = "manual" }) { Text("Write it myself") }
                    Button(onClick = { noneFit = false; viewModel.findRecipeMatches(description.trim()) }, enabled = online && description.isNotBlank() && !generating) { Text("Find it on recipe sites") }
                }
                if (generating) LoadingRow(if (generate?.matches?.isNotEmpty() == true || noneFit) "Writing the recipe…" else "Looking for matching recipes…")
                val matches = generate?.matches ?: emptyList()
                if (matches.isNotEmpty() && generate?.draft == null && !noneFit) {
                    SectionHeader("Is it one of these?")
                    for (match in matches) {
                        // A tap only opens the recipe to look at; saving it is its own button in that sheet.
                        Card(onClick = { previewUrl = match.url; viewModel.openRecipe(match) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Spacing.Card)) {
                                Text(match.displayTitle, style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(match.source, match.title.takeIf { it != match.displayTitle }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(enabled = online && !generating, onClick = { write() }) { Text("Write it for me") }
                    }
                } else if (generate != null && matches.isEmpty() && generate?.draft == null && !generating) {
                    Text("No matching recipes on the sites", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(enabled = online, onClick = { write() }) { Text("Write it for me") }
                    }
                }
            }
            if (showEditor) {
                if (origin == "ai") Text("Drafted by baskt. Check the amounts and steps before saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = attempted && missingTitle,
                    supportingText = if (attempted && missingTitle) { { Text("Give the recipe a title") } } else null,
                )
                OutlinedTextField(value = servings, onValueChange = { servings = it }, label = { Text("Servings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = ingredients, onValueChange = { ingredients = it }, label = { Text("Ingredients") }, minLines = 5, modifier = Modifier.fillMaxWidth(),
                    isError = attempted && missingIngredients,
                    supportingText = { Text(if (attempted && missingIngredients) "Add at least one ingredient" else "One per line, with amounts") },
                )
                SectionHeader("Steps")
                if (attempted && missingSteps) Text("Add at least one step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                // Reordering: the dragged step follows the finger and swaps with neighbours as it passes them.
                var dragging by remember { mutableStateOf<Int?>(null) }
                var dragOffset by remember { mutableStateOf(0f) }
                val heights = remember { mutableMapOf<Int, Int>() }
                for ((index, step) in steps.withIndex()) {
                    val isDragged = dragging == index
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .onSizeChanged { heights[index] = it.height }
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 8.dp.toPx() else 0f }
                            .zIndex(if (isDragged) 1f else 0f),
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                        OutlinedTextField(
                            value = step,
                            onValueChange = { value -> steps = steps.toMutableList().also { it[index] = value } },
                            placeholder = { Text("What to do in this step") },
                            minLines = 2,
                            isError = attempted && missingSteps && index == 0,
                            modifier = Modifier.weight(1f),
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                            IconButton(onClick = { removeStep(index) }) { Icon(Icons.Default.Delete, contentDescription = "Remove step ${index + 1}") }
                            // Dragging needs a steady finger, so a screen reader gets "Move up" and "Move down" on the handle instead.
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Reorder step ${index + 1}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .semantics {
                                        customActions = listOfNotNull(
                                            if (index > 0) CustomAccessibilityAction("Move up") { moveStep(index, index - 1); true } else null,
                                            if (index < steps.lastIndex) CustomAccessibilityAction("Move down") { moveStep(index, index + 1); true } else null,
                                        )
                                    }
                                    .pointerInput(steps.size) {
                                        detectDragGestures(
                                            onDragStart = { dragging = index; dragOffset = 0f },
                                            onDragEnd = { dragging = null; dragOffset = 0f },
                                            onDragCancel = { dragging = null; dragOffset = 0f },
                                        ) { change, amount ->
                                            change.consume()
                                            val current = dragging ?: return@detectDragGestures
                                            dragOffset += amount.y
                                            val below = heights[current + 1]
                                            val above = heights[current - 1]
                                            if (below != null && dragOffset > below / 2f) {
                                                moveStep(current, current + 1)
                                                dragging = current + 1
                                                dragOffset -= below
                                            } else if (above != null && dragOffset < -above / 2f) {
                                                moveStep(current, current - 1)
                                                dragging = current - 1
                                                dragOffset += above
                                            }
                                        }
                                    }.minimumInteractiveComponentSize(),
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { steps = steps + "" }) { IconLabel(Icons.Default.Add, "Add step") }
                }
            }
        }
    }

    val preview = generate?.matches?.firstOrNull { it.url == previewUrl }
    if (preview != null) {
        fun closePreview() { previewUrl = null; viewModel.closeRecipe() }
        RecipeSheet(title = preview.displayTitle, detail = recipeDetail, onDismiss = { closePreview() }) {
            Button(enabled = recipeDetail != null, onClick = { savePreview(viewModel, preview); closePreview(); onBack() }) { Text("Save to my recipes") }
        }
    }
}

/** Copies the looked-at site recipe into My recipes and says so. */
private fun savePreview(viewModel: AppViewModel, recipe: RecipeSummary) {
    viewModel.saveSiteRecipeAsMine(recipe)
    viewModel.notify("Saved “${recipe.displayTitle}” to My recipes")
}
