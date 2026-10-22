package nl.baskt.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.basket.BasketScreen
import nl.baskt.ui.compare.PlanScreen
import nl.baskt.ui.orders.OrdersScreen
import kotlin.math.absoluteValue

/** Where the default pager fling settles on the next page when the finger lets go; the haptic marks it. */
private const val CommitFraction = 0.5f
/** Drag back this far below the commit point before the gesture counts as un-committed again. */
private const val CommitHysteresis = 0.1f

/** The basket sits at this index; previous orders are the page to its left. */
private const val BasketPage = 1

/**
 * Orders ← Basket → Plan as three pages of one pager. The pages lie side by side and follow the finger
 * 1:1 with the stock pager fling, like the order days inside Orders: per-frame scaling, clipping and shadows on
 * whole screens, and composing a neighbour mid-gesture, made this swipe drop most of its frames. A single light
 * tick marks the point where letting go would navigate.
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
    onTidy: () -> Unit = {},
) {
    // `startPage` counts from the basket (0 = basket, 1 = plan); the orders page sits before it.
    val pager = rememberPagerState(initialPage = startPage + BasketPage) { 3 }
    val scope = rememberCoroutineScope()
    fun go(page: Int) = scope.launch { pager.animateScrollToPage(page) }
    // The first frame composes only the basket; neighbours follow once it is up, so a swipe never has to build one.
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); warm = true }
    BackHandler(enabled = pager.currentPage != BasketPage) { go(if (pager.currentPage < BasketPage) BasketPage else pager.currentPage - 1) }

    // Haptic at the commit threshold only: one tick when the drag crosses the point where releasing
    // would change page, one softer tick if it is pulled back below it, with hysteresis so wobbling
    // around the line stays quiet. Button-driven page changes never vibrate.
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragStartPage by remember { mutableIntStateOf(startPage + BasketPage) }
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

    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = if (warm) 1 else 0,
        key = { it },
    ) { page ->
        Box(Modifier.fillMaxSize()) {
            when (page) {
                0 -> OrdersScreen(viewModel, onBack = { go(BasketPage) })
                BasketPage -> BasketScreen(
                    viewModel = viewModel,
                    onOrders = { go(0) },
                    onOpenItem = onOpenItem,
                    onOpenGroup = onOpenGroup,
                    onPlan = { go(2) },
                    onSettings = onSettings,
                    onStock = onStock,
                    onSearch = onSearch,
                    onDeals = onDeals,
                    onRecipes = onRecipes,
                    onShop = onShop,
                    onPurchases = onPurchases,
                    onScan = onScan,
                    onChat = onChat,
                    onTidy = onTidy,
                )
                else -> PlanScreen(viewModel, active = pager.currentPage == 2 || pager.targetPage == 2, onBack = { go(BasketPage) }, onOpenItem = onOpenItem, onShop = onShop)
            }
        }
    }
}
