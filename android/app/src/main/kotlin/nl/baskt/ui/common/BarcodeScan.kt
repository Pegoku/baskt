package nl.baskt.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import nl.baskt.BasktApp

/**
 * The formats a product code comes in: the usual EAN/UPC, plus the GS1 DataMatrix and QR (and GS1-128) printed
 * on labels of meat and cheese weighed in the store. Loyalty cards use [Barcode.FORMAT_ALL_FORMATS] instead.
 */
val ProductBarcodeFormats = intArrayOf(
    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
    Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128,
)

/**
 * Google's one-shot code scanner: opens its own full-screen camera and hands back a single code. Cancelling
 * is silent. Any other failure goes to [onFailure] as a sentence to show; without one it lands in the app's
 * global message bar, since the caller's screen may be gone by then.
 */
fun startBarcodeScan(context: Context, formats: IntArray = ProductBarcodeFormats, onFailure: ((String) -> Unit)? = null, onCode: (String) -> Unit) =
    startBarcodeScanWithFormat(context, formats, onFailure) { raw, _ -> onCode(raw) }

/** [startBarcodeScan] that also says how the code was printed (a [Barcode] FORMAT_ constant), to draw it again. */
fun startBarcodeScanWithFormat(context: Context, formats: IntArray = ProductBarcodeFormats, onFailure: ((String) -> Unit)? = null, onCode: (String, Int) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray())
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let { onCode(it, barcode.format) } }
        .addOnFailureListener { error ->
            val cancelled = error is MlKitException && error.errorCode == MlKitException.CODE_SCANNER_CANCELLED
            if (cancelled) return@addOnFailureListener
            val message = "Couldn’t open the scanner"
            if (onFailure != null) onFailure(message)
            else (context.applicationContext as? BasktApp)?.container?.basket?.notices?.tryEmit(message)
        }
}

/** [startBarcodeScan] bound to the current context, with the latest [onCode] so recompositions never stale it. */
@Composable
fun rememberBarcodeScanner(formats: IntArray = ProductBarcodeFormats, onFailure: ((String) -> Unit)? = null, onCode: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onCode)
    val latestFailure by rememberUpdatedState(onFailure)
    return { startBarcodeScan(context, formats, latestFailure?.let { { message: String -> latestFailure?.invoke(message) ?: Unit } }) { latest(it) } }
}

/** [rememberBarcodeScanner] with the printed format of the code. */
@Composable
fun rememberBarcodeScannerWithFormat(formats: IntArray = ProductBarcodeFormats, onFailure: ((String) -> Unit)? = null, onCode: (String, Int) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onCode)
    val latestFailure by rememberUpdatedState(onFailure)
    return { startBarcodeScanWithFormat(context, formats, latestFailure?.let { { message: String -> latestFailure?.invoke(message) ?: Unit } }) { raw, format -> latest(raw, format) } }
}
