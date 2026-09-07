package nl.baskt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.baskt.AppContainer
import nl.baskt.data.AppSettings
import nl.baskt.data.BasketItem
import nl.baskt.data.Comparison
import nl.baskt.data.BarcodeResponse
import nl.baskt.data.Deal
import nl.baskt.data.DealsResponse
import nl.baskt.data.Product
import nl.baskt.data.ProductSearchResponse
import nl.baskt.data.PriceChangesResponse
import nl.baskt.data.PricePoint
import nl.baskt.data.RecipeDetail
import nl.baskt.data.RecipeFavourite
import nl.baskt.data.RecipeSummary
import nl.baskt.data.RecipeSuggestion
import nl.baskt.data.StockItem

class AppViewModel(val container: AppContainer) : ViewModel() {
    val basket = container.basket

    private val _comparison = MutableStateFlow<Comparison?>(null)
    val comparison: StateFlow<Comparison?> = _comparison

    private val _comparing = MutableStateFlow(false)
    val comparing: StateFlow<Boolean> = _comparing

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    /** When the typed text asks for a dish, the full ingredient list the AI proposes. */
    private val _recipeSuggestion = MutableStateFlow<RecipeSuggestion?>(null)
    val recipeSuggestion: StateFlow<RecipeSuggestion?> = _recipeSuggestion
    private var suggestJob: Job? = null

    /** Debounced autocomplete for the idea input; history answers instantly, AI interpretations follow. */
    fun onIdeaTextChanged(text: String) {
        suggestJob?.cancel()
        val query = text.trim()
        if (query.length < 2) {
            _suggestions.value = emptyList()
            _recipeSuggestion.value = null
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            val result = runCatching { container.api.suggest(query) }.getOrNull()
            _suggestions.value = result?.suggestions?.filter { it.trim().lowercase() != query.lowercase() } ?: emptyList()
            _recipeSuggestion.value = result?.recipe
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        _recipeSuggestion.value = null
    }

    fun addToGroup(groupId: String, text: String, quantity: Int) = viewModelScope.launch { basket.add(text, quantity, groupId) }

    private val _searchResults = MutableStateFlow<ProductSearchResponse?>(null)
    val searchResults: StateFlow<ProductSearchResponse?> = _searchResults
    private val _scanResult = MutableStateFlow<BarcodeResponse?>(null)
    val scanResult: StateFlow<BarcodeResponse?> = _scanResult
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    fun searchProducts(query: String) = viewModelScope.launch {
        _searching.value = true
        _scanResult.value = null
        _searchResults.value = basket.searchProducts(query)
        _searching.value = false
    }

    fun lookupBarcode(gtin: String) = viewModelScope.launch {
        _searching.value = true
        _searchResults.value = null
        _scanResult.value = basket.barcode(gtin)
        _searching.value = false
    }

    fun clearSearch() {
        _searchResults.value = null
        _scanResult.value = null
    }

    fun addFromProduct(product: Product) = viewModelScope.launch { basket.addFromProduct(product) }

    private val _recipeResults = MutableStateFlow<List<RecipeSummary>>(emptyList())
    val recipeResults: StateFlow<List<RecipeSummary>> = _recipeResults
    private val _recipeFavourites = MutableStateFlow<List<RecipeFavourite>>(emptyList())
    val recipeFavourites: StateFlow<List<RecipeFavourite>> = _recipeFavourites
    private val _recipeDetail = MutableStateFlow<RecipeDetail?>(null)
    val recipeDetail: StateFlow<RecipeDetail?> = _recipeDetail
    private val _recipesBusy = MutableStateFlow(false)
    val recipesBusy: StateFlow<Boolean> = _recipesBusy

    fun searchRecipes(query: String) = viewModelScope.launch {
        _recipesBusy.value = true
        _recipeResults.value = runCatching { container.api.searchRecipes(query).results }.getOrElse { basket.run { }; emptyList() }
        _recipesBusy.value = false
    }

    fun loadRecipeFavourites() = viewModelScope.launch { _recipeFavourites.value = runCatching { container.api.recipeFavourites() }.getOrDefault(emptyList()) }

    fun toggleRecipeFavourite(recipe: RecipeSummary) = viewModelScope.launch {
        val existing = _recipeFavourites.value.firstOrNull { it.url == recipe.url }
        runCatching { if (existing != null) container.api.removeRecipeFavourite(existing.id) else container.api.addRecipeFavourite(recipe) }
        loadRecipeFavourites()
    }

    fun openRecipe(recipeUrl: String) = viewModelScope.launch {
        _recipeDetail.value = null
        _recipesBusy.value = true
        _recipeDetail.value = runCatching { container.api.fetchRecipe(recipeUrl) }.getOrNull()
        _recipesBusy.value = false
    }

    fun closeRecipe() { _recipeDetail.value = null }

    fun addRecipeFolder(recipeUrl: String) = viewModelScope.launch { basket.addGroupFromUrl(recipeUrl) }

    private val _priceChanges = MutableStateFlow<PriceChangesResponse?>(null)
    val priceChanges: StateFlow<PriceChangesResponse?> = _priceChanges
    fun loadPriceChanges() = viewModelScope.launch { _priceChanges.value = runCatching { container.api.priceChanges() }.getOrNull() }

    suspend fun priceHistory(productId: String): List<PricePoint> = runCatching { container.api.priceHistory(productId) }.getOrDefault(emptyList())

    private val _deals = MutableStateFlow<DealsResponse?>(null)
    val deals: StateFlow<DealsResponse?> = _deals
    private val _loadingDeals = MutableStateFlow(false)
    val loadingDeals: StateFlow<Boolean> = _loadingDeals

    fun findDeals(live: Boolean) = viewModelScope.launch {
        _loadingDeals.value = true
        _deals.value = basket.deals(live)
        _loadingDeals.value = false
    }

    /** Switches the item at that store to the promoted product. */
    fun takeDeal(deal: Deal) = viewModelScope.launch {
        val item = basket.items.value.firstOrNull { it.id == deal.itemId } ?: return@launch
        basket.choose(item, deal.store, deal.product.id)
        _deals.value = _deals.value?.let { current -> current.copy(deals = current.deals.filterNot { it.itemId == deal.itemId && it.store == deal.store }) }
    }

    /** Adds a recipe folder; with [items] the selected ingredients, otherwise the server looks the recipe up. */
    fun addGroup(title: String, items: List<String>? = null) = viewModelScope.launch { clearSuggestions(); basket.addGroup(title, items) }

    fun addMany(items: List<String>) = viewModelScope.launch {
        clearSuggestions()
        for (item in items) basket.add(item, 1)
    }

    init {
        viewModelScope.launch {
            _settings.value = container.awaitSettings()
            container.settingsStore.settings.collect { _settings.value = it }
        }
        reload()
    }

    fun reload() = viewModelScope.launch {
        val current = container.awaitSettings()
        // Tell the server which language to use for suggestions and interpretations.
        runCatching { container.api.setLanguage(current.resolvedLanguage) }
        basket.refreshStores()
        basket.refreshBaskets()
        basket.refresh()
    }

    fun switchBasket(id: String) = viewModelScope.launch { basket.switchBasket(id) }
    fun refreshStock() = viewModelScope.launch { basket.refreshStock(); basket.refreshServerSettings() }
    fun addStock(text: String) = viewModelScope.launch { basket.addStock(text) }
    fun addToStockFromItem(item: BasketItem) = viewModelScope.launch { basket.addStock(item.parsed?.canonicalName ?: item.text) }
    fun removeStock(item: StockItem) = viewModelScope.launch { basket.removeStock(item) }
    fun setSkipInStock(enabled: Boolean) = viewModelScope.launch { basket.setSkipInStock(enabled) }
    fun addSkipped(group: BasketItem) = viewModelScope.launch { basket.addSkipped(group) }
    fun setDefaultServings(servings: Int?) = viewModelScope.launch { basket.setDefaultServings(servings) }
    fun setRankBy(rankBy: String) = viewModelScope.launch { basket.setRankBy(rankBy); compare() }
    fun setGroupServings(group: BasketItem, servings: Int) = viewModelScope.launch { basket.setGroupServings(group, servings) }
    fun createBasket(name: String, emoji: String?, switchTo: Boolean = true) = viewModelScope.launch {
        val created = basket.createBasket(name, emoji)
        if (switchTo && created != null) basket.switchBasket(created.id)
    }
    fun renameBasket(id: String, name: String, emoji: String?) = viewModelScope.launch { basket.renameBasket(id, name, emoji) }
    fun deleteBasket(id: String) = viewModelScope.launch { basket.deleteBasket(id) }
    fun transfer(item: BasketItem, basketId: String, copy: Boolean) = viewModelScope.launch { basket.transfer(item, basketId, copy) }

    fun setLanguage(language: String) = viewModelScope.launch {
        container.settingsStore.saveLanguage(language)
        val current = container.awaitSettings()
        runCatching { container.api.setLanguage(current.resolvedLanguage) }
    }

    fun add(text: String, quantity: Int) = viewModelScope.launch {
        if (text.contains('\n') || text.contains(',') || text.contains(';')) basket.addFromText(text) else basket.add(text, quantity)
    }

    fun toggleChecked(item: BasketItem) = viewModelScope.launch { basket.setChecked(item, !item.checked) }
    fun setQuantity(item: BasketItem, quantity: Int) = viewModelScope.launch { basket.setQuantity(item, quantity.coerceAtLeast(1)) }
    fun rename(item: BasketItem, text: String) = viewModelScope.launch { basket.rename(item, text) }
    fun delete(item: BasketItem) = viewModelScope.launch { basket.delete(item) }
    fun clearChecked() = viewModelScope.launch { basket.clearChecked() }
    fun choose(item: BasketItem, store: String, productId: String?) = viewModelScope.launch { basket.choose(item, store, productId) }
    fun reject(item: BasketItem, store: String) = viewModelScope.launch { basket.reject(item, store) }
    fun feedback(item: BasketItem, store: String, productId: String, up: Boolean) = viewModelScope.launch { basket.feedback(item, store, productId, up) }
    fun searchMore(item: BasketItem, store: String, query: String) = viewModelScope.launch { basket.searchMore(item, store, query) }
    fun rematch(item: BasketItem) = viewModelScope.launch { basket.rematch(item) }
    fun setEnabledStores(codes: List<String>) = viewModelScope.launch { basket.setEnabledStores(codes); basket.refresh(false) }

    fun compare(refreshPrices: Boolean = false) = viewModelScope.launch {
        _comparing.value = true
        if (refreshPrices) runCatching { container.api.refreshPrices() }
        _comparison.value = basket.compare()
        _comparing.value = false
    }

    suspend fun saveSettings(baseUrl: String, token: String) {
        container.settingsStore.save(baseUrl, token)
        container.awaitSettings()
    }

    suspend fun testConnection(): Result<String> = runCatching {
        val health = container.api.health()
        val ai = health.ai?.get("configured")?.toString() == "true"
        "Connected to baskt ${health.version}" + if (ai) " · AI ready" else " · AI not configured (text matching only)"
    }
}
