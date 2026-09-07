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
)

@Serializable
data class StockItem(val id: String, val text: String, val canonical: String = "", val quantityText: String? = null, val addedAt: Long = 0, val updatedAt: Long = 0)

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
data class BasketItem(
    val id: String,
    val basketId: String = "default",
    val kind: String = "item", // item | group
    val parentId: String? = null,
    val recipe: RecipeInfo? = null,
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
    val isGroup: Boolean get() = kind == "group"
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
    val products: Map<String, Product> = emptyMap(),
)

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
