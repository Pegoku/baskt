package nl.baskt.ui.recipes

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.RecipeDraft
import nl.baskt.data.RecipeStep
import nl.baskt.ui.AppViewModel

/**
 * Write a recipe by hand, or describe it: baskt first looks for matching recipes on the sites
 * (pick one, or say none fits), then the AI drafts one for you to review before saving.
 */
@Composable
fun RecipeEditorScreen(viewModel: AppViewModel, recipeId: String?, onBack: () -> Unit) {
    val mine by viewModel.myRecipes.collectAsState()
    val generate by viewModel.generate.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val scope = rememberCoroutineScope()
    val existing = mine.firstOrNull { it.id == recipeId }
    var description by remember { mutableStateOf("") }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var servings by remember(existing?.id) { mutableStateOf(existing?.servings ?: "") }
    var ingredients by remember(existing?.id) { mutableStateOf(existing?.ingredientLines?.joinToString("\n") ?: "") }
    var steps by remember(existing?.id) { mutableStateOf(existing?.steps?.joinToString("\n") { it.text } ?: "") }
    var origin by remember { mutableStateOf(existing?.origin ?: "manual") }
    var showEditor by remember { mutableStateOf(existing != null) }
    var noneFit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.clearGenerate(); if (recipeId != null && existing == null) viewModel.loadMyRecipes() }
    LaunchedEffect(generate?.draft) {
        val draft = generate?.draft ?: return@LaunchedEffect
        title = draft.title; servings = draft.servings ?: ""; ingredients = draft.ingredientLines.joinToString("\n"); steps = draft.steps.joinToString("\n") { it.text }
        origin = "ai"; showEditor = true
    }
    fun currentDraft() = RecipeDraft(
        title = title.trim(),
        description = description.trim().ifBlank { existing?.description },
        servings = servings.trim().ifBlank { null },
        ingredientLines = ingredients.lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.isNotEmpty() },
        steps = steps.lines().map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotEmpty() }.map { RecipeStep(it) },
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (existing != null) "Edit recipe" else "New recipe") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (existing == null && !showEditor) {
                Text("Describe the dish", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("e.g. arroz cubano: rice with a fried egg and tomato sauce") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { noneFit = false; viewModel.findRecipeMatches(description.trim()) }, enabled = description.isNotBlank() && !generating) { Text("Find it on recipe sites") }
                    TextButton(onClick = { showEditor = true; origin = "manual" }) { Text("Write it myself") }
                }
                if (generating) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text(if (generate?.matches?.isNotEmpty() == true || noneFit) "Writing the recipe…" else "Looking for matching recipes…") }
                val matches = generate?.matches ?: emptyList()
                if (matches.isNotEmpty() && generate?.draft == null && !noneFit) {
                    Text("Is one of these what you mean?", style = MaterialTheme.typography.titleSmall)
                    for (match in matches) {
                        Card(onClick = { viewModel.saveSiteRecipeAsMine(match); onBack() }) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(match.displayTitle, style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(match.source, match.title.takeIf { it != match.displayTitle }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    FilledTonalButton(onClick = { noneFit = true; viewModel.draftRecipe(description.trim()) }) { Text("None of these — write it for me") }
                } else if (generate != null && matches.isEmpty() && generate?.draft == null && !generating) {
                    Text("Nothing similar found on the sites.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { noneFit = true; viewModel.draftRecipe(description.trim()) }) { Text("Write it for me with AI") }
                }
            }
            if (showEditor) {
                if (origin == "ai") Text("Drafted by the AI — check amounts and steps before saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = servings, onValueChange = { servings = it }, label = { Text("Servings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ingredients, onValueChange = { ingredients = it }, label = { Text("Ingredients, one per line, with amounts") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = steps, onValueChange = { steps = it }, label = { Text("Steps, one per line") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { if (viewModel.saveRecipe(currentDraft(), origin, existing?.id) != null) onBack() } }, enabled = title.isNotBlank() && ingredients.isNotBlank()) { Text(if (existing != null) "Save changes" else "Save recipe") }
                    TextButton(onClick = onBack) { Text("Cancel") }
                }
            }
        }
    }
}
