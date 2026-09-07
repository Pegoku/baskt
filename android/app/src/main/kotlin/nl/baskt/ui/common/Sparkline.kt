package nl.baskt.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import nl.baskt.data.PricePoint
import nl.baskt.data.euros
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small price-over-time line; hidden until at least two points exist. */
@Composable
fun PriceSparkline(productId: String, load: suspend (String) -> List<PricePoint>) {
    var points by remember(productId) { mutableStateOf<List<PricePoint>>(emptyList()) }
    LaunchedEffect(productId) { points = load(productId) }
    if (points.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val min = points.minOf { it.priceCents }
    val max = points.maxOf { it.priceCents }
    val format = SimpleDateFormat("d MMM", Locale.getDefault())
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            val path = Path()
            val span = (max - min).coerceAtLeast(1).toFloat()
            points.forEachIndexed { index, point ->
                val x = size.width * index / (points.size - 1).coerceAtLeast(1)
                val y = size.height - (point.priceCents - min) / span * (size.height - 6f) - 3f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 4f))
            val last = points.last()
            drawCircle(color, radius = 6f, center = Offset(size.width, size.height - (last.priceCents - min) / span * (size.height - 6f) - 3f))
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("${format.format(Date(points.first().capturedAt))} · ${points.first().priceCents.euros()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("low ${min.euros()} · high ${max.euros()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
