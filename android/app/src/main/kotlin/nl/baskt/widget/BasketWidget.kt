package nl.baskt.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import nl.baskt.BasktApp
import nl.baskt.EXTRA_DICTATE
import nl.baskt.EXTRA_FOCUS_INPUT
import nl.baskt.MainActivity
import nl.baskt.data.BasketItem

/** Home-screen list of the current basket's open items; tapping a line checks it off, the header opens the app. */
class BasketWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as BasktApp).container
        val settings = container.settingsStore.settings.first()
        val loaded = runCatching { container.api.basket(settings.currentBasketId) }.getOrNull()
        val basketName = runCatching { container.api.baskets().firstOrNull { it.id == settings.currentBasketId }?.label }.getOrNull() ?: "baskt"
        val items = loaded?.items?.filter { !it.isGroup && !it.checked } ?: emptyList()
        provideContent {
            GlanceTheme {
                Column(modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp)) {
                    Row(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                        Text(basketName, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface), modifier = GlanceModifier.defaultWeight())
                        Text(if (loaded == null) "offline" else "${items.size} open", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                    if (items.isEmpty()) {
                        Box(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(if (loaded == null) "Open baskt to connect" else "Nothing to buy", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                        }
                    } else {
                        LazyColumn(modifier = GlanceModifier.defaultWeight()) {
                            items(items.take(20), itemId = { it.id.hashCode().toLong() }) { item -> WidgetLine(item) }
                        }
                    }
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.End) {
                        Text("＋ add", style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium), modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<Boolean>(EXTRA_FOCUS_INPUT) to true))))
                        Text("🎤", modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<Boolean>(EXTRA_DICTATE) to true))))
                    }
                }
            }
        }
    }

    companion object {
        suspend fun refreshAll(context: Context) {
            runCatching {
                val manager = GlanceAppWidgetManager(context)
                val widget = BasketWidget()
                for (id in manager.getGlanceIds(BasketWidget::class.java)) widget.update(context, id)
            }
        }
    }
}

@Composable
private fun WidgetLine(item: BasketItem) {
    val price = item.matches.firstNotNullOfOrNull { it.effective?.priceCents }?.let { "€" + (it * item.quantity / 100) + "," + ((it * item.quantity) % 100).toString().padStart(2, '0') }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionRunCallback<CheckItemAction>(actionParametersOf(CheckItemAction.itemId to item.id))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("☐  ", style = TextStyle(color = GlanceTheme.colors.primary))
        Text((if (item.quantity > 1) "${item.quantity}× " else "") + item.text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
        if (price != null) Text(price, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
    }
}

class CheckItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[itemId] ?: return
        val container = (context.applicationContext as BasktApp).container
        runCatching { container.api.updateItem(id, checked = true) }
        BasketWidget().update(context, glanceId)
    }

    companion object {
        val itemId = ActionParameters.Key<String>("itemId")
    }
}

class BasketWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BasketWidget()
}

/** Small widget: one tap opens the app with the input focused, the mic starts dictation. */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.primaryContainer).cornerRadius(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = GlanceModifier.defaultWeight().fillMaxSize().clickable(actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<Boolean>(EXTRA_FOCUS_INPUT) to true))), contentAlignment = Alignment.Center) {
                        Text("＋ idea", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onPrimaryContainer))
                    }
                    Box(modifier = GlanceModifier.defaultWeight().fillMaxSize().clickable(actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<Boolean>(EXTRA_DICTATE) to true))), contentAlignment = Alignment.Center) {
                        Text("🎤", style = TextStyle(fontSize = 20.sp))
                    }
                }
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

@Suppress("unused")
private fun launchIntent(context: Context) = Intent(context, MainActivity::class.java)
