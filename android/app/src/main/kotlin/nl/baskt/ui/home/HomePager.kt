package nl.baskt.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

/** The basket sits at this index; orders are the page to its left. */
private const val BasketPage = 1

/**
 * Orders ← Basket → Plan as three pages of one pager, with three small dots on top that show which one is open. The pages lie side by side and follow the finger
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

    Column(Modifier.fillMaxSize()) {
    PageDots(current = pager.currentPage, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)))
    HorizontalPager(
        state = pager,
        // The dots above already took the status bar; without this every page's top bar would add it again.
        modifier = Modifier.fillMaxWidth().weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
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
}

/** The names of the three pages, in pager order. */
private val PageNames = listOf("Orders", "Basket", "Plan")

/**
 * Three dots under the status bar: the only hint that Orders and Plan lie to the left and right of the basket.
 * The current page's dot is a wider pill. They only inform (swiping and the buttons on each page navigate),
 * so they stay small and are read out as one "Page 2 of 3" label.
 */
@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp).semantics(mergeDescendants = true) { contentDescription = "${PageNames[current]}, page ${current + 1} of ${PageNames.size}" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (page in PageNames.indices) {
            val width by animateDpAsState(if (page == current) 18.dp else 6.dp, animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "dot")
            Box(Modifier.size(width = width, height = 6.dp).clip(MaterialTheme.shapes.extraSmall).background(if (page == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}
