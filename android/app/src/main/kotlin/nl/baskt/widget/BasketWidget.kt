package nl.baskt.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import kotlinx.coroutines.withTimeoutOrNull
import nl.baskt.BasktApp
import nl.baskt.EXTRA_DICTATE
import nl.baskt.EXTRA_FOCUS_INPUT
import nl.baskt.MainActivity
import nl.baskt.R
import nl.baskt.data.Basket
import nl.baskt.data.BasketItem
import nl.baskt.data.SettingsStore
import nl.baskt.data.euros
import nl.baskt.ui.common.counted

/** How many items the widget lists before it says "+N more". */
private const val MAX_LINES = 20

/** Changes whenever the basket should be read again (after a check-off, or a basket switch in the app). */
private val refreshKey = longPreferencesKey("refresh")
/** The item being checked off right now: its line shows as done while the request runs. */
private val checkingKey = stringPreferencesKey("checking")
/** The last check-off did not go through. */
private val failedKey = booleanPreferencesKey("failed")

// Android 8 to 11 have no dynamic colour, so the widgets wear the app's own green there. These are the roles
// the widgets use, with the same values as the app's fallback scheme in ui/theme/Theme.kt.
private val FallbackColors = ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFF246A4B), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFAAF2CA), onPrimaryContainer = Color(0xFF005235),
        error = Color(0xFFBA1A1A), background = Color(0xFFF5FBF4), onBackground = Color(0xFF171D19), surface = Color(0xFFF5FBF4), onSurface = Color(0xFF171D19),
        surfaceVariant = Color(0xFFDCE5DC), onSurfaceVariant = Color(0xFF404943),
    ),
    dark = darkColorScheme(
        primary = Color(0xFF8ED5AF), onPrimary = Color(0xFF003823), primaryContainer = Color(0xFF005235), onPrimaryContainer = Color(0xFFAAF2CA),
        error = Color(0xFFFFB4AB), background = Color(0xFF0F1511), onBackground = Color(0xFFDEE4DD), surface = Color(0xFF0F1511), onSurface = Color(0xFFDEE4DD),
        surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFC0C9C0),
    ),
)

/** Both widgets in the same colours: the wallpaper's on Android 12 and up, the app's green before that. */
@Composable
private fun WidgetTheme(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme(content = content) else GlanceTheme(colors = FallbackColors, content = content)
}

/** The system's widget corner where it has one (Android 12 and up), 16dp before that. */
private fun GlanceModifier.widgetCorners(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(android.R.dimen.system_app_widget_background_radius) else cornerRadius(16.dp)

/** What the basket widget shows: [items] is null when nothing could be read, [offline] when it comes from the phone's saved copy. */
private data class Snapshot(val name: String, val items: List<BasketItem>?, val offline: Boolean, val unconfigured: Boolean)

/**
 * Reads the current basket from the server, and from the app's saved copy when the server cannot be reached
 * (or when changes are still waiting to sync, because the saved copy already has them).
 */
private suspend fun loadSnapshot(context: Context): Snapshot {
    val container = (context.applicationContext as BasktApp).container
    val settings = container.awaitSettings()
    val unconfigured = settings.token.isBlank() && settings.baseUrl == SettingsStore.DEFAULT_BASE_URL
    val id = settings.currentBasketId
    val waiting = container.basket.pending.value.isNotEmpty()
    val live = if (unconfigured || waiting) null else withTimeoutOrNull(8_000) { runCatching { container.api.basket(id).items }.getOrNull() }
    val saved = if (live == null) container.offline.load<List<BasketItem>>("items-$id") else null
    val baskets = (if (live != null) withTimeoutOrNull(8_000) { runCatching { container.api.baskets() }.getOrNull() } else null) ?: container.offline.load<List<Basket>>("baskets")
    val name = baskets?.firstOrNull { it.id == id }?.label ?: "baskt"
    return Snapshot(name, (live ?: saved)?.filter { !it.isGroup && !it.checked && it.boughtAt == null }, offline = live == null, unconfigured = unconfigured)
}

private fun openApp(extra: String? = null) =
    if (extra == null) actionStartActivity<MainActivity>() else actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<Boolean>(extra) to true))

/** Home-screen list of the current basket's open items; tapping an item checks it off, the header opens the app. */
class BasketWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadSnapshot(context)
        provideContent {
            val state = currentState<Preferences>()
            val refresh = state[refreshKey] ?: 0L
            val checking = state[checkingKey]
            val failed = state[failedKey] == true
            // A running widget is only recomposed, not started again, so the basket is read again here when asked to.
            val started = remember { refresh }
            val snapshot by produceState(first, refresh) { if (refresh != started) value = loadSnapshot(context) }
            val items = snapshot.items
            WidgetTheme {
                Column(modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).widgetCorners().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(modifier = GlanceModifier.fillMaxWidth().height(48.dp).clickable(openApp()), verticalAlignment = Alignment.CenterVertically) {
                        Text(snapshot.name, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                        if (failed) Text("Couldn’t check off", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.error))
                        else if (items != null) Text(listOfNotNull("Offline".takeIf { snapshot.offline }, counted(items.size, "item")).joinToString(" · "), style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                    if (items.isNullOrEmpty()) {
                        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight().clickable(openApp()), contentAlignment = Alignment.Center) {
                            Text(
                                when { items != null -> "Nothing to buy"; snapshot.unconfigured -> "Open baskt to connect"; else -> "Couldn’t load your basket" },
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                            )
                        }
                    } else {
                        LazyColumn(modifier = GlanceModifier.defaultWeight()) {
                            items(items.take(MAX_LINES), itemId = { it.id.hashCode().toLong() }) { item -> WidgetLine(item, checking = item.id == checking) }
                            if (items.size > MAX_LINES) item {
                                Row(modifier = GlanceModifier.fillMaxWidth().height(48.dp).clickable(openApp()), verticalAlignment = Alignment.CenterVertically) {
                                    Text("+${items.size - MAX_LINES} more", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }
                    Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
                        AddButton(GlanceModifier.height(48.dp).padding(horizontal = 12.dp))
                        DictateButton(GlanceModifier.size(48.dp))
                    }
                }
            }
        }
    }

    companion object {
        /** Makes every basket widget read the basket again, e.g. after the app switched baskets. */
        suspend fun refreshAll(context: Context) {
            runCatching {
                val manager = GlanceAppWidgetManager(context)
                val widget = BasketWidget()
                for (id in manager.getGlanceIds(BasketWidget::class.java)) {
                    updateAppWidgetState(context, id) { it[refreshKey] = System.currentTimeMillis(); it.remove(failedKey) }
                    widget.update(context, id)
                }
            }
        }
    }
}

/** "Add" with its plus: opens the app with the add field focused. */
@Composable
private fun AddButton(modifier: GlanceModifier) {
    Row(modifier = modifier.clickable(openApp(EXTRA_FOCUS_INPUT)), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(R.drawable.ic_widget_add), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.primary), modifier = GlanceModifier.size(20.dp))
        Spacer(GlanceModifier.width(6.dp))
        Text("Add", style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium, fontSize = 14.sp))
    }
}

/** The microphone: opens the app and starts dictating. */
@Composable
private fun DictateButton(modifier: GlanceModifier) {
    Box(modifier = modifier.clickable(openApp(EXTRA_DICTATE)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_widget_mic), contentDescription = "Dictate", colorFilter = ColorFilter.tint(GlanceTheme.colors.primary), modifier = GlanceModifier.size(24.dp))
    }
}

@Composable
private fun WidgetLine(item: BasketItem, checking: Boolean) {
    // The price at the store the item is assigned to, or else the cheapest one found.
    val product = item.assignedStore?.let { item.match(it)?.effective } ?: item.matches.mapNotNull { it.effective }.minByOrNull { it.priceCents }
    val price = product?.let { (it.priceCents * item.quantity).euros() }
    val label = (if (item.quantity > 1) "${item.quantity}× " else "") + item.text
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Check off $label" }.clickable(actionRunCallback<CheckItemAction>(actionParametersOf(CheckItemAction.itemId to item.id))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(if (checking) R.drawable.ic_widget_check_box else R.drawable.ic_widget_check_box_blank), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.primary), modifier = GlanceModifier.size(24.dp))
        Spacer(GlanceModifier.width(12.dp))
        Text(label, style = TextStyle(color = if (checking) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface, fontSize = 14.sp, textDecoration = if (checking) TextDecoration.LineThrough else TextDecoration.None), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
        if (price != null) Text(price, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
    }
}

/**
 * Checks an item off from the widget. The line shows as done at once; the change goes through the app's own
 * basket, so it also works offline (it waits to sync), and a failure is shown in the widget's header.
 */
class CheckItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[itemId] ?: return
        val container = (context.applicationContext as BasktApp).container
        updateAppWidgetState(context, glanceId) { it[checkingKey] = id; it.remove(failedKey) }
        BasketWidget().update(context, glanceId)
        val done = runCatching {
            container.awaitSettings()
            val item = container.basket.items.value.firstOrNull { it.id == id }
            if (item != null) container.basket.setChecked(item, true) else container.api.updateItem(id, checked = true)
        }.isSuccess
        updateAppWidgetState(context, glanceId) {
            it.remove(checkingKey)
            if (!done) it[failedKey] = true
            it[refreshKey] = System.currentTimeMillis()
        }
        BasketWidget().update(context, glanceId)
    }

    companion object {
        val itemId = ActionParameters.Key<String>("itemId")
    }
}

class BasketWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BasketWidget()
}

/** Small widget: one half opens the app with the add field focused, the other starts dictation. */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetTheme {
                Row(modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).widgetCorners(), verticalAlignment = Alignment.CenterVertically) {
                    AddButton(GlanceModifier.defaultWeight().fillMaxHeight())
                    DictateButton(GlanceModifier.defaultWeight().fillMaxHeight())
                }
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
