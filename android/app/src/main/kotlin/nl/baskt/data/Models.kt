package nl.baskt.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Product(
    val id: String,
    val store: String,
    val sourceId: String,
    val title: String,
    val brand: String? = null,
    val quantityText: String,
    val unitAmount: Double? = null,
    val unit: String? = null,
    val priceCents: Int,
    val regularPriceCents: Int? = null,
    val unitPriceCents: Int? = null,
    val unitPriceUnit: String? = null,
    val dealText: String? = null,
    val isDeal: Boolean = false,
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val category: String? = null,
    val available: Boolean = true,
    val fetchedAt: Long = 0,
)

@Serializable
data class SizeHint(val amount: Double, val unit: String)

@Serializable
data class ParsedIdea(
    val canonicalName: String,
    val attributes: List<String> = emptyList(),
    val sizeHint: SizeHint? = null,
    val queries: Map<String, String> = emptyMap(),
    val ambiguous: Boolean = false,
)

@Serializable
data class StoreMatch(
    val store: String,
    val status: String, // PENDING | CHOSEN | NONE | EXHAUSTED
    val chosenBy: String? = null,
    val chosen: Product? = null,
    val provisional: Product? = null,
    val options: List<Product> = emptyList(),
    val equivalences: Map<String, String> = emptyMap(),
    val hasMore: Boolean = true,
    val totalCandidates: Int = 0,
    val page: Int = 1,
    val confidence: Double? = null,
    val reason: String? = null,
    val updatedAt: Long = 0,
) {
    val effective: Product? get() = chosen ?: provisional
}

@Serializable
data class SkippedIngredient(val text: String, val quantity: Int = 1, val reason: String = "")

@Serializable
data class RecipeInfo(
    val title: String,
    val sourceUrl: String? = null,
    val servings: String? = null,
    val ingredientLines: List<String> = emptyList(),
    val skipped: List<SkippedIngredient> = emptyList(),
    val baseServings: Double? = null,
    val currentServings: Double? = null,
    val imageUrl: String? = null,
    val steps: List<RecipeStep> = emptyList(),
)

@Serializable
data class StockItem(val id: String, val text: String, val canonical: String = "", val quantityText: String? = null, val productId: String? = null, val imageUrl: String? = null, val barcode: String? = null, val addedAt: Long = 0, val updatedAt: Long = 0)

@Serializable
data class StockResponse(val items: List<StockItem>)

@Serializable
data class Basket(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val sortOrder: Int = 0,
    val itemCount: Int = 0,
    val openCount: Int = 0,
) {
    val label: String get() = listOfNotNull(emoji, name).joinToString(" ")
}

@Serializable
data class BasketsResponse(val baskets: List<Basket>)

@Serializable
data class ShareLink(val token: String, val url: String)

@Serializable
data class BasketItem(
    val id: String,
    val basketId: String = "default",
    val kind: String = "item", // item | group
    val parentId: String? = null,
    val recipe: RecipeInfo? = null,
    val assignedStore: String? = null,
    val skippedReason: String? = null,
    val text: String,
    val quantity: Int = 1,
    val checked: Boolean = false,
    val sortOrder: Int = 0,
    val status: String, // NEW | PARSING | MATCHING | MATCHED | ERROR
    val error: String? = null,
    val parsed: ParsedIdea? = null,
    val needsChoice: Boolean = false,
    val matches: List<StoreMatch> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isProcessing: Boolean get() = status == "NEW" || status == "PARSING" || status == "MATCHING"
    /** Created while offline; waits for the connection to be sent. */
    val isQueued: Boolean get() = status == "QUEUED" || id.startsWith("local-")
    val isGroup: Boolean get() = kind == "group"
    /** Checked automatically because the idea was already in stock. */
    val inStock: Boolean get() = checked && skippedReason != null
    /** The product picked (or suggested) at any store, preferring confirmed picks. */
    val anyProduct: Product? get() = matches.firstOrNull { it.status == "CHOSEN" }?.chosen ?: matches.firstOrNull { it.effective != null }?.effective
    fun match(store: String) = matches.firstOrNull { it.store == store }
}

@Serializable
data class GroupResponse(val group: BasketItem, val items: List<BasketItem> = emptyList())

@Serializable
data class RecipeSuggestion(val title: String, val ingredients: List<String> = emptyList())

@Serializable
data class BasketResponse(
    val serverTime: Long,
    val basketId: String = "default",
    val items: List<BasketItem>,
    val deletedIds: List<String> = emptyList(),
    val processing: Boolean = false,
    val stores: List<String> = emptyList(),
)

@Serializable
data class StoreInfo(
    val code: String,
    val name: String,
    val color: String,
    val coverage: String = "full",
    val enabled: Boolean = true,
)

@Serializable
data class StoresResponse(val stores: List<StoreInfo>)

@Serializable
data class SettingsResponse(
    val enabledStores: List<String>? = null,
    val language: String? = null,
    val recipeSkipInStock: Boolean? = null,
    val defaultServings: Int? = null,
    val rankBy: String? = null,
)

@Serializable
data class StoreLine(
    val productId: String,
    val unitsToBuy: Int,
    val lineCents: Int,
    val unitPriceCents: Int? = null,
    val unitPriceUnit: String? = null,
    val confirmed: Boolean,
)

@Serializable
data class StoreSummary(
    val store: String,
    val rank: Int,
    val fullTotalCents: Int,
    val comparableTotalCents: Int,
    val matchedCount: Int,
    val unconfirmedCount: Int,
    val missingItemIds: List<String> = emptyList(),
)

@Serializable
data class ComparisonItem(
    val itemId: String,
    val text: String,
    val cheapestStore: String? = null,
    val cheapestByUnitPriceStore: String? = null,
    val packSizeDiffers: Boolean = false,
    val perStore: Map<String, StoreLine?> = emptyMap(),
)

@Serializable
data class Comparison(
    val stores: List<StoreSummary>,
    val items: List<ComparisonItem>,
    val mixAndMatchTotalCents: Int,
    val missingEverywhere: List<String> = emptyList(),
    val computedAt: Long = 0,
    val rankBy: String = "price",
    val order: OrderSummary = OrderSummary(),
    val products: Map<String, Product> = emptyMap(),
)

@Serializable
data class OrderStoreTotal(val totalCents: Int = 0, val count: Int = 0)

@Serializable
data class OrderSummary(val perStore: Map<String, OrderStoreTotal> = emptyMap(), val unassigned: List<String> = emptyList())

@Serializable
data class SuggestResponse(val suggestions: List<String> = emptyList(), val source: String = "none", val recipe: RecipeSuggestion? = null)

@Serializable
data class StoreSearchResult(val store: String, val source: String = "cache", val products: List<Product> = emptyList(), val error: String? = null)

@Serializable
data class ProductSearchResponse(val query: String, val results: List<StoreSearchResult> = emptyList())

@Serializable
data class BarcodeResult(val store: String, val product: Product? = null, val error: String? = null)

@Serializable
data class BarcodeResponse(val gtin: String, val results: List<BarcodeResult> = emptyList())

@Serializable
data class Deal(
    val itemId: String,
    val itemText: String,
    val store: String,
    val product: Product,
    val currentProductId: String? = null,
    val currentPriceCents: Int? = null,
    val savingCents: Int? = null,
    val equivalence: String = "EQUIVALENT",
)

@Serializable
data class DealsResponse(val deals: List<Deal> = emptyList(), val computedAt: Long = 0)

@Serializable
data class DealCard(
    val store: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val dealText: String? = null,
    val imageUrl: String? = null,
    val url: String? = null,
    val priceCents: Int? = null,
    val regularPriceCents: Int? = null,
    val productId: String? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
)

@Serializable
data class StoreDeals(val store: String, val deals: List<DealCard> = emptyList(), val error: String? = null)

@Serializable
data class AllDealsResponse(val query: String = "", val terms: List<String> = emptyList(), val results: List<StoreDeals> = emptyList(), val computedAt: Long = 0)

@Serializable
data class Choice(val id: String, val itemText: String, val canonical: String = "", val store: String, val chosenProductId: String? = null, val chosenTitle: String? = null, val rejectedTitles: List<String> = emptyList(), val createdAt: Long = 0)

@Serializable
data class ChoicesResponse(val choices: List<Choice> = emptyList())

@Serializable
data class RecipeSummary(val title: String, val url: String, val imageUrl: String? = null, val slug: String = "", val titleLocalized: String? = null, val source: String? = null, val language: String? = null) {
    val displayTitle: String get() = titleLocalized?.takeIf { it.isNotBlank() } ?: title
}

@Serializable
data class StockDish(val title: String, val searchQuery: String, val uses: List<String> = emptyList(), val missing: List<String> = emptyList(), val minutes: Int? = null, val imageUrl: String? = null, val recipeUrl: String? = null, val source: String? = null)

@Serializable
data class StockDishesResponse(val dishes: List<StockDish> = emptyList())

@Serializable
data class UserRecipe(
    val id: String,
    val title: String,
    val description: String? = null,
    val servings: String? = null,
    val ingredientLines: List<String> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val origin: String = "manual",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val url: String get() = "baskt://recipe/$id"
}

@Serializable
data class UserRecipesResponse(val recipes: List<UserRecipe> = emptyList())

@Serializable
data class RecipeDraft(val title: String, val description: String? = null, val servings: String? = null, val ingredientLines: List<String> = emptyList(), val steps: List<RecipeStep> = emptyList())

@Serializable
data class GenerateResponse(val dish: String? = null, val matches: List<RecipeSummary> = emptyList(), val draft: RecipeDraft? = null)

@Serializable
data class RecipeStep(val text: String, val imageUrl: String? = null)

@Serializable
data class RecipeSearchResponse(val query: String = "", val dish: String? = null, val results: List<RecipeSummary> = emptyList(), val errors: List<SourceError> = emptyList(), val message: String? = null)

@Serializable
data class SourceError(val source: String, val message: String)

@Serializable
data class RecipeFavourite(val id: String, val title: String, val url: String, val imageUrl: String? = null, val createdAt: Long = 0)

@Serializable
data class RecipeFavouritesResponse(val favourites: List<RecipeFavourite> = emptyList())

@Serializable
data class RecipeDetail(
    val title: String,
    val sourceUrl: String? = null,
    val servings: String? = null,
    val ingredientLines: List<String> = emptyList(),
    val imageUrl: String? = null,
    val steps: List<RecipeStep> = emptyList(),
    val totalTime: String? = null,
    val description: String? = null,
    val original: RecipeDetail? = null,
    val translationAvailable: Boolean = false,
    val originalTitle: String? = null,
    val language: String? = null,
)

@Serializable
data class PricePoint(val capturedAt: Long, val priceCents: Int, val isDeal: Boolean = false)

@Serializable
data class PriceHistoryResponse(val points: List<PricePoint> = emptyList())

@Serializable
data class PriceChange(val product: Product, val previousCents: Int, val currentCents: Int, val changedAt: Long, val diffCents: Int)

@Serializable
data class ScanStatus(val lastRunAt: Long? = null, val nextRunAt: Long? = null, val running: Boolean = false)

@Serializable
data class PriceChangesResponse(val changes: List<PriceChange> = emptyList(), val scan: ScanStatus = ScanStatus())

@Serializable
data class VoiceItem(val text: String, val quantity: Int = 1, val wanted: Boolean = true, val kind: String = "item", val note: String? = null)

@Serializable
data class InterpretResponse(val items: List<VoiceItem> = emptyList())

@Serializable
data class ReceiptLine(
    val name: String,
    val quantity: Double = 1.0,
    val unitPriceCents: Int? = null,
    val totalPriceCents: Int,
    val dealText: String? = null,
    val productId: String? = null,
    val product: Product? = null,
)

@Serializable
data class ReceiptScan(val store: String? = null, val purchasedAt: String? = null, val totalCents: Int? = null, val lines: List<ReceiptLine> = emptyList(), val notes: String? = null)

@Serializable
data class Purchase(val id: String, val store: String, val purchasedAt: Long, val totalCents: Int, val source: String = "receipt", val createdAt: Long = 0, val lineCount: Int = 0)

@Serializable
data class PurchasesResponse(val purchases: List<Purchase> = emptyList())

@Serializable
data class PurchaseLine(val id: String, val purchaseId: String, val name: String, val productId: String? = null, val quantity: Double = 1.0, val unitPriceCents: Int? = null, val totalPriceCents: Int, val dealText: String? = null, val sortOrder: Int = 0)

@Serializable
data class PurchaseDetail(val purchase: Purchase, val lines: List<PurchaseLine> = emptyList(), val products: Map<String, Product> = emptyMap())

@Serializable
data class SpendMonth(val month: String, val totalCents: Int, val perStore: Map<String, Int> = emptyMap(), val count: Int = 0)

@Serializable
data class TopProduct(val name: String, val times: Int, val totalCents: Int)

@Serializable
data class SpendSummary(val months: List<SpendMonth> = emptyList(), val totalCents: Int = 0, val purchases: Int = 0, val topProducts: List<TopProduct> = emptyList())

@Serializable
data class WhatsAppStatus(val enabled: Boolean = false, val status: String = "disabled", val me: String? = null, val hasQr: Boolean = false, val lastError: String? = null, val chatId: String? = null, val chatName: String? = null)

@Serializable
data class WhatsAppQr(val qr: String? = null, val status: String = "")

@Serializable
data class WhatsAppChat(val id: String, val name: String? = null, val isGroup: Boolean = false)

@Serializable
data class WhatsAppChats(val chats: List<WhatsAppChat> = emptyList())

@Serializable
data class ProposedChange(
    val type: String,
    val itemId: String? = null,
    val text: String? = null,
    val quantity: Int? = null,
    val from: String? = null,
    val to: String? = null,
    val store: String? = null,
    val productId: String? = null,
    val url: String? = null,
    val title: String? = null,
    val inStock: Boolean? = null,
    val stockName: String? = null,
    val inList: String? = null,
)

@Serializable
data class Proposal(val summary: String, val changes: List<ProposedChange> = emptyList(), val applied: List<Int>? = null)

@Serializable
data class RecipeCard(val title: String, val url: String, val source: String? = null, val imageUrl: String? = null)

@Serializable
data class ChatMessage(
    val id: String,
    val basketId: String = "default",
    val role: String,
    val content: String,
    val proposalJson: Proposal? = null,
    val recipesJson: List<RecipeCard>? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ChatHistory(val basketId: String = "default", val messages: List<ChatMessage> = emptyList())

@Serializable
data class ChatReply(val messages: List<ChatMessage> = emptyList())

@Serializable
data class ChatProgress(val busy: Boolean = false, val steps: List<String> = emptyList())

@Serializable
data class ApplyResult(val index: Int, val ok: Boolean, val error: String? = null)

@Serializable
data class ApplyResponse(val message: ChatMessage, val results: List<ApplyResult> = emptyList())

/** What the server heard (whisper) plus the proposal it read out of it. */
@Serializable
data class DictateResponse(val transcript: String = "", val items: List<VoiceItem> = emptyList())

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String = "",
    val serverTime: Long = 0,
    val ai: JsonObject? = null,
)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String)

fun Int.euros(): String {
    val negative = this < 0
    val abs = kotlin.math.abs(this)
    return (if (negative) "-" else "") + "€" + (abs / 100) + "," + (abs % 100).toString().padStart(2, '0')
}

fun Product.unitPriceLabel(): String? =
    if (unitPriceCents != null && unitPriceUnit != null) "${unitPriceCents.euros()}/${if (unitPriceUnit == "piece") "st" else unitPriceUnit}" else null

@Serializable
data class TransferResult(val id: String, val children: List<BasketItem> = emptyList())

@Serializable
data class ScaledIngredient(val id: String, val text: String, val quantity: Int)

@Serializable
data class TranslationRequest(val texts: List<String>, val language: String, val descriptionIndices: List<Int> = emptyList())
@Serializable
data class TranslationResponse(val texts: List<String>, val language: String, val translated: Boolean = false)
@Serializable
data class ProductDetail(val product: Product, val description: String? = null, val imageUrls: List<String> = emptyList())

@Serializable
data class SpeechModel(val id: String, val name: String, val description: String, val voices: List<String>, val languages: List<String>)
@Serializable
data class SpeechModels(val configured: Boolean = false, val models: List<SpeechModel> = emptyList())
@Serializable
data class SpeechRequest(val text: String, val model: String, val voice: String, val language: String, val preview: Boolean = false)
