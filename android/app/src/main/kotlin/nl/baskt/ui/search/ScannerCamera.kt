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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class CaptureFeedback(val image: ImageBitmap?, val bounds: RectF)

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
internal fun ScannerCamera(paused: Boolean, cameraFraction: Float, onScan: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val currentScan by rememberUpdatedState(onScan)
    val currentCameraFraction by rememberUpdatedState(cameraFraction)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var retry by remember { mutableIntStateOf(0) }
    val gate = remember { BarcodeConfirmation() }
    var capture by remember { mutableStateOf<CaptureFeedback?>(null) }
    val frameProgress = remember { Animatable(0f) }
    val freezeOpacity = remember { Animatable(1f) }
    LaunchedEffect(capture, paused, retry) {
        if (paused) { capture = null; return@LaunchedEffect }
        if (capture == null) return@LaunchedEffect
        frameProgress.snapTo(0f)
        freezeOpacity.snapTo(1f)
        // Lock onto the label, briefly hold confirmation, then blend back into live scanning.
        frameProgress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        delay(120)
        coroutineScope {
            launch { freezeOpacity.animateTo(0f, tween(180)) }
            launch { frameProgress.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
        }
        capture = null
    }
    DisposableEffect(lifecycle, paused, retry) {
        var disposed = false
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
        ).build())
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
                                    if (!disposed && error == null && capture == null) {
                                        val output = previewView.outputTransform
                                        // Accept labels up to 65% of the guide's height above and below it.
                                        // Use the exposed camera area, so labels hidden by the batch panel never scan.
                                        val visibleHeight = previewView.height * currentCameraFraction
                                        val target = RectF(previewView.width * .1f, visibleHeight * (.35f - .3f * .65f), previewView.width * .9f, visibleHeight * (.65f + .3f * .65f))
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
                                        gate.observe(code, SystemClock.elapsedRealtime())?.let { confirmed ->
                                            if (currentScan(confirmed)) {
                                                val bounds = RectF(inside.single().boundingBox!!)
                                                CoordinateTransform(transform, output!!).mapRect(bounds)
                                                // Snapshot and bounds use PreviewView coordinates, including its crop/rotation.
                                                val padding = 12 * context.resources.displayMetrics.density
                                                bounds.inset(-padding, -padding)
                                                val normalized = RectF(
                                                    (bounds.left / previewView.width).coerceIn(0f, 1f),
                                                    (bounds.top / previewView.height).coerceIn(0f, 1f),
                                                    (bounds.right / previewView.width).coerceIn(0f, 1f),
                                                    (bounds.bottom / previewView.height).coerceIn(0f, 1f),
                                                )
                                                capture = CaptureFeedback(previewView.bitmap?.asImageBitmap(), normalized)
                                            }
                                        }
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
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(camera, capture) {
            detectTapGestures { point ->
                if (capture == null) camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(
                    previewView.meteringPointFactory.createPoint(point.x, point.y),
                ).build())
            }
        }) {
            val feedback = capture
            feedback?.image?.let {
                drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = freezeOpacity.value)
            }
            val progress = if (feedback == null) 0f else frameProgress.value
            val bounds = feedback?.bounds
            fun interpolate(start: Float, end: Float?) = start + ((end ?: start) - start) * progress
            val left = size.width * interpolate(.1f, bounds?.left)
            val top = size.height * interpolate(.35f * cameraFraction, bounds?.top)
            val right = size.width * interpolate(.9f, bounds?.right)
            val bottom = size.height * interpolate(.65f * cameraFraction, bounds?.bottom)
            val width = right - left
            val height = bottom - top
            val radius = CornerRadius(12.dp.toPx())
            // One continuous mask avoids seams between separate dimming rectangles and
            // covers the corners outside the rounded target during the capture animation.
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(left, top, right, bottom), radius))
            }
            drawPath(mask, Color.Black.copy(alpha = .55f))
            drawRoundRect(Color.White, Offset(left, top), Size(width, height), cornerRadius = radius, style = Stroke(2.dp.toPx()))
        }
        if (paused || error != null) Surface(color = Color(0xFF383838).copy(alpha = .9f), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(cameraFraction).wrapContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "Scanning paused", color = Color.White)
                if (error != null) TextButton(onClick = { retry++ }) { Text("Try again") }
            }
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(cameraFraction)) {
        if (!paused && error == null) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (camera?.cameraInfo?.hasFlashUnit() == true) FilledTonalButton(enabled = capture == null, onClick = {
                torch = !torch; camera?.cameraControl?.enableTorch(torch)
            }) { Text(if (torch) "Light on" else "Light off") }
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            if (maxZoom >= 2f) FilledTonalButton(enabled = capture == null, onClick = {
                zoom = if (zoom == 1f) 2f else 1f
                camera?.cameraControl?.setZoomRatio(zoom)
            }) { Text("${zoom.toInt()}× zoom") }
        }
        }
    }
}
