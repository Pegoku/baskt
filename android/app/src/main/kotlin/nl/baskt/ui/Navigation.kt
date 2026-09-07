package nl.baskt.ui

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import nl.baskt.ui.basket.BasketScreen
import nl.baskt.ui.compare.CompareScreen
import nl.baskt.ui.basket.GroupScreen
import nl.baskt.ui.item.ItemDetailScreen
import nl.baskt.ui.settings.SettingsScreen

@Serializable data object BasketRoute : NavKey
@Serializable data class ItemRoute(val itemId: String) : NavKey
@Serializable data class GroupRoute(val groupId: String) : NavKey
@Serializable data object CompareRoute : NavKey
@Serializable data object SettingsRoute : NavKey

@Composable
fun BasktNavigation(viewModel: AppViewModel, startAtSettings: Boolean) {
    val backStack = rememberNavBackStack(BasketRoute)
    if (startAtSettings && backStack.size == 1) backStack.add(SettingsRoute)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<BasketRoute> {
                BasketScreen(
                    viewModel = viewModel,
                    onOpenItem = { backStack.add(ItemRoute(it)) },
                    onOpenGroup = { backStack.add(GroupRoute(it)) },
                    onCompare = { backStack.add(CompareRoute) },
                    onSettings = { backStack.add(SettingsRoute) },
                )
            }
            entry<GroupRoute> { route ->
                GroupScreen(viewModel, route.groupId, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) })
            }
            entry<ItemRoute> { route -> ItemDetailScreen(viewModel, route.itemId, onBack = { backStack.removeLastOrNull() }) }
            entry<CompareRoute> {
                CompareScreen(viewModel, onBack = { backStack.removeLastOrNull() }, onOpenItem = { backStack.add(ItemRoute(it)) })
            }
            entry<SettingsRoute> { SettingsScreen(viewModel, onBack = { backStack.removeLastOrNull() }) }
        },
    )
}
