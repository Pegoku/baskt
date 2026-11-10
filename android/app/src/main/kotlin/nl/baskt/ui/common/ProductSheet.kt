package nl.baskt.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import nl.baskt.BasktApp
import nl.baskt.data.Product
import nl.baskt.data.ProductDetail
import nl.baskt.data.euros
import nl.baskt.data.dealPricing
import nl.baskt.data.unitPriceLabel

/** A picture that opens full screen when tapped, where it can be zoomed. */
@Composable
fun ZoomableImage(url: String, title: String, modifier: Modifier = Modifier) {
    var zoomed by rememberSaveable(url) { mutableStateOf(false) }
    AsyncImage(url, contentDescription = title, modifier = modifier.clickable(role = Role.Button, onClickLabel = "Enlarge the picture") { zoomed = true })
    if (zoomed) ImageViewer(url, title) { zoomed = false }
}

/** A full-screen dialog, so the zoomed image can grow past its card. Only taps beside both, or Close, dismiss it. */
@Composable
private fun ImageViewer(url: String, title: String, onDismiss: () -> Unit) {
    var card by remember { mutableStateOf(Rect.Zero) }
    var frame by remember { mutableStateOf(Rect.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Keep the scaled image covering its frame so it can't be dragged away.
    fun clamp(value: Offset, s: Float): Offset {
        val maxX = (s - 1) * frame.width / 2
        val maxY = (s - 1) * frame.height / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    // Scaling happens around the frame's centre, so a point keeps its place when the offset follows it.
    fun zoomAround(point: Offset, newScale: Float, pan: Offset = Offset.Zero) {
        val focus = point - frame.center
        offset = clamp(focus - (focus - offset) * (newScale / scale) + pan, newScale)
        scale = newScale
    }
    fun onImage(point: Offset): Boolean {
        val center = frame.center + offset
        val half = Offset(frame.width, frame.height) * (scale / 2)
        return Rect(center - half, center + half).contains(point)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { point -> if (scale > 1f) { scale = 1f; offset = Offset.Zero } else if (onImage(point)) zoomAround(point, 2.5f) },
                        onTap = { point -> if (!card.contains(point) && !onImage(point)) onDismiss() },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ -> zoomAround(centroid, (scale * zoom).coerceIn(1f, 5f), pan) }
                },
            contentAlignment = Alignment.Center,
        ) {
            // A background rather than a Surface, which would clip the zoomed image to the card.
            Column(
                Modifier.padding(24.dp).fillMaxWidth()
                    .onGloballyPositioned { card = it.boundsInRoot() }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(url, title, modifier = Modifier.fillMaxWidth().aspectRatio(1f).zIndex(1f)
                    .onGloballyPositioned { frame = it.boundsInRoot() }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

/**
 * Everything about one product in one sheet: pictures, price, facts, description, and the button that puts it
 * in the basket. "Similar products" and the details of a similar product are shown inside this same sheet,
 * with a back arrow, so nothing ever opens on top of it.
 */
@Composable
fun ProductSheet(product: Product, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as BasktApp).container
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val stores by container.basket.stores.collectAsState()
    // The product on show. Opening a similar product puts the one it came from on [trail], for the way back.
    var current by remember(product.id) { mutableStateOf(product) }
    val trail = remember(product.id) { mutableStateListOf<Product>() }
    var similar by rememberSaveable(product.id) { mutableStateOf(false) }
    var detail by remember(current.id) { mutableStateOf<ProductDetail?>(null) }
    var loading by remember(current.id) { mutableStateOf(true) }
    var failed by remember(current.id) { mutableStateOf(false) }
    var original by rememberSaveable(product.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var added by remember(product.id) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(current.id, retry) {
        loading = true
        failed = false
        val key = "product-detail-${current.id}"
        detail = container.offline.load(key)
        try { detail = container.api.productDetail(current.id).also { container.offline.save(key, it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true /* Saved product facts stay on show when offline. */ }
        finally { loading = false }
    }
    val source = detail?.product ?: current
    val texts = listOf(source.title, detail?.description.orEmpty(), source.quantityText, source.category.orEmpty(), source.dealText.orEmpty())
    val translation = rememberTranslation(texts, !original, descriptionIndices = listOf(1))
    val shown = if (!original && translation?.translated == true) translation.texts else texts

    fun add() {
        added = added + source.id
        scope.launch {
            try { container.basket.addFromProduct(source) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                added = added - source.id
                container.basket.notices.tryEmit("Couldn’t add “${source.title}”")
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (similar) {
            SheetPage(title = "Similar products", onBack = { similar = false }) {
                SimilarContent(source, onOpen = { next -> trail.add(current); current = next; similar = false })
            }
            return@ModalBottomSheet
        }
        SheetPage(
            title = shown[0],
            onBack = if (trail.isEmpty()) null else ({ current = trail.removeAt(trail.lastIndex); similar = true }),
            titleTrailing = { OriginalToggle(original) { original = !original } },
            actions = {
                source.sourceUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("View at ${storeName(source.store, stores)}", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                Button(onClick = { add() }, enabled = source.id !in added) { Text(if (source.id in added) "Added" else "Add to basket") }
            },
        ) {
            if (!original && translation?.translated != true) Text(if (translation == null) "Translating…" else "Translation unavailable, showing the original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val images = (detail?.imageUrls?.takeIf { it.isNotEmpty() } ?: listOfNotNull(source.imageUrl)).distinct()
            if (images.isNotEmpty()) ImagePager(images, shown[0])
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.priceCents.euros(), style = MaterialTheme.typography.titleLargeEmphasized)
                if (source.regularPriceCents != null && source.regularPriceCents > source.priceCents) {
                    Text(source.regularPriceCents.euros(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
                }
                Spacer(Modifier.weight(1f))
                StoreBadge(source.store, stores)
            }
            val facts = listOfNotNull(source.brand, shown[2].takeIf { it.isNotBlank() }, source.unitPriceLabel()).joinToString(" · ")
            if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.bodyMedium)
            if (shown[3].isNotBlank()) Text(shown[3], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shown[4].isNotBlank()) {
                val deal = source.dealPricing()
                Text(listOfNotNull(shown[4], deal?.let { "${it.count} for ${it.bundleCents.euros()}" }, deal?.label()).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            // A product without a description shows none; only a load that failed says so and offers another go.
            if (shown[1].isNotBlank()) DescriptionText(shown[1])
            else if (loading) LoadingRow("Loading description…")
            else if (failed) EmptyState("Couldn’t load the description", action = { FilledTonalButton(onClick = { retry++ }) { Text("Try again") } })
            OutlinedButton(onClick = { similar = true }, modifier = Modifier.fillMaxWidth()) { IconLabel(Icons.Default.Storefront, "Similar products") }
        }
    }
}

/** A [SheetBody] page, with a back arrow before the title when the page was reached from another one in the same sheet. */
@Composable
private fun SheetPage(
    title: String,
    onBack: (() -> Unit)?,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack == null) {
        SheetBody(title = title, titleTrailing = titleTrailing, actions = actions, content = content)
        return
    }
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = Spacing.SheetSide, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            titleTrailing?.invoke(this)
        }
        SheetBody(actions = actions, content = content)
    }
}

/** The product's pictures side by side: swipe between them, tap one to enlarge it. Dots show which one is up. */
@Composable
private fun ImagePager(images: List<String>, title: String) {
    val pager = rememberPagerState { images.size }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state = pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { page ->
            // White behind the photo: the stores ship them cut out on white.
            Surface(shape = MaterialTheme.shapes.large, color = Color.White, modifier = Modifier.fillMaxWidth().height(220.dp)) {
                ZoomableImage(images[page], title, Modifier.fillMaxSize().padding(8.dp))
            }
        }
        if (images.size > 1) Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clearAndSetSemantics { contentDescription = "Picture ${pager.currentPage + 1} of ${images.size}" },
        ) {
            for (index in images.indices) {
                Box(Modifier.size(8.dp).background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall))
            }
        }
    }
}
