package nl.baskt.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DescriptionText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { descriptionBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            if (block.marker != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(block.marker, style = MaterialTheme.typography.bodyLarge)
                Text(block.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            } else Text(block.text, style = if (block.heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
        }
    }
}
