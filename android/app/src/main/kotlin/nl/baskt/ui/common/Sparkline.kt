package nl.baskt.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import nl.baskt.data.PricePoint
import nl.baskt.data.euros
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small price-over-time line with the range and today's price under it; hidden until at least two points exist. */
@Composable
fun PriceSparkline(productId: String, load: suspend (String) -> List<PricePoint>) {
    var points by remember(productId) { mutableStateOf<List<PricePoint>>(emptyList()) }
    LaunchedEffect(productId) { points = load(productId) }
    if (points.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val min = points.minOf { it.priceCents }
    val max = points.maxOf { it.priceCents }
    val current = points.last().priceCents
    val since = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(points.first().capturedAt))
    val range = "Since $since: low ${min.euros()}, high ${max.euros()}"
    val now = "Now ${current.euros()}"
    // Read out as one sentence; the line itself and the two captions under it say nothing more.
    Column(modifier = Modifier.clearAndSetSemantics { contentDescription = "Price history. $range. $now" }) {
        Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            val stroke = 2.dp.toPx()
            val dot = 3.dp.toPx()
            val path = Path()
            val span = (max - min).coerceAtLeast(1).toFloat()
            // Inset by the dot so neither the line nor the dot is cut off at the edges.
            fun x(index: Int) = dot + (size.width - 2 * dot) * index / (points.size - 1)
            fun y(cents: Int) = size.height - dot - (cents - min) / span * (size.height - 2 * dot)
            points.forEachIndexed { index, point -> if (index == 0) path.moveTo(x(index), y(point.priceCents)) else path.lineTo(x(index), y(point.priceCents)) }
            drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = dot, center = Offset(x(points.lastIndex), y(current)))
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(range, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(now, style = MaterialTheme.typography.labelSmallEmphasized, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
