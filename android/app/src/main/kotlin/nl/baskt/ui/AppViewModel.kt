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
import nl.baskt.data.AllDealsResponse
import nl.baskt.data.Choice
import nl.baskt.data.WhatsAppChat
import nl.baskt.data.WhatsAppStatus
import kotlinx.coroutines.flow.update
import nl.baskt.data.BarcodeResponse
import nl.baskt.data.DealCard
import nl.baskt.data.Deal
import nl.baskt.data.DealsResponse
import nl.baskt.data.Product
import nl.baskt.data.ProductSearchResponse
import nl.baskt.data.PriceChangesResponse
import nl.baskt.data.Purchase
import nl.baskt.data.PurchaseDetail
import nl.baskt.data.ReceiptScan
import nl.baskt.data.SpendSummary
import nl.baskt.data.PricePoint
import nl.baskt.data.GenerateResponse
import nl.baskt.data.RecipeDraft
import nl.baskt.data.StockDish
import nl.baskt.data.UserRecipe
import nl.baskt.data.RecipeDetail
import nl.baskt.data.RecipeFavourite
import nl.baskt.data.RecipeSummary
import nl.baskt.data.RecipeSuggestion
import nl.baskt.data.StockItem
import nl.baskt.data.VoiceItem

class AppViewModel(val container: AppContainer) : ViewModel() {
    val basket = container.basket

    private val _comparison = MutableStateFlow<Comparison?>(null)
    val comparison: StateFlow<Comparison?> = _comparison

    private val _comparing = MutableStateFlow(false)
    val comparing: StateFlow<Boolean> = _comparing

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings

    /** One-shot requests coming from widgets / intents. */
    val focusInputRequest = MutableStateFlow(false)
    val dictateRequest = MutableStateFlow(false)

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

    /** One-line messages for the user (failures, outcomes), shown by a global snackbar. */
    val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun notify(message: String) { notices.tryEmit(message) }

    /** Runs a server call and turns any failure into a visible message instead of silence. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        val offline = e is java.io.IOException || e is io.ktor.client.plugins.HttpRequestTimeoutException || e is io.ktor.client.network.sockets.ConnectTimeoutException
        notify(if (offline) "You're offline — $what needs the server" else "$what failed: ${e.message ?: "unknown error"}")
        null
    }

    /** Why the last recipe search shows nothing (from the server, or a failure). */
    private val _recipeMessage = MutableStateFlow<String?>(null)
    val recipeMessage: StateFlow<String?> = _recipeMessage

    fun searchRecipes(query: String) = viewModelScope.launch {
        _recipesBusy.value = true
        _recipeMessage.value = null
        val response = attempt("Recipe search") { container.api.searchRecipes(query) }
        _recipeResults.value = response?.results ?: emptyList()
        _recipeMessage.value = response?.message ?: if (response == null) "The search could not be completed." else null
        if (response != null && response.errors.isNotEmpty() && response.results.isNotEmpty()) notify("Some sites did not answer: ${response.errors.joinToString { it.source }}")
        _recipesBusy.value = false
    }

    /** Loads from the server and caches the result; when offline returns the last cached copy. */
    private inline fun <reified T> cachedLoad(name: String, fallback: T, fetch: () -> T): T =
        runCatching(fetch).map { container.offline.save(name, it); it }.getOrElse { error ->
            container.offline.load<T>(name) ?: run { notify("Could not load ${name.replace('-', ' ')}: ${error.message ?: "server unreachable"}"); fallback }
        }

    val online get() = basket.online
    val pending get() = basket.pending
    fun retryConnection() = viewModelScope.launch { basket.tryReconnect() }
    fun dropPending(op: nl.baskt.data.PendingOp) { basket.pending.update { it - op }; container.offline.saveOps(basket.pending.value) }

    fun loadRecipeFavourites() = viewModelScope.launch { _recipeFavourites.value = cachedLoad("recipe-favourites", emptyList()) { container.api.recipeFavourites() } }

    fun toggleRecipeFavourite(recipe: RecipeSummary) = viewModelScope.launch {
        val existing = _recipeFavourites.value.firstOrNull { it.url == recipe.url }
        attempt(if (existing != null) "Removing favourite" else "Saving favourite") { if (existing != null) container.api.removeRecipeFavourite(existing.id) else container.api.addRecipeFavourite(recipe) }
        loadRecipeFavourites()
    }

    fun openRecipe(recipeUrl: String) = viewModelScope.launch {
        _recipeDetail.value = null
        _recipesBusy.value = true
        _recipeDetail.value = attempt("Loading the recipe") { container.api.fetchRecipe(recipeUrl) }
        _recipesBusy.value = false
    }

    fun closeRecipe() { _recipeDetail.value = null }

    private val _stockDishes = MutableStateFlow<List<StockDish>?>(null)
    val stockDishes: StateFlow<List<StockDish>?> = _stockDishes
    fun loadStockDishes() = viewModelScope.launch { _recipesBusy.value = true; _stockDishes.value = cachedLoad("stock-dishes", emptyList()) { container.api.dishesFromStock() }; _recipesBusy.value = false }

    private val _myRecipes = MutableStateFlow<List<UserRecipe>>(emptyList())
    val myRecipes: StateFlow<List<UserRecipe>> = _myRecipes
    fun loadMyRecipes() = viewModelScope.launch { _myRecipes.value = cachedLoad("my-recipes", emptyList()) { container.api.myRecipes() } }
    fun deleteMyRecipe(id: String) = viewModelScope.launch { runCatching { container.api.deleteMyRecipe(id) }; loadMyRecipes() }
    fun saveSiteRecipeAsMine(recipe: RecipeSummary) = viewModelScope.launch {
        runCatching { container.api.saveMyRecipe(RecipeDraft(title = recipe.displayTitle), origin = "site", fromUrl = recipe.url) }
        loadMyRecipes()
    }

    /** Create-recipe flow state. */
    private val _generate = MutableStateFlow<GenerateResponse?>(null)
    val generate: StateFlow<GenerateResponse?> = _generate
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating
    fun findRecipeMatches(description: String) = viewModelScope.launch {
        _generating.value = true
        _generate.value = attempt("Finding matching recipes") { container.api.generateRecipe(description, draft = false) }
        _generating.value = false
    }
    fun draftRecipe(description: String) = viewModelScope.launch {
        _generating.value = true
        _generate.value = attempt("Writing the recipe") { container.api.generateRecipe(description, draft = true) }
        _generating.value = false
    }
    fun clearGenerate() { _generate.value = null }
    suspend fun saveRecipe(draft: RecipeDraft, origin: String, existingId: String?): UserRecipe? =
        runCatching { if (existingId != null) container.api.updateMyRecipe(existingId, draft) else container.api.saveMyRecipe(draft, origin) }.getOrNull().also { loadMyRecipes() }

    /** Localized recipe (ingredients + steps) of a folder, for the folder screen. */
    fun openGroupRecipe(groupId: String) = viewModelScope.launch {
        _recipeDetail.value = null
        _recipesBusy.value = true
        _recipeDetail.value = runCatching { container.api.groupRecipe(groupId) }.getOrNull()
        _recipesBusy.value = false
    }

    fun addRecipeFolder(recipeUrl: String) = viewModelScope.launch { basket.addGroupFromUrl(recipeUrl) }

    private val _priceChanges = MutableStateFlow<PriceChangesResponse?>(null)
    val priceChanges: StateFlow<PriceChangesResponse?> = _priceChanges
    fun loadPriceChanges() = viewModelScope.launch { _priceChanges.value = attempt("Loading price changes") { container.api.priceChanges() } }

    suspend fun priceHistory(productId: String): List<PricePoint> = runCatching { container.api.priceHistory(productId) }.getOrDefault(emptyList())

    /** Proposal from dictation, shown on the confirm sheet until the user accepts or dismisses it. */
    private val _voiceProposal = MutableStateFlow<List<VoiceItem>?>(null)
    val voiceProposal: StateFlow<List<VoiceItem>?> = _voiceProposal
    private val _interpreting = MutableStateFlow(false)
    val interpreting: StateFlow<Boolean> = _interpreting

    fun interpret(transcript: String) = viewModelScope.launch {
        _interpreting.value = true
        _voiceProposal.value = basket.interpret(transcript) ?: emptyList()
        _interpreting.value = false
    }

    fun confirmProposal(items: List<VoiceItem>) = viewModelScope.launch {
        _voiceProposal.value = null
        if (items.isNotEmpty()) basket.confirm(items)
    }

    fun dismissProposal() { _voiceProposal.value = null }

    private val _scan = MutableStateFlow<ReceiptScan?>(null)
    val scan: StateFlow<ReceiptScan?> = _scan
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    val purchases: StateFlow<List<Purchase>> = _purchases
    private val _spend = MutableStateFlow<SpendSummary?>(null)
    val spend: StateFlow<SpendSummary?> = _spend
    private val _purchaseDetail = MutableStateFlow<PurchaseDetail?>(null)
    val purchaseDetail: StateFlow<PurchaseDetail?> = _purchaseDetail

    fun scanReceipt(images: List<Pair<String, ByteArray>>, store: String?) = viewModelScope.launch {
        _scanning.value = true
        _scanError.value = null
        runCatching { container.api.scanReceipt(images, store) }
            .onSuccess { _scan.value = it }
            .onFailure { _scanError.value = it.message ?: "Scan failed" }
        _scanning.value = false
    }

    fun updateScan(scan: ReceiptScan) { _scan.value = scan }
    fun clearScan() { _scan.value = null; _scanError.value = null }

    fun savePurchase(scan: ReceiptScan, store: String) = viewModelScope.launch {
        runCatching { container.api.savePurchase(store, scan.purchasedAt, scan.totalCents, scan.lines) }
            .onSuccess { _scan.value = null; loadPurchases() }
            .onFailure { _scanError.value = it.message }
    }

    fun loadPurchases() = viewModelScope.launch {
        _purchases.value = cachedLoad("purchases", emptyList()) { container.api.purchases() }
        _spend.value = cachedLoad<SpendSummary?>("spend", null) { container.api.spendSummary() }
    }

    fun openPurchase(id: String) = viewModelScope.launch { _purchaseDetail.value = runCatching { container.api.purchase(id) }.getOrNull() }
    fun closePurchase() { _purchaseDetail.value = null }
    fun deletePurchase(id: String) = viewModelScope.launch { runCatching { container.api.deletePurchase(id) }; _purchaseDetail.value = null; loadPurchases() }

    private val _deals = MutableStateFlow<DealsResponse?>(null)
    val deals: StateFlow<DealsResponse?> = _deals
    private val _loadingDeals = MutableStateFlow(false)
    val loadingDeals: StateFlow<Boolean> = _loadingDeals

    fun findDeals(live: Boolean) = viewModelScope.launch {
        _loadingDeals.value = true
        _deals.value = attempt("Finding deals") { container.api.deals(basket.currentBasketId.value, live) }
        _loadingDeals.value = false
    }

    private val _whatsapp = MutableStateFlow<WhatsAppStatus?>(null)
    val whatsapp: StateFlow<WhatsAppStatus?> = _whatsapp
    private val _whatsappQr = MutableStateFlow<String?>(null)
    val whatsappQr: StateFlow<String?> = _whatsappQr
    private val _whatsappChats = MutableStateFlow<List<WhatsAppChat>>(emptyList())
    val whatsappChats: StateFlow<List<WhatsAppChat>> = _whatsappChats
    fun loadWhatsApp() = viewModelScope.launch {
        _whatsapp.value = runCatching { container.api.whatsappStatus() }.getOrNull()
        _whatsappQr.value = if (_whatsapp.value?.hasQr == true) runCatching { container.api.whatsappQr().qr }.getOrNull() else null
        _whatsappChats.value = if (_whatsapp.value?.status == "connected") runCatching { container.api.whatsappChats() }.getOrDefault(emptyList()) else emptyList()
    }
    fun setWhatsAppChat(chat: WhatsAppChat) = viewModelScope.launch { runCatching { container.api.whatsappSetChat(chat) }; loadWhatsApp() }
    suspend fun sendToWhatsApp(store: String? = null): Result<Int> = runCatching { container.api.whatsappSend(basket.currentBasketId.value, store) }

    /** Continuous scanner: one entry per distinct barcode seen in this session. */
    data class Scan(val gtin: String, val products: List<Product> = emptyList(), val loading: Boolean = true, val done: String? = null, val at: Long = System.currentTimeMillis())
    val scans = MutableStateFlow<List<Scan>>(emptyList())
    private var lastSeen: Pair<String, Long>? = null
    fun onBarcodeSeen(raw: String) {
        val gtin = raw.filter { it.isDigit() }
        if (gtin.length < 8) return
        val now = System.currentTimeMillis()
        // Ignore the same code while it stays in view; a different code (or the same after 4 s) is a new scan.
        if (lastSeen?.first == gtin && now - (lastSeen?.second ?: 0) < 4000) { lastSeen = gtin to now; return }
        lastSeen = gtin to now
        if (scans.value.any { it.gtin == gtin }) return
        scans.update { listOf(Scan(gtin)) + it } // newest first
        viewModelScope.launch {
            val result = runCatching { container.api.barcode(gtin) }.getOrNull()
            val products = result?.results?.mapNotNull { it.product } ?: emptyList()
            scans.update { list -> list.map { if (it.gtin == gtin) it.copy(products = products, loading = false) else it } }
            if (products.isEmpty()) {
                // Unknown codes only clutter the list: show the notice briefly, then drop it.
                delay(3000)
                scans.update { list -> list.filterNot { it.gtin == gtin && it.products.isEmpty() } }
            }
        }
    }
    fun markScan(gtin: String, done: String) { scans.update { list -> list.map { if (it.gtin == gtin) it.copy(done = done) else it } } }
    fun clearScans() { scans.value = emptyList(); lastSeen = null }

    private val _memory = MutableStateFlow<List<Choice>>(emptyList())
    val memory: StateFlow<List<Choice>> = _memory
    fun loadMemory() = viewModelScope.launch { _memory.value = cachedLoad("memory", emptyList()) { container.api.memory() } }
    fun deleteMemory(id: String) = viewModelScope.launch { runCatching { container.api.deleteMemory(id) }; _memory.update { list -> list.filterNot { it.id == id } } }

    private val _allDeals = MutableStateFlow<AllDealsResponse?>(null)
    val allDeals: StateFlow<AllDealsResponse?> = _allDeals
    private val _loadingAllDeals = MutableStateFlow(false)
    val loadingAllDeals: StateFlow<Boolean> = _loadingAllDeals

    fun loadAllDeals(query: String, store: String?) = viewModelScope.launch {
        _loadingAllDeals.value = true
        _allDeals.value = attempt("Loading deals") { container.api.allDeals(query, store) }
        _loadingAllDeals.value = false
    }

    /** Adds a store-wide deal to the basket: known products are pinned, promotion groups become an idea. */
    fun addDeal(card: DealCard) = viewModelScope.launch {
        val product = card.productId?.let { id -> runCatching { container.api.searchProducts(card.title, card.store) }.getOrNull()?.results?.firstOrNull()?.products?.firstOrNull { it.id == id } }
        if (product != null) basket.addFromProduct(product) else basket.add(card.title, 1)
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
        // Repository failures (adds, picks, deletes…) become visible on whatever screen is open.
        viewModelScope.launch {
            basket.error.collect { message -> if (message != null) { notify(message); basket.clearError() } }
        }
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
    fun addProductToStock(product: Product, barcode: String?) = viewModelScope.launch { basket.addProductToStock(product, barcode) }
    fun stockEntryFor(product: Product, barcode: String?): StockItem? = basket.stock.value.firstOrNull { it.productId == product.id || (barcode != null && it.barcode == barcode) }
    fun addToStockFromItem(item: BasketItem) = viewModelScope.launch { basket.addItemToStock(item) }

    /** Shopping mode: buying an item puts it in stock with the product you bought. */
    fun markBought(item: BasketItem, product: Product) = viewModelScope.launch {
        basket.setChecked(item, true)
        basket.addItemToStock(item, product)
    }
    val lastAdded get() = basket.lastAdded
    fun consumeLastAdded() { basket.lastAdded.value = null }
    fun removeStock(item: StockItem) = viewModelScope.launch { basket.removeStock(item) }
    fun setSkipInStock(enabled: Boolean) = viewModelScope.launch { basket.setSkipInStock(enabled) }
    fun addSkipped(group: BasketItem) = viewModelScope.launch { basket.addSkipped(group) }
    fun setDefaultServings(servings: Int?) = viewModelScope.launch { basket.setDefaultServings(servings) }
    fun setRankBy(rankBy: String) = viewModelScope.launch { basket.setRankBy(rankBy); compare(force = true) }
    fun setGroupServings(group: BasketItem, servings: Int) = viewModelScope.launch { basket.setGroupServings(group, servings) }
    fun createBasket(name: String, emoji: String?, switchTo: Boolean = true) = viewModelScope.launch {
        val created = basket.createBasket(name, emoji)
        if (switchTo && created != null) basket.switchBasket(created.id)
    }
    fun renameBasket(id: String, name: String, emoji: String?) = viewModelScope.launch { basket.renameBasket(id, name, emoji) }
    fun deleteBasket(id: String) = viewModelScope.launch { basket.deleteBasket(id) }
    fun transfer(item: BasketItem, basketId: String, copy: Boolean) = viewModelScope.launch { basket.transfer(item, basketId, copy) }

    /** Multi-select in the basket list. */
    val selection = MutableStateFlow<Set<String>>(emptySet())
    fun toggleSelected(id: String) { selection.value = if (id in selection.value) selection.value - id else selection.value + id }
    fun clearSelection() { selection.value = emptySet() }
    private fun selectedItems() = basket.items.value.filter { it.id in selection.value }
    fun deleteSelected() = viewModelScope.launch { basket.deleteMany(selectedItems()); clearSelection() }
    fun groupSelected(name: String) = viewModelScope.launch { basket.groupFromItems(name, selectedItems().filter { !it.isGroup }); clearSelection() }
    fun transferSelected(basketId: String, copy: Boolean) = viewModelScope.launch { basket.transferMany(selectedItems(), basketId, copy); clearSelection() }
    fun checkSelected(checked: Boolean) = viewModelScope.launch { basket.setCheckedMany(selectedItems(), checked); clearSelection() }

    suspend fun shareLink(): String? = basket.shareLink()?.url

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
    /** Store cards that are waiting for the server (alternatives search, manual search, choose). */
    val busyMatches = MutableStateFlow<Set<String>>(emptySet())
    private fun <T> busy(item: BasketItem, store: String, block: suspend () -> T) = viewModelScope.launch {
        val key = "${item.id}:$store"
        busyMatches.update { it + key }
        try { block() } finally { busyMatches.update { it - key } }
    }
    fun choose(item: BasketItem, store: String, productId: String?) = busy(item, store) { basket.choose(item, store, productId) }
    fun reject(item: BasketItem, store: String) = busy(item, store) { basket.reject(item, store) }
    fun unskip(item: BasketItem, store: String) = busy(item, store) { basket.unskip(item, store) }
    fun feedback(item: BasketItem, store: String, productId: String, up: Boolean) = viewModelScope.launch { basket.feedback(item, store, productId, up) }
    fun searchMore(item: BasketItem, store: String, query: String) = busy(item, store) { basket.searchMore(item, store, query) }
    fun rematch(item: BasketItem) = viewModelScope.launch { basket.rematch(item) }
    fun setEnabledStores(codes: List<String>) = viewModelScope.launch { basket.setEnabledStores(codes); basket.refresh(false) }

    fun assign(mode: String) = viewModelScope.launch { basket.assign(mode); compare(force = true) }
    fun assignItem(item: BasketItem, store: String?) = viewModelScope.launch { basket.assignItem(item, store); compare(force = true) }

    /** Fingerprint of everything the comparison depends on; unchanged basket = no reload. */
    private fun basketFingerprint(): Int = basket.items.value.filter { !it.isGroup }
        .map { listOf(it.id, it.checked, it.quantity, it.assignedStore, it.matches.map { m -> "${m.store}:${m.status}:${m.effective?.id}:${m.effective?.priceCents}" }) }
        .hashCode() * 31 + basket.currentBasketId.value.hashCode()
    private var comparedFor: Int? = null

    fun compare(refreshPrices: Boolean = false, force: Boolean = false) = viewModelScope.launch {
        val key = basketFingerprint()
        if (!refreshPrices && !force && comparedFor == key && _comparison.value != null) return@launch
        // Only show the bar when there is nothing to look at yet; otherwise update quietly.
        if (_comparison.value == null) _comparing.value = true
        if (refreshPrices) runCatching { container.api.refreshPrices() }
        basket.compare()?.let { _comparison.value = it; comparedFor = key }
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
