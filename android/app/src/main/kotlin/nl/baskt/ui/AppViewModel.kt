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
import nl.baskt.data.PurchaseLine
import nl.baskt.data.ProductSearchResponse
import nl.baskt.data.PriceChangesResponse
import nl.baskt.data.Purchase
import nl.baskt.data.PurchaseDetail
import nl.baskt.data.ReceiptScan
import nl.baskt.data.ReceiptUpload
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

/** A line for the global snackbar, optionally with one action such as Undo. */
data class Notice(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

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

    fun lookupBarcode(code: String) = viewModelScope.launch {
        val gtin = nl.baskt.data.productGtin(code) ?: return@launch notify("That isn’t a product barcode")
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

    /**
     * Puts a line from a previous order back on the list: the same product when the line still knows it, otherwise
     * just the name, so it gets matched again. Weighed lines (1.5 kg) come back as one entry.
     */
    fun addPurchaseLines(lines: List<PurchaseLine>, products: Map<String, Product>, bumpExisting: Boolean = false) = viewModelScope.launch {
        var bumped = 0
        for (line in lines) {
            val quantity = purchaseQuantity(line)
            val existing = if (bumpExisting) basketItemFor(line, products) else null
            if (existing != null) { basket.setQuantity(existing, existing.quantity + quantity); bumped++; continue }
            val product = line.productId?.let { products[it] }
            if (product != null) basket.addFromProduct(product, quantity = quantity) else basket.add(line.name, quantity)
        }
        notify(when {
            lines.size == 1 && bumped == 1 -> "Added ${purchaseQuantity(lines.single())} more “${lines.single().name}” to your basket"
            lines.size == 1 -> "Added “${lines.single().name}” to your basket"
            bumped > 0 -> "Added ${lines.size} items to your basket ($bumped already in it)"
            else -> "Added ${lines.size} items to your basket"
        })
    }

    fun purchaseQuantity(line: PurchaseLine) = if (line.quantity % 1.0 == 0.0) line.quantity.toInt().coerceAtLeast(1) else 1

    /**
     * The open item on the current list that a previous-order line is already there as: the same product at any
     * store, the same barcode, or the same name.
     */
    fun basketItemFor(line: PurchaseLine, products: Map<String, Product>): BasketItem? {
        val product = line.productId?.let { products[it] }
        val names = listOfNotNull(line.name, product?.title).map { it.trim().lowercase() }.toSet()
        return basket.items.value.filter { it.isOpen && !it.isGroup && !it.isBought }.firstOrNull { item ->
            (line.productId != null && item.matches.any { it.chosen?.id == line.productId || it.provisional?.id == line.productId }) ||
                (line.barcode != null && item.barcode == line.barcode) ||
                listOfNotNull(item.text, item.parsed?.canonicalName, item.anyProduct?.title).any { it.trim().lowercase() in names }
        }
    }

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
    val notices = kotlinx.coroutines.flow.MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    fun notify(message: String) { notices.tryEmit(Notice(message)) }
    /** A message with one button, e.g. "Removed “milk”" with Undo. The app's rule: reversible actions act at once and offer this. */
    fun notify(message: String, actionLabel: String, onAction: () -> Unit) { notices.tryEmit(Notice(message, actionLabel, onAction)) }
    fun notifyUndo(message: String, undo: () -> Unit) = notify(message, "Undo", undo)
    init { viewModelScope.launch { basket.notices.collect { notify(it) } } }

    /**
     * Runs a server call and turns any failure into a visible message instead of silence. [what] is the whole
     * failure phrase in user terms ("Couldn’t search recipes"); the reason is added after a colon.
     */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        if (!online.value) throw java.io.IOException("Server unavailable")
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        notify(failure(what, e))
        null
    }

    /**
     * One failure sentence for a notice: what failed, then why. "No connection" is only said when the phone really
     * could not reach the server; a slow answer and a server error are named as such, and a missing reason adds nothing.
     */
    private fun failure(what: String, e: Throwable): String {
        val reason = when {
            !online.value || e is java.net.ConnectException || e is java.net.UnknownHostException || e is io.ktor.client.network.sockets.ConnectTimeoutException -> "no connection"
            e is io.ktor.client.plugins.HttpRequestTimeoutException || e is java.net.SocketTimeoutException -> "it took too long"
            else -> e.message?.takeIf { it.isNotBlank() }
        }
        return if (reason == null) what else "$what: $reason"
    }

    /** Why the last recipe search shows nothing (from the server, or a failure). */
    private val _recipeMessage = MutableStateFlow<String?>(null)
    val recipeMessage: StateFlow<String?> = _recipeMessage

    /**
     * Searches the recipe sites. [pinned] is a recipe the user already chose (a Discover photo): it is shown
     * at once as the first result and kept on top when the other sites answer.
     */
    private var recipeQuery: String? = null
    private var recipePage = 1
    private var moreJob: kotlinx.coroutines.Job? = null
    private val _recipesHasMore = MutableStateFlow(false)
    val recipesHasMore: StateFlow<Boolean> = _recipesHasMore
    private val _recipesLoadingMore = MutableStateFlow(false)
    val recipesLoadingMore: StateFlow<Boolean> = _recipesLoadingMore

    /** The next page of the current search, appended; asked for by the list before the user reaches its end. */
    fun loadMoreRecipes() {
        val query = recipeQuery ?: return
        if (_recipesBusy.value || _recipesLoadingMore.value || !_recipesHasMore.value || !online.value) return
        moreJob = viewModelScope.launch {
            _recipesLoadingMore.value = true
            try {
                val response = runCatching { container.api.searchRecipes(query, recipePage + 1) }.getOrNull()
                if (recipeQuery != query) return@launch
                if (response == null) { _recipesHasMore.value = false; return@launch }
                recipePage = response.page
                val known = _recipeResults.value.map { it.url }.toSet()
                val fresh = response.results.filter { it.url !in known }
                _recipeResults.value = _recipeResults.value + fresh
                // A site that ignores the page number repeats itself: nothing new means the end.
                _recipesHasMore.value = response.hasMore && fresh.isNotEmpty()
            } finally {
                _recipesLoadingMore.value = false
            }
        }
    }

    fun searchRecipes(query: String, pinned: RecipeSummary? = null) = viewModelScope.launch {
        moreJob?.cancel()
        recipeQuery = query
        recipePage = 1
        _recipesHasMore.value = false
        _recipesLoadingMore.value = false
        _recipesBusy.value = true
        _recipeMessage.value = null
        _recipeResults.value = listOfNotNull(pinned)
        val response = attempt("Couldn’t search recipes") { container.api.searchRecipes(query) }
        val found = response?.results ?: emptyList()
        _recipeResults.value = if (pinned == null) found else listOf(pinned) + found.filter { it.url != pinned.url }
        _recipeMessage.value = response?.message ?: if (response == null) "Couldn’t search recipes" else null
        _recipesHasMore.value = response?.hasMore == true
        if (response != null && response.errors.isNotEmpty() && response.results.isNotEmpty()) notify("Some recipe sites didn’t answer: ${response.errors.joinToString { it.source }}")
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
        if (_recipeDetail.value == null) notify("Couldn’t open this recipe: no connection")
    }

    fun closeRecipe() { _recipeDetail.value = null }

    private var recipeWarmup: Job? = null
    /**
     * Gets recipes ready before they are tapped: downloads each one (if needed), then translates its ingredients
     * and, only when that worked, its steps, into the same disk cache the recipe sheet reads. One recipe at a time,
     * in list order, so it never crowds out the recipe the user actually opens.
     */
    fun warmRecipes(recipes: List<RecipeSummary>) {
        recipeWarmup?.cancel()
        recipeWarmup = viewModelScope.launch {
            delay(400)
            val settings = container.currentSettings
            val language = settings.resolvedLanguage
            val translations = container.translations(settings)
            // The sheet also translates what is at home and in the basket for its "In stock" labels.
            val owned = basket.stock.value.map { it.text } + basket.items.value.filter { it.kind != "group" && !it.checked }.map { it.text }
            if (owned.isNotEmpty() && online.value) translations.translate(owned, language)
            for (recipe in recipes.distinctBy { it.url }.take(15)) {
                if (!online.value) break
                val detail = container.recipes.detail(recipe.url) ?: continue
                val source = detail.original ?: detail
                if (source.ingredientLines.isEmpty()) continue
                val head = nl.baskt.data.recipeHeadTexts(source)
                if (!translations.translate(head, language, listOf(head.lastIndex)).translated) continue
                if (source.steps.isNotEmpty()) translations.translate(source.steps.map { it.text }, language)
            }
        }
    }

    /** What the recipe AI field gave back: an [answer] to a question, or a proposed [draft] (with [summary]) to preview. */
    data class RecipeAiReply(val answer: String? = null, val draft: RecipeDraft? = null, val summary: String = "")

    /** Recipe AI field: answers a question or proposes a change. Nothing is saved here. Null when it failed (with a notice). */
    suspend fun askRecipe(detail: RecipeDetail, message: String, history: List<nl.baskt.data.RecipeTurn>): RecipeAiReply? {
        val current = RecipeDraft(detail.title, detail.description, detail.servings, detail.ingredientLines, detail.steps)
        val reply = attempt("Couldn’t get an answer") { container.api.adaptRecipe(current, message, history) } ?: return null
        if (reply.answer == null && reply.draft == null) { notify("Couldn’t get an answer"); return null }
        return RecipeAiReply(reply.answer, reply.draft, reply.summary)
    }

    /**
     * Saves an accepted AI change: my recipes change in place, a site recipe becomes a changed copy in my recipes.
     * Returns the recipe now shown, or null when saving failed (with a notice).
     */
    suspend fun applyRecipeChange(recipe: RecipeSummary, draft: RecipeDraft): RecipeSummary? {
        val ownId = recipe.url.removePrefix("baskt://recipe/").takeIf { recipe.url.startsWith("baskt://recipe/") }
        val saved = try {
            container.recipes.save(draft, "ai", existingId = ownId)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { notify(failure("Couldn’t save the recipe", e)); return null }
        if (ownId == null) notify("Saved to my recipes")
        val summary = RecipeSummary(saved.title, saved.url, saved.imageUrl, source = "Mine")
        container.recipes.recordViewed(summary)
        _recipeDetail.value = container.recipes.detail(saved.url)
        return summary
    }

    fun clearRecentRecipes() = container.recipes.clearRecent()
    /** Undo of [clearRecentRecipes]: the same recipes, in the same order. */
    fun restoreRecentRecipes(recipes: List<RecipeSummary>) { recipes.asReversed().forEach { container.recipes.recordViewed(it) } }

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
        if (detail == null) { notify("Couldn’t copy this recipe: no connection"); return@launch }
        container.recipes.save(RecipeDraft(detail.title, servings = detail.servings, ingredientLines = detail.ingredientLines, steps = detail.steps), "site")
    }

    /** Create-recipe flow state. */
    private val _generate = MutableStateFlow<GenerateResponse?>(null)
    val generate: StateFlow<GenerateResponse?> = _generate
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating
    fun findRecipeMatches(description: String) = viewModelScope.launch {
        _generating.value = true
        _generate.value = attempt("Couldn’t find matching recipes") { container.api.generateRecipe(description, draft = false) }
        _generating.value = false
    }
    fun draftRecipe(description: String) = viewModelScope.launch {
        _generating.value = true
        _generate.value = attempt("Couldn’t write the recipe") { container.api.generateRecipe(description, draft = true) }
        _generating.value = false
    }
    fun clearGenerate() { _generate.value = null }
    suspend fun saveRecipe(draft: RecipeDraft, origin: String, existingId: String?): UserRecipe? =
        attempt("Couldn’t save the recipe") { container.recipes.save(draft, origin, existingId) }

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
    /** The magic wand's proposal; null until asked, an empty plan when the list is already tidy. */
    private val _tidy = MutableStateFlow<TidyPlan?>(null)
    val tidy: StateFlow<TidyPlan?> = _tidy
    private val _tidying = MutableStateFlow(false)
    val tidying: StateFlow<Boolean> = _tidying

    /** True when the last tidy request failed, so the screen can say so and offer "Try again" instead of "Nothing to tidy". */
    val tidyFailed = MutableStateFlow(false)

    fun planTidy() = viewModelScope.launch {
        _tidying.value = true
        tidyReview.value = TidyReview()
        val plan = basket.planTidy()
        tidyFailed.value = plan == null
        _tidy.value = plan ?: TidyPlan()
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

    /** Set when a receipt arrives through the share sheet: the receipts screen should come up to show the result. */
    val openReceiptsRequest = MutableStateFlow(false)

    fun scanReceipt(files: List<ReceiptUpload>, store: String?) = viewModelScope.launch {
        if (files.isEmpty()) return@launch
        if (!online.value) { _scanError.value = "Couldn’t read the receipt: no connection"; return@launch }
        _scanning.value = true
        _scanError.value = null
        runCatching { container.api.scanReceipt(files, store) }
            .onSuccess { _scan.value = it }
            .onFailure { _scanError.value = failure("Couldn’t read the receipt", it) }
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
            "$id-$index", id, line.name, line.productId, line.quantity, line.unitPriceCents, line.totalPriceCents, line.dealText, index, itemId = line.itemId,
        ) }, scan.lines.mapNotNull { it.product }.associateBy { it.id })
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

    /** The corrected receipt of a saved purchase: the list, the open sheet and the cache all show it right away. */
    fun updatePurchase(id: String, scan: ReceiptScan, store: String) = viewModelScope.launch {
        val current = _purchaseDetail.value?.takeIf { it.purchase.id == id } ?: container.offline.load<PurchaseDetail>("purchase-$id") ?: return@launch
        val at = scan.purchasedAt?.let { runCatching { java.time.LocalDate.parse(it).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() } ?: current.purchase.purchasedAt
        val total = scan.totalCents ?: scan.lines.sumOf { it.totalPriceCents }
        val purchase = current.purchase.copy(store = store, purchasedAt = at, totalCents = total, lineCount = scan.lines.size)
        val detail = PurchaseDetail(
            purchase,
            scan.lines.mapIndexed { index, line -> nl.baskt.data.PurchaseLine("$id-$index", id, line.name, line.productId, line.quantity, line.unitPriceCents, line.totalPriceCents, line.dealText, index, itemId = line.itemId) },
            scan.lines.mapNotNull { it.product }.associateBy { it.id },
        )
        basket.queued(nl.baskt.data.PendingOp(type = "purchaseUpdate", itemId = id, store = store, receipt = scan), {
            _purchases.update { list -> list.map { if (it.id == id) purchase else it } }
            container.offline.save("purchases", _purchases.value)
            container.offline.save("purchase-$id", detail)
            _purchaseDetail.value = detail
        })
    }

    /** Open list items (this basket) with what they would be bought as at the store, for linking a receipt line by hand. */
    fun openItemsForReceipt(store: String, query: String): List<Pair<BasketItem, Product?>> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        return basket.items.value
            .filter { !it.isGroup && it.isOpen }
            .map { it to (it.match(store)?.effective ?: it.anyProduct) }
            .filter { (item, product) -> words.isEmpty() || words.any { word -> item.text.lowercase().contains(word) || product?.title?.lowercase()?.contains(word) == true } }
            .take(8)
    }

    /** Products at a store for linking a receipt line by name. */
    suspend fun productsForReceipt(query: String, store: String): List<Product> =
        runCatching { container.api.searchProducts(query, store) }.getOrNull()?.results?.flatMap { it.products }?.filter { it.store == store } ?: emptyList()

    /** The product behind a scanned barcode, at this store first and at any store otherwise. */
    suspend fun productForBarcode(gtin: String, store: String): Product? {
        val results = basket.barcode(gtin)?.results ?: return null
        return results.firstOrNull { it.store == store }?.product ?: results.firstNotNullOfOrNull { it.product }
    }
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

    private val _ahAccount = MutableStateFlow<nl.baskt.data.AhAccountStatus?>(null)
    val ahAccount: StateFlow<nl.baskt.data.AhAccountStatus?> = _ahAccount
    private val _ahOffers = MutableStateFlow<nl.baskt.data.PersonalOffersResponse?>(null)
    val ahOffers: StateFlow<nl.baskt.data.PersonalOffersResponse?> = _ahOffers
    private val _loadingAhOffers = MutableStateFlow(false)
    val loadingAhOffers: StateFlow<Boolean> = _loadingAhOffers
    /** Offer ids with an activation in flight. */
    private val _activating = MutableStateFlow<Set<String>>(emptySet())
    val activating: StateFlow<Set<String>> = _activating

    /** Why the AH account could not be loaded, in a few words; null while loading or once it is there. */
    private val _ahAccountError = MutableStateFlow<String?>(null)
    val ahAccountError: StateFlow<String?> = _ahAccountError

    fun loadAhAccount() = viewModelScope.launch {
        _ahAccountError.value = null
        // A server that never answers must not leave the screens waiting forever.
        val result = kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { container.api.ahAccount() } }
        if (result == null) _ahAccountError.value = "it took too long"
        else result.onSuccess { _ahAccount.value = it }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _ahAccountError.value = failure("", it).removePrefix(": ").ifBlank { "something went wrong" } }
    }

    /** Called by the login WebView with the appie://login-exit URL it intercepted. */
    suspend fun connectAh(redirect: String): Result<nl.baskt.data.AhAccountStatus> =
        runCatching { container.api.ahConnect(redirect) }.onSuccess { _ahAccount.value = it; _ahOffers.value = null; loadAhOffers(force = true) }

    fun disconnectAh() = viewModelScope.launch {
        runCatching { container.api.ahDisconnect() }.onSuccess { _ahAccount.value = it; _ahOffers.value = null }.onFailure { notify(failure("Couldn’t log out", it)) }
    }

    fun setAhAutoActivate(enabled: Boolean) = viewModelScope.launch {
        runCatching { container.api.ahSetAutoActivate(enabled) }.onSuccess { _ahAccount.value = it }.onFailure { notify(failure("Couldn’t change the Bonus Box setting", it)) }
    }

    fun loadAhOffers(force: Boolean = false) = viewModelScope.launch {
        _loadingAhOffers.value = true
        _ahOffers.value = cachedLoad<nl.baskt.data.PersonalOffersResponse?>("ah-offers", _ahOffers.value) { container.api.ahOffers(force) }
        if (_ahAccount.value == null) loadAhAccount()
        _loadingAhOffers.value = false
    }

    private val _importingReceipts = MutableStateFlow(false)
    val importingReceipts: StateFlow<Boolean> = _importingReceipts

    /** Pulls new AH digital receipts into the orders (the server also does this hourly). */
    fun importAhReceipts() = viewModelScope.launch {
        _importingReceipts.value = true
        runCatching { container.api.ahImportReceipts() }
            .onSuccess { result ->
                notify(if (result.imported == 0) "No new AH receipts" else "Added ${nl.baskt.ui.common.counted(result.imported, "AH receipt")}")
                if (result.imported > 0) { loadHistory(); basket.refresh() }
            }
            .onFailure { notify(failure("Couldn’t get AH receipts", it)) }
        _importingReceipts.value = false
    }

    fun activateOffer(offer: nl.baskt.data.PersonalOffer) = viewModelScope.launch {
        _activating.value = _activating.value + offer.id
        runCatching { container.api.ahActivate(offer.id) }
            .onSuccess {
                _ahOffers.value = it.offers
                notify("Activated “${offer.title}”")
                // Prices and deals now include the offer.
                _deals.value = null
                basket.refresh()
            }
            .onFailure { notify(failure("Couldn’t activate “${offer.title}”", it)) }
        _activating.value = _activating.value - offer.id
    }

    private val _whatsapp = MutableStateFlow<WhatsAppStatus?>(null)
    val whatsapp: StateFlow<WhatsAppStatus?> = _whatsapp
    private val _whatsappQr = MutableStateFlow<String?>(null)
    val whatsappQr: StateFlow<String?> = _whatsappQr
    private val _whatsappChats = MutableStateFlow<List<WhatsAppChat>>(emptyList())
    val whatsappChats: StateFlow<List<WhatsAppChat>> = _whatsappChats
    /** The chat list of the linked WhatsApp account: still loading, or failed to load. */
    private val _whatsappChatsLoading = MutableStateFlow(false)
    val whatsappChatsLoading: StateFlow<Boolean> = _whatsappChatsLoading
    private val _whatsappChatsFailed = MutableStateFlow(false)
    val whatsappChatsFailed: StateFlow<Boolean> = _whatsappChatsFailed
    fun loadWhatsApp() = viewModelScope.launch {
        _whatsapp.value = runCatching { container.api.whatsappStatus() }.getOrNull()
        _whatsappQr.value = if (_whatsapp.value?.hasQr == true) runCatching { container.api.whatsappQr().qr }.getOrNull() else null
        if (_whatsapp.value?.status != "connected") { _whatsappChats.value = emptyList(); _whatsappChatsFailed.value = false; return@launch }
        _whatsappChatsLoading.value = _whatsappChats.value.isEmpty()
        val chats = runCatching { container.api.whatsappChats() }
        _whatsappChatsFailed.value = chats.isFailure
        chats.onSuccess { _whatsappChats.value = it }
        _whatsappChatsLoading.value = false
    }
    fun setWhatsAppChat(chat: WhatsAppChat) = viewModelScope.launch { runCatching { container.api.whatsappSetChat(chat) }; loadWhatsApp() }
    suspend fun sendToWhatsApp(store: String? = null): Result<Int> = runCatching { container.api.whatsappSend(basket.currentBasketId.value, store) }

    /** One row per barcode; a deliberate rescan reopens completed rows. [offline]: never looked up because the server was unreachable. */
    data class Scan(
        val gtin: String,
        val products: List<Product> = emptyList(),
        val loading: Boolean = true,
        val done: String? = null,
        val saving: Boolean = false,
        val offline: Boolean = false,
        /** Name and picture found by the phone itself, shown while (or when) no store product is available. */
        val label: nl.baskt.data.ScannedLabel? = null,
        /** No store knows the code: the closest store products to its Open Food Facts name, best first. */
        val suggestions: List<Product> = emptyList(),
        val at: Long = System.currentTimeMillis(),
    ) {
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
            // Anything the phone already knows about the code appears at once, so the card is never a bare number.
            basket.knownLabel(gtin)?.let { known -> scans.update { list -> list.map { if (it.gtin == gtin) it.copy(label = known) else it } } }
            if (cached.isNotEmpty() && !basket.online.value) return@launch
            val result = basket.barcode(gtin)
            val products = result?.results?.mapNotNull { it.product } ?: cached
            val offline = products.isEmpty() && !basket.online.value
            val suggestions = if (products.isEmpty()) result?.suggestions.orEmpty() else emptyList()
            // The server names unknown codes from Open Food Facts too, so the phone does not have to ask again.
            val serverLabel = result?.label?.let { label ->
                val brand = label.brand?.takeUnless { label.name.contains(it, ignoreCase = true) }
                nl.baskt.data.ScannedLabel(listOfNotNull(brand, label.name).joinToString(" "), label.quantityText, label.imageUrl, "openfoodfacts")
            }
            scans.update { list -> list.map { if (it.gtin == gtin) it.copy(products = products, loading = false, offline = offline, suggestions = suggestions, label = it.label ?: serverLabel) else it } }
            // No store knows the code (or the server is unreachable): ask Open Food Facts from the phone.
            if (products.isEmpty() && scans.value.firstOrNull { it.gtin == gtin }?.label == null) {
                val label = runCatching { basket.resolveLabel(gtin) }.getOrNull()
                if (label != null) scans.update { list -> list.map { if (it.gtin == gtin) it.copy(label = label) else it } }
            }
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
    /** "This one": the suggestion becomes the scan's product, and the code is remembered as that product. */
    fun pickSuggestion(gtin: String, product: Product) {
        scans.update { list -> list.map { if (it.gtin == gtin) it.copy(products = listOf(product), suggestions = emptyList()) else it } }
        viewModelScope.launch { basket.linkBarcode(gtin, product) }
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
                catch (_: Exception) { notify("Couldn’t save “${scan.products.firstOrNull()?.title ?: scan.label?.title ?: "the scanned item"}”") }
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

    private var chatJob: Job? = null
    /** A message that was not sent (it failed, or the user stopped it): the chat screen puts it back in the field. */
    val chatUnsent = MutableStateFlow<String?>(null)

    fun sendChat(text: String, voiceInput: Boolean = false) {
        if (_chatBusy.value || text.isBlank()) return
        stopSpeaking()
        if (!online.value) { notify("Couldn’t reach the assistant: no connection"); chatUnsent.value = text; return }
        _chatBusy.value = true
        chatJob = viewModelScope.launch {
            _chatSteps.value = listOf("Sending…")
            stepsJob?.cancel()
            stepsJob = viewModelScope.launch {
                while (true) {
                    delay(700)
                    runCatching { container.api.chatProgress(basket.currentBasketId.value) }.getOrNull()?.let { if (it.steps.isNotEmpty()) _chatSteps.value = it.steps }
                }
            }
            // Show the user's line immediately; the server returns both lines once the assistant is done.
            val localId = "local-${System.currentTimeMillis()}"
            _chat.update { it + ChatMessage(id = localId, role = "user", content = text) }
            var sent = false
            try {
                val reply = attempt("Couldn’t reach the assistant") { container.api.chatSend(basket.currentBasketId.value, text) }
                if (reply != null) {
                    sent = true
                    _chat.update { list -> list.filterNot { it.id.startsWith("local-") } + reply }
                    container.offline.save("chat-${basket.currentBasketId.value}", _chat.value)
                    if (voiceInput && settings.value?.speakReplies == true) {
                        reply.lastOrNull { it.role == "assistant" }?.let { speakMessage(it) }
                    }
                }
            } finally {
                // Failed or stopped: the line leaves the conversation and goes back to the field, so nothing is retyped.
                if (!sent) { _chat.update { list -> list.filterNot { it.id == localId } }; chatUnsent.value = text }
                stepsJob?.cancel()
                _chatSteps.value = emptyList()
                _chatBusy.value = false
            }
        }
    }

    /** Stops waiting for the assistant's answer; the message goes back to the field. */
    fun cancelChat() { chatJob?.cancel() }
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
            catch (_: Exception) { notify(if (preview) "Couldn’t play the voice" else "Couldn’t read the reply aloud") }
            finally { if (speechGeneration == generation) _speaking.value = null }
        }
    }

    fun clearChat() = viewModelScope.launch { basket.queued(nl.baskt.data.PendingOp(type = "chatClear", basketId = basket.currentBasketId.value), {
        _chat.value = emptyList(); container.offline.save("chat-${basket.currentBasketId.value}", _chat.value)
    }) }
    fun applyProposal(message: ChatMessage, indices: List<Int>) = viewModelScope.launch {
        val result = attempt("Couldn’t apply the changes") { container.api.applyProposal(message.id, indices) } ?: return@launch
        _chat.update { list -> list.map { if (it.id == message.id) result.message else it } }
        val failed = result.results.filter { !it.ok }
        notify(if (failed.isEmpty()) "Applied ${nl.baskt.ui.common.counted(result.results.size, "change")}" else "Applied ${nl.baskt.ui.common.counted(result.results.size - failed.size, "change")}, ${failed.size} couldn’t be applied")
        basket.refresh(false)
        if (indices.any { message.proposalJson?.changes?.getOrNull(it)?.type?.startsWith("stock_") == true }) basket.refreshStock()
    }

    private val _memory = MutableStateFlow<List<Choice>>(emptyList())
    val memory: StateFlow<List<Choice>> = _memory
    private val _memoryNames = MutableStateFlow<List<NamePreference>>(emptyList())
    val memoryNames: StateFlow<List<NamePreference>> = _memoryNames
    /** The learned preferences are being fetched, or could not be fetched and nothing is saved on the phone. */
    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading: StateFlow<Boolean> = _memoryLoading
    private val _memoryFailed = MutableStateFlow(false)
    val memoryFailed: StateFlow<Boolean> = _memoryFailed
    fun loadMemory() = viewModelScope.launch {
        _memoryLoading.value = true
        _memoryFailed.value = false
        _memory.value = (container.offline.load<List<Choice>>("memory") ?: emptyList()).filterNot { "choice-${it.id}" in memoryRemovals }
        _memoryNames.value = (container.offline.load<List<NamePreference>>("memory-names") ?: emptyList()).filterNot { "name-${it.id}" in memoryRemovals }
        val response = cachedLoad<nl.baskt.data.ChoicesResponse?>("memory-response", null) { container.api.memory() }
        _memoryLoading.value = false
        if (response == null) { _memoryFailed.value = true; return@launch }
        _memory.value = response.choices.filterNot { "choice-${it.id}" in memoryRemovals }; container.offline.save("memory", _memory.value)
        _memoryNames.value = response.names.filterNot { "name-${it.id}" in memoryRemovals }; container.offline.save("memory-names", _memoryNames.value)
    }

    /**
     * Learned preferences on their way out. The server cannot put one back, so removing hides the row at once and
     * only deletes it for real once the Undo notice has had its time; Undo before that simply shows it again.
     */
    private val memoryRemovals = mutableMapOf<String, Job>()
    private fun removeWithUndo(key: String, message: String, hide: () -> Unit, restore: () -> Unit, delete: suspend () -> Unit) {
        hide()
        // On the app's own scope: leaving the screen (or the app) must not cancel the delete that was asked for.
        memoryRemovals[key] = container.scope.launch { delay(10_000); memoryRemovals.remove(key); delete() }
        notifyUndo(message) { memoryRemovals.remove(key)?.let { it.cancel(); restore() } }
    }
    fun deleteNameMemory(id: String) {
        val index = _memoryNames.value.indexOfFirst { it.id == id }
        val name = _memoryNames.value.getOrNull(index) ?: return
        removeWithUndo("name-$id", "Removed “${name.preferred}”",
            hide = { _memoryNames.update { list -> list.filterNot { it.id == id } } },
            restore = { _memoryNames.update { list -> list.toMutableList().apply { add(index.coerceAtMost(size), name) } } },
        ) { basket.queued(nl.baskt.data.PendingOp(type = "nameDelete", itemId = id), {
            _memoryNames.update { list -> list.filterNot { it.id == id } }; container.offline.save("memory-names", _memoryNames.value)
        }) }
    }
    fun deleteMemory(id: String) {
        val index = _memory.value.indexOfFirst { it.id == id }
        val choice = _memory.value.getOrNull(index) ?: return
        removeWithUndo("choice-$id", "Removed what was learned for “${choice.itemText}”",
            hide = { _memory.update { list -> list.filterNot { it.id == id } } },
            restore = { _memory.update { list -> list.toMutableList().apply { add(index.coerceAtMost(size), choice) } } },
        ) { basket.queued(nl.baskt.data.PendingOp(type = "memoryDelete", itemId = id), {
            _memory.update { list -> list.filterNot { it.id == id } }; container.offline.save("memory", _memory.value)
        }) }
    }

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
        val product = card.product ?: card.productId?.let { id -> runCatching { container.api.searchProducts(card.title, card.store) }.getOrNull()?.results?.firstOrNull()?.products?.firstOrNull { it.id == id } }
        addedWithUndo(card.title) { if (product != null) basket.addFromProduct(product) else basket.add(card.title, 1) }
    }

    /** Adds a product to the basket and says so, with Undo (deal and Bonus Box product rows). */
    fun addProductWithUndo(product: Product) = viewModelScope.launch { addedWithUndo(product.title) { basket.addFromProduct(product) } }

    /** Runs an add and reports it with an Undo that deletes the item the add created. */
    private suspend fun addedWithUndo(title: String, add: suspend () -> Unit) {
        val before = basket.items.value.map { it.id }.toSet()
        add()
        val created = basket.items.value.lastOrNull { it.id !in before && !it.isGroup }
        if (created == null) { notify("Added “$title” to your basket"); return }
        notifyUndo("Added “$title” to your basket") {
            // The item gets a new id once the server has it, so fall back to the newest open item with the same text.
            val current = basket.items.value.firstOrNull { it.id == created.id } ?: basket.items.value.lastOrNull { !it.isGroup && it.isOpen && it.text == created.text }
            if (current != null) delete(current)
        }
    }

    /** Switches the item at that store to the promoted product; Undo goes back to the product it had. */
    fun takeDeal(deal: Deal) = viewModelScope.launch {
        val item = basket.items.value.firstOrNull { it.id == deal.itemId } ?: return@launch
        val shown = _deals.value
        basket.choose(item, deal.store, deal.product.id)
        _deals.value = _deals.value?.let { current -> current.copy(deals = current.deals.filterNot { it.itemId == deal.itemId && it.store == deal.store }) }
        notifyUndo("Switched “${deal.itemText}” to ${deal.product.title}") {
            viewModelScope.launch {
                basket.items.value.firstOrNull { it.id == deal.itemId }?.let { basket.choose(it, deal.store, deal.currentProductId) }
                _deals.value = shown
            }
        }
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
    fun refreshStock() = viewModelScope.launch {
        basket.refreshStock()
        basket.refreshServerSettings()
        repeat(10) {
            if (basket.stock.value.none { it.searchPending }) return@launch
            kotlinx.coroutines.delay(2_000)
            basket.refreshStock()
        }
    }
    fun addFromStock(item: StockItem) = viewModelScope.launch { basket.addFromStock(item) }
    fun addStock(text: String) = viewModelScope.launch {
        basket.addStock(text)
        // The server sorts free text into a category in the background; pick that up once it has had a moment.
        kotlinx.coroutines.delay(2_000); basket.refreshStock()
        kotlinx.coroutines.delay(6_000); basket.refreshStock()
    }
    fun addProductToStock(product: Product, barcode: String?) = viewModelScope.launch { basket.addProductToStock(product, barcode) }
    fun stockEntryFor(product: Product, barcode: String?): StockItem? = basket.stock.value.firstOrNull { it.productId == product.id || (barcode != null && it.barcode == barcode) }
    fun addToStockFromItem(item: BasketItem) = viewModelScope.launch { basket.addItemToStock(item) }

    /** Shopping mode: buying an item records it on today's trip, puts it in stock and takes it off the open list. */
    fun markBought(item: BasketItem, store: String, product: Product?) = viewModelScope.launch { basket.markBought(item, store, product) }
    fun unbuy(item: BasketItem) = viewModelScope.launch { basket.unbuy(item) }
    /** Picked up in the store without being on the list. */
    /** The whole code goes to the server: a weighed product's label also carries its weight and price. */
    /** [format] is set when the code should be kept, as read, for the checkout screen (the till relay). */
    fun scanItem(store: String, code: String, format: Int? = null) = viewModelScope.launch {
        if (nl.baskt.data.productGtin(code) == null) return@launch notify("That isn’t a product barcode")
        basket.scanItem(store, code.trim(), format = format)
    }
    val tripScans: StateFlow<List<TripScan>> = basket.tripScans
    private val removedTillCodes = mutableMapOf<String, nl.baskt.data.TillCode>()
    fun removeScan(scan: TripScan) = viewModelScope.launch {
        basket.tillCodes.value.firstOrNull { it.scanId == scan.id }?.let { removedTillCodes[scan.id] = it }
        basket.removeScan(scan)
    }
    /** Undo of [removeScan]: the same code, recorded at the time it was first scanned (and kept for the till again). */
    fun restoreScan(scan: TripScan) = viewModelScope.launch {
        val code = removedTillCodes.remove(scan.id)
        basket.scanItem(scan.store, code?.raw ?: scan.barcode, scan.at, code?.format)
    }

    /** AH in-store self-scan: the live AH basket for this trip (store Wi-Fi only). */
    val selfScan: StateFlow<nl.baskt.data.SelfScanState> = container.selfScan.state
    val tickedByBarcode: StateFlow<Map<String, String>> = basket.tickedByBarcode
    fun setSelfScan(enabled: Boolean) = viewModelScope.launch { container.settingsStore.saveSelfScan(enabled); if (!enabled) container.selfScan.stop() }
    fun setSelfScanWifi(name: String) = viewModelScope.launch { container.settingsStore.saveSelfScanWifi(name) }
    fun startSelfScan() = viewModelScope.launch {
        val current = container.awaitSettings()
        val card = current.loyaltyCards["AH"] ?: return@launch notify("Add your AH card in Settings first")
        container.selfScan.start(card, current.selfScanWifi)
    }
    fun stopSelfScan() = container.selfScan.stop()

    /**
     * A scan in AH shop mode while self-scan runs: AH gets it first, and only a code AH took goes on to baskt (ticking
     * the list item or adding an extra), so the two baskets never drift apart.
     */
    fun selfScanItem(store: String, code: String, format: Int? = null) = viewModelScope.launch {
        if (container.selfScan.add(code)) {
            // AH also takes codes that are not products (deposit slips, coupons); those stay off the baskt list.
            if (nl.baskt.data.productGtin(code) != null) scanItem(store, code, format)
            return@launch
        }
        val state = container.selfScan.state.value
        state.issues.lastOrNull { it.barcode == code.trim() }?.let { notify("AH didn’t take that code: ${it.title}") } ?: state.notice?.message?.let { notify(it) }
    }

    /** Quantity edit on an AH line; at 0 the line leaves the AH basket and baskt takes back what that scan did. */
    fun setSelfScanQuantity(store: String, barcode: String, quantity: Int) = viewModelScope.launch {
        if (!container.selfScan.setQuantity(barcode, quantity) || quantity > 0) return@launch
        val gtin = nl.baskt.data.productGtin(barcode) ?: barcode
        val extra = basket.tripScans.value.lastOrNull { it.store == store && it.barcode == gtin }
        if (extra != null) { removeScan(extra); return@launch }
        val ticked = basket.tickedByBarcode.value[gtin]?.let { id -> basket.items.value.firstOrNull { it.id == id && it.isBought } }
        if (ticked != null) { basket.unbuy(ticked); basket.tickedByBarcode.update { it - gtin } }
    }
    fun dismissSelfScanIssue(id: Long) = container.selfScan.dismissIssue(id)
    fun dismissSelfScanNotice() = container.selfScan.dismissNotice()
    fun refreshSelfScan() { container.selfScan.refresh() }
    fun selfScanCheckout() = container.selfScan.checkout()
    fun closeSelfScanCheckout() = container.selfScan.closeCheckout()

    val tillCodes: StateFlow<List<nl.baskt.data.TillCode>> = basket.tillCodes
    val barcodeKinds: StateFlow<List<nl.baskt.data.BarcodeKindEntry>> = basket.barcodeKinds
    fun loadBarcodeKinds(store: String) = viewModelScope.launch { basket.loadBarcodeKinds(store) }
    fun linkScan(scan: TripScan, item: BasketItem, store: String) = viewModelScope.launch { basket.linkScan(scan, item, store) }
    fun reportNoBarcode(store: String, item: BasketItem) = viewModelScope.launch { basket.reportBarcodeKind(store, item, code = null, kind = "none") }
    fun clearTillCodes(store: String) = basket.clearTillCodes(store)
    fun setTillRelay(enabled: Boolean) = viewModelScope.launch { container.settingsStore.saveTillRelay(enabled) }
    fun setTillRelayInterval(ms: Int) = viewModelScope.launch { container.settingsStore.saveTillRelayInterval(ms) }

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
    /**
     * Takes an item out of the archive and back into the trip at [store]. Unlike [buyAt] this does not depend on
     * the item's own state, so it also works as the undo of an archive swipe, where the copy in hand is stale.
     */
    fun restore(item: BasketItem, store: String) = viewModelScope.launch {
        basket.setChecked(item, false)
        basket.assignItem(item, store)
        compare(force = true)
    }

    /** Order step: buying at a store also brings a skipped item back into the trip. */
    fun buyAt(item: BasketItem, store: String) = viewModelScope.launch {
        if (item.isSkipped) basket.setChecked(item, false)
        basket.assignItem(item, store)
        compare(force = true)
    }
    fun setDealKindHidden(kind: String, hidden: Boolean) = viewModelScope.launch {
        val current = settings.value?.hiddenDealKinds ?: emptySet()
        container.settingsStore.saveHiddenDealKinds(if (hidden) current + kind else current - kind)
    }

    /** Saves a card number, or removes the card with null; a removal can be undone, since the number is tedious to retype. */
    fun saveLoyaltyCard(store: String, number: String?): Job = viewModelScope.launch {
        val previous = settings.value?.loyaltyCards?.get(store)
        container.settingsStore.saveLoyaltyCard(store, number)
        if (number == null && previous != null) notifyUndo("Removed the ${nl.baskt.ui.common.storeName(store, basket.stores.value)} card") { saveLoyaltyCard(store, previous) }
    }
    val lastAdded get() = basket.lastAdded
    fun consumeLastAdded() { basket.lastAdded.value = null }
    fun removeStock(item: StockItem) = viewModelScope.launch { basket.removeStock(item) }
    /** Undo of [removeStock]: the entry comes back with its picture, category and running-low mark. */
    fun restoreStock(item: StockItem) = viewModelScope.launch { basket.restoreStock(item) }
    fun moveStock(item: StockItem, category: String) = viewModelScope.launch { basket.updateStock(item, category = category) }
    /** Running low puts it on the basket and keeps it in stock until it is bought again. */
    fun setRunningLow(item: StockItem, low: Boolean) = viewModelScope.launch {
        basket.updateStock(item, runningLow = low)
        if (low && basket.items.value.none { !it.checked && it.text.equals(item.text, ignoreCase = true) }) basket.addFromStock(item)
    }
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
    /** Thumbs-downs still inside their Undo window, as "itemId:store:productId". The item screen shows these dimmed. */
    val pendingRejections = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Thumbs-down with a way back. A rejection teaches the matching and cannot be taken back afterwards, so it is
     * only sent once the Undo message has had its time on screen; until then the product just waits in [pendingRejections].
     */
    fun rejectWithUndo(item: BasketItem, store: String, product: Product) {
        val key = "${item.id}:$store:${product.id}"
        if (key in pendingRejections.value) return
        pendingRejections.update { it + key }
        val send = viewModelScope.launch {
            delay(10_500)
            basket.feedback(item, store, product.id, false)
            pendingRejections.update { it - key }
        }
        notifyUndo("Removed “${product.title}” from the suggestions") { send.cancel(); pendingRejections.update { it - key } }
    }
    fun searchMore(item: BasketItem, store: String, query: String) = busy(item, store) { basket.searchMore(item, store, query) }
    fun rematch(item: BasketItem) = viewModelScope.launch { basket.rematch(item) }
    fun setEnabledStores(codes: List<String>) = viewModelScope.launch { basket.setEnabledStores(codes); basket.refresh(false) }

    fun assign(mode: String) = viewModelScope.launch { basket.assign(mode); compare(force = true) }
    /** [assign] with a way back: it moves every open item at once, so Undo puts each one back at the store it had. */
    fun assignWithUndo(mode: String, message: String) = viewModelScope.launch {
        val before = basket.items.value.filter { !it.isGroup }.associate { it.id to it.assignedStore }
        basket.assign(mode)
        compare(force = true)
        notifyUndo(message) {
            viewModelScope.launch {
                for (item in basket.items.value) if (item.id in before && item.assignedStore != before[item.id]) basket.assignItem(item, before[item.id])
                compare(force = true)
            }
        }
    }
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
        if (refreshPrices) { attempt("Couldn’t refresh prices") { container.api.refreshPrices() }; basket.refresh(false) }
        basket.compare().let { _comparison.value = it; comparedFor = key }
        _comparing.value = false
    }

    suspend fun saveSettings(baseUrl: String, token: String) {
        basket.withSyncPaused {
            val changed = container.currentSettings.baseUrl != baseUrl || container.currentSettings.token != token
            require(!changed || pending.value.isEmpty()) { "Changes are still waiting to sync. Sync or discard them before changing the server." }
            container.settingsStore.save(baseUrl, token)
            container.awaitSettings()
            if (changed) { _chat.value = emptyList(); _purchases.value = emptyList(); _memory.value = emptyList(); _recipeDetail.value = null; _comparison.value = null; _spend.value = null; _purchaseDetail.value = null }
        }
    }

    /** The outcome of "Save and test": one plain line, plus a [note] when the server works but something is limited. */
    data class ConnectionCheck(val connected: Boolean, val message: String, val note: String? = null)

    /** Asks the saved server whether it is there and whether this token is accepted. */
    suspend fun testConnection(): ConnectionCheck = try {
        val health = container.api.health()
        val smart = health.ai?.get("configured")?.toString() == "true"
        ConnectionCheck(true, "Connected", if (smart) null else "Smart matching is off on this server")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        val reason = when {
            e is nl.baskt.data.ApiException && (e.status == 401 || e.status == 403) -> "the access token wasn’t accepted"
            e is java.net.UnknownHostException -> "that address wasn’t found"
            e is io.ktor.client.plugins.HttpRequestTimeoutException || e is io.ktor.client.network.sockets.ConnectTimeoutException || e is java.net.SocketTimeoutException -> "the server didn’t answer"
            else -> e.message?.takeIf { it.isNotBlank() }
        }
        ConnectionCheck(false, if (reason == null) "Couldn’t connect" else "Couldn’t connect: $reason")
    }
    override fun onCleared() { stopSpeaking(); super.onCleared() }

}
