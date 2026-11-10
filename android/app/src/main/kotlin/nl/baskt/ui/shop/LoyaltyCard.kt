package nl.baskt.ui.shop

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import nl.baskt.data.StoreInfo
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.storeName
import nl.baskt.ui.theme.BasktTheme

/** EAN-13 when the number is 13 digits with a valid check digit (every Dutch supermarket card), Code 128 otherwise. */
fun loyaltyFormat(number: String): BarcodeFormat {
    val digits = number.all { it.isDigit() }
    if (digits && number.length == 13) {
        val sum = number.take(12).mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 1 else 3 }.sum()
        if ((10 - sum % 10) % 10 == number.last() - '0') return BarcodeFormat.EAN_13
    }
    if (digits && number.length == 8) return BarcodeFormat.EAN_8
    return BarcodeFormat.CODE_128
}

/** Renders the number as a barcode bitmap; null when the number cannot be encoded. */
fun barcodeBitmap(number: String, width: Int = 1200, height: Int = 400): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(number, loyaltyFormat(number), width, height, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix.get(index % matrix.width, index / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** The number as a barcode, black on white. Call it on a white surface: the fallback text takes the surface's content colour. */
@Composable
fun BarcodeImage(number: String, modifier: Modifier = Modifier) {
    val bitmap = remember(number) { barcodeBitmap(number) }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = "Barcode $number", modifier = modifier, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Can’t show this number as a barcode", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
    }
}

/** Full-screen, full-brightness card for the checkout scanner, always in the light theme so it reads on white. */
@Composable
fun LoyaltyCardDialog(store: String, stores: List<StoreInfo>, number: String, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose { window?.let { it.attributes = it.attributes.apply { screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BasktTheme(darkTheme = false) {
            Surface(color = Color.White, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StoreLogo(store, stores, size = 40)
                        Text("${storeName(store, stores)} card", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                    Spacer(Modifier.weight(1f))
                    BarcodeImage(number, modifier = Modifier.fillMaxWidth().aspectRatio(2.6f))
                    Spacer(Modifier.height(16.dp))
                    Text(number.chunked(4).joinToString(" "), style = tillNumberStyle(), textAlign = TextAlign.Center)
                    Spacer(Modifier.weight(1f))
                    Text("Hold the screen to the scanner at the checkout", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    TextButton(onClick = onEdit) { Text("Change card number") }
                }
            }
        }
    }
}

/**
 * Type the card number or scan the plastic card, in a sheet so the field, the scan button and the preview stay
 * in reach above the keyboard. Save needs a number; taking the card away is "Remove", which calls [onSave] with
 * null (the view model then offers the undo).
 */
@Composable
fun LoyaltyCardEditor(store: String, stores: List<StoreInfo>, initial: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var number by rememberSaveable { mutableStateOf(initial ?: "") }
    val scanCard = nl.baskt.ui.common.rememberBarcodeScanner(intArrayOf(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS)) { number = it }
    val cleaned = number.filter { !it.isWhitespace() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBody(
            title = "${storeName(store, stores)} card",
            titleTrailing = { StoreLogo(store, stores) },
            actions = {
                if (!initial.isNullOrBlank()) TextButton(onClick = { onSave(null) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(cleaned) }, enabled = cleaned.isNotEmpty()) { Text("Save") }
            },
        ) {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Card number") },
                // Jumbo prints no number on the card itself: its Extra's programme goes by the client number.
                supportingText = { Text(if (store.equals("JUMBO", ignoreCase = true)) "Your Jumbo Extra’s client number" else "The number under the barcode on your card") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { scanCard() }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding, modifier = Modifier.fillMaxWidth()) { IconLabel(Icons.Default.QrCodeScanner, "Scan the card") }
            // The preview is the white card the till will see, whatever the app's theme.
            if (cleaned.isNotEmpty()) BasktTheme(darkTheme = false) {
                Surface(color = Color.White, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().aspectRatio(3f)) {
                    BarcodeImage(cleaned, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** How a card or payment number is printed under its barcode on the white till screens, so all of them read the same. */
@Composable
internal fun tillNumberStyle() = MaterialTheme.typography.titleLargeEmphasized.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
