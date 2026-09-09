package nl.baskt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.togetherWith
import kotlinx.serialization.Serializable
import nl.baskt.ui.basket.BasketScreen
import nl.baskt.ui.compare.CompareScreen
import nl.baskt.ui.compare.OrderScreen
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import nl.baskt.ui.home.HomePager
import nl.baskt.ui.basket.GroupScreen
import nl.baskt.ui.item.ItemDetailScreen
import nl.baskt.ui.settings.SettingsScreen
import nl.baskt.ui.settings.MemoryScreen
import nl.baskt.ui.stock.StockScreen
import nl.baskt.ui.search.SearchScreen
import nl.baskt.ui.search.ScannerScreen
import nl.baskt.ui.deals.DealsScreen
import nl.baskt.ui.recipes.RecipesScreen
import nl.baskt.ui.shop.ShopModeScreen
import nl.baskt.ui.purchases.PurchasesScreen
import nl.baskt.ui.recipes.RecipeEditorScreen

@Serializable data object BasketRoute : NavKey
@Serializable data class ItemRoute(val itemId: String) : NavKey
@Serializable data class GroupRoute(val groupId: String) : NavKey
@Serializable data object CompareRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data object StockRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object DealsRoute : NavKey
@Serializable data object RecipesRoute : NavKey
@Serializable data class ShopRoute(val store: String) : NavKey
@Serializable data object PurchasesRoute : NavKey
@Serializable data class RecipeEditorRoute(val recipeId: String? = null) : NavKey
@Serializable data object MemoryRoute : NavKey
@Serializable data object OrderRoute : NavKey
@Serializable data object ScannerRoute : NavKey

@Composable
fun BasktNavigation(viewModel: AppViewModel, startAtSettings: Boolean) {
    val backStack = rememberNavBackStack(BasketRoute)
    if (startAtSettings && backStack.size == 1) backStack.add(SettingsRoute)
    // Close the keyboard and drop focus whenever the visible screen changes.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val top = backStack.lastOrNull()
    androidx.compose.runtime.LaunchedEffect(top) { focusManager.clearFocus(force = true); keyboard?.hide() }
    // Shared-axis motion: the new screen slides in from the right over a fading old one; back mirrors it.
    val enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 3 } +
        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))
    val exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 5 } +
        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220))
    val popEnter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 5 } +
        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))
    val popExit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 3 } +
        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220))
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = { enter togetherWith exit },
        popTransitionSpec = { popEnter togetherWith popExit },
        predictivePopTransitionSpec = { popEnter togetherWith popExit },
        entryProvider = entryProvider {
            entry<BasketRoute> {
                HomePager(
                    viewModel = viewModel,
                    onOpenItem = { backStack.add(ItemRoute(it)) },
                    onOpenGroup = { backStack.add(GroupRoute(it)) },
                    onSettings = { backStack.add(SettingsRoute) },
                    onStock = { backStack.add(StockRoute) },
                    onSearch = { backStack.add(SearchRoute) },
                    onDeals = { backStack.add(DealsRoute) },
                    onRecipes = { backStack.add(RecipesRoute) },
                    onShop = { backStack.add(ShopRoute(it)) },
                    onPurchases = { backStack.add(PurchasesRoute) },
                    onScan = { backStack.add(ScannerRoute) },
                )
            }
            entry<GroupRoute> { route ->
                GroupScreen(viewModel, route.groupId, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) })
            }
            entry<ItemRoute> { route -> ItemDetailScreen(viewModel, route.itemId, onBack = { backStack.removeLastOrNull() }) }
            entry<CompareRoute> {
                HomePager(viewModel, startPage = 1, onOpenItem = { backStack.add(ItemRoute(it)) }, onOpenGroup = { backStack.add(GroupRoute(it)) }, onSettings = { backStack.add(SettingsRoute) }, onStock = { backStack.add(StockRoute) }, onSearch = { backStack.add(SearchRoute) }, onDeals = { backStack.add(DealsRoute) }, onRecipes = { backStack.add(RecipesRoute) }, onShop = { backStack.add(ShopRoute(it)) }, onPurchases = { backStack.add(PurchasesRoute) }, onScan = { backStack.add(ScannerRoute) })
            }
            entry<ScannerRoute> { ScannerScreen(viewModel, onClose = { backStack.removeLastOrNull() }) }
            entry<PurchasesRoute> { PurchasesScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
            entry<ShopRoute> { route -> ShopModeScreen(viewModel, route.store, onBack = { backStack.removeLastOrNull() }) }
            entry<RecipesRoute> { RecipesScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onFolderAdded = { backStack.removeLastOrNull() }, onCreate = { backStack.add(RecipeEditorRoute(it)) }) }
            entry<RecipeEditorRoute> { route -> RecipeEditorScreen(viewModel, route.recipeId, onBack = { backStack.removeLastOrNull() }) }
            entry<DealsRoute> { DealsScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) }) }
            entry<SearchRoute> { SearchScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) }) }
            entry<StockRoute> { StockScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
            entry<SettingsRoute> { SettingsScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onMemory = { backStack.add(MemoryRoute) }) }
            entry<MemoryRoute> { MemoryScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
        },
    )
    }
}
