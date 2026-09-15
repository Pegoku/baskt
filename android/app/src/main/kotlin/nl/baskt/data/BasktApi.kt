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
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class ApiException(val status: Int, message: String) : Exception(message)

/** Thin client for the baskt backend. Base URL and token come from [SettingsStore] and can change at runtime. */
class BasktApi(private val settingsProvider: () -> AppSettings, private val operationId: String? = null, sharedClient: HttpClient? = null) {
    fun forOperation(id: String): BasktApi { val settings = settingsProvider(); return BasktApi({ settings }, id, client) }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val client = sharedClient ?: HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@BasktApi.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000 // OkHttp's default read timeout is 10 s, too short for AI-backed calls
        }
        expectSuccess = false
    }

    private fun HttpRequestBuilder.auth() {
        operationId?.let { header("Idempotency-Key", it) }
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

    suspend fun translate(texts: List<String>, language: String, descriptionIndices: List<Int> = emptyList()): TranslationResponse = client.post(url("/translate")) {
        auth(); contentType(ContentType.Application.Json); setBody(TranslationRequest(texts, language, descriptionIndices))
    }.expect()

    suspend fun productDetail(id: String): ProductDetail = client.get(url("/products/$id/details")) { auth() }.expect()

    suspend fun speechModels(): SpeechModels = client.get(url("/tts/voices")) { auth() }.expect()
    suspend fun speech(request: SpeechRequest): ByteArray = client.post(url("/tts")) {
        auth(); contentType(ContentType.Application.Json); setBody(request)
        timeout { requestTimeoutMillis = 150_000; socketTimeoutMillis = 150_000 }
    }.expect()

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

    suspend fun addStock(text: String, quantityText: String? = null, productId: String? = null, imageUrl: String? = null, barcode: String? = null): StockItem =
        client.post(url("/stock")) { auth(); contentType(ContentType.Application.Json); setBody(StockRequest(text, quantityText, productId, imageUrl, barcode)) }.expect()

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

    suspend fun setGroupServings(groupId: String, servings: Int, children: List<ScaledIngredient>? = null, recipe: RecipeInfo? = null): GroupResponse =
        client.post(url("/basket/groups/$groupId/servings")) {
            auth(); contentType(ContentType.Application.Json); setBody(GroupServingsRequest(servings, children, recipe))
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
        client.patch(url("/baskets/$id")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("name" to JsonPrimitive(name), "emoji" to (emoji?.let { JsonPrimitive(it) } ?: JsonNull))))
        }.expect()

    suspend fun deleteBasket(id: String) {
        client.delete(url("/baskets/$id")) { auth() }.expect<Unit>()
    }

    suspend fun shareLink(basketId: String): ShareLink =
        client.post(url("/baskets/$basketId/share")) {
            auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("baseUrl" to JsonPrimitive(settingsProvider().baseUrl.trimEnd('/')))))
        }.expect()

    suspend fun groupFromItems(text: String, itemIds: List<String>): GroupResponse =
        client.post(url("/basket/groups/from-items")) {
            auth(); contentType(ContentType.Application.Json); setBody(GroupFromItemsRequest(text, itemIds))
        }.expect()

    suspend fun deleteItems(itemIds: List<String>): Int =
        client.post(url("/basket/items/delete")) {
            auth(); contentType(ContentType.Application.Json); setBody(ItemIdsRequest(itemIds))
        }.expect<DeletedResponse>().deleted

    suspend fun transferItem(id: String, basketId: String, copy: Boolean): TransferResult =
        client.post(url("/basket/items/$id/transfer")) {
            auth(); contentType(ContentType.Application.Json); setBody(TransferRequest(basketId, copy))
        }.expect()

    suspend fun addItem(text: String, quantity: Int, basketId: String, parentId: String? = null): BasketItem =
        client.post(url("/basket/items")) {
            auth(); contentType(ContentType.Application.Json); setBody(AddItemRequest(text, quantity, parentId, basketId))
        }.expect()

    /** Creates a folder; with [items] the children are given, otherwise the server looks up a recipe. */
    suspend fun addGroup(text: String, items: List<String>?, basketId: String, recipe: RecipeInfo? = null): GroupResponse =
        client.post(url("/basket/groups")) {
            auth(); contentType(ContentType.Application.Json); setBody(AddGroupRequest(text, items, basketId, recipe))
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

    suspend fun unskip(id: String, store: String): BasketItem = client.post(url("/basket/items/$id/matches/$store/unskip")) { auth() }.expect()

    suspend fun reject(id: String, store: String): BasketItem =
        client.post(url("/basket/items/$id/matches/$store/reject")) { auth() }.expect()

    suspend fun searchMore(id: String, store: String, query: String): BasketItem =
        client.post(url("/basket/items/$id/matches/$store/search")) {
            auth(); contentType(ContentType.Application.Json); setBody(QueryRequest(query))
        }.expect()

    suspend fun assign(basketId: String, mode: String): List<BasketItem> =
        client.post(url("/basket/assign")) {
            auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("basketId" to JsonPrimitive(basketId), "mode" to JsonPrimitive(mode))))
        }.expect<ItemsResponse>().items

    suspend fun assignItem(id: String, store: String?): BasketItem =
        client.patch(url("/basket/items/$id")) {
            auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("assignedStore" to (store?.let { JsonPrimitive(it) } ?: JsonNull))))
        }.expect()

    suspend fun compare(basketId: String): Comparison = client.get(url("/basket/compare")) { auth(); parameter("basketId", basketId) }.expect()

    suspend fun suggest(text: String): SuggestResponse =
        client.get(url("/basket/suggest")) { auth(); parameter("q", text) }.expect()

    suspend fun refreshPrices(): JsonObject = client.post(url("/admin/refresh")) { auth() }.expect()

    suspend fun searchRecipes(query: String): RecipeSearchResponse = client.get(url("/recipes/search")) { auth(); parameter("q", query) }.expect()

    suspend fun fetchRecipe(recipeUrl: String): RecipeDetail = client.get(url("/recipes/fetch")) { auth(); parameter("url", recipeUrl) }.expect()

    suspend fun groupRecipe(groupId: String): RecipeDetail = client.get(url("/basket/groups/$groupId/recipe")) { auth() }.expect()

    suspend fun dishesFromStock(): List<StockDish> = client.get(url("/recipes/from-stock")) { auth() }.expect<StockDishesResponse>().dishes

    suspend fun myRecipes(): List<UserRecipe> = client.get(url("/recipes/mine")) { auth() }.expect<UserRecipesResponse>().recipes

    suspend fun saveMyRecipe(draft: RecipeDraft, origin: String, fromUrl: String? = null): UserRecipe =
        client.post(url("/recipes/mine")) {
            auth(); contentType(ContentType.Application.Json); setBody(SaveRecipeRequest(draft.title, draft.description, draft.servings, draft.ingredientLines, draft.steps.map { it.text }, origin, fromUrl))
        }.expect()

    suspend fun updateMyRecipe(id: String, draft: RecipeDraft): UserRecipe =
        client.patch(url("/recipes/mine/$id")) {
            auth(); contentType(ContentType.Application.Json)
            val request = SaveRecipeRequest(draft.title, draft.description, draft.servings, draft.ingredientLines, draft.steps.map { it.text }, null, null)
            setBody(JsonObject(json.encodeToJsonElement(request).jsonObject + mapOf(
                "description" to JsonPrimitive(draft.description ?: ""), "servings" to (draft.servings?.let { JsonPrimitive(it) } ?: JsonNull),
            )))
        }.expect()

    suspend fun deleteMyRecipe(id: String) {
        client.delete(url("/recipes/mine/$id")) { auth() }.expect<Unit>()
    }

    suspend fun generateRecipe(description: String, draft: Boolean): GenerateResponse =
        client.post(url("/recipes/generate")) {
            auth(); contentType(ContentType.Application.Json); setBody(GenerateRequest(description, draft))
        }.expect()

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

    /** Uploads a recording for server-side transcription; 503 means the server has no speech-to-text. */
    suspend fun dictate(audio: ByteArray, language: String): DictateResponse = uploadDictation(audio, language, "/basket/dictate")

    suspend fun transcribe(audio: ByteArray, language: String): String = uploadDictation(audio, language, "/transcribe").transcript

    private suspend fun uploadDictation(audio: ByteArray, language: String, path: String): DictateResponse =
        client.post(url(path)) {
            auth()
            setBody(
                io.ktor.client.request.forms.MultiPartFormDataContent(
                    io.ktor.client.request.forms.formData {
                        append("language", language)
                        append("audio", audio, io.ktor.http.Headers.build {
                            append(io.ktor.http.HttpHeaders.ContentType, "audio/m4a")
                            append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"dictation.m4a\"")
                        })
                    },
                ),
            )
            timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
        }.expect()

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

    suspend fun whatsappStatus(): WhatsAppStatus = client.get(url("/whatsapp/status")) { auth() }.expect()
    suspend fun whatsappQr(): WhatsAppQr = client.get(url("/whatsapp/qr")) { auth() }.expect()
    suspend fun whatsappChats(): List<WhatsAppChat> = client.get(url("/whatsapp/chats")) { auth() }.expect<WhatsAppChats>().chats
    suspend fun whatsappSetChat(chat: WhatsAppChat) {
        client.patch(url("/whatsapp/settings")) { auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("chatId" to JsonPrimitive(chat.id), "chatName" to JsonPrimitive(chat.name ?: chat.id)))) }.expect<JsonObject>()
    }
    suspend fun whatsappSend(basketId: String, store: String?): Int =
        client.post(url("/whatsapp/send")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("basketId" to JsonPrimitive(basketId), "store" to (store?.let { JsonPrimitive(it) } ?: JsonNull))))
        }.expect<JsonObject>()["sent"]?.toString()?.toIntOrNull() ?: 0

    suspend fun chatHistory(basketId: String): List<ChatMessage> = client.get(url("/chat")) { auth(); parameter("basketId", basketId) }.expect<ChatHistory>().messages

    suspend fun chatSend(basketId: String, text: String): List<ChatMessage> =
        client.post(url("/chat")) {
            auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("basketId" to JsonPrimitive(basketId), "text" to JsonPrimitive(text))))
            timeout { requestTimeoutMillis = 300_000; socketTimeoutMillis = 300_000 }
        }.expect<ChatReply>().messages

    suspend fun chatProgress(basketId: String): ChatProgress = client.get(url("/chat/progress")) { auth(); parameter("basketId", basketId) }.expect()

    suspend fun chatClear(basketId: String) {
        client.delete(url("/chat")) { auth(); parameter("basketId", basketId) }.expect<Unit>()
    }

    suspend fun applyProposal(messageId: String, indices: List<Int>): ApplyResponse =
        client.post(url("/chat/proposals/$messageId/apply")) {
            auth(); contentType(ContentType.Application.Json); setBody(JsonObject(mapOf("indices" to kotlinx.serialization.json.JsonArray(indices.map { JsonPrimitive(it) }))))
        }.expect()

    suspend fun memory(): List<Choice> = client.get(url("/memory")) { auth() }.expect<ChoicesResponse>().choices

    suspend fun deleteMemory(id: String) {
        client.delete(url("/memory/$id")) { auth() }.expect<Unit>()
    }

    suspend fun allDeals(query: String, store: String?): AllDealsResponse =
        client.get(url("/basket/deals/all")) { auth(); parameter("q", query); if (store != null) parameter("store", store) }.expect()

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
@Serializable private data class AddGroupRequest(val text: String, val items: List<String>? = null, val basketId: String = "default", val recipe: RecipeInfo? = null)
@Serializable private data class FromTextRequest(val text: String, val basketId: String = "default")
@Serializable private data class BasketRequest(val name: String, val emoji: String? = null)
@Serializable private data class StockRequest(val text: String, val quantityText: String? = null, val productId: String? = null, val imageUrl: String? = null, val barcode: String? = null)
@Serializable private data class ConfirmRequest(val items: List<VoiceItem>, val basketId: String)
@Serializable private data class SaveRecipeRequest(val title: String, val description: String?, val servings: String?, val ingredientLines: List<String>, val steps: List<String>, val origin: String?, val fromUrl: String?)
@Serializable private data class GenerateRequest(val description: String, val draft: Boolean)
@Serializable private data class SavePurchaseRequest(val store: String, val purchasedAt: String?, val totalCents: Int?, val lines: List<ReceiptLine>)
@Serializable private data class TransferRequest(val basketId: String, val copy: Boolean)
@Serializable private data class GroupFromItemsRequest(val text: String, val itemIds: List<String>)
@Serializable private data class ItemIdsRequest(val itemIds: List<String>)
@Serializable private data class QueryRequest(val query: String)
@Serializable private data class ItemsResponse(val items: List<BasketItem>)
@Serializable private data class DeletedResponse(val deleted: Int)

@Serializable private data class GroupServingsRequest(val servings: Int, val children: List<ScaledIngredient>? = null, val recipe: RecipeInfo? = null)
