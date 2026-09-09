package nl.baskt.ui.common

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Screen-level horizontal swipe: a clear right-to-left swipe calls [onSwipeLeft] (go forward),
 * left-to-right calls [onSwipeRight] (go back). Ignores small or slow drags.
 */
fun Modifier.swipeNavigation(onSwipeLeft: (() -> Unit)? = null, onSwipeRight: (() -> Unit)? = null): Modifier = composed {
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    pointerInput(onSwipeLeft, onSwipeRight) {
        var total = 0f
        var last = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f; last = 0f },
            onHorizontalDrag = { _, delta -> total += delta; last = delta },
            onDragEnd = {
                val flick = abs(last) > 12f
                if (total < -threshold && (flick || total < -threshold * 1.5f)) onSwipeLeft?.invoke()
                else if (total > threshold && (flick || total > threshold * 1.5f)) onSwipeRight?.invoke()
            },
        )
    }
}
