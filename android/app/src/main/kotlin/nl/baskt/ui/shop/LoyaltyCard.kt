package nl.baskt.ui.shop

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import nl.baskt.data.StoreInfo
import nl.baskt.ui.common.StoreLogo

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

@Composable
fun BarcodeImage(number: String, modifier: Modifier = Modifier) {
    val bitmap = remember(number) { barcodeBitmap(number) }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = "Barcode $number", modifier = modifier, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Cannot draw this number as a barcode", color = Color.Black) }
    }
}

/** Full-screen, full-brightness card for the checkout scanner. */
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
        Column(
            modifier = Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StoreLogo(store, stores, size = 40)
                Text(stores.firstOrNull { it.code == store }?.name ?: store, style = MaterialTheme.typography.titleLarge, color = Color.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black) }
            }
            Spacer(Modifier.weight(1f))
            BarcodeImage(number, modifier = Modifier.fillMaxWidth().aspectRatio(2.6f).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(16.dp))
            Text(number.chunked(4).joinToString(" "), color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Text("Hold the screen to the scanner at the checkout", color = Color.DarkGray, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            TextButton(onClick = onEdit) { Text("Change card number", color = Color.Black) }
        }
    }
}

/** Type the card number or scan the plastic card; saving an empty field removes the card. */
@Composable
fun LoyaltyCardEditor(store: String, stores: List<StoreInfo>, initial: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf(initial ?: "") }
    fun scanCard() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { number = it } }
            .addOnFailureListener { error ->
                val cancelled = error is com.google.mlkit.common.MlKitException && error.errorCode == com.google.mlkit.common.MlKitException.CODE_SCANNER_CANCELLED
                if (!cancelled) android.widget.Toast.makeText(context, error.message ?: "Scanner unavailable", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { StoreLogo(store, stores); Text("${stores.firstOrNull { it.code == store }?.name ?: store} card") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The number under the barcode on your card. Jumbo Extra's is your client number; it is shown as the same barcode at the checkout.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Card number") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { scanCard() }, modifier = Modifier.fillMaxWidth()) { Text("Scan the card") }
                val cleaned = number.filter { !it.isWhitespace() }
                if (cleaned.isNotEmpty()) BarcodeImage(cleaned, modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(6.dp)).background(Color.White))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(number.filter { !it.isWhitespace() }.ifBlank { null }) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!initial.isNullOrBlank()) TextButton(onClick = { onSave(null) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
