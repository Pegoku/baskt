package nl.baskt.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.basket.BasketScreen
import nl.baskt.ui.compare.CompareScreen
import nl.baskt.ui.compare.OrderScreen
import kotlin.math.absoluteValue

/** Fraction of a page the finger must travel before releasing navigates; a brisk fling commits sooner. */
private const val CommitFraction = 0.35f
/** Drag back this far below the commit point before the gesture counts as un-committed again. */
private const val CommitHysteresis = 0.1f
/** How much slower the receding page moves than the finger. */
private const val UnderlayParallax = 0.25f

/**
 * Basket → Review → Order as three pages of one pager, treated as a gesture-driven lateral transition:
 * the page follows the finger 1:1 once touch slop is cleared, deeper pages slide over the shallower one
 * like a stack (the one underneath recedes, lags and rounds off), release settles on the fast Expressive spatial
 * spring that keeps the finger's velocity, and a single light tick marks the point where letting go would
 * navigate.
 */
@Composable
fun HomePager(
    viewModel: AppViewModel,
    startPage: Int = 0,
    onOpenItem: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onSettings: () -> Unit,
    onStock: () -> Unit,
    onSearch: () -> Unit,
    onDeals: () -> Unit,
    onRecipes: () -> Unit,
    onShop: (String) -> Unit,
    onPurchases: () -> Unit,
    onScan: () -> Unit,
    onChat: () -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = startPage) { 3 }
    val scope = rememberCoroutineScope()
    // Fast spatial spring: snappy settle with a touch of bounce; the default one lingers.
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    fun go(page: Int) = scope.launch { pager.animateScrollToPage(page, animationSpec = spring) }
    BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }

    // Haptic at the commit threshold only: one tick when the drag crosses the point where releasing
    // would change page, one softer tick if it is pulled back below it, with hysteresis so wobbling
    // around the line stays quiet. Button-driven page changes never vibrate.
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragStartPage by remember { mutableIntStateOf(startPage) }
    var committed by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        launch {
            pager.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        dragStartPage = pager.currentPage
                        committed = false
                        dragging = true
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                }
            }
        }
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction - dragStartPage }.collect { travelled ->
            if (!dragging) return@collect
            val distance = travelled.absoluteValue
            if (!committed && distance >= CommitFraction) {
                committed = true
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            } else if (committed && distance <= CommitFraction - CommitHysteresis) {
                committed = false
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

    val fling = PagerDefaults.flingBehavior(
        state = pager,
        snapPositionalThreshold = CommitFraction,
        snapAnimationSpec = spring,
    )

    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        flingBehavior = fling,
        key = { it },
    ) { page ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // > 0: this page sits left of the viewport centre (the shallower page, underneath);
                // < 0: it sits to the right (the deeper page, sliding over the top).
                val position = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val away = position.absoluteValue.coerceIn(0f, 1f)
                val transit = 4f * away * (1f - away) // 0 at rest, 1 halfway through a swipe
                if (position > 0f) {
                    val scale = lerp(1f, 0.92f, away)
                    scaleX = scale
                    scaleY = scale
                    translationX = position * size.width * UnderlayParallax
                    alpha = lerp(1f, 0.7f, away)
                } else {
                    shadowElevation = 16.dp.toPx() * transit
                }
                shape = RoundedCornerShape(lerp(0f, 28.dp.toPx(), away))
                clip = true
            },
        ) {
            when (page) {
                0 -> BasketScreen(
                    viewModel = viewModel,
                    onOpenItem = onOpenItem,
                    onOpenGroup = onOpenGroup,
                    onCompare = { go(1) },
                    onSettings = onSettings,
                    onStock = onStock,
                    onSearch = onSearch,
                    onDeals = onDeals,
                    onRecipes = onRecipes,
                    onShop = onShop,
                    onPurchases = onPurchases,
                    onScan = onScan,
                    onChat = onChat,
                )
                1 -> CompareScreen(viewModel, onBack = { go(0) }, onOpenItem = onOpenItem, onOrder = { go(2) })
                else -> OrderScreen(viewModel, onBack = { go(1) }, onShop = onShop)
            }
        }
    }
}
