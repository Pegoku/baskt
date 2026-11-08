package nl.baskt.ui.shop

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
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
 * for the till's scanner. It moves on by itself at the chosen pace; a tap on the left or right goes back or on, the
 * middle pauses, and the volume keys step back and forth. The end lists what the till still has to be shown by hand.
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
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    // The same code twice in a row: a short blank between them, or the scanner takes the second for the first.
    var blank by remember { mutableStateOf(false) }
    val reported = remember { mutableStateOf(setOf<String>()) }
    fun go(to: Int) { index = to.coerceIn(0, steps.lastIndex); if (steps[index] is TillStep.Done) playing = false }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
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
                    Text("Till", style = MaterialTheme.typography.titleLarge, color = Color.Black)
                    Text(
                        when (step) {
                            is TillStep.Card -> "Loyalty card · then $codeCount codes"
                            is TillStep.Code -> "Code ${steps.take(index + 1).count { it is TillStep.Code }} of $codeCount"
                            TillStep.Done -> "All $codeCount codes shown"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black) }
            }
            LinearWavyProgressIndicator(progress = { if (steps.size <= 1) 1f else index.toFloat() / (steps.size - 1) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))

            if (step is TillStep.Done) {
                DoneStep(unscanned, reported.value, onNoBarcode = { item -> reported.value = reported.value + item.id; onNoBarcode(item) }, modifier = Modifier.weight(1f))
            } else {
                // Tap zones over the code: left third back, right third on, middle play or pause.
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (step) {
                        is TillStep.Card -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BarcodeImage(step.number, modifier = Modifier.fillMaxWidth().aspectRatio(2.6f))
                            Spacer(Modifier.height(12.dp))
                            Text("Loyalty card", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                        is TillStep.Code -> if (!blank) CodeView(step.code)
                        TillStep.Done -> Unit
                    }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = null) { go(index - 1) })
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = null) { playing = !playing })
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = null) { go(index + 1) })
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { go(index - 1) }, enabled = index > 0, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous code") }
                if (step is TillStep.Done) FilledIconButton(onClick = { go(0) }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.Replay, contentDescription = "Start again") }
                else FilledIconButton(onClick = { playing = !playing }, modifier = Modifier.size(72.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
                }
                FilledIconButton(onClick = { go(index + 1) }, enabled = step !is TillStep.Done, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next code") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                for (ms in INTERVALS) FilterChip(selected = ms == intervalMs, onClick = { onInterval(ms) }, label = { Text("${ms / 1000.0} s".replace(".0 s", " s")) })
            }
            Text(
                if (step is TillStep.Done) "Check the till's total before you pay." else "Tap the middle or press ▶ to run · volume keys step back and on",
                style = MaterialTheme.typography.bodySmall, color = Color.DarkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            if (step is TillStep.Done) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onDismiss) { Text("Keep codes") }
                Button(onClick = onPaid) { Text("Paid · clear codes") }
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
            Text("This code cannot be drawn again; scan the item at the till.", color = Color.Black, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        Text(code.title ?: "Scanned item", color = Color.Black, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(code.raw.replace('\u001d', '·'), color = Color.DarkGray, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DoneStep(unscanned: List<BasketItem>, reported: Set<String>, onNoBarcode: (BasketItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (unscanned.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Everything you ticked off was scanned.", color = Color.Black, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            return
        }
        Text("Show these at the till by hand", color = Color.Black, style = MaterialTheme.typography.titleMedium)
        Text("Ticked off without a scan. If one had no barcode at all, say so and baskt remembers for next time.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            items(unscanned, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (item.quantity > 1) "${item.quantity}× " else "") + item.text, color = Color.Black, modifier = Modifier.weight(1f))
                    if (item.id in reported) Text("Noted", color = Color.DarkGray, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                    else TextButton(onClick = { onNoBarcode(item) }) { Text("No barcode") }
                }
                HorizontalDivider()
            }
        }
    }
}
