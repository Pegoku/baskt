package nl.baskt.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ApiException(val status: Int, message: String) : Exception(message)

/** Thin client for the baskt backend. Base URL and token come from [SettingsStore] and can change at runtime. */
class BasktApi(private val settingsProvider: () -> AppSettings) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@BasktApi.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private fun HttpRequestBuilder.auth() {
        val token = settingsProvider().token
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
    }

    private fun url(path: String) = settingsProvider().baseUrl.trimEnd('/') + "/api/v1" + path

    private suspend inline fun <reified T> HttpResponse.expect(): T {
        if (status.value in 200..299) return body()
        val text = bodyAsText()
        val message = runCatching { json.decodeFromString<ApiErrorBody>(text).error.message }.getOrNull() ?: text.ifBlank { "HTTP ${status.value}" }
        throw ApiException(status.value, message)
    }

    suspend fun health(): HealthResponse = client.get(url("/health")) { auth() }.expect()

    suspend fun stores(): List<StoreInfo> = client.get(url("/stores")) { auth() }.expect<StoresResponse>().stores

    suspend fun setEnabledStores(codes: List<String>): List<String> =
        client.patch(url("/settings")) {
            auth(); contentType(ContentType.Application.Json); setBody(SettingsResponse(enabledStores = codes))
        }.expect<SettingsResponse>().enabledStores ?: codes

    suspend fun serverSettings(): SettingsResponse = client.get(url("/settings")) { auth() }.expect()

    suspend fun setSkipInStock(enabled: Boolean): SettingsResponse =
        client.patch(url("/settings")) { auth(); contentType(ContentType.Application.Json); setBody(SettingsResponse(recipeSkipInStock = enabled)) }.expect()

    suspend fun stock(): List<StockItem> = client.get(url("/stock")) { auth() }.expect<StockResponse>().items

    suspend fun addStock(text: String): StockItem =
        client.post(url("/stock")) { auth(); contentType(ContentType.Application.Json); setBody(StockRequest(text)) }.expect()

    suspend fun removeStock(id: String) {
        client.delete(url("/stock/$id")) { auth() }.expect<Unit>()
    }

    suspend fun setRankBy(rankBy: String): SettingsResponse =
        client.patch(url("/settings")) { auth(); contentType(ContentType.Application.Json); setBody(SettingsResponse(rankBy = rankBy)) }.expect()

    suspend fun setDefaultServings(servings: Int?): SettingsResponse =
        client.patch(url("/settings")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("defaultServings" to (servings?.let { JsonPrimitive(it) } ?: JsonNull))))
        }.expect()

    suspend fun setGroupServings(groupId: String, servings: Int): GroupResponse =
        client.post(url("/basket/groups/$groupId/servings")) {
            auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("servings" to JsonPrimitive(servings))))
        }.expect()

    suspend fun addSkipped(groupId: String): List<BasketItem> =
        client.post(url("/basket/groups/$groupId/add-skipped")) { auth() }.expect<ItemsResponse>().items

    suspend fun setLanguage(language: String): String? =
        client.patch(url("/settings")) {
            auth(); contentType(ContentType.Application.Json); setBody(SettingsResponse(language = language))
        }.expect<SettingsResponse>().language

    suspend fun basket(basketId: String): BasketResponse = client.get(url("/basket")) { auth(); parameter("basketId", basketId) }.expect()

    suspend fun baskets(): List<Basket> = client.get(url("/baskets")) { auth() }.expect<BasketsResponse>().baskets

    suspend fun createBasket(name: String, emoji: String?): Basket =
        client.post(url("/baskets")) { auth(); contentType(ContentType.Application.Json); setBody(BasketRequest(name, emoji)) }.expect()

    suspend fun renameBasket(id: String, name: String, emoji: String?): Basket =
        client.patch(url("/baskets/$id")) { auth(); contentType(ContentType.Application.Json); setBody(BasketRequest(name, emoji)) }.expect()

    suspend fun deleteBasket(id: String) {
        client.delete(url("/baskets/$id")) { auth() }.expect<Unit>()
    }

    suspend fun transferItem(id: String, basketId: String, copy: Boolean): BasketItem =
        client.post(url("/basket/items/$id/transfer")) {
            auth(); contentType(ContentType.Application.Json); setBody(TransferRequest(basketId, copy))
        }.expect()

    suspend fun addItem(text: String, quantity: Int, basketId: String, parentId: String? = null): BasketItem =
        client.post(url("/basket/items")) {
            auth(); contentType(ContentType.Application.Json); setBody(AddItemRequest(text, quantity, parentId, basketId))
        }.expect()

    /** Creates a folder; with [items] the children are given, otherwise the server looks up a recipe. */
    suspend fun addGroup(text: String, items: List<String>?, basketId: String): GroupResponse =
        client.post(url("/basket/groups")) {
            auth(); contentType(ContentType.Application.Json); setBody(AddGroupRequest(text, items, basketId))
        }.expect()

    suspend fun addFromText(text: String, basketId: String): List<BasketItem> =
        client.post(url("/basket/from-text")) {
            auth(); contentType(ContentType.Application.Json); setBody(FromTextRequest(text, basketId))
        }.expect<ItemsResponse>().items

    suspend fun updateItem(id: String, text: String? = null, quantity: Int? = null, checked: Boolean? = null): BasketItem =
        client.patch(url("/basket/items/$id")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildMap<String, Any?> {
                if (text != null) put("text", text)
                if (quantity != null) put("quantity", quantity)
                if (checked != null) put("checked", checked)
            }.toJsonObject())
        }.expect()

    suspend fun deleteItem(id: String) {
        client.delete(url("/basket/items/$id")) { auth() }.expect<Unit>()
    }

    suspend fun clearChecked(basketId: String): Int =
        client.delete(url("/basket")) { auth(); parameter("checked", "true"); parameter("basketId", basketId) }.expect<DeletedResponse>().deleted

    suspend fun rematch(id: String): BasketItem = client.post(url("/basket/items/$id/rematch")) { auth() }.expect()

    suspend fun choose(id: String, store: String, productId: String?): BasketItem =
        client.post(url("/basket/items/$id/matches/$store/choose")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("productId" to (productId?.let { JsonPrimitive(it) } ?: JsonNull))))
        }.expect()

    suspend fun feedback(id: String, store: String, productId: String, up: Boolean): BasketItem =
        client.post(url("/basket/items/$id/matches/$store/feedback")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("productId" to JsonPrimitive(productId), "up" to JsonPrimitive(up))))
        }.expect()

    suspend fun reject(id: String, store: String): BasketItem =
        client.post(url("/basket/items/$id/matches/$store/reject")) { auth() }.expect()

    suspend fun searchMore(id: String, store: String, query: String): BasketItem =
        client.post(url("/basket/items/$id/matches/$store/search")) {
            auth(); contentType(ContentType.Application.Json); setBody(QueryRequest(query))
        }.expect()

    suspend fun compare(basketId: String): Comparison = client.get(url("/basket/compare")) { auth(); parameter("basketId", basketId) }.expect()

    suspend fun suggest(text: String): SuggestResponse =
        client.get(url("/basket/suggest")) { auth(); parameter("q", text) }.expect()

    suspend fun refreshPrices(): JsonObject = client.post(url("/admin/refresh")) { auth() }.expect()

    suspend fun searchRecipes(query: String): RecipeSearchResponse = client.get(url("/recipes/search")) { auth(); parameter("q", query) }.expect()

    suspend fun fetchRecipe(recipeUrl: String): RecipeDetail = client.get(url("/recipes/fetch")) { auth(); parameter("url", recipeUrl) }.expect()

    suspend fun groupRecipe(groupId: String): RecipeDetail = client.get(url("/basket/groups/$groupId/recipe")) { auth() }.expect()

    suspend fun recipeFavourites(): List<RecipeFavourite> = client.get(url("/recipes/favourites")) { auth() }.expect<RecipeFavouritesResponse>().favourites

    suspend fun addRecipeFavourite(recipe: RecipeSummary): RecipeFavourite =
        client.post(url("/recipes/favourites")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("title" to JsonPrimitive(recipe.title), "url" to JsonPrimitive(recipe.url), "imageUrl" to (recipe.imageUrl?.let { JsonPrimitive(it) } ?: JsonNull))))
        }.expect()

    suspend fun removeRecipeFavourite(id: String) {
        client.delete(url("/recipes/favourites/$id")) { auth() }.expect<Unit>()
    }

    suspend fun addGroupFromUrl(recipeUrl: String, basketId: String): GroupResponse =
        client.post(url("/basket/groups")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("url" to JsonPrimitive(recipeUrl), "basketId" to JsonPrimitive(basketId))))
        }.expect()

    suspend fun priceHistory(productId: String, days: Int = 90): List<PricePoint> =
        client.get(url("/products/$productId/price-history")) { auth(); parameter("days", days) }.expect<PriceHistoryResponse>().points

    suspend fun priceChanges(days: Int = 7): PriceChangesResponse = client.get(url("/prices/changes")) { auth(); parameter("days", days) }.expect()

    suspend fun interpret(text: String): List<VoiceItem> =
        client.post(url("/basket/interpret")) { auth(); contentType(ContentType.Application.Json); setBody(FromTextRequest(text)) }.expect<InterpretResponse>().items

    suspend fun confirm(items: List<VoiceItem>, basketId: String): List<BasketItem> =
        client.post(url("/basket/confirm")) {
            auth(); contentType(ContentType.Application.Json); setBody(ConfirmRequest(items, basketId))
        }.expect<ItemsResponse>().items

    suspend fun scanReceipt(images: List<Pair<String, ByteArray>>, store: String?): ReceiptScan =
        client.post(url("/purchases/scan")) {
            auth()
            setBody(
                io.ktor.client.request.forms.MultiPartFormDataContent(
                    io.ktor.client.request.forms.formData {
                        if (store != null) append("store", store)
                        for ((name, bytes) in images) {
                            append("files", bytes, io.ktor.http.Headers.build {
                                append(io.ktor.http.HttpHeaders.ContentType, "image/jpeg")
                                append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"$name\"")
                            })
                        }
                    },
                ),
            )
        }.expect()

    suspend fun savePurchase(store: String, purchasedAt: String?, totalCents: Int?, lines: List<ReceiptLine>): PurchaseDetail =
        client.post(url("/purchases")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(SavePurchaseRequest(store, purchasedAt, totalCents, lines.map { it.copy(product = null) }))
        }.expect()

    suspend fun purchases(): List<Purchase> = client.get(url("/purchases")) { auth() }.expect<PurchasesResponse>().purchases

    suspend fun purchase(id: String): PurchaseDetail = client.get(url("/purchases/$id")) { auth() }.expect()

    suspend fun deletePurchase(id: String) {
        client.delete(url("/purchases/$id")) { auth() }.expect<Unit>()
    }

    suspend fun spendSummary(): SpendSummary = client.get(url("/purchases/summary")) { auth() }.expect()

    suspend fun deals(basketId: String, live: Boolean): DealsResponse =
        client.get(url("/basket/deals")) { auth(); parameter("basketId", basketId); if (live) parameter("live", "true") }.expect()

    suspend fun searchProducts(query: String, store: String? = null, force: Boolean = false): ProductSearchResponse =
        client.get(url("/products/search")) {
            auth(); parameter("q", query); if (store != null) parameter("store", store); if (force) parameter("force", "true")
        }.expect()

    suspend fun barcode(gtin: String): BarcodeResponse = client.get(url("/products/barcode/$gtin")) { auth() }.expect()

    suspend fun addFromProduct(productId: String, basketId: String, parentId: String? = null, quantity: Int = 1): BasketItem =
        client.post(url("/basket/items/from-product")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(buildMap {
                put("productId", JsonPrimitive(productId)); put("basketId", JsonPrimitive(basketId)); put("quantity", JsonPrimitive(quantity))
                if (parentId != null) put("parentId", JsonPrimitive(parentId))
            }))
        }.expect()

    private fun Map<String, Any?>.toJsonObject() = JsonObject(mapValues { (_, value) ->
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    })
}

@Serializable private data class AddItemRequest(val text: String, val quantity: Int, val parentId: String? = null, val basketId: String = "default")
@Serializable private data class AddGroupRequest(val text: String, val items: List<String>? = null, val basketId: String = "default")
@Serializable private data class FromTextRequest(val text: String, val basketId: String = "default")
@Serializable private data class BasketRequest(val name: String, val emoji: String? = null)
@Serializable private data class StockRequest(val text: String)
@Serializable private data class ConfirmRequest(val items: List<VoiceItem>, val basketId: String)
@Serializable private data class SavePurchaseRequest(val store: String, val purchasedAt: String?, val totalCents: Int?, val lines: List<ReceiptLine>)
@Serializable private data class TransferRequest(val basketId: String, val copy: Boolean)
@Serializable private data class QueryRequest(val query: String)
@Serializable private data class ItemsResponse(val items: List<BasketItem>)
@Serializable private data class DeletedResponse(val deleted: Int)
