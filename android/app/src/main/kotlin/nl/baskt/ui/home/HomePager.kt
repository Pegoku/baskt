package nl.baskt.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.basket.BasketScreen
import nl.baskt.ui.compare.CompareScreen
import nl.baskt.ui.compare.OrderScreen
import kotlin.math.absoluteValue

/**
 * Basket → Review → Order as three pages of one pager: swipes follow the finger, flings and quick
 * successive swipes are handled natively, and the neighbouring pages stay composed so they are ready.
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
) {
    val pager = rememberPagerState(initialPage = startPage) { 3 }
    val scope = rememberCoroutineScope()
    fun go(page: Int) = scope.launch { pager.animateScrollToPage(page) }
    BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }

    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
        // Subtle depth: a page fades and shrinks a touch as it leaves the centre.
        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = lerp(1f, 0.55f, offset)
                val scale = lerp(1f, 0.96f, offset)
                scaleX = scale
                scaleY = scale
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
                )
                1 -> CompareScreen(viewModel, onBack = { go(0) }, onOpenItem = onOpenItem, onOrder = { go(2) })
                else -> OrderScreen(viewModel, onBack = { go(1) }, onShop = onShop)
            }
        }
    }
}
