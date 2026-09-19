package nl.baskt.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.RecipeDetail

/** Recipe viewer: photo, ingredients and numbered steps (with step images when the site has them), translated by the server. */
@Composable
fun RecipeSheet(title: String, detail: RecipeDetail?, onDismiss: () -> Unit, expanded: Boolean = false, actions: @Composable () -> Unit) {
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
    val texts = source?.let { listOf(it.title) + it.ingredientLines + it.steps.map { step -> step.text } + listOf(it.servings.orEmpty(), it.totalTime.orEmpty(), it.description.orEmpty()) } ?: emptyList()
    val translation = nl.baskt.ui.common.rememberTranslation(texts, source != null && !original, descriptionIndices = if (texts.isEmpty()) emptyList() else listOf(texts.lastIndex))
    val displayed = if (source != null && !original && translation?.translated == true) {
        var index = 0
        source.copy(title = translation.texts[index++], ingredientLines = source.ingredientLines.map { translation.texts[index++] },
            steps = source.steps.map { it.copy(text = translation.texts[index++]) },
            servings = translation.texts[index++].ifBlank { null }, totalTime = translation.texts[index++].ifBlank { null }, description = translation.texts[index].ifBlank { null })
    } else source
    var tab by remember { mutableIntStateOf(0) }
    // Fixed sheet height: it opens half-way and can be dragged up (or straight to full height when [expanded]);
    // content changes animate instead of jumping.
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = expanded)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Recipe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                nl.baskt.ui.common.OriginalToggle(original) { original = !original }
            }
            if (!original && source != null && translation?.translated != true) Text(if (translation == null) "Translating…" else "Translation unavailable · showing original", style = MaterialTheme.typography.labelSmall)
            if (original && originalMissing) {
                Text("The original was not saved with this older download.", style = MaterialTheme.typography.bodySmall)
                detail?.sourceUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    androidx.compose.material3.TextButton(onClick = { uri.openUri(url) }) { Text("View original recipe page") }
                }
            }
            val image = displayed?.imageUrl
            if (image != null) {
                nl.baskt.ui.common.ZoomableImage(image, displayed?.title ?: title, modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)))
            }
            Text(displayed?.title ?: title, style = MaterialTheme.typography.titleLarge)
            displayed?.description?.takeIf { it.isNotBlank() }?.let { nl.baskt.ui.common.DescriptionText(it) }
            val meta = listOfNotNull(displayed?.servings?.let { "$it servings" }, displayed?.totalTime)
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (displayed == null) {
                LoadingIndicator()
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ingredients (${displayed.ingredientLines.size})") }
                    SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = displayed.steps.isNotEmpty()) { Text("Steps (${displayed.steps.size})") }
                }
                if (tab == 0) {
                    for ((index, line) in displayed.ingredientLines.withIndex()) {
                        val variants = listOfNotNull(line, source?.ingredientLines?.getOrNull(index), detail?.ingredientLines?.getOrNull(index), translation?.texts?.getOrNull(index + 1))
                        fun matches(position: Int): Boolean = variants.any { ingredient ->
                            nl.baskt.data.ingredientMatches(ingredient, ownedTexts[position]) ||
                                ownedTranslation?.texts?.getOrNull(position)?.let { nl.baskt.data.ingredientMatches(ingredient, it) } == true
                        }
                        val inStock = stock.indices.any { matches(it) }
                        val inBasket = shopping.indices.any { matches(stock.size + it) }
                        Column {
                            Text("• $line", style = MaterialTheme.typography.bodyMedium)
                            val labels = listOfNotNull(if (inStock) "In stock" else null, if (inBasket) "In basket" else null)
                            if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                } else {
                    for ((index, step) in displayed.steps.withIndex()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(step.text, style = MaterialTheme.typography.bodyMedium)
                                if (step.imageUrl != null) {
                                    nl.baskt.ui.common.ZoomableImage(step.imageUrl, displayed.title, modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            actions()
        }
    }
}
