package nl.baskt.ui

import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.togetherWith
import kotlinx.serialization.Serializable
import nl.baskt.ui.basket.BasketScreen
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import nl.baskt.ui.home.HomePager
import nl.baskt.ui.chat.ChatScreen
import nl.baskt.ui.basket.GroupScreen
import nl.baskt.ui.basket.TidyScreen
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
import nl.baskt.ui.ah.AhLoginScreen

@Serializable data object BasketRoute : NavKey
@Serializable data class ItemRoute(val itemId: String) : NavKey
@Serializable data class GroupRoute(val groupId: String) : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data object StockRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object DealsRoute : NavKey
@Serializable data object RecipesRoute : NavKey
@Serializable data class ShopRoute(val store: String) : NavKey
@Serializable data object PurchasesRoute : NavKey
@Serializable data class RecipeEditorRoute(val recipeId: String? = null) : NavKey
@Serializable data object MemoryRoute : NavKey
@Serializable data object ChatRoute : NavKey
@Serializable data object OrderRoute : NavKey
@Serializable data object ScannerRoute : NavKey
@Serializable data object TidyRoute : NavKey
@Serializable data object AhLoginRoute : NavKey

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BasktNavigation(viewModel: AppViewModel, startAtSettings: Boolean) {
    val backStack = rememberNavBackStack(BasketRoute)
    // Once per launch: an unconfigured install opens on Settings, and Back from there must stay back.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (startAtSettings && backStack.size == 1) backStack.add(SettingsRoute) }
    // A receipt shared from another app lands on the receipts screen, where the scan result shows up.
    val openReceipts by viewModel.openReceiptsRequest.collectAsState()
    androidx.compose.runtime.LaunchedEffect(openReceipts) {
        if (openReceipts) {
            viewModel.openReceiptsRequest.value = false
            if (backStack.lastOrNull() != PurchasesRoute) backStack.add(PurchasesRoute)
        }
    }
    // Close the keyboard and drop focus whenever the visible screen changes.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val top = backStack.lastOrNull()
    androidx.compose.runtime.LaunchedEffect(top) { focusManager.clearFocus(force = true); keyboard?.hide() }
    // Shared-axis motion on the theme's expressive springs: the new screen slides in from the right over a fading old one; back mirrors it.
    val motion = androidx.compose.material3.MaterialTheme.motionScheme
    val slide = motion.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fade = motion.defaultEffectsSpec<Float>()
    val enter = androidx.compose.animation.slideInHorizontally(slide) { it / 3 } + androidx.compose.animation.fadeIn(fade)
    val exit = androidx.compose.animation.slideOutHorizontally(slide) { -it / 5 } + androidx.compose.animation.fadeOut(fade)
    val popEnter = androidx.compose.animation.slideInHorizontally(slide) { -it / 5 } + androidx.compose.animation.fadeIn(fade)
    val popExit = androidx.compose.animation.slideOutHorizontally(slide) { it / 3 } + androidx.compose.animation.fadeOut(fade)
    // Global messages: anything that fails or finishes elsewhere is announced here, on top of every screen.
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    // Each message replaces the one on screen instead of queueing behind it; one with an action (Undo) stays longer.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.notices.collectLatest { notice ->
            val result = snackbar.showSnackbar(notice.message, actionLabel = notice.actionLabel, withDismissAction = true, duration = if (notice.actionLabel != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) notice.onAction?.invoke()
        }
    }
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
                    onChat = { backStack.add(ChatRoute) },
                    onTidy = { backStack.add(TidyRoute) },
                )
            }
            // Adding a recipe from the chat stays in the conversation; "View" on its notice goes back to the basket.
            entry<ChatRoute> { ChatScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onViewBasket = { while (backStack.size > 1) backStack.removeLastOrNull() }) }
            entry<GroupRoute> { route ->
                GroupScreen(viewModel, route.groupId, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) })
            }
            entry<ItemRoute> { route -> ItemDetailScreen(viewModel, route.itemId, onBack = { backStack.removeLastOrNull() }) }
            entry<TidyRoute> { TidyScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
            entry<ScannerRoute> { ScannerScreen(viewModel, onClose = { backStack.removeLastOrNull() }) }
            entry<PurchasesRoute> { PurchasesScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
            entry<ShopRoute> { route -> ShopModeScreen(viewModel, route.store, onBack = { backStack.removeLastOrNull() }) }
            entry<RecipesRoute> { RecipesScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onFolderAdded = { backStack.removeLastOrNull() }, onCreate = { backStack.add(RecipeEditorRoute(it)) }) }
            entry<RecipeEditorRoute> { route -> RecipeEditorScreen(viewModel, route.recipeId, onBack = { backStack.removeLastOrNull() }) }
            entry<DealsRoute> { DealsScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) }, onAhLogin = { backStack.add(AhLoginRoute) }) }
            entry<AhLoginRoute> { AhLoginScreen(viewModel, onDone = { backStack.removeLastOrNull() }) }
            entry<SearchRoute> { SearchScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) }) }
            entry<StockRoute> { StockScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
            entry<SettingsRoute> { SettingsScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onMemory = { backStack.add(MemoryRoute) }, onAhLogin = { backStack.add(AhLoginRoute) }) }
            entry<MemoryRoute> { MemoryScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
        },
    )
    // The safe-drawing insets lift the message above the navigation bar and the keyboard (e.g. while typing in the
    // assistant); the extra offset clears a floating button or a bottom bar. The basket page has the tallest one:
    // the add bar, and above it the trip strip, which hides while the keyboard is up.
    val snackbarOffset = when {
        top != BasketRoute -> 88.dp
        androidx.compose.foundation.layout.WindowInsets.isImeVisible -> 96.dp
        else -> 152.dp
    }
    androidx.compose.material3.SnackbarHost(snackbar, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(bottom = snackbarOffset).zIndex(2f))
    }
}
