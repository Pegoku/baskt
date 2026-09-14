package nl.baskt.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nl.baskt.BasktApp
import nl.baskt.data.TranslationResponse

private val translationSlots = Semaphore(2)

/** Translation is presentation-only: source values are never changed or written back. */
@Composable
fun rememberTranslation(texts: List<String>, enabled: Boolean = true): TranslationResponse? {
    val container = (LocalContext.current.applicationContext as BasktApp).container
    val settings by container.settingsStore.settings.collectAsState(initial = container.currentSettings)
    val language = settings.resolvedLanguage
    var result by remember(texts, language, settings.baseUrl, settings.token) { mutableStateOf<TranslationResponse?>(null) }
    LaunchedEffect(texts, language, settings.baseUrl, settings.token, enabled) {
        if (!enabled) return@LaunchedEffect
        val key = "translation-v1-$language-${texts.joinToString("\u0000")}"
        val cached = container.offline.load<TranslationResponse>(key)
        if (cached != null) { result = cached; return@LaunchedEffect }
        result = try {
            translationSlots.withPermit {
                container.offline.load<TranslationResponse>(key) ?: container.api.translate(texts, language).also {
                    if (it.translated && it.texts.size == texts.size) container.offline.save(key, it)
                }
            }.takeIf { it.texts.size == texts.size } ?: TranslationResponse(texts, language, false)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TranslationResponse(texts, language, false) }
    }
    return result
}

@Composable
fun OriginalToggle(showOriginal: Boolean, onToggle: () -> Unit) {
    val label = if (showOriginal) "Show translation" else "Show original"
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onToggle) {
            Icon(if (showOriginal) Icons.Default.Translate else Icons.Default.Description, contentDescription = label)
        }
    }
}

@Composable
fun TranslatedText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    var original by rememberSaveable(text) { mutableStateOf(false) }
    val translation = rememberTranslation(listOf(text), !original)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (original) "Original" else if (translation?.translated == true) "Translated" else if (translation == null) "Translating…" else "Translation unavailable · showing original", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            OriginalToggle(original) { original = !original }
        }
        Text(if (!original && translation?.translated == true) translation.texts[0] else text, style = style)
    }
}

/** Compact previews use the same language; the detail sheet exposes the complete original. */
@Composable
fun TranslatedLabel(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
                    maxLines: Int = Int.MAX_VALUE, overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip) {
    val result = rememberTranslation(listOf(text))
    Text(result?.takeIf { it.translated }?.texts?.firstOrNull() ?: text, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)
}
