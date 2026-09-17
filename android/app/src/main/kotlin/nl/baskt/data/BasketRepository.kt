package nl.baskt.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the basket state for the whole app. The backend is the source of truth; this class
 * refreshes it on demand and polls while any item is still being matched.
 */
class BasketRepository(
    private val api: BasktApi,
    private val scope: CoroutineScope,
    private val offline: OfflineStore? = null,
    private val onBasketSwitched: suspend (String) -> Unit = {},
    private val onQueued: () -> Unit = {},
) {
    private val _items = MutableStateFlow<List<BasketItem>>(emptyList())
    val items: StateFlow<List<BasketItem>> = _items

    /** False after a request failed to reach the server; flips back when one succeeds. */
    val online = MutableStateFlow(true)

    /**
     * Whether the server can transcribe speech itself (Whisper). Refreshed by every health check and
     * remembered across restarts, so the first dictation after a cold start already knows where to go.
     */
    val serverStt = MutableStateFlow(offline?.load<Boolean>("server-stt") ?: false)

    /** One health round trip: reports reachability and refreshes what the server can do. */
    private suspend fun checkHealth(): Boolean = runCatching { api.health() }
        .onSuccess { health ->
            val stt = (health.ai?.get("stt") as? kotlinx.serialization.json.JsonObject)?.get("configured")?.toString() == "true"
            if (stt != serverStt.value) { serverStt.value = stt; offline?.save("server-stt", stt) }
        }
        .isSuccess

    /** Changes waiting to be sent to the server. */
    val pending = MutableStateFlow<List<PendingOp>>(offline?.loadOps() ?: emptyList())
    private var replaying = false
    private val syncLock = kotlinx.coroutines.sync.Mutex()
    private var localVersion = 0L
    val revision: Long get() = localVersion

    private fun cacheItems() { offline?.save("items-${_currentBasketId.value}", _items.value) }
    private fun loadCachedItems() { offline?.load<List<BasketItem>>("items-${_currentBasketId.value}")?.let { _items.value = it } }

    /** Network-level failures (no route, timeout, DNS) mean "offline"; HTTP errors do not. */
    private fun isConnectivityError(e: Throwable): Boolean =
        e is java.io.IOException || e is io.ktor.client.plugins.HttpRequestTimeoutException || e is io.ktor.client.network.sockets.ConnectTimeoutException ||
            e is io.ktor.client.network.sockets.SocketTimeoutException || (e.cause != null && e.cause !== e && isConnectivityError(e.cause!!))

    private var probing = false

    /**
     * A failed request only means "offline" if a quick health probe fails too; one slow or aborted
     * call must not flip the whole app into offline mode.
     */
    private fun wentOffline() {
        if (!online.value || probing) return
        probing = true
        scope.launch {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(4000) { checkHealth() } == true
                if (!ok) online.value = false
            } finally {
                probing = false
            }
        }
    }

    /** Quick check used by the periodic monitor: true when the server answers. */
    suspend fun probe(): Boolean = kotlinx.coroutines.withTimeoutOrNull(4000) { checkHealth() } == true

    private fun enqueue(op: PendingOp) {
        localVersion++
        pending.update { it + op }
        offline?.saveOps(pending.value)
    }

    /** Persist intent before contacting the server. All writes share the ordered outbox. */
    suspend fun queued(op: PendingOp, optimistic: () -> Unit) {
        enqueue(op)
        optimistic()
        persistLocal()
        onQueued()
        scope.launch { if (online.value) tryReconnect() }
    }

    private fun persistLocal() {
        cacheItems()
        _baskets.update { list -> list.map { basket ->
            val items = if (basket.id == _currentBasketId.value) _items.value else offline?.load<List<BasketItem>>("items-${basket.id}")
            if (items == null) basket else basket.copy(itemCount = items.count { !it.isGroup }, openCount = items.count { !it.isGroup && !it.checked })
        } }
        offline?.save("stock", _stock.value)
        offline?.save("baskets", _baskets.value)
        offline?.save("settings", _serverSettings.value)
    }

    private fun localId() = "local-" + java.util.UUID.randomUUID().toString()

    /** Sends queued changes in order. Stops on failures and retains the operation for review or retry. */
    suspend fun replayQueue() {
        if (replaying || pending.value.isEmpty()) return
        replaying = true
        val idMap = (offline?.load<Map<String, String>>("id-map") ?: emptyMap()).toMutableMap()
        fun real(id: String?): String? = id?.let {
            val resolved = idMap[it] ?: it
            check(!resolved.startsWith("local-")) { "Waiting for an earlier creation to sync" }
            resolved
        }
        try {
            while (pending.value.isNotEmpty()) {
                val op = pending.value.first()
                val api = api.forOperation(op.id)
                try {
                    when (op.type) {
                        "add" -> {
                            val created = api.addItem(op.text ?: "", op.quantity ?: 1, real(op.basketId) ?: _currentBasketId.value, real(op.parentId))
                            op.itemId?.let { idMap[it] = created.id }
                        }
                        "addGroup" -> {
                            val created = api.addGroup(op.text ?: "", op.items, real(op.basketId) ?: _currentBasketId.value, op.recipeInfo)
                            op.itemId?.let { idMap[it] = created.group.id }
                            op.childIds?.zip(created.items)?.forEach { (local, remote) -> idMap[local] = remote.id }
                        }
                        "checked" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.updateItem(it, checked = op.checked) }
                        "quantity" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.updateItem(it, quantity = op.quantity) }
                        "rename" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.updateItem(it, text = op.text) }
                        "delete" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.deleteItem(it) }
                        "deleteMany" -> api.deleteItems((op.items ?: emptyList()).mapNotNull { real(it) }.filterNot { it.startsWith("local-") })
                        "clearChecked" -> api.clearChecked(real(op.basketId) ?: _currentBasketId.value)
                        "choose" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.choose(it, op.store ?: return@let, op.productId) }
                        "assign" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.assignItem(it, op.store) }
                        "assignMode" -> api.assign(real(op.basketId) ?: _currentBasketId.value, op.mode ?: "mix")
                        "transfer" -> {
                            val result = api.transferItem(real(op.itemId)!!, real(op.basketId)!!, op.copy == true)
                            if (op.copy == true) {
                                op.parentId?.let { idMap[it] = result.id }
                                op.childIds?.zip(result.children)?.forEach { (local, remote) -> idMap[local] = remote.id }
                            }
                        }
                        "stockAdd" -> {
                            val stock = op.stock
                            val created = api.addStock(op.text ?: "", stock?.quantityText, op.productId, stock?.imageUrl, stock?.barcode)
                            op.itemId?.let { idMap[it] = created.id }
                        }
                        "stockRemove" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.removeStock(it) }
                        "feedback" -> api.feedback(real(op.itemId)!!, op.store!!, op.productId!!, op.checked == true)
                        "unskip" -> api.unskip(real(op.itemId)!!, op.store!!)
                        "basketCreate" -> { val created = api.createBasket(op.text!!, op.emoji); idMap[op.itemId!!] = created.id }
                        "basketRename" -> api.renameBasket(real(op.itemId)!!, op.text!!, op.emoji)
                        "basketDelete" -> api.deleteBasket(real(op.itemId)!!)
                        "groupItems" -> { val created = api.groupFromItems(op.text!!, op.items!!.map { real(it)!! }); idMap[op.itemId!!] = created.group.id }
                        "productAdd" -> { val created = api.addFromProduct(op.productId!!, real(op.basketId)!!, real(op.parentId)); idMap[op.itemId!!] = created.id }
                        "skipStock" -> api.setSkipInStock(op.checked == true)
                        "servings" -> api.setDefaultServings(op.quantity)
                        "rankBy" -> api.setRankBy(op.text!!)
                        "stores" -> api.setEnabledStores(op.items!!)
                        "language" -> api.setLanguage(op.text!!)
                        "groupServings" -> api.setGroupServings(real(op.itemId)!!, op.quantity!!, op.scaledChildren?.map { it.copy(id = real(it.id)!!) }, op.recipeInfo)
                        "addSkipped" -> {
                            val children = api.addSkipped(real(op.itemId)!!)
                            op.childIds?.zip(children)?.forEach { (local, remote) -> idMap[local] = remote.id }
                        }
                        "recipeSave" -> { val created = api.saveMyRecipe(op.draft!!, op.mode ?: "manual"); idMap[op.itemId!!] = created.id }
                        "recipeUpdate" -> api.updateMyRecipe(real(op.itemId)!!, op.draft!!)
                        "recipeDelete" -> api.deleteMyRecipe(real(op.itemId)!!)
                        "favouriteAdd" -> {
                            val recipe = op.recipe!!
                            val url = if (recipe.url.startsWith("baskt://recipe/")) "baskt://recipe/${real(recipe.url.removePrefix("baskt://recipe/"))}" else recipe.url
                            val created = api.addRecipeFavourite(recipe.copy(url = url)); idMap[op.itemId!!] = created.id
                        }
                        "favouriteDelete" -> api.removeRecipeFavourite(real(op.itemId)!!)
                        "purchaseSave" -> {
                            val scan = op.receipt!!
                            val created = api.savePurchase(op.store!!, scan.purchasedAt, scan.totalCents, scan.lines)
                            idMap[op.itemId!!] = created.purchase.id
                        }
                        "purchaseDelete" -> api.deletePurchase(real(op.itemId)!!)
                        "memoryDelete" -> api.deleteMemory(op.itemId!!)
                        "chatClear" -> api.chatClear(real(op.basketId)!!)
                        else -> error("Unknown queued action: ${op.type}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (isConnectivityError(e)) { online.value = false; return }
                    // Deleting an already absent object is successful; every other failure stays reviewable.
                    if (!(e is ApiException && e.status == 404 && op.type in setOf("delete", "stockRemove", "basketDelete", "recipeDelete", "favouriteDelete", "purchaseDelete", "memoryDelete"))) {
                        pending.update { list -> list.map { if (it.id == op.id) it.copy(failure = e.message ?: "Sync failed") else it } }
                        offline?.saveOps(pending.value)
                        _error.value = "Could not sync ${op.label}: ${e.message}"
                        return
                    }
                }
                offline?.save("id-map", idMap)
                pending.update { list -> list.filterNot { it.id == op.id } }
                offline?.saveOps(pending.value)
            }
        } finally {
            replaying = false
        }
    }

    /** Called when the network comes back (or on demand): replay the queue, then reload everything. */
    suspend fun tryReconnect() {
        if (!syncLock.tryLock()) return
        try {
            if (!probe()) { online.value = false; return }
            online.value = true
            replayQueue()
            if (pending.value.isNotEmpty()) return
            val ids = offline?.load<Map<String, String>>("id-map") ?: emptyMap()
            ids[_currentBasketId.value]?.let { _currentBasketId.value = it; onBasketSwitched(it) }
            refreshBaskets(); refreshStores(); refreshStock(); refreshServerSettings(); refresh(false)
            // Download every basket, not just the one open when connectivity disappears.
            for (basket in _baskets.value) {
                if (pending.value.isNotEmpty()) return
                val version = revision
                val response = api.basket(basket.id)
                if (pending.value.isNotEmpty() || version != revision) return
                offline?.save("items-${basket.id}", response.items)
            }
            recipeLibrary?.refresh()
            warmHistory()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (isConnectivityError(e)) online.value = false else _error.value = e.message }
        finally { syncLock.unlock() }
    }

    private var historyWarmedAt = 0L
    private suspend fun warmHistory() {
        if (offline == null || pending.value.isNotEmpty() || System.currentTimeMillis() - historyWarmedAt < 60_000) return
        val version = revision
        val purchases = api.purchases()
        if (pending.value.isNotEmpty() || version != revision) return
        offline.save("purchases", purchases)
        val spend = api.spendSummary()
        if (pending.value.isNotEmpty() || version != revision) return
        offline.save("spend", spend)
        val memory = api.memory()
        if (pending.value.isNotEmpty() || version != revision) return
        offline.save("memory", memory)
        for (purchase in purchases) {
            if (pending.value.isNotEmpty()) return
            if (offline.load<PurchaseDetail>("purchase-${purchase.id}") == null) offline.save("purchase-${purchase.id}", api.purchase(purchase.id))
        }
        for (basket in _baskets.value) {
            if (pending.value.isNotEmpty()) return
            val chat = api.chatHistory(basket.id)
            if (pending.value.isNotEmpty() || version != revision) return
            offline.save("chat-${basket.id}", chat)
        }
        historyWarmedAt = System.currentTimeMillis()
    }

    suspend fun <T> withSyncPaused(block: suspend () -> T): T {
        syncLock.lock()
        try { return block() } finally { syncLock.unlock() }
    }

    private val _stores = MutableStateFlow<List<StoreInfo>>(emptyList())
    val stores: StateFlow<List<StoreInfo>> = _stores

    private val _baskets = MutableStateFlow<List<Basket>>(emptyList())
    val baskets: StateFlow<List<Basket>> = _baskets

    private val _currentBasketId = MutableStateFlow("default")
    val currentBasketId: StateFlow<String> = _currentBasketId

    private val _stock = MutableStateFlow<List<StockItem>>(emptyList())
    val stock: StateFlow<List<StockItem>> = _stock

    private val _serverSettings = MutableStateFlow(SettingsResponse())
    val serverSettings: StateFlow<SettingsResponse> = _serverSettings

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        // Show what we last saw before any network call completes.
        offline?.let { store ->
            store.load<List<Basket>>("baskets")?.let { _baskets.value = it }
            store.load<List<StoreInfo>>("stores")?.let { _stores.value = it }
            store.load<List<StockItem>>("stock")?.let { _stock.value = it }
            store.load<SettingsResponse>("settings")?.let { _serverSettings.value = it }
        }
    }

    private var pollJob: Job? = null

    val enabledStores: List<StoreInfo> get() = _stores.value.filter { it.enabled }

    suspend fun refreshStock() = guard {
        if (pending.value.isNotEmpty()) return@guard
        val version = revision
        val stock = api.stock()
        if (pending.value.isEmpty() && version == revision) { _stock.value = stock; offline?.save("stock", stock) }
    }
    private suspend fun stockAdd(stock: StockItem) {
        val existing = _stock.value.firstOrNull { it.text.equals(stock.text, ignoreCase = true) || (stock.productId != null && it.productId == stock.productId) }
        val local = stock.copy(id = existing?.id ?: stock.id)
        queued(PendingOp(type = "stockAdd", itemId = local.id, text = local.text, productId = local.productId, stock = local), {
            _stock.update { list -> list.filterNot { it.id == local.id } + local }
        })
    }
    suspend fun addStock(text: String) = stockAdd(StockItem(id = localId(), text = text, canonical = text.lowercase()))
    suspend fun addItemToStock(item: BasketItem, product: Product? = item.anyProduct) = stockAdd(StockItem(
        id = localId(), text = item.parsed?.canonicalName ?: item.text, quantityText = product?.quantityText, productId = product?.id, imageUrl = product?.imageUrl,
    ))
    suspend fun addProductToStock(product: Product, barcode: String?) = stockAdd(StockItem(
        id = localId(), text = product.title, quantityText = product.quantityText, productId = product.id, imageUrl = product.imageUrl, barcode = barcode,
    ))
    suspend fun removeStock(item: StockItem) {
        queued(PendingOp(type = "stockRemove", itemId = item.id), optimistic = { _stock.update { list -> list.filterNot { it.id == item.id } } })
    }
    suspend fun refreshServerSettings() = guard {
        if (pending.value.isNotEmpty()) return@guard
        val version = revision
        val settings = api.serverSettings()
        if (pending.value.isEmpty() && version == revision) { _serverSettings.value = settings; offline?.save("settings", settings) }
    }
    suspend fun setSkipInStock(enabled: Boolean) = queued(PendingOp(type = "skipStock", checked = enabled), { _serverSettings.value = _serverSettings.value.copy(recipeSkipInStock = enabled) })
    suspend fun setDefaultServings(servings: Int?) = queued(PendingOp(type = "servings", quantity = servings), { _serverSettings.value = _serverSettings.value.copy(defaultServings = servings) })
    suspend fun setRankBy(rankBy: String) = queued(PendingOp(type = "rankBy", text = rankBy), { _serverSettings.value = _serverSettings.value.copy(rankBy = rankBy) })
    suspend fun setLanguage(language: String) = queued(PendingOp(type = "language", text = language), { _serverSettings.value = _serverSettings.value.copy(language = language) })
    suspend fun setGroupServings(group: BasketItem, servings: Int) {
        val info = _items.value.firstOrNull { it.id == group.id }?.recipe ?: return
        val base = info.currentServings ?: info.baseServings ?: info.servings?.toDoubleOrNull() ?: 1.0
        val factor = servings / base
        val children = _items.value.filter { it.parentId == group.id }.map { child ->
            val hint = child.parsed?.sizeHint
            val text = if (hint != null) "${scaleIngredient(hint.amount.toString(), factor)} ${hint.unit} ${child.parsed.canonicalName}" else scaleIngredient(child.text, factor)
            child.copy(text = text, parsed = child.parsed?.copy(sizeHint = hint?.copy(amount = hint.amount * factor)))
        }
        val recipe = info.copy(currentServings = servings.toDouble(), baseServings = servings.toDouble(), servings = servings.toString(),
            ingredientLines = info.ingredientLines.map { scaleIngredient(it, factor) }, skipped = info.skipped.map { it.copy(text = scaleIngredient(it.text, factor)) })
        queued(PendingOp(type = "groupServings", itemId = group.id, quantity = servings, recipeInfo = recipe, scaledChildren = children.map { ScaledIngredient(it.id, it.text, it.quantity) }), {
            val byId = children.associateBy { it.id }
            _items.update { list -> list.map { if (it.id == group.id) it.copy(recipe = recipe) else byId[it.id] ?: it } }
        })
    }
    suspend fun addSkipped(group: BasketItem) {
        val children = group.recipe?.skipped.orEmpty().map { localItem(it.text, it.quantity, group.id) }
        queued(PendingOp(type = "addSkipped", itemId = group.id, childIds = children.map { it.id }), {
            _items.update { list -> list.map { if (it.id == group.id) it.copy(recipe = it.recipe?.copy(skipped = emptyList())) else it } + children }
        })
    }

    suspend fun refreshBaskets() {
        if (!online.value) return
        try {
            if (pending.value.isNotEmpty()) return
            val version = revision
            val list = api.baskets()
            if (pending.value.isNotEmpty() || version != revision) return
            _baskets.value = list
            offline?.save("baskets", list)
            online.value = true
            if (list.none { it.id == _currentBasketId.value } && list.isNotEmpty()) _currentBasketId.value = list.first().id
        } catch (e: Exception) {
            if (isConnectivityError(e)) wentOffline() else _error.value = e.message ?: "Could not load baskets"
        }
    }

    suspend fun switchBasket(id: String) {
        if (_currentBasketId.value == id) return
        _currentBasketId.value = id
        _items.value = emptyList()
        loadCachedItems()
        onBasketSwitched(id)
        refresh()
    }

    fun restoreBasket(id: String) {
        if (_currentBasketId.value != id) {
            _currentBasketId.value = id
            if (_items.value.isEmpty()) loadCachedItems()
        } else if (_items.value.isEmpty()) loadCachedItems()
    }

    suspend fun createBasket(name: String, emoji: String?): Basket {
        val basket = Basket(localId(), name, emoji)
        queued(PendingOp(type = "basketCreate", itemId = basket.id, text = name, emoji = emoji), { _baskets.update { it + basket } })
        return basket
    }
    suspend fun renameBasket(id: String, name: String, emoji: String?) = queued(PendingOp(type = "basketRename", itemId = id, text = name, emoji = emoji), {
        _baskets.update { list -> list.map { if (it.id == id) it.copy(name = name, emoji = emoji) else it } }
    })
    suspend fun deleteBasket(id: String) {
        if (_baskets.value.size <= 1) { _error.value = "Keep at least one basket"; return }
        queued(PendingOp(type = "basketDelete", itemId = id), { _baskets.update { list -> list.filterNot { it.id == id } } })
        if (_currentBasketId.value == id) switchBasket(_baskets.value.first().id)
    }

    suspend fun shareLink(): ShareLink? = guard { api.shareLink(_currentBasketId.value) }

    suspend fun groupFromItems(text: String, items: List<BasketItem>) {
        val group = localItem(text, 1, null, "group")
        val ids = items.map { it.id }
        queued(PendingOp(type = "groupItems", itemId = group.id, text = text, items = ids), {
            _items.update { list -> list.map { if (it.id in ids) it.copy(parentId = group.id) else it } + group }
        })
    }

    suspend fun deleteMany(items: List<BasketItem>) {
        val ids = items.map { it.id }.toSet()
        val remove = { _items.update { list -> list.filterNot { it.id in ids || it.parentId in ids } } }
        queued(PendingOp(type = "deleteMany", items = ids.toList()), optimistic = remove)
    }

    suspend fun transferMany(items: List<BasketItem>, basketId: String, copy: Boolean) {
        for (item in items) transfer(item, basketId, copy)
    }

    suspend fun setCheckedMany(items: List<BasketItem>, checked: Boolean) {
        for (item in items) setChecked(item, checked)
        if (online.value) refresh(false)
    }

    suspend fun transfer(item: BasketItem, basketId: String, copy: Boolean) {
        val children = _items.value.filter { it.parentId == item.id }
        val target = item.copy(id = if (copy) localId() else item.id, basketId = basketId, parentId = null, checked = if (copy) false else item.checked)
        val targetChildren = children.map { it.copy(id = if (copy) localId() else it.id, basketId = basketId, parentId = target.id, checked = if (copy) false else it.checked) }
        queued(PendingOp(type = "transfer", itemId = item.id, basketId = basketId, copy = copy, parentId = if (copy) target.id else null, childIds = targetChildren.map { it.id }), {
            val destination = if (basketId == _currentBasketId.value) _items.value else offline?.load<List<BasketItem>>("items-$basketId") ?: emptyList()
            val next = destination.filterNot { it.id == target.id || it.parentId == target.id } + target + targetChildren
            if (basketId == _currentBasketId.value) _items.value = next
            else {
                offline?.save("items-$basketId", next)
                if (!copy) _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } }
            }
        })
    }

    suspend fun refresh(showSpinner: Boolean = true) {
        if (!online.value || pending.value.isNotEmpty()) { if (_items.value.isEmpty()) loadCachedItems(); return }
        if (showSpinner && _items.value.isEmpty()) _loading.value = true
        try {
            val basketId = _currentBasketId.value
            val version = revision
            val response = api.basket(basketId)
            if (response.basketId != _currentBasketId.value || version != revision) return
            online.value = true
            if (pending.value.isNotEmpty()) return
            _items.value = response.items
            cacheItems()
            if (response.processing || response.items.any { it.isProcessing }) startPolling()
            _error.value = null
        } catch (e: Exception) {
            if (isConnectivityError(e)) {
                wentOffline()
                if (_items.value.isEmpty()) loadCachedItems()
            } else _error.value = e.message ?: "Could not reach the server"
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshStores() {
        if (!online.value) return
        try {
            if (pending.value.isNotEmpty()) return
            val version = revision
            val stores = api.stores()
            if (pending.value.isNotEmpty() || version != revision) return
            _stores.value = stores
            offline?.save("stores", _stores.value)
        } catch (e: Exception) {
            if (isConnectivityError(e)) wentOffline() else _error.value = e.message ?: "Could not load stores"
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var attempts = 0
            while (isActive && attempts < 60) {
                delay(1500)
                attempts += 1
                val version = revision
                val response = runCatching { api.basket(_currentBasketId.value) }.getOrNull() ?: continue
                if (response.basketId != _currentBasketId.value) continue
                if (pending.value.isNotEmpty() || version != revision) continue
                _items.value = response.items
                cacheItems()
                if (!response.processing && response.items.none { it.isProcessing }) break
            }
        }
    }

    private fun replace(item: BasketItem) {
        _items.update { list -> if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item }
        if (item.isProcessing) startPolling()
        cacheItems()
        countsDirty()
    }

    private var countsJob: Job? = null

    /** Basket counts come from the server; re-read them shortly after any change (debounced). */
    private fun countsDirty() {
        countsJob?.cancel()
        countsJob = scope.launch {
            delay(600)
            if (pending.value.isEmpty()) refreshBaskets()
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        if (!online.value) throw java.io.IOException("Server unavailable")
        block().also { _error.value = null; online.value = true }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (isConnectivityError(e)) {
            wentOffline()
            _error.value = "You're offline — this needs the server"
        } else _error.value = e.message ?: "Request failed"
        null
    }

    private fun localItem(text: String, quantity: Int, parentId: String?, kind: String = "item") = BasketItem(
        id = localId(), basketId = _currentBasketId.value, kind = kind, parentId = parentId, text = text, quantity = quantity,
        status = "QUEUED", sortOrder = (_items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
    )

    /** Last item created online; the UI uses it to explain "already in stock" once. */
    val lastAdded = MutableStateFlow<BasketItem?>(null)

    suspend fun add(text: String, quantity: Int, parentId: String? = null) {
        val local = localItem(text, quantity, parentId)
        queued(PendingOp(type = "add", text = text, quantity = quantity, parentId = parentId, basketId = _currentBasketId.value, itemId = local.id), optimistic = { _items.update { it + local } })
    }

    suspend fun addGroup(text: String, items: List<String>? = null, recipe: RecipeInfo? = null) {
        val local = localItem(text, 1, null, kind = "group").copy(recipe = recipe)
        val children = (items ?: emptyList()).map { localItem(it, 1, local.id) }
        queued(PendingOp(type = "addGroup", text = text, recipeInfo = recipe, items = items ?: emptyList(), childIds = children.map { it.id }, basketId = _currentBasketId.value, itemId = local.id), optimistic = {
            _items.update { list -> list + local + children }
        })
    }

    suspend fun addFromText(text: String) {
        text.split(Regex("[\\n,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it, 1) }
    }

    private fun patchLocal(id: String, transform: (BasketItem) -> BasketItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    suspend fun setChecked(item: BasketItem, checked: Boolean) {
        val optimistic = {
            patchLocal(item.id) { it.copy(checked = checked) }
            if (item.isGroup) _items.update { list -> list.map { if (it.parentId == item.id) it.copy(checked = checked) else it } }
        }
        queued(PendingOp(type = "checked", itemId = item.id, checked = checked), optimistic = optimistic)
    }

    suspend fun setQuantity(item: BasketItem, quantity: Int) {
        queued(PendingOp(type = "quantity", itemId = item.id, quantity = quantity), optimistic = { patchLocal(item.id) { it.copy(quantity = quantity) } })
    }

    suspend fun rename(item: BasketItem, text: String) {
        queued(PendingOp(type = "rename", itemId = item.id, text = text), optimistic = { patchLocal(item.id) { it.copy(text = text) } })
    }

    suspend fun delete(item: BasketItem) {
        val remove = { _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } } }
        queued(PendingOp(type = "delete", itemId = item.id), optimistic = remove)
    }

    suspend fun clearChecked() = deleteMany(_items.value.filter { it.checked })

    suspend fun choose(item: BasketItem, store: String, productId: String?) {
        queued(PendingOp(type = "choose", itemId = item.id, store = store, productId = productId), optimistic = {
            patchLocal(item.id) { current ->
                current.copy(matches = current.matches.map { match ->
                    if (match.store != store) match
                    else if (productId == null) match.copy(status = "NONE", chosen = null, provisional = null, chosenBy = "USER")
                    else match.copy(status = "CHOSEN", chosen = match.options.firstOrNull { it.id == productId } ?: match.effective, chosenBy = "USER")
                })
            }
        })
    }

    suspend fun resetRejections(item: BasketItem, store: String) = guard { replace(api.resetRejections(item.id, store)) }
    suspend fun reject(item: BasketItem, store: String) = guard { replace(api.reject(item.id, store)) }
    suspend fun unskip(item: BasketItem, store: String) = queued(PendingOp(type = "unskip", itemId = item.id, store = store), {
        patchLocal(item.id) { it.copy(matches = it.matches.map { match -> if (match.store == store) match.copy(status = "PENDING") else match }) }
    })

    suspend fun feedback(item: BasketItem, store: String, productId: String, up: Boolean) {
        queued(PendingOp(type = "feedback", itemId = item.id, store = store, productId = productId, checked = up), {
            patchLocal(item.id) { current -> current.copy(matches = current.matches.map { match ->
                if (match.store != store) match else if (up) match.copy(chosen = match.options.firstOrNull { it.id == productId } ?: match.effective, chosenBy = "USER", status = "CHOSEN")
                else {
                    val options = match.options.filterNot { it.id == productId }
                    val chosen = match.chosen?.takeUnless { it.id == productId }
                    match.copy(options = options, chosen = chosen, provisional = match.provisional?.takeUnless { it.id == productId } ?: options.firstOrNull(),
                        status = if (chosen != null) "CHOSEN" else if (options.isEmpty()) "EXHAUSTED" else "PENDING")
                }
            }) }
        })
    }

    suspend fun searchMore(item: BasketItem, store: String, query: String) = guard { replace(api.searchMore(item.id, store, query)) }

    suspend fun rematch(item: BasketItem) = guard { replace(api.rematch(item.id)) }

    suspend fun compare(): Comparison = localComparison(_items.value, _stores.value, _serverSettings.value.rankBy ?: "price")

    suspend fun assign(mode: String) {
        queued(PendingOp(type = "assignMode", basketId = _currentBasketId.value, mode = mode), optimistic = {
            val preferred = mode.removePrefix("store:").takeIf { mode.startsWith("store:") }
            _items.update { list -> list.map { item ->
                if (item.isGroup || item.checked) item else {
                    val available = item.matches.filter { it.status != "NONE" && it.effective != null }
                    val store = when {
                        mode == "clear" -> null
                        preferred != null && available.any { it.store == preferred } -> preferred
                        else -> available.minByOrNull { it.effective!!.priceCents }?.store
                    }
                    item.copy(assignedStore = store)
                }
            } }
        })
    }
    suspend fun assignItem(item: BasketItem, store: String?) {
        queued(PendingOp(type = "assign", itemId = item.id, store = store), optimistic = { patchLocal(item.id) { it.copy(assignedStore = store) } })
    }

    private suspend inline fun <reified T> cachedRead(key: String, fetch: () -> T): T? {
        if (!online.value) return offline?.load<T>(key)
        return try { fetch().also { offline?.save(key, it) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (isConnectivityError(e)) wentOffline() else _error.value = e.message; offline?.load<T>(key) }
    }

    suspend fun searchProducts(query: String, force: Boolean = false): ProductSearchResponse? = cachedRead("product-search-${query.trim().lowercase()}") { api.searchProducts(query, force = force) }

    /** The same or the closest product at every enabled store; the last answer is kept for offline viewing. */
    suspend fun similarProducts(product: Product): SimilarResponse? = cachedRead("similar-${product.id}") { api.similarProducts(product.id) }

    /** Thumbs on a similar-product pairing; the server drops rejected pairings and pins confirmed ones next time. */
    suspend fun similarFeedback(reference: Product, product: Product, up: Boolean?): Boolean = guard { api.similarFeedback(reference.id, product.id, up) } != null

    suspend fun deals(live: Boolean): DealsResponse? = guard { api.deals(_currentBasketId.value, live) }

    suspend fun interpret(text: String): List<VoiceItem>? = guard { api.interpret(text) }

    /**
     * Transcribes a recording on the server. Null means the caller should dictate on the phone instead:
     * either no provider answered or this server has none configured (then stop asking it).
     */
    suspend fun dictate(audio: ByteArray, language: String): DictateResponse? {
        if (!online.value) return null
        return try {
            api.dictate(audio, language)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isConnectivityError(e)) wentOffline()
            if (e is ApiException && e.status == 503) { serverStt.value = false; offline?.save("server-stt", false) }
            null
        }
    }

    suspend fun findDuplicates(): DedupeResponse? = guard { api.findDuplicates(_currentBasketId.value) }

    /**
     * Applies the user's decisions through the normal item operations, so they queue offline like any edit.
     * Merging keeps the entry whose products are furthest along (a user's pick beats suggestions beats nothing)
     * so no confirmed choice is thrown away, and gives it the summed quantity.
     */
    suspend fun resolveDuplicates(decisions: List<Pair<DuplicateGroup, DuplicateResolution>>) {
        val current = _items.value
        val toDelete = mutableListOf<BasketItem>()
        for ((group, resolution) in decisions) {
            val members = group.items.mapNotNull { member -> current.firstOrNull { it.id == member.id } }
            if (members.size < 2) continue
            when (resolution) {
                DuplicateResolution.KeepAll -> continue
                is DuplicateResolution.Keep -> toDelete += members.filter { it.id != resolution.id }
                DuplicateResolution.Merge -> {
                    val keep = members.maxWith(compareBy<BasketItem>({ item -> item.matches.count { it.chosenBy == "USER" } }, { item -> item.matches.count { it.effective != null } }, { -it.createdAt }))
                    val total = members.sumOf { it.quantity.coerceAtLeast(1) }
                    if (keep.quantity != total) setQuantity(keep, total)
                    toDelete += members.filter { it.id != keep.id }
                }
            }
        }
        if (toDelete.isNotEmpty()) deleteMany(toDelete)
    }

    suspend fun confirm(items: List<VoiceItem>) {
        for (item in items.filter { it.wanted }) { if (item.kind == "group") addGroup(item.text) else add(item.text, item.quantity) }
    }

    suspend fun addGroupFromUrl(recipeUrl: String, language: String) {
        val recipe = recipeLibrary?.detail(recipeUrl)
        if (recipe == null) { _error.value = "Open this recipe online to download its ingredients first"; return }
        val source = recipe.original ?: recipe
        val texts = listOf(source.title) + source.ingredientLines + source.steps.map { it.text } + listOf(source.servings.orEmpty(), source.totalTime.orEmpty(), source.description.orEmpty())
        val descriptionIndices = listOf(texts.lastIndex)
        val key = "translation-v2-$language-${descriptionIndices.joinToString(",")}-${texts.joinToString("\u0000")}"
        val translated = offline?.load<TranslationResponse>(key) ?: try {
            api.translate(texts, language, descriptionIndices).also {
                if (it.translated && it.texts.size == texts.size) offline?.save(key, it)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        val title = translated?.takeIf { it.translated }?.texts?.firstOrNull() ?: recipe.title
        addGroup(title, recipe.ingredientLines, RecipeInfo(recipe.title, recipe.sourceUrl, recipe.servings, recipe.ingredientLines,
            baseServings = recipe.servings?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull(),
            currentServings = recipe.servings?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull(), imageUrl = recipe.imageUrl, steps = recipe.steps))
    }

    var recipeLibrary: RecipeLibrary? = null

    suspend fun barcode(gtin: String): BarcodeResponse? = cachedRead("barcode-$gtin") { api.barcode(gtin) }

    suspend fun addFromProduct(product: Product, parentId: String? = null) {
        val local = localItem(product.title, 1, parentId).copy(matches = listOf(StoreMatch(product.store, "CHOSEN", chosenBy = "USER", chosen = product)))
        queued(PendingOp(type = "productAdd", itemId = local.id, productId = product.id, basketId = _currentBasketId.value, parentId = parentId), { _items.update { it + local } })
    }

    suspend fun setEnabledStores(codes: List<String>) = queued(PendingOp(type = "stores", items = codes), {
        _stores.update { list -> list.map { it.copy(enabled = it.code in codes) } }
        _serverSettings.value = _serverSettings.value.copy(enabledStores = codes)
        offline?.save("stores", _stores.value)
    })

    fun reloadLocal() {
        localVersion++
        pollJob?.cancel()
        countsJob?.cancel()
        historyWarmedAt = 0L
        online.value = true
        pending.value = offline?.loadOps() ?: emptyList()
        _baskets.value = offline?.load("baskets") ?: emptyList()
        _stores.value = offline?.load("stores") ?: emptyList()
        _stock.value = offline?.load("stock") ?: emptyList()
        _serverSettings.value = offline?.load("settings") ?: SettingsResponse()
        _items.value = emptyList()
        loadCachedItems()
    }

    fun discard(op: PendingOp) {
        if (replaying) { _error.value = "Wait for syncing to finish before discarding a change"; return }
        val removedIds = mutableSetOf<String>()
        fun creations(change: PendingOp) {
            if (change.type in setOf("add", "addGroup", "groupItems", "productAdd", "basketCreate", "stockAdd", "recipeSave", "favouriteAdd", "purchaseSave")) change.itemId?.let { removedIds.add(it) }
            if (change.type == "transfer" && change.copy == true) change.parentId?.let { removedIds.add(it) }
            if (change.type in setOf("addGroup", "addSkipped") || (change.type == "transfer" && change.copy == true)) removedIds.addAll(change.childIds.orEmpty())
        }
        creations(op)
        val kept = mutableListOf<PendingOp>()
        for (change in pending.value) {
            val depends = change.itemId in removedIds || change.parentId in removedIds || change.basketId in removedIds ||
                change.items.orEmpty().any { it in removedIds } || change.recipe?.url?.removePrefix("baskt://recipe/") in removedIds
            if (change.id == op.id || depends) creations(change) else kept.add(change)
        }
        localVersion++
        pending.value = kept
        offline?.saveOps(kept)
        _items.update { list -> list.filterNot { it.id in removedIds || it.parentId in removedIds } }
        _stock.update { list -> list.filterNot { it.id in removedIds } }
        for (basket in _baskets.value) {
            val cached = offline?.load<List<BasketItem>>("items-${basket.id}") ?: continue
            offline.save("items-${basket.id}", cached.filterNot { it.id in removedIds || it.parentId in removedIds })
        }
        _baskets.update { list -> list.filterNot { it.id in removedIds } }
        if (_currentBasketId.value in removedIds) {
            _currentBasketId.value = _baskets.value.firstOrNull()?.id ?: "default"
            _items.value = emptyList()
            loadCachedItems()
            scope.launch { onBasketSwitched(_currentBasketId.value) }
        }
        offline?.load<List<Purchase>>("purchases")?.let { purchases -> offline.save("purchases", purchases.filterNot { it.id in removedIds }) }
        recipeLibrary?.discardLocal(removedIds)
        persistLocal()
        _error.value = "Discarded change and dependent creations. Server data will refresh when connected."
        scope.launch { if (online.value) tryReconnect() }
    }

    fun clearError() {
        _error.value = null
    }
}
