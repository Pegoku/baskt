package nl.baskt.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Interactive horizontal navigation between neighbouring screens.
 * Dragging right-to-left pulls [right] in over the current screen; left-to-right slides the current
 * screen away to reveal [left] underneath. Both follow the finger after a small margin and commit or
 * snap back on release.
 */
@Composable
fun SwipeStage(
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    onGoLeft: () -> Unit = {},
    onGoRight: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val margin = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    // Positive = dragging towards the right screen (forward), negative = towards the left (back).
    val offset = remember { Animatable(0f) }
    var travelled by remember { mutableFloatStateOf(0f) }
    var lastDelta by remember { mutableFloatStateOf(0f) }

    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .onSizeChanged { width = it.width }
            .pointerInput(left != null, right != null) {
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f; lastDelta = 0f },
                    onHorizontalDrag = { change, delta ->
                        travelled += delta
                        lastDelta = delta
                        val beyond = when {
                            travelled < -margin -> travelled + margin
                            travelled > margin -> travelled - margin
                            else -> 0f
                        }
                        val target = when {
                            beyond < 0 && right != null -> -beyond
                            beyond > 0 && left != null -> beyond * -1f
                            else -> 0f
                        }
                        if (target != 0f || offset.value != 0f) {
                            change.consume()
                            scope.launch { offset.snapTo(target.coerceIn(-width.toFloat(), width.toFloat())) }
                        }
                    },
                    onDragEnd = {
                        val value = offset.value
                        val flick = abs(lastDelta) > 14f
                        val commitForward = value > 0 && (value > width * 0.35f || (flick && lastDelta < 0))
                        val commitBack = value < 0 && (-value > width * 0.35f || (flick && lastDelta > 0))
                        scope.launch {
                            when {
                                // Stay at the end position: the navigation swaps the screen underneath, and this stage is
                                // disposed with it. Resetting here would flash the old screen for a frame.
                                commitForward -> { offset.animateTo(width.toFloat(), spring(stiffness = 700f)); onGoRight() }
                                commitBack -> { offset.animateTo(-width.toFloat(), spring(stiffness = 700f)); onGoLeft() }
                                else -> offset.animateTo(0f, spring(stiffness = 600f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                )
            },
    ) {
        val value = offset.value
        val w = width.toFloat().coerceAtLeast(1f)
        // Back: previous screen sits underneath, fully laid out; current slides right off it.
        if (value < 0 && left != null) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationX = -(w + value) * 0.3f }) { left() }
        }
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = if (value > 0) -value * 0.3f else -value
                shadowElevation = if (value < 0) 16f else 0f
            }.drawWithContent {
                drawContent()
                // Dim the screen being covered.
                if (value > 0) drawRect(Color.Black.copy(alpha = (value / w * 0.35f).coerceIn(0f, 0.35f)))
            },
        ) { content() }
        // Forward: next screen slides in from the right over the current one.
        if (value > 0 && right != null) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationX = w - value; shadowElevation = 16f }) { right() }
        }
    }
}
