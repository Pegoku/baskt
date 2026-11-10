package nl.baskt.ui.shop

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.delay
import nl.baskt.data.BasketItem
import nl.baskt.data.TillCode
import nl.baskt.data.gs1Ais
import nl.baskt.ui.common.ChoiceGroup
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.counted
import nl.baskt.ui.theme.BasktTheme

private sealed interface TillStep {
    data class Card(val number: String) : TillStep
    data class Code(val code: TillCode, val repeat: Int) : TillStep
    data object Done : TillStep
}

/** The text to encode and how, for a code as the camera read it; GS1 strings keep their separators as FNC1. */
private fun encodingFor(code: TillCode): Triple<String, BarcodeFormat, Boolean> {
    val raw = code.raw.removePrefix("]d2").removePrefix("]C1").removePrefix("]Q3")
    val digits = raw.all { it.isDigit() } && raw.length <= 14
    val gs1 = !digits && gs1Ais(raw.trimStart('\u001d')) != null
    val format = when (code.format) {
        Barcode.FORMAT_EAN_13 -> BarcodeFormat.EAN_13
        Barcode.FORMAT_EAN_8 -> BarcodeFormat.EAN_8
        Barcode.FORMAT_UPC_A -> BarcodeFormat.UPC_A
        Barcode.FORMAT_UPC_E -> BarcodeFormat.UPC_E
        Barcode.FORMAT_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
        Barcode.FORMAT_QR_CODE -> BarcodeFormat.QR_CODE
        Barcode.FORMAT_CODE_128 -> BarcodeFormat.CODE_128
        else -> when {
            digits && raw.length == 13 -> BarcodeFormat.EAN_13
            digits && raw.length == 8 -> BarcodeFormat.EAN_8
            gs1 -> BarcodeFormat.DATA_MATRIX
            else -> BarcodeFormat.CODE_128
        }
    }
    return Triple(raw.trimStart('\u001d'), format, gs1)
}

private fun isSquare(format: BarcodeFormat) = format == BarcodeFormat.DATA_MATRIX || format == BarcodeFormat.QR_CODE

/** The code encoded again the way it was read; null when it cannot be. */
internal fun tillMatrix(code: TillCode): com.google.zxing.common.BitMatrix? = runCatching {
    val (text, format, gs1) = encodingFor(code)
    val hints = buildMap<EncodeHintType, Any> {
        put(EncodeHintType.MARGIN, if (isSquare(format)) 2 else 8)
        if (gs1) put(EncodeHintType.GS1_FORMAT, true)
        if (gs1 && format == BarcodeFormat.DATA_MATRIX) put(EncodeHintType.DATA_MATRIX_COMPACT, true)
    }
    val (width, height) = if (isSquare(format)) 800 to 800 else 1200 to 420
    MultiFormatWriter().encode(text, format, width, height, hints)
}.getOrNull()

/** The code drawn again for the till's scanner; null when it cannot be encoded the way it was read. */
internal fun tillBitmap(code: TillCode): Bitmap? {
    val matrix = tillMatrix(code) ?: return null
    val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix.get(index % matrix.width, index / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

private val INTERVALS = listOf(800, 1200, 2000, 3000)

/**
 * Checkout for the till relay: the loyalty card first, then every code scanned in the shop, one at a time, drawn
 * for the till's scanner. It moves on by itself at the chosen pace; the buttons under the code and the volume keys
 * step back and forth. The end lists what the till still has to be shown by hand. Always in the light theme, so
 * every control is readable on the white a scanner needs.
 */
@Composable
fun TillRelayDialog(
    card: String?,
    codes: List<TillCode>,
    unscanned: List<BasketItem>,
    intervalMs: Int,
    onInterval: (Int) -> Unit,
    onNoBarcode: (BasketItem) -> Unit,
    onPaid: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose { window?.let { it.attributes = it.attributes.apply { screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    val steps = remember(card, codes) {
        buildList {
            if (card != null) add(TillStep.Card(card))
            var previous: String? = null
            var repeat = 0
            for (code in codes) {
                repeat = if (code.raw == previous) repeat + 1 else 0
                previous = code.raw
                add(TillStep.Code(code, repeat))
            }
            add(TillStep.Done)
        }
    }
    val codeCount = steps.count { it is TillStep.Code }
    // Saved, so turning the phone at the till keeps the place in the run.
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = savedIndex.coerceIn(0, steps.lastIndex)
    var playing by rememberSaveable { mutableStateOf(false) }
    // The same code twice in a row: a short blank between them, or the scanner takes the second for the first.
    var blank by remember { mutableStateOf(false) }
    var reported by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmPaid by rememberSaveable { mutableStateOf(false) }
    fun go(to: Int) { savedIndex = to.coerceIn(0, steps.lastIndex); if (steps[savedIndex] is TillStep.Done) playing = false }
    val step = steps[index]

    LaunchedEffect(index) {
        val current = steps[index]
        if (current is TillStep.Code && current.repeat > 0) { blank = true; delay(300); blank = false }
    }
    LaunchedEffect(index, playing, intervalMs) {
        if (playing && steps[index] !is TillStep.Done) { delay(intervalMs.toLong()); go(index + 1) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BasktTheme(darkTheme = false) {
            val colors = MaterialTheme.colorScheme
            if (confirmPaid) ConfirmDialog(
                title = "Clear the till codes?",
                body = "This can’t be undone.",
                confirmLabel = "Clear",
                onDismiss = { confirmPaid = false },
                onConfirm = onPaid,
            )
            Surface(color = Color.White, contentColor = colors.onSurface, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.VolumeUp -> { if (event.type == KeyEventType.KeyDown) go(index + 1); true }
                                Key.VolumeDown -> { if (event.type == KeyEventType.KeyDown) go(index - 1); true }
                                else -> false
                            }
                        }
                        .safeDrawingPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("At the till", style = MaterialTheme.typography.titleLarge)
                            Text(
                                when (step) {
                                    is TillStep.Card -> "Loyalty card, then ${counted(codeCount, "code")}"
                                    is TillStep.Code -> "Code ${steps.take(index + 1).count { it is TillStep.Code }} of $codeCount"
                                    TillStep.Done -> "${counted(codeCount, "code")} shown"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                    LinearWavyProgressIndicator(progress = { if (steps.size <= 1) 1f else index.toFloat() / (steps.size - 1) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))

                    if (step is TillStep.Done) {
                        DoneStep(unscanned, reported, onNoBarcode = { item -> reported = reported + item.id; onNoBarcode(item) }, modifier = Modifier.weight(1f))
                    } else {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when (step) {
                                is TillStep.Card -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    BarcodeImage(step.number, modifier = Modifier.fillMaxWidth().aspectRatio(2.6f))
                                    Spacer(Modifier.height(12.dp))
                                    Text("Loyalty card", style = MaterialTheme.typography.titleMedium)
                                    Text(step.number.chunked(4).joinToString(" "), style = tillNumberStyle(), textAlign = TextAlign.Center)
                                }
                                is TillStep.Code -> if (!blank) CodeView(step.code)
                                TillStep.Done -> Unit
                            }
                        }
                    }

                    // Previous, play and next at the M3 medium and large icon-button sizes, icons scaled to match.
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        val side = Modifier.size(IconButtonDefaults.mediumContainerSize())
                        val sideIcon = Modifier.size(IconButtonDefaults.mediumIconSize)
                        val main = Modifier.size(IconButtonDefaults.largeContainerSize())
                        val mainIcon = Modifier.size(IconButtonDefaults.largeIconSize)
                        FilledIconButton(onClick = { go(index - 1) }, enabled = index > 0, modifier = side) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous code", modifier = sideIcon) }
                        if (step is TillStep.Done) FilledIconButton(onClick = { go(0) }, modifier = main) { Icon(Icons.Default.Replay, contentDescription = "Start again", modifier = mainIcon) }
                        else FilledIconButton(onClick = { playing = !playing }, modifier = main) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play", modifier = mainIcon)
                        }
                        FilledIconButton(onClick = { go(index + 1) }, enabled = step !is TillStep.Done, modifier = side) { Icon(Icons.Default.SkipNext, contentDescription = "Next code", modifier = sideIcon) }
                    }
                    if (step is TillStep.Done) {
                        Text("Check the till’s total before you pay", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            OutlinedButton(onClick = onDismiss) { Text("Not yet") }
                            Button(onClick = { confirmPaid = true }) { Text("Paid") }
                        }
                    } else {
                        Text("Seconds per code", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp))
                        ChoiceGroup(
                            options = INTERVALS.map { "${it / 1000.0}".removeSuffix(".0") },
                            selected = INTERVALS.indexOf(intervalMs),
                            onSelect = { onInterval(INTERVALS[it]) },
                        )
                        Text("Volume keys also step", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeView(code: TillCode) {
    val bitmap = remember(code.raw, code.format) { tillBitmap(code) }
    val square = remember(code.raw, code.format) { isSquare(encodingFor(code).second) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), contentDescription = "Barcode ${code.raw}",
                modifier = if (square) Modifier.fillMaxWidth(0.75f).aspectRatio(1f) else Modifier.fillMaxWidth().aspectRatio(2.6f),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None,
            )
        } else {
            Text("Can’t show this code. Scan the item at the till.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        Text(code.title ?: "Scanned item", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(code.raw.replace('\u001d', '·'), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DoneStep(unscanned: List<BasketItem>, reported: List<String>, onNoBarcode: (BasketItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (unscanned.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Everything was scanned", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            return
        }
        Text("Show these at the till by hand", style = MaterialTheme.typography.titleMedium)
        Text("Checked off without a scan", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            items(unscanned, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((if (item.quantity > 1) "${item.quantity}× " else "") + item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    // Telling baskt an item has no barcode stops it from being asked for on the next trip.
                    if (item.id in reported) Tag("No barcode")
                    else TextButton(onClick = { onNoBarcode(item) }) { Text("No barcode") }
                }
            }
        }
    }
}
