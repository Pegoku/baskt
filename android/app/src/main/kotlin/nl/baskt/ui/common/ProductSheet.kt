package nl.baskt.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import nl.baskt.data.unitPriceLabel

@Composable
fun ZoomableImage(url: String, title: String, modifier: Modifier = Modifier) {
    var zoomed by remember { mutableStateOf(false) }
    AsyncImage(url, contentDescription = "Enlarge $title", modifier = modifier.clickable { zoomed = true })
    if (zoomed) Dialog(onDismissRequest = { zoomed = false }) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().clickable { zoomed = false }) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(url, title, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                Text(title, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { zoomed = false }) { Text("Close image") }
            }
        }
    }
}

@Composable
fun ProductSheet(product: Product, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as BasktApp).container
    val uri = LocalUriHandler.current
    var detail by remember(product.id) { mutableStateOf<ProductDetail?>(null) }
    var loading by remember(product.id) { mutableStateOf(true) }
    var original by rememberSaveable(product.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(product.id, retry) {
        loading = true
        val key = "product-detail-${product.id}"
        detail = container.offline.load(key)
        try { detail = container.api.productDetail(product.id).also { container.offline.save(key, it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep saved product facts when offline. */ }
        finally { loading = false }
    }
    val source = detail?.product ?: product
    val texts = listOf(source.title, detail?.description.orEmpty(), source.quantityText, source.category.orEmpty(), source.dealText.orEmpty())
    val translation = rememberTranslation(texts, !original)
    val shown = if (!original && translation?.translated == true) translation.texts else texts
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(.92f).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Product details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OriginalToggle(original) { original = !original }
            }
            if (!original && translation?.translated != true) Text(if (translation == null) "Translating…" else "Translation unavailable · showing original", style = MaterialTheme.typography.labelSmall)
            val images = (detail?.imageUrls?.takeIf { it.isNotEmpty() } ?: listOfNotNull(source.imageUrl)).distinct()
            for (image in images) ZoomableImage(image, shown[0], Modifier.fillMaxWidth().height(220.dp))
            Text(shown[0], style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(source.brand, shown[2], source.priceCents.euros(), source.unitPriceLabel()).joinToString(" · "))
            if (shown[3].isNotBlank()) Text(shown[3], color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shown[4].isNotBlank()) Text(shown[4], color = MaterialTheme.colorScheme.tertiary)
            if (shown[1].isNotBlank()) DescriptionText(shown[1])
            else if (loading) Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(Modifier.size(24.dp)); Text("Loading description…") }
            else {
                Text("No additional description is available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { retry++ }) { Text("Retry details") }
            }
            source.sourceUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("View original store page") } }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}
