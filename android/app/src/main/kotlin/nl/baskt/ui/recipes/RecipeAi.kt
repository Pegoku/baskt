package nl.baskt.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.RecipeDetail
import nl.baskt.data.RecipeDraft
import nl.baskt.data.RecipeTurn
import nl.baskt.ui.AppViewModel

/** A change the AI proposed for the open recipe, waiting for Apply or Discard. */
data class RecipeChangePreview(val request: String, val draft: RecipeDraft, val summary: String)

/**
 * Inline AI for the open recipe: a compact field at the bottom of the sheet. Questions ("how long do I boil the
 * egg?") are answered right above it; a change ("without olives", "for 6") is shown as a preview of what would
 * change and only saved after Apply. [onApply] saves the draft and returns whether it worked.
 */
@Composable
fun RecipeAiPanel(
    detail: RecipeDetail?,
    viewModel: AppViewModel,
    chat: List<RecipeTurn>,
    onChat: (List<RecipeTurn>) -> Unit,
    preview: RecipeChangePreview?,
    onPreview: (RecipeChangePreview?) -> Unit,
    savesCopy: Boolean,
    onApply: suspend (RecipeDraft) -> Boolean,
) {
    var message by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val online by viewModel.online.collectAsState()
    val scope = rememberCoroutineScope()
    fun send() {
        val value = message.trim()
        if (value.isEmpty() || working || detail == null) return
        working = true
        scope.launch {
            try {
                val reply = viewModel.askRecipe(detail, value, chat) ?: return@launch
                message = ""
                when {
                    reply.answer != null -> onChat(chat + RecipeTurn(value, reply.answer))
                    reply.draft != null -> onPreview(RecipeChangePreview(value, reply.draft, reply.summary))
                }
            } finally { working = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        for (turn in chat) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(turn.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(turn.answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
        preview?.let { change ->
            ChangePreviewCard(change, detail, savesCopy, working, onDiscard = { onPreview(null) }) {
                working = true
                scope.launch { try { if (onApply(change.draft)) onPreview(null) } finally { working = false } }
            }
        }
        OutlinedTextField(
            value = message, onValueChange = { message = it }, enabled = !working && detail != null && online,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(if (online) "Ask or change: \"without olives\"" else "Recipe AI needs a connection") },
            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            trailingIcon = {
                if (working) LoadingIndicator(modifier = Modifier.size(32.dp))
                else IconButton(onClick = { send() }, enabled = message.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
        )
    }
}

/** What the change would do: removed and added ingredient lines, and how many steps differ. */
@Composable
private fun ChangePreviewCard(change: RecipeChangePreview, detail: RecipeDetail?, savesCopy: Boolean, working: Boolean, onDiscard: () -> Unit, onApply: () -> Unit) {
    val before = detail?.ingredientLines.orEmpty()
    val removed = before.filterNot { it in change.draft.ingredientLines }
    val added = change.draft.ingredientLines.filterNot { it in before }
    val oldSteps = detail?.steps.orEmpty().map { it.text }
    val newSteps = change.draft.steps.map { it.text }
    // Rewritten steps show up on both sides, added or dropped ones on one: the larger side is the number that changed.
    val stepsChanged = maxOf(newSteps.count { it !in oldSteps }, oldSteps.count { it !in newSteps })
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("“${change.request}”", style = MaterialTheme.typography.labelLarge)
            if (change.summary.isNotBlank()) Text(change.summary, style = MaterialTheme.typography.bodyMedium)
            for (line in removed) Text("− $line", style = MaterialTheme.typography.bodySmall, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.error)
            for (line in added) Text("+ $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (change.draft.servings != detail?.servings && change.draft.servings != null) Text("Servings: ${detail?.servings ?: "?"} → ${change.draft.servings}", style = MaterialTheme.typography.bodySmall)
            if (stepsChanged > 0) Text(if (stepsChanged == 1) "1 step changes" else "$stepsChanged steps change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (removed.isEmpty() && added.isEmpty() && stepsChanged == 0) Text("Nothing would change.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDiscard, enabled = !working) { Text("Discard") }
                Button(onClick = onApply, enabled = !working) { Text(if (savesCopy) "Save as my recipe" else "Apply") }
            }
        }
    }
}
