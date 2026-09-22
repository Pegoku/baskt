package nl.baskt.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the phone alone can say about a scanned barcode: enough to recognise the item on screen, without a price. */
@Serializable
data class ScannedLabel(
    val title: String,
    val quantityText: String? = null,
    val imageUrl: String? = null,
    /** Where it came from: "cache" (an earlier server answer), "stock", or "openfoodfacts". */
    val source: String,
)

/**
 * Names and pictures for barcodes when the backend cannot be reached — in the shop the home server usually is
 * out of reach. The server stays the preferred source; this only fills the gap, in order: what the phone already
 * knows (an earlier lookup, or something in stock), then Open Food Facts when the phone itself has internet.
 */
class OfflineBarcodes(private val offline: OfflineStore?, private val http: HttpClient = defaultClient()) {

    /** Answers instantly from disk, with or without any connection at all. */
    fun known(gtin: String, stock: List<StockItem> = emptyList()): ScannedLabel? {
        val code = normalise(gtin)
        offline?.load<BarcodeResponse>("barcode-$gtin")?.results?.firstNotNullOfOrNull { it.product }?.let {
            return ScannedLabel(it.title, it.quantityText, it.imageUrl, "cache")
        }
        offline?.load<ScannedLabel>("phone-barcode-$gtin")?.let { return it }
        stock.firstOrNull { it.barcode != null && normalise(it.barcode) == code }?.let {
            return ScannedLabel(it.text, it.quantityText, it.imageUrl, "stock")
        }
        return null
    }

    /** [known] first; otherwise asks Open Food Facts, which needs internet on the phone but not the server. */
    suspend fun resolve(gtin: String, stock: List<StockItem> = emptyList()): ScannedLabel? =
        known(gtin, stock) ?: openFoodFacts(gtin)?.also { offline?.save("phone-barcode-$gtin", it) }

    private fun normalise(gtin: String) = gtin.filter { it.isDigit() }.padStart(14, '0')

    private suspend fun openFoodFacts(gtin: String): ScannedLabel? = try {
        val response = http.get("https://world.openfoodfacts.org/api/v2/product/${normalise(gtin).trimStart('0')}.json") {
            parameter("fields", "product_name,product_name_nl,product_name_en,product_name_es,brands,quantity,image_front_small_url,image_small_url")
            // Open Food Facts asks every client to identify itself, and blocks anonymous ones.
            header("User-Agent", "baskt/0.1 (Android; https://github.com/pegoku/baskt)")
        }
        if (response.status.value !in 200..299) null else parse(response.bodyAsText())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null // No internet either, or the service is down: the scan simply stays a bare number.
    }

    internal fun parse(body: String): ScannedLabel? {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val product = root["product"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        fun field(name: String) = product[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
        val name = field("product_name") ?: field("product_name_nl") ?: field("product_name_es") ?: field("product_name_en") ?: return null
        val brand = field("brands")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val title = if (brand != null && !name.contains(brand, ignoreCase = true)) "$brand $name" else name
        return ScannedLabel(title, field("quantity"), field("image_front_small_url") ?: field("image_small_url"), "openfoodfacts")
    }

    companion object {
        /** Short timeouts: this runs while the user waits in front of a shelf, and it is only a nicety. */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 4_000; socketTimeoutMillis = 8_000 }
            expectSuccess = false
        }
    }
}
