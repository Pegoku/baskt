package nl.baskt.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import nl.baskt.data.RecipeDetail
import nl.baskt.ui.common.ChoiceGroup
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted

/** "4" as "4 servings"; a value that already carries its own word ("4 personen", "1 loaf") is shown as written. */
internal fun servingsLabel(servings: String): String = servings.trim().toIntOrNull()?.let { counted(it, "serving") } ?: servings.trim()

/**
 * Recipe viewer: photo, ingredients and numbered steps (with step images when the site has them), translated by
 * the server. The sheet's title is the recipe's name. [actions] are pinned under the scrolling content, so the
 * main one is always in reach; [extra] is drawn at the end of the scrolling content (the "ask or change" field).
 */
@Composable
fun RecipeSheet(title: String, detail: RecipeDetail?, onDismiss: () -> Unit, expanded: Boolean = false, extra: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit) {
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as nl.baskt.BasktApp).container
    val stock by container.basket.stock.collectAsState()
    val basketItems by container.basket.items.collectAsState()
    val shopping = basketItems.filter { it.kind != "group" && !it.checked }
    val ownedTexts = stock.map { it.text } + shopping.map { it.text }
    val ownedTranslation = nl.baskt.ui.common.rememberTranslation(ownedTexts, ownedTexts.isNotEmpty())
    val source = detail?.original ?: detail
    val originalMissing = detail?.original == null && detail?.originalTitle != null && detail.originalTitle != detail.title
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var original by androidx.compose.runtime.saveable.rememberSaveable(detail?.sourceUrl, title) { androidx.compose.runtime.mutableStateOf(false) }
    // Two smaller requests instead of one: the ingredients (with title and description) show first, and the steps
    // are only asked for once those arrived. Both are usually on disk already, warmed by the recipe list.
    val texts = source?.let { nl.baskt.data.recipeHeadTexts(it) } ?: emptyList()
    val translation = nl.baskt.ui.common.rememberTranslation(texts, source != null && !original, descriptionIndices = if (texts.isEmpty()) emptyList() else listOf(texts.lastIndex))
    val headTranslated = source != null && !original && translation?.translated == true
    val stepTexts = source?.steps?.map { it.text } ?: emptyList()
    val stepsTranslation = nl.baskt.ui.common.rememberTranslation(stepTexts, headTranslated && stepTexts.isNotEmpty())
    val displayed = if (source != null && headTranslated) {
        var index = 0
        source.copy(title = translation.texts[index++], ingredientLines = source.ingredientLines.map { translation.texts[index++] },
            servings = translation.texts[index++].ifBlank { null }, totalTime = translation.texts[index++].ifBlank { null }, description = translation.texts[index].ifBlank { null },
            steps = if (stepsTranslation?.translated == true) source.steps.mapIndexed { step, value -> value.copy(text = stepsTranslation.texts[step]) } else source.steps)
    } else source
    val stepsPending = headTranslated && stepTexts.isNotEmpty() && stepsTranslation?.translated != true
    // "In stock" / "In basket" per ingredient line. Every wording of the line is compared with every owned item,
    // so words are extracted once per text and the result only recomputed when its inputs change (it used to run
    // on each recomposition and froze the sheet's opening for over a second).
    val ingredientVariants = displayed?.ingredientLines?.indices?.map { index ->
        listOfNotNull(displayed.ingredientLines[index], source?.ingredientLines?.getOrNull(index), detail?.ingredientLines?.getOrNull(index), translation?.texts?.getOrNull(index + 1)).distinct()
    } ?: emptyList()
    val availability by androidx.compose.runtime.produceState(emptyList<Pair<Boolean, Boolean>>(), ingredientVariants, ownedTexts, ownedTranslation, stock.size) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val owned = ownedTexts.indices.map { position ->
                listOfNotNull(ownedTexts[position], ownedTranslation?.texts?.getOrNull(position)).map { nl.baskt.data.ingredientWords(it) }
            }
            ingredientVariants.map { variants ->
                val words = variants.map { nl.baskt.data.ingredientWords(it) }
                fun matches(position: Int) = words.any { line -> owned[position].any { nl.baskt.data.ingredientWordsMatch(line, it) } }
                (stock.indices.any { matches(it) }) to (owned.indices.drop(stock.size).any { matches(it) })
            }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Fixed sheet height: it opens half-way and can be dragged up (or straight to full height when [expanded]),
    // so the content arriving does not make it jump.
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = expanded)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetBody(
            title = displayed?.title ?: title,
            modifier = Modifier.fillMaxHeight(0.92f),
            titleTrailing = { nl.baskt.ui.common.OriginalToggle(original) { original = !original } },
            actions = actions,
        ) {
            if (!original && source != null && translation?.translated != true) Text(if (translation == null) "Translating…" else "Couldn’t translate, showing the original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (original && originalMissing) {
                detail.sourceUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    androidx.compose.material3.TextButton(onClick = { uri.openUri(url) }) { Text("View original recipe page") }
                }
            }
            val image = displayed?.imageUrl
            if (image != null) {
                nl.baskt.ui.common.ZoomableImage(image, displayed.title, modifier = Modifier.fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.large))
            }
            displayed?.description?.takeIf { it.isNotBlank() }?.let { nl.baskt.ui.common.DescriptionText(it) }
            val meta = listOfNotNull(displayed?.servings?.takeIf { it.isNotBlank() }?.let { servingsLabel(it) }, displayed?.totalTime)
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (displayed == null) {
                Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { LoadingRow("Loading the recipe…") }
            } else {
                val showSteps = tab == 1 && displayed.steps.isNotEmpty()
                if (displayed.steps.isNotEmpty()) ChoiceGroup(listOf("Ingredients · ${displayed.ingredientLines.size}", "Steps · ${displayed.steps.size}"), if (showSteps) 1 else 0, onSelect = { tab = it })
                if (!showSteps) {
                    for ((index, line) in displayed.ingredientLines.withIndex()) {
                        val (inStock, inBasket) = availability.getOrElse(index) { false to false }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (inStock) Tag("In stock")
                            if (inBasket) Tag("In basket")
                        }
                    }
                } else {
                    if (stepsPending) Text(if (stepsTranslation == null) "Translating the steps…" else "Couldn’t translate the steps, showing the original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for ((index, step) in displayed.steps.withIndex()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${index + 1}", style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(step.text, style = MaterialTheme.typography.bodyMedium)
                                if (step.imageUrl != null) {
                                    nl.baskt.ui.common.ZoomableImage(step.imageUrl, displayed.title, modifier = Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.medium))
                                }
                            }
                        }
                    }
                }
            }
            extra?.invoke()
        }
    }
}
