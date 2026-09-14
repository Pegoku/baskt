package nl.baskt.ui.search

import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.view.doOnLayout
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
internal fun ScannerCamera(paused: Boolean, onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val currentScan by rememberUpdatedState(onScan)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var retry by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle, paused, retry) {
        var disposed = false
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
        ).build())
        val gate = BarcodeConfirmation()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var provider: ProcessCameraProvider? = null
        error = null
        if (!paused) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                previewView.doOnLayout {
                    if (!disposed) try {
                        provider = future.get()
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null) { proxy.close(); return@setAnalyzer }
                            val transform = ImageProxyTransformFactory().apply {
                                isUsingRotationDegrees = true
                                isUsingCropRect = true
                            }.getOutputTransform(proxy)
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes ->
                                    if (!disposed && error == null) {
                                        val output = previewView.outputTransform
                                        val target = RectF(previewView.width * .1f, previewView.height * .35f, previewView.width * .9f, previewView.height * .65f)
                                        val inside = if (output == null) emptyList() else codes.filter { code ->
                                            code.boundingBox?.let { bounds ->
                                                val mapped = RectF(bounds)
                                                CoordinateTransform(transform, output).mapRect(mapped)
                                                target.contains(mapped)
                                            } ?: false
                                        }
                                        // Ambiguous frames (two products in the target) must never choose arbitrarily.
                                        val code = inside.singleOrNull()?.let {
                                            if (it.format == Barcode.FORMAT_UPC_E) it.rawValue?.let(::expandUpce) else it.rawValue
                                        }?.let { if (it.length == 12) "0$it" else it }
                                        gate.observe(code, SystemClock.elapsedRealtime())?.let(currentScan)
                                    }
                                }
                                .addOnFailureListener { if (!disposed) error = "Barcode scanning stopped. Try again." }
                                .addOnCompleteListener { proxy.close() }
                        }
                        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                        previewView.viewPort?.let { group.setViewPort(it) }
                        camera = provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                        camera?.cameraControl?.enableTorch(torch)
                        camera?.cameraControl?.setZoomRatio(zoom)
                    } catch (_: Exception) { error = "Camera couldn’t start. Try again." }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed = true
            camera = null
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(camera) {
            detectTapGestures { point ->
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(
                    previewView.meteringPointFactory.createPoint(point.x, point.y),
                ).build())
            }
        }) {
            val left = size.width * .1f
            val top = size.height * .35f
            val width = size.width * .8f
            val height = size.height * .3f
            val shade = Color.Black.copy(alpha = .55f)
            drawRect(shade, size = Size(size.width, top))
            drawRect(shade, Offset(0f, top + height), Size(size.width, size.height - top - height))
            drawRect(shade, Offset(0f, top), Size(left, height))
            drawRect(shade, Offset(left + width, top), Size(left, height))
            drawRoundRect(Color.White, Offset(left, top), Size(width, height), style = Stroke(2.dp.toPx()))
        }
        if (paused || error != null) Surface(color = Color.Black.copy(alpha = .85f), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.wrapContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "Scanning paused", color = Color.White)
                if (error != null) TextButton(onClick = { retry++ }) { Text("Try again") }
            }
        }
        if (!paused && error == null) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (camera?.cameraInfo?.hasFlashUnit() == true) FilledTonalButton(onClick = {
                torch = !torch; camera?.cameraControl?.enableTorch(torch)
            }) { Text(if (torch) "Light on" else "Light off") }
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            if (maxZoom >= 2f) FilledTonalButton(onClick = {
                zoom = if (zoom == 1f) 2f else 1f
                camera?.cameraControl?.setZoomRatio(zoom)
            }) { Text("${zoom.toInt()}× zoom") }
        }
    }
}
