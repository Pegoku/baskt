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
import nl.baskt.data.PurchaseHistory
import nl.baskt.data.TripScan
import nl.baskt.data.DuplicateGroup
import nl.baskt.data.DuplicateResolution
import nl.baskt.data.MergeDecision
import nl.baskt.data.NamePreference
import nl.baskt.data.TidyPlan
import nl.baskt.data.TidySelection
import nl.baskt.data.TidyRename
import nl.baskt.data.AllDealsResponse
import nl.baskt.data.ChatMessage
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
import nl.baskt.data.DiscoverDish
import nl.baskt.data.DiscoverParams
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

    /** Whether the server transcribes speech itself; otherwise the microphone opens Android's recogniser. */
    val serverStt: StateFlow<Boolean> = basket.serverStt

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    /** When the typed text asks for a dish, the full ingredient list the AI proposes. */
    private val _recipeSuggestion = MutableStateFlow<RecipeSuggestion?>(null)
    val recipeSuggestion: StateFlow<RecipeSuggestion?> = _recipeSuggestion
    private var suggestJob: Job? = null

    /** Debounced autocomplete for the idea input; history answers instantly, AI interpretations follow. */
    fun onIdeaTextChanged(text: String) {
        suggestJob?.cancel()
        if (!online.value) {
            _suggestions.value = basket.items.value.map { it.text }.filter { it.contains(text, ignoreCase = true) && it != text }.distinct().take(5)
            _recipeSuggestion.value = null
            return
        }
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
    private val _recipeFavourites = container.recipes.favourites
    val recipeFavourites: StateFlow<List<RecipeFavourite>> = _recipeFavourites
    val recentRecipes: StateFlow<List<RecipeSummary>> = container.recipes.recent
    private val _recipeDetail = MutableStateFlow<RecipeDetail?>(null)
    val recipeDetail: StateFlow<RecipeDetail?> = _recipeDetail
    private val _recipesBusy = MutableStateFlow(false)
    val recipesBusy: StateFlow<Boolean> = _recipesBusy

    /** One-line messages for the user (failures, outcomes), shown by a global snackbar. */
    val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun notify(message: String) { notices.tryEmit(message) }

    /** Runs a server call and turns any failure into a visible message instead of silence. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        if (!online.value) throw java.io.IOException("Server unavailable")
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val offline = e is java.io.IOException || e is io.ktor.client.plugins.HttpRequestTimeoutException || e is io.ktor.client.network.sockets.ConnectTimeoutException
        notify(if (offline) "You're offline — $what needs the server" else "$what failed: ${e.message ?: "unknown error"}")
        null
    }

    /** Why the last recipe search shows nothing (from the server, or a failure). */
    private val _recipeMessage = MutableStateFlow<String?>(null)
    val recipeMessage: StateFlow<String?> = _recipeMessage

    /**
     * Searches the recipe sites. [pinned] is a recipe the user already chose (a Discover photo): it is shown
     * at once as the first result and kept on top when the other sites answer.
     */
    fun searchRecipes(query: String, pinned: RecipeSummary? = null) = viewModelScope.launch {
        _recipesBusy.value = true
        _recipeMessage.value = null
        _recipeResults.value = listOfNotNull(pinned)
        val response = attempt("Recipe search") { container.api.searchRecipes(query) }
        val found = response?.results ?: emptyList()
        _recipeResults.value = if (pinned == null) found else listOf(pinned) + found.filter { it.url != pinned.url }
        _recipeMessage.value = response?.message ?: if (response == null) "The search could not be completed." else null
        if (response != null && response.errors.isNotEmpty() && response.results.isNotEmpty()) notify("Some sites did not answer: ${response.errors.joinToString { it.source }}")
        _recipesBusy.value = false
    }

    /** Loads from the server and caches the result; when offline returns the last cached copy. */
    private inline fun <reified T> cachedLoad(name: String, fallback: T, fetch: () -> T): T {
        val cached = container.offline.load<T>(name)
        if (!online.value || pending.value.isNotEmpty()) return cached ?: fallback
        val version = basket.revision
        return try {
            val result = fetch()
            if (version != basket.revision || pending.value.isNotEmpty()) container.offline.load<T>(name) ?: fallback
            else result.also { container.offline.save(name, it) }
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { cached ?: fallback }
    }

    val online get() = basket.online
    val pending get() = basket.pending
    fun retryConnection() = viewModelScope.launch { basket.tryReconnect() }
    fun dropPending(op: nl.baskt.data.PendingOp) { basket.discard(op); _purchases.value = container.offline.load("purchases") ?: emptyList() }

    fun loadRecipeFavourites() = viewModelScope.launch { container.recipes.refresh() }
    fun toggleRecipeFavourite(recipe: RecipeSummary) = viewModelScope.launch { container.recipes.toggle(recipe) }
    fun openRecipe(recipe: RecipeSummary) = viewModelScope.launch {
        container.recipes.recordViewed(recipe)
        _recipeDetail.value = container.recipes.detail(recipe.url)
        if (_recipeDetail.value == null) notify("This recipe has not been downloaded. Connect to open it.")
    }

    fun closeRecipe() { _recipeDetail.value = null }
    fun clearRecentRecipes() = container.recipes.clearRecent()

    /** Discover tab: the current question, the ideas answering it (null until loaded), and how to order them. */
    private val _discoverParams = MutableStateFlow(DiscoverParams())
    val discoverParams: StateFlow<DiscoverParams> = _discoverParams
    private val _discoverDishes = MutableStateFlow<List<DiscoverDish>?>(null)
    val discoverDishes: StateFlow<List<DiscoverDish>?> = _discoverDishes
    private val _discoverByTime = MutableStateFlow(false)
    val discoverByTime: StateFlow<Boolean> = _discoverByTime
    private val _discoverBusy = MutableStateFlow(false)
    val discoverBusy: StateFlow<Boolean> = _discoverBusy
    private var discoverLoaded: DiscoverParams? = null
    private var discoverJob: kotlinx.coroutines.Job? = null

    fun setDiscoverParams(params: DiscoverParams) { _discoverParams.value = params }
    fun setDiscoverByTime(byTime: Boolean) { _discoverByTime.value = byTime }

    /** Fetches ideas for the current parameters; unchanged parameters reuse what is on screen unless forced. */
    fun loadDiscover(force: Boolean = false) {
        val params = _discoverParams.value
        if (!params.complete) { discoverJob?.cancel(); _discoverDishes.value = null; discoverLoaded = null; _discoverBusy.value = false; return }
        if (!force && params == discoverLoaded && _discoverDishes.value != null) return
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            _discoverBusy.value = true
            _discoverDishes.value = null
            try {
                _discoverDishes.value = cachedLoad(params.cacheKey, emptyList()) { container.api.discover(params) }
                discoverLoaded = params
            } finally { _discoverBusy.value = false }
        }
    }

    private val _myRecipes = container.recipes.mine
    val myRecipes: StateFlow<List<UserRecipe>> = _myRecipes
    fun loadMyRecipes() = viewModelScope.launch { container.recipes.refresh() }
    fun deleteMyRecipe(id: String) = viewModelScope.launch { container.recipes.delete(id) }
    fun saveSiteRecipeAsMine(recipe: RecipeSummary) = viewModelScope.launch {
        val detail = container.recipes.detail(recipe.url)
        if (detail == null) { notify("Download this recipe before saving a copy"); return@launch }
        container.recipes.save(RecipeDraft(detail.title, servings = detail.servings, ingredientLines = detail.ingredientLines, steps = detail.steps), "site")
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
        attempt("Saving recipe") { container.recipes.save(draft, origin, existingId) }

    /** Localized recipe (ingredients + steps) of a folder, for the folder screen. */
    fun openGroupRecipe(groupId: String) = viewModelScope.launch {
        _recipeDetail.value = null
        _recipesBusy.value = true
        val local = basket.items.value.firstOrNull { it.id == groupId }?.recipe
        _recipeDetail.value = if (local != null && local.steps.isNotEmpty()) RecipeDetail(local.title, local.sourceUrl, local.servings, local.ingredientLines, local.imageUrl, local.steps)
        else cachedLoad<RecipeDetail?>("group-recipe-$groupId", null) { container.api.groupRecipe(groupId) }
        _recipesBusy.value = false
    }

    fun addRecipeFolder(recipeUrl: String) = viewModelScope.launch { basket.addGroupFromUrl(recipeUrl, container.currentSettings.resolvedLanguage) }

    private val _priceChanges = MutableStateFlow<PriceChangesResponse?>(null)
    val priceChanges: StateFlow<PriceChangesResponse?> = _priceChanges
    fun loadPriceChanges() = viewModelScope.launch { _priceChanges.value = cachedLoad<PriceChangesResponse?>("price-changes", null) { container.api.priceChanges() } }

    suspend fun priceHistory(productId: String): List<PricePoint> = cachedLoad("price-history-$productId", emptyList()) { container.api.priceHistory(productId) }

    /** Proposal from dictation, shown on the confirm sheet until the user accepts or dismisses it. */
    private val _voiceProposal = MutableStateFlow<List<VoiceItem>?>(null)
    val voiceProposal: StateFlow<List<VoiceItem>?> = _voiceProposal
    private val _interpreting = MutableStateFlow(false)
    val interpreting: StateFlow<Boolean> = _interpreting

    /** What the server heard, shown above the proposal so a misheard word is obvious. */
    private val _transcript = MutableStateFlow<String?>(null)
    val transcript: StateFlow<String?> = _transcript

    fun interpret(transcript: String) = viewModelScope.launch {
        _interpreting.value = true
        _transcript.value = transcript
        _voiceProposal.value = basket.interpret(transcript) ?: emptyList()
        _interpreting.value = false
    }

    fun confirmProposal(items: List<VoiceItem>) = viewModelScope.launch {
        _voiceProposal.value = null
        if (items.isNotEmpty()) basket.confirm(items)
    }

    fun dismissProposal() { _voiceProposal.value = null; _transcript.value = null }

    /** Duplicate groups from the last scan; null until the user asks, empty when the list is clean. */
    private val _duplicates = MutableStateFlow<List<DuplicateGroup>?>(null)
    val duplicates: StateFlow<List<DuplicateGroup>?> = _duplicates
    private val _findingDuplicates = MutableStateFlow(false)
    val findingDuplicates: StateFlow<Boolean> = _findingDuplicates

    fun findDuplicates() = viewModelScope.launch {
        _findingDuplicates.value = true
        _duplicates.value = basket.findDuplicates()?.groups ?: emptyList()
        _findingDuplicates.value = false
    }

    fun resolveDuplicates(decisions: List<Pair<DuplicateGroup, DuplicateResolution>>) = viewModelScope.launch {
        _duplicates.value = null
        basket.resolveDuplicates(decisions)
    }

    fun dismissDuplicates() { _duplicates.value = null }

    /** The magic wand's proposal; null until asked, an empty plan when the list is already tidy. */
    private val _tidy = MutableStateFlow<TidyPlan?>(null)
    val tidy: StateFlow<TidyPlan?> = _tidy
    private val _tidying = MutableStateFlow(false)
    val tidying: StateFlow<Boolean> = _tidying

    fun planTidy() = viewModelScope.launch {
        _tidying.value = true
        tidyReview.value = TidyReview()
        _tidy.value = basket.planTidy() ?: TidyPlan()
        _tidying.value = false
    }

    /**
     * What the user decided on the tidy screen so far. Lives here, not in the composable, so a rotation
     * neither forgets the ticks nor refetches the plan. Keys are "rename-id", "merge-keepId".
     */
    data class TidyReview(
        val skipped: Set<String> = emptySet(),
        val summed: Set<String>? = null,
        val selected: Set<String> = emptySet(),
        /** Chosen promotion per item id; nothing is chosen by default. */
        val dealChoice: Map<String, String> = emptyMap(),
    )
    val tidyReview = MutableStateFlow(TidyReview())
    fun updateTidyReview(transform: (TidyReview) -> TidyReview) { tidyReview.update(transform) }

    fun applyTidy(renames: List<TidyRename>, merges: List<MergeDecision>, deals: List<Deal>) = viewModelScope.launch {
        _tidy.value = null
        basket.applyTidy(renames, merges, deals)
        // The deals screen would otherwise keep offering promotions that are now the pick.
        _deals.value = null
    }

    fun dismissTidy() { _tidy.value = null }

    /** Comment mode inside the wand: on by default; the toggle in the sheet's corner turns it off for the session. */
    val tidyCommentMode = MutableStateFlow(true)
    fun toggleTidyCommentMode() { tidyCommentMode.value = !tidyCommentMode.value }
    private val _revising = MutableStateFlow(false)
    val revising: StateFlow<Boolean> = _revising

    /** Sends a comment about the selected proposals; the revised plan replaces the current one. */
    fun reviseTidy(selected: TidySelection, comment: String) = viewModelScope.launch {
        val plan = _tidy.value ?: return@launch
        _revising.value = true
        basket.reviseTidy(plan, selected, comment)?.let { _tidy.value = it }
        _revising.value = false
    }

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
        if (!online.value) { _scanError.value = "Receipt recognition needs a connection"; return@launch }
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
        val id = "local-${java.util.UUID.randomUUID()}"
        val at = scan.purchasedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
        val total = scan.totalCents ?: scan.lines.sumOf { it.totalPriceCents }
        val purchase = Purchase(id, store, at, total, lineCount = scan.lines.size)
        val detail = PurchaseDetail(purchase, scan.lines.mapIndexed { index, line -> nl.baskt.data.PurchaseLine(
            "$id-$index", id, line.name, line.productId, line.quantity, line.unitPriceCents, line.totalPriceCents, line.dealText, index,
        ) })
        basket.queued(nl.baskt.data.PendingOp(type = "purchaseSave", itemId = id, store = store, receipt = scan), {
            _purchases.update { listOf(purchase) + it }
            container.offline.save("purchases", _purchases.value)
            container.offline.save("purchase-$id", detail)
            _scan.value = null
        })
    }

    fun loadPurchases() = viewModelScope.launch {
        _purchases.value = container.offline.load<List<Purchase>>("purchases") ?: _purchases.value
        _spend.value = container.offline.load<SpendSummary>("spend") ?: _spend.value
        _purchases.value = cachedLoad("purchases", emptyList()) { container.api.purchases() }
        _spend.value = cachedLoad<SpendSummary?>("spend", null) { container.api.spendSummary() }
    }

    fun openPurchase(id: String) = viewModelScope.launch { _purchaseDetail.value = cachedLoad<PurchaseDetail?>("purchase-$id", null) { container.api.purchase(id) } }
    fun closePurchase() { _purchaseDetail.value = null }
    fun deletePurchase(id: String) = viewModelScope.launch { basket.queued(nl.baskt.data.PendingOp(type = "purchaseDelete", itemId = id), {
        _purchases.update { list -> list.filterNot { it.id == id } }; container.offline.save("purchases", _purchases.value); _purchaseDetail.value = null
    }) }

    private val _deals = MutableStateFlow<DealsResponse?>(null)
    val deals: StateFlow<DealsResponse?> = _deals
    private val _loadingDeals = MutableStateFlow(false)
    val loadingDeals: StateFlow<Boolean> = _loadingDeals

    fun findDeals(live: Boolean) = viewModelScope.launch {
        _loadingDeals.value = true
        _deals.value = cachedLoad<DealsResponse?>("basket-deals-${basket.currentBasketId.value}", null) { container.api.deals(basket.currentBasketId.value, live) }
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

    /** One row per barcode; a deliberate rescan reopens completed rows. [offline]: never looked up because the server was unreachable. */
    data class Scan(val gtin: String, val products: List<Product> = emptyList(), val loading: Boolean = true, val done: String? = null, val saving: Boolean = false, val offline: Boolean = false, val at: Long = System.currentTimeMillis()) {
        /** Can go on the list: either a known product or a code the server will resolve later. */
        val listable: Boolean get() = !loading && !saving && done == null && (products.isNotEmpty() || offline)
        fun stockItem(stock: List<StockItem>): StockItem? = stock.firstOrNull { item ->
            products.any { it.id == item.productId } ||
                item.barcode?.let { it.padStart(13, '0') == gtin.padStart(13, '0') } == true
        }
    }
    val scans = MutableStateFlow<List<Scan>>(emptyList())
    private val scanJobs = mutableMapOf<String, Job>()
    private var scanSession = 0L
    fun onBarcodeSeen(raw: String): Boolean {
        val gtin = raw.trim()
        if (!nl.baskt.ui.search.validRetailBarcode(gtin)) return false
        val previous = scans.value.firstOrNull { it.gtin == gtin }
        if (previous != null && (previous.done == null || previous.saving)) return false
        val reopened = previous?.copy(done = null, at = System.currentTimeMillis()) ?: Scan(gtin)
        scans.update { listOf(reopened) + it.filterNot { scan -> scan.gtin == gtin } }
        if (previous == null) lookupScan(gtin)
        return true
    }
    private fun lookupScan(gtin: String) {
        scanJobs[gtin]?.cancel()
        scanJobs[gtin] = viewModelScope.launch {
            // Codes scanned before answer instantly from disk, with or without a connection; the server only refreshes them.
            val cached = basket.cachedBarcode(gtin)?.results?.mapNotNull { it.product }.orEmpty()
            if (cached.isNotEmpty()) scans.update { list -> list.map { if (it.gtin == gtin) it.copy(products = cached, loading = false, offline = false) else it } }
            if (cached.isNotEmpty() && !basket.online.value) return@launch
            val result = basket.barcode(gtin)
            val products = result?.results?.mapNotNull { it.product } ?: cached
            val offline = products.isEmpty() && !basket.online.value
            scans.update { list -> list.map { if (it.gtin == gtin) it.copy(products = products, loading = false, offline = offline) else it } }
        }
    }
    /** Connectivity changes: mark unresolved scans as offline, and look them up once the server is back. */
    private fun watchScanConnectivity() = viewModelScope.launch {
        basket.online.collect { online ->
            if (!online) scans.update { list -> list.map { if (!it.loading && it.products.isEmpty()) it.copy(offline = true) else it } }
            else scans.value.filter { it.offline && it.done == null && !it.saving }.forEach { retryScan(it.gtin) }
        }
    }
    fun retryScan(gtin: String) {
        scans.update { list -> list.map { if (it.gtin == gtin) it.copy(loading = true) else it } }
        lookupScan(gtin)
    }
    fun dismissScan(gtin: String) {
        scanJobs.remove(gtin)?.cancel()
        scans.update { list -> list.filterNot { it.gtin == gtin } }
    }
    fun applyScans(gtins: List<String>, destination: String) {
        val session = scanSession
        val ready = scans.value.filter {
            it.gtin in gtins && !it.loading && !it.saving && it.done == null &&
                when (destination) {
                    "stock" -> it.products.isNotEmpty() && it.stockItem(basket.stock.value) == null
                    "unstock" -> it.products.isNotEmpty() && it.stockItem(basket.stock.value) != null
                    "list" -> it.listable
                    else -> false
                }
        }
        val ids = ready.map { it.gtin }.toSet()
        scans.update { list -> list.map { if (it.gtin in ids) it.copy(saving = true) else it } }
        viewModelScope.launch {
            for (scan in ready) {
                try {
                    val product = scan.products.firstOrNull()
                    when (destination) {
                        "list" -> if (product != null) basket.addFromProduct(product) else basket.addFromBarcode(scan.gtin)
                        "stock" -> if (scan.stockItem(basket.stock.value) == null) basket.addProductToStock(product!!, scan.gtin)
                        "unstock" -> scan.stockItem(basket.stock.value)?.let { basket.removeStock(it) }
                        else -> error("Unknown scan destination")
                    }
                    if (session == scanSession) scans.update { list -> list.map { if (it.gtin == scan.gtin) it.copy(done = destination) else it } }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { notify("Couldn’t save ${scan.gtin}. Please try again.") }
                finally { if (session == scanSession) scans.update { list -> list.map { if (it.gtin == scan.gtin) it.copy(saving = false) else it } } }
            }
        }
    }
    fun clearScans() {
        scanSession++
        scanJobs.values.forEach { it.cancel() }
        scanJobs.clear()
        scans.value = emptyList()
    }

    /** Assistant chat for the current basket. */
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat
    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy
    fun loadChat() = viewModelScope.launch { _chat.value = container.offline.load("chat-${basket.currentBasketId.value}") ?: emptyList(); _chat.value = cachedLoad("chat-${basket.currentBasketId.value}", emptyList()) { container.api.chatHistory(basket.currentBasketId.value) } }
    /** What the assistant is doing right now (polled from the server while a turn runs). */
    private val _chatSteps = MutableStateFlow<List<String>>(emptyList())
    val chatSteps: StateFlow<List<String>> = _chatSteps
    private var stepsJob: Job? = null

    fun sendChat(text: String, voiceInput: Boolean = false) = viewModelScope.launch {
        if (_chatBusy.value || text.isBlank()) return@launch
        stopSpeaking()
        if (!online.value) { notify("The assistant needs a connection"); return@launch }
        _chatBusy.value = true
        _chatSteps.value = listOf("Sending…")
        stepsJob?.cancel()
        stepsJob = viewModelScope.launch {
            while (true) {
                delay(700)
                runCatching { container.api.chatProgress(basket.currentBasketId.value) }.getOrNull()?.let { if (it.steps.isNotEmpty()) _chatSteps.value = it.steps }
            }
        }
        // Show the user's line immediately; the server returns both lines once the assistant is done.
        _chat.update { it + ChatMessage(id = "local-${System.currentTimeMillis()}", role = "user", content = text) }
        val reply = attempt("The assistant") { container.api.chatSend(basket.currentBasketId.value, text) }
        if (reply != null) {
            _chat.update { list -> list.filterNot { it.id.startsWith("local-") } + reply }
            container.offline.save("chat-${basket.currentBasketId.value}", _chat.value)
            if (voiceInput && settings.value?.speakReplies == true) {
                reply.lastOrNull { it.role == "assistant" }?.let { speakMessage(it) }
            }
        }
        stepsJob?.cancel()
        _chatSteps.value = emptyList()
        _chatBusy.value = false
    }
    private var speechGeneration = 0
    private var speechJob: Job? = null
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking
    fun stopSpeaking() { speechGeneration++; speechJob?.cancel(); speechJob = null; _speaking.value = null }
    fun setSpeakReplies(enabled: Boolean) = viewModelScope.launch {
        if (!enabled) stopSpeaking()
        container.settingsStore.saveSpeakReplies(enabled)
    }
    fun setSpeechVoice(model: String, voice: String) = viewModelScope.launch {
        stopSpeaking()
        container.settingsStore.saveVoice(model, voice)
    }
    fun toggleMessageSpeech(message: ChatMessage) = viewModelScope.launch {
        val enabled = !(settings.value?.speakReplies ?: false)
        container.settingsStore.saveSpeakReplies(enabled)
        if (enabled) speakMessage(message) else stopSpeaking()
    }
    fun speakMessage(message: ChatMessage) {
        val text = message.content.ifBlank { message.proposalJson?.summary.orEmpty() }
        speak(text, message.id)
    }
    fun previewSpeech(model: String, voice: String) { speak("", "preview", model, voice, true) }
    private fun speak(text: String, id: String, modelOverride: String? = null, voiceOverride: String? = null, preview: Boolean = false) {
        stopSpeaking()
        if (!preview && text.isBlank()) return
        val generation = speechGeneration
        speechJob = viewModelScope.launch {
            _speaking.value = id
            try {
                val current = settings.value ?: container.currentSettings
                val language = current.resolvedLanguage
                val models = container.api.speechModels(language).models
                val selected = models.firstOrNull { it.id == (modelOverride ?: current.ttsModel) && language in it.languages }
                    ?: models.firstOrNull { language in it.languages }
                    ?: error("No speech voice supports this language")
                val voice = (voiceOverride ?: current.ttsVoice).takeIf { it in selected.voices } ?: selected.voices.first()
                val chunks = if (preview) listOf("") else nl.baskt.data.speechChunks(text)
                for (chunk in chunks) {
                    val bytes = container.api.speech(nl.baskt.data.SpeechRequest(chunk, selected.id, voice, language, preview))
                    container.speechPlayback.play(bytes)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { notify("Speech is unavailable. You can still read the reply.") }
            finally { if (speechGeneration == generation) _speaking.value = null }
        }
    }

    fun clearChat() = viewModelScope.launch { basket.queued(nl.baskt.data.PendingOp(type = "chatClear", basketId = basket.currentBasketId.value), {
        _chat.value = emptyList(); container.offline.save("chat-${basket.currentBasketId.value}", _chat.value)
    }) }
    fun applyProposal(message: ChatMessage, indices: List<Int>) = viewModelScope.launch {
        val result = attempt("Applying changes") { container.api.applyProposal(message.id, indices) } ?: return@launch
        _chat.update { list -> list.map { if (it.id == message.id) result.message else it } }
        val failed = result.results.filter { !it.ok }
        notify(if (failed.isEmpty()) "Applied ${result.results.size} change${if (result.results.size == 1) "" else "s"}" else "${result.results.size - failed.size} applied, ${failed.size} failed: ${failed.first().error}")
        basket.refresh(false)
    }

    private val _memory = MutableStateFlow<List<Choice>>(emptyList())
    val memory: StateFlow<List<Choice>> = _memory
    private val _memoryNames = MutableStateFlow<List<NamePreference>>(emptyList())
    val memoryNames: StateFlow<List<NamePreference>> = _memoryNames
    fun loadMemory() = viewModelScope.launch {
        _memory.value = container.offline.load("memory") ?: emptyList()
        _memoryNames.value = container.offline.load("memory-names") ?: emptyList()
        val response = cachedLoad<nl.baskt.data.ChoicesResponse?>("memory-response", null) { container.api.memory() } ?: return@launch
        _memory.value = response.choices; container.offline.save("memory", _memory.value)
        _memoryNames.value = response.names; container.offline.save("memory-names", _memoryNames.value)
    }
    fun deleteNameMemory(id: String) = viewModelScope.launch { basket.queued(nl.baskt.data.PendingOp(type = "nameDelete", itemId = id), {
        _memoryNames.update { list -> list.filterNot { it.id == id } }; container.offline.save("memory-names", _memoryNames.value)
    }) }
    fun deleteMemory(id: String) = viewModelScope.launch { basket.queued(nl.baskt.data.PendingOp(type = "memoryDelete", itemId = id), {
        _memory.update { list -> list.filterNot { it.id == id } }; container.offline.save("memory", _memory.value)
    }) }

    private val _allDeals = MutableStateFlow<AllDealsResponse?>(null)
    val allDeals: StateFlow<AllDealsResponse?> = _allDeals
    private val _loadingAllDeals = MutableStateFlow(false)
    val loadingAllDeals: StateFlow<Boolean> = _loadingAllDeals

    fun loadAllDeals(query: String, store: String?) = viewModelScope.launch {
        _loadingAllDeals.value = true
        _allDeals.value = cachedLoad<AllDealsResponse?>("deals-$query-$store", null) { container.api.allDeals(query, store) }
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
        watchScanConnectivity()
        reload()
    }

    fun reload() = viewModelScope.launch {
        val current = container.awaitSettings()
        // Tell the server which language to use for suggestions and interpretations.
        basket.setLanguage(current.resolvedLanguage)
        basket.refreshStores()
        basket.refreshBaskets()
        basket.tryReconnect()
    }

    fun switchBasket(id: String) = viewModelScope.launch { basket.switchBasket(id) }
    fun refreshStock() = viewModelScope.launch { basket.refreshStock(); basket.refreshServerSettings() }
    fun addStock(text: String) = viewModelScope.launch { basket.addStock(text) }
    fun addProductToStock(product: Product, barcode: String?) = viewModelScope.launch { basket.addProductToStock(product, barcode) }
    fun stockEntryFor(product: Product, barcode: String?): StockItem? = basket.stock.value.firstOrNull { it.productId == product.id || (barcode != null && it.barcode == barcode) }
    fun addToStockFromItem(item: BasketItem) = viewModelScope.launch { basket.addItemToStock(item) }

    /** Shopping mode: buying an item records it on today's trip, puts it in stock and takes it off the open list. */
    fun markBought(item: BasketItem, store: String, product: Product?) = viewModelScope.launch { basket.markBought(item, store, product) }
    fun unbuy(item: BasketItem) = viewModelScope.launch { basket.unbuy(item) }
    /** Picked up in the store without being on the list. */
    fun scanItem(store: String, barcode: String) = viewModelScope.launch { basket.scanItem(store, barcode.filter { it.isDigit() }) }
    val tripScans: StateFlow<List<TripScan>> = basket.tripScans

    /** Day-by-day orders: purchases recorded in the store plus scanned receipts. */
    private val _history = MutableStateFlow<PurchaseHistory?>(null)
    val history: StateFlow<PurchaseHistory?> = _history
    fun loadHistory() = viewModelScope.launch {
        _history.value = container.offline.load<PurchaseHistory>("purchase-history") ?: _history.value
        basket.purchaseHistory()?.let { _history.value = it }
    }

    /** Main-list tick = leave this item out of the trip; the order step's Skip does the same. */
    fun skip(item: BasketItem, skipped: Boolean) = viewModelScope.launch {
        basket.setChecked(item, skipped)
        if (skipped && item.assignedStore != null) basket.assignItem(item, null)
        compare(force = true)
    }
    fun unskipAll() = viewModelScope.launch { basket.unskipAll(); compare(force = true) }
    /** Order step: buying at a store also brings a skipped item back into the trip. */
    fun buyAt(item: BasketItem, store: String) = viewModelScope.launch {
        if (item.isSkipped) basket.setChecked(item, false)
        basket.assignItem(item, store)
        compare(force = true)
    }
    fun saveLoyaltyCard(store: String, number: String?) = viewModelScope.launch { container.settingsStore.saveLoyaltyCard(store, number) }
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
        if (switchTo) basket.switchBasket(created.id)
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
        basket.setLanguage(current.resolvedLanguage)
    }

    fun add(text: String, quantity: Int) = viewModelScope.launch {
        if (text.contains('\n') || text.contains(',') || text.contains(';')) basket.addFromText(text) else basket.add(text, quantity)
    }

    fun toggleChecked(item: BasketItem) = viewModelScope.launch { basket.setChecked(item, !item.checked) }
    fun setQuantity(item: BasketItem, quantity: Int) = viewModelScope.launch { basket.setQuantity(item, quantity.coerceAtLeast(1)) }
    fun rename(item: BasketItem, text: String) = viewModelScope.launch { basket.rename(item, text) }
    fun delete(item: BasketItem) = viewModelScope.launch { basket.delete(item) }
    fun reorder(orderedIds: List<String>) = viewModelScope.launch { basket.reorder(orderedIds) }
    fun clearChecked() = viewModelScope.launch { basket.clearChecked() }
    /** Store cards that are waiting for the server (alternatives search, manual search, choose). */
    val busyMatches = MutableStateFlow<Set<String>>(emptySet())
    private fun <T> busy(item: BasketItem, store: String, block: suspend () -> T) = viewModelScope.launch {
        val key = "${item.id}:$store"
        busyMatches.update { it + key }
        try { block() } finally { busyMatches.update { it - key } }
    }
    fun choose(item: BasketItem, store: String, productId: String?) = busy(item, store) { basket.choose(item, store, productId) }
    fun resetRejections(item: BasketItem, store: String) = busy(item, store) { basket.resetRejections(item, store) }
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
        if (refreshPrices) { attempt("Refreshing prices") { container.api.refreshPrices() }; basket.refresh(false) }
        basket.compare().let { _comparison.value = it; comparedFor = key }
        _comparing.value = false
    }

    suspend fun saveSettings(baseUrl: String, token: String) {
        basket.withSyncPaused {
            val changed = container.currentSettings.baseUrl != baseUrl || container.currentSettings.token != token
            require(!changed || pending.value.isEmpty()) { "Sync or discard pending changes before changing servers" }
            container.settingsStore.save(baseUrl, token)
            container.awaitSettings()
            if (changed) { _chat.value = emptyList(); _purchases.value = emptyList(); _memory.value = emptyList(); _recipeDetail.value = null; _comparison.value = null; _spend.value = null; _purchaseDetail.value = null }
        }
    }

    suspend fun testConnection(): Result<String> = runCatching {
        val health = container.api.health()
        val ai = health.ai?.get("configured")?.toString() == "true"
        val stt = (health.ai?.get("stt") as? kotlinx.serialization.json.JsonObject)?.get("configured")?.toString() == "true"
        "Connected to baskt ${health.version}" + (if (ai) " · AI ready" else " · AI not configured (text matching only)") +
            if (stt) " · dictation on the server" else " · dictation on this phone"
    }
    override fun onCleared() { stopSpeaking(); super.onCleared() }

}
