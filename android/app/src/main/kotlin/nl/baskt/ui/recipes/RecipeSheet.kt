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
fun RecipeSheet(title: String, detail: RecipeDetail?, onDismiss: () -> Unit, actions: @Composable () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    // Fixed sheet height: it opens half-way and can be dragged up; content changes animate instead of jumping.
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxHeight(0.92f).padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val image = detail?.imageUrl
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)))
            }
            Text(detail?.title ?: title, style = MaterialTheme.typography.titleLarge)
            val meta = listOfNotNull(detail?.servings?.let { "$it servings" }, detail?.totalTime, detail?.originalTitle?.takeIf { it != detail.title })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail == null) {
                LoadingIndicator()
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ingredients (${detail.ingredientLines.size})") }
                    SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = detail.steps.isNotEmpty()) { Text("Steps (${detail.steps.size})") }
                }
                if (tab == 0) {
                    for (line in detail.ingredientLines) Text("• $line", style = MaterialTheme.typography.bodyMedium)
                } else {
                    for ((index, step) in detail.steps.withIndex()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(step.text, style = MaterialTheme.typography.bodyMedium)
                                if (step.imageUrl != null) {
                                    AsyncImage(model = step.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
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
