package nl.baskt.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import nl.baskt.BasktApp
import nl.baskt.data.TranslationResponse

/** Translation is presentation-only: source values are never changed or written back. */
@Composable
fun rememberTranslation(texts: List<String>, enabled: Boolean = true, descriptionIndices: List<Int> = emptyList()): TranslationResponse? {
    val container = (LocalContext.current.applicationContext as BasktApp).container
    val settings by container.settingsStore.settings.collectAsState(initial = container.currentSettings)
    val online by container.basket.online.collectAsState()
    val language = settings.resolvedLanguage
    var result by remember(texts, descriptionIndices, language, settings.baseUrl, settings.token) { mutableStateOf<TranslationResponse?>(null) }
    LaunchedEffect(texts, descriptionIndices, language, settings.baseUrl, settings.token, enabled, online) {
        if (!enabled) return@LaunchedEffect
        result = try {
            container.translations(settings).translate(texts, language, descriptionIndices)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TranslationResponse(texts, language, false) }
    }
    return result
}

/**
 * The one translation switch of a screen or sheet: on while texts are shown translated, off for the original
 * wording. Callers keep "show original" as their state, so [showOriginal] is the switch turned off.
 */
@Composable
fun OriginalToggle(showOriginal: Boolean, onToggle: () -> Unit) {
    val label = if (showOriginal) "Show translation" else "Show original"
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconToggleButton(checked = !showOriginal, onCheckedChange = { onToggle() }) {
            Icon(Icons.Default.Translate, contentDescription = "Translate")
        }
    }
}

/** A longer text in the app language, or as written when [showOriginal] is on. The screen's one toggle decides, so there is no switch here. */
@Composable
fun TranslatedText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, showOriginal: Boolean = false, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    val translation = rememberTranslation(listOf(text), !showOriginal)
    Text(if (!showOriginal && translation?.translated == true) translation.texts[0] else text, modifier = modifier, style = style, color = color)
}

/** Compact previews use the same language; the detail sheet exposes the complete original. */
@Composable
fun TranslatedLabel(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
                    maxLines: Int = Int.MAX_VALUE, overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip) {
    val result = rememberTranslation(listOf(text))
    Text(result?.takeIf { it.translated }?.texts?.firstOrNull() ?: text, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)
}
