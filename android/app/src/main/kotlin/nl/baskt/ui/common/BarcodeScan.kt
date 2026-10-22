package nl.baskt.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** The retail formats a product barcode comes in; loyalty cards use [Barcode.FORMAT_ALL_FORMATS] instead. */
val ProductBarcodeFormats = intArrayOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)

/**
 * Google's one-shot code scanner: opens its own full-screen camera and hands back a single code. Cancelling
 * is silent; any other failure is shown as a toast, since the caller's screen may be gone by then.
 */
fun startBarcodeScan(context: Context, formats: IntArray = ProductBarcodeFormats, onCode: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray())
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onCode) }
        .addOnFailureListener { error ->
            val cancelled = error is MlKitException && error.errorCode == MlKitException.CODE_SCANNER_CANCELLED
            if (!cancelled) Toast.makeText(context, error.message ?: "Scanner unavailable", Toast.LENGTH_SHORT).show()
        }
}

/** [startBarcodeScan] bound to the current context, with the latest [onCode] so recompositions never stale it. */
@Composable
fun rememberBarcodeScanner(formats: IntArray = ProductBarcodeFormats, onCode: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onCode)
    return { startBarcodeScan(context, formats) { latest(it) } }
}
