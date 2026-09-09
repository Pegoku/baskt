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
) {
    private val _items = MutableStateFlow<List<BasketItem>>(emptyList())
    val items: StateFlow<List<BasketItem>> = _items

    /** False after a request failed to reach the server; flips back when one succeeds. */
    val online = MutableStateFlow(true)

    /** Changes waiting to be sent to the server. */
    val pending = MutableStateFlow<List<PendingOp>>(offline?.loadOps() ?: emptyList())
    private var replaying = false

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
                val ok = kotlinx.coroutines.withTimeoutOrNull(4000) { runCatching { api.health() }.isSuccess } == true
                if (!ok) online.value = false
            } finally {
                probing = false
            }
        }
    }

    /** Quick check used by the periodic monitor: true when the server answers. */
    suspend fun probe(): Boolean = kotlinx.coroutines.withTimeoutOrNull(4000) { runCatching { api.health() }.isSuccess } == true

    private fun enqueue(op: PendingOp) {
        pending.update { it + op }
        offline?.saveOps(pending.value)
    }

    /**
     * Runs a server call; when the server is unreachable the optimistic local change is applied instead and
     * the operation is queued for later. Returns true when it ran online.
     */
    private suspend fun queued(op: PendingOp, optimistic: () -> Unit, block: suspend () -> Unit): Boolean = try {
        block()
        online.value = true
        _error.value = null
        true
    } catch (e: Exception) {
        if (isConnectivityError(e)) {
            wentOffline()
            optimistic()
            enqueue(op)
            cacheItems()
            offline?.save("stock", _stock.value)
        } else {
            _error.value = e.message ?: "Request failed"
        }
        false
    }

    private fun localId() = "local-" + java.util.UUID.randomUUID().toString()

    /** Sends queued changes in order. Stops at the first connectivity failure; server errors drop the op. */
    suspend fun replayQueue() {
        if (replaying || pending.value.isEmpty()) return
        replaying = true
        val idMap = mutableMapOf<String, String>()
        fun real(id: String?) = id?.let { idMap[it] ?: it }
        try {
            while (pending.value.isNotEmpty()) {
                val op = pending.value.first()
                try {
                    when (op.type) {
                        "add" -> {
                            val created = api.addItem(op.text ?: "", op.quantity ?: 1, op.basketId ?: _currentBasketId.value, real(op.parentId))
                            op.itemId?.let { idMap[it] = created.id }
                        }
                        "addGroup" -> {
                            val created = api.addGroup(op.text ?: "", op.items, op.basketId ?: _currentBasketId.value)
                            op.itemId?.let { idMap[it] = created.group.id }
                        }
                        "checked" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.updateItem(it, checked = op.checked) }
                        "quantity" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.updateItem(it, quantity = op.quantity) }
                        "rename" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.updateItem(it, text = op.text) }
                        "delete" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.deleteItem(it) }
                        "deleteMany" -> api.deleteItems((op.items ?: emptyList()).mapNotNull { real(it) }.filterNot { it.startsWith("local-") })
                        "clearChecked" -> api.clearChecked(op.basketId ?: _currentBasketId.value)
                        "choose" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.choose(it, op.store ?: return@let, op.productId) }
                        "assign" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.assignItem(it, op.store) }
                        "assignMode" -> api.assign(op.basketId ?: _currentBasketId.value, op.mode ?: "mix")
                        "transfer" -> real(op.itemId)?.takeUnless { it.startsWith("local-") }?.let { api.transferItem(it, op.basketId ?: return@let, op.copy == true) }
                        "stockAdd" -> api.addStock(op.text ?: "", productId = op.productId)
                        "stockRemove" -> op.itemId?.takeUnless { it.startsWith("local-") }?.let { api.removeStock(it) }
                    }
                } catch (e: Exception) {
                    if (isConnectivityError(e)) { wentOffline(); return }
                    // Server rejected it (e.g. the item is gone): drop and continue.
                }
                pending.update { it.drop(1) }
                offline?.saveOps(pending.value)
            }
        } finally {
            replaying = false
        }
    }

    /** Called when the network comes back (or on demand): replay the queue, then reload everything. */
    suspend fun tryReconnect() {
        if (!probe()) { online.value = false; return }
        online.value = true
        replayQueue()
        if (pending.value.isEmpty()) {
            refreshBaskets(); refreshStores(); refreshStock(); refresh(false)
        }
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

    suspend fun refreshStock() = guard { _stock.value = api.stock(); offline?.save("stock", _stock.value) }
    suspend fun addStock(text: String) {
        queued(PendingOp(type = "stockAdd", text = text), optimistic = {
            _stock.update { list -> list + StockItem(id = localId(), text = text, canonical = text.lowercase()) }
        }) { api.addStock(text); refreshStock() }
    }
    /** Stocks an idea using its picked product for the picture and size. */
    suspend fun addItemToStock(item: BasketItem, product: Product? = item.anyProduct) {
        val text = item.parsed?.canonicalName?.takeIf { it.isNotBlank() } ?: item.text
        queued(PendingOp(type = "stockAdd", text = text, productId = product?.id), optimistic = {
            _stock.update { list -> list + StockItem(id = localId(), text = text, canonical = text.lowercase(), quantityText = product?.quantityText, productId = product?.id, imageUrl = product?.imageUrl) }
        }) { api.addStock(text = text, quantityText = product?.quantityText, productId = product?.id, imageUrl = product?.imageUrl); refreshStock() }
    }

    suspend fun addProductToStock(product: Product, barcode: String?) {
        val text = product.title.replace(Regex("^(AH|Jumbo(?:'s)?)\\s+"), "")
        queued(PendingOp(type = "stockAdd", text = text, productId = product.id), optimistic = {
            _stock.update { list -> list + StockItem(id = localId(), text = text, canonical = text.lowercase(), quantityText = product.quantityText, productId = product.id, imageUrl = product.imageUrl, barcode = barcode) }
        }) { api.addStock(text = text, quantityText = product.quantityText, productId = product.id, imageUrl = product.imageUrl, barcode = barcode); refreshStock() }
    }
    suspend fun removeStock(item: StockItem) {
        queued(PendingOp(type = "stockRemove", itemId = item.id), optimistic = { _stock.update { list -> list.filterNot { it.id == item.id } } }) {
            api.removeStock(item.id)
            _stock.update { list -> list.filterNot { it.id == item.id } }
        }
    }
    suspend fun refreshServerSettings() = guard { _serverSettings.value = api.serverSettings(); offline?.save("settings", _serverSettings.value) }
    suspend fun setSkipInStock(enabled: Boolean) = guard { _serverSettings.value = api.setSkipInStock(enabled) }
    suspend fun setDefaultServings(servings: Int?) = guard { _serverSettings.value = api.setDefaultServings(servings) }
    suspend fun setRankBy(rankBy: String) = guard { _serverSettings.value = api.setRankBy(rankBy) }
    suspend fun setGroupServings(group: BasketItem, servings: Int) = guard {
        _items.update { list -> list.map { if (it.id == group.id) it.copy(status = "PARSING") else it } }
        api.setGroupServings(group.id, servings)
        refresh(false)
    }
    suspend fun addSkipped(group: BasketItem) = guard {
        api.addSkipped(group.id).forEach(::replace)
        refresh(false)
    }

    suspend fun refreshBaskets() {
        try {
            val list = api.baskets()
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

    suspend fun createBasket(name: String, emoji: String?): Basket? = guard {
        val basket = api.createBasket(name, emoji)
        refreshBaskets()
        basket
    }

    suspend fun renameBasket(id: String, name: String, emoji: String?) = guard { api.renameBasket(id, name, emoji); refreshBaskets() }

    suspend fun deleteBasket(id: String) = guard {
        api.deleteBasket(id)
        refreshBaskets()
        if (_currentBasketId.value == id) refresh()
    }

    suspend fun shareLink(): ShareLink? = guard { api.shareLink(_currentBasketId.value) }

    suspend fun groupFromItems(text: String, items: List<BasketItem>) = guard {
        api.groupFromItems(text, items.map { it.id })
        refresh(false)
    }

    suspend fun deleteMany(items: List<BasketItem>) {
        val ids = items.map { it.id }.toSet()
        val remove = { _items.update { list -> list.filterNot { it.id in ids || it.parentId in ids } } }
        queued(PendingOp(type = "deleteMany", items = ids.toList()), optimistic = remove) {
            api.deleteItems(items.map { it.id }.filterNot { it.startsWith("local-") })
            remove(); countsDirty()
        }
    }

    suspend fun transferMany(items: List<BasketItem>, basketId: String, copy: Boolean) = guard {
        for (item in items) api.transferItem(item.id, basketId, copy)
        if (!copy) { val ids = items.map { it.id }.toSet(); _items.update { list -> list.filterNot { it.id in ids || it.parentId in ids } } }
        refreshBaskets()
    }

    suspend fun setCheckedMany(items: List<BasketItem>, checked: Boolean) {
        for (item in items) setChecked(item, checked)
        if (online.value) refresh(false)
    }

    suspend fun transfer(item: BasketItem, basketId: String, copy: Boolean) {
        queued(PendingOp(type = "transfer", itemId = item.id, basketId = basketId, copy = copy), optimistic = {
            if (!copy) _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } }
        }) {
            api.transferItem(item.id, basketId, copy)
            if (!copy) _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } }
            refreshBaskets()
        }
    }

    suspend fun refresh(showSpinner: Boolean = true) {
        if (showSpinner && _items.value.isEmpty()) _loading.value = true
        try {
            val basketId = _currentBasketId.value
            val response = api.basket(basketId)
            if (response.basketId != _currentBasketId.value) return
            online.value = true
            if (pending.value.isNotEmpty()) {
                // Local edits are still queued: keep the local view, but flush the queue first.
                replayQueue()
                if (pending.value.isNotEmpty()) return
                _items.value = api.basket(basketId).items
            } else {
                _items.value = response.items
            }
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
        try {
            _stores.value = api.stores()
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
                val response = runCatching { api.basket(_currentBasketId.value) }.getOrNull() ?: continue
                if (response.basketId != _currentBasketId.value) continue
                _items.value = response.items
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
            runCatching { _baskets.value = api.baskets() }
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block().also { _error.value = null; online.value = true }
    } catch (e: Exception) {
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
        queued(PendingOp(type = "add", text = text, quantity = quantity, parentId = parentId, basketId = _currentBasketId.value, itemId = local.id), optimistic = { _items.update { it + local } }) {
            val created = api.addItem(text, quantity, _currentBasketId.value, parentId)
            replace(created)
            lastAdded.value = created
        }
    }

    suspend fun addGroup(text: String, items: List<String>? = null) {
        val local = localItem(text, 1, null, kind = "group")
        queued(PendingOp(type = "addGroup", text = text, items = items, basketId = _currentBasketId.value, itemId = local.id), optimistic = {
            _items.update { list -> list + local + (items ?: emptyList()).map { localItem(it, 1, local.id) } }
        }) {
            val response = api.addGroup(text, items, _currentBasketId.value)
            replace(response.group)
            response.items.forEach(::replace)
            if (response.group.isProcessing || response.items.any { it.isProcessing }) startPolling()
        }
    }

    suspend fun addFromText(text: String) = guard {
        api.addFromText(text, _currentBasketId.value).forEach(::replace)
    }

    private fun patchLocal(id: String, transform: (BasketItem) -> BasketItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    suspend fun setChecked(item: BasketItem, checked: Boolean) {
        val optimistic = {
            patchLocal(item.id) { it.copy(checked = checked) }
            if (item.isGroup) _items.update { list -> list.map { if (it.parentId == item.id) it.copy(checked = checked) else it } }
        }
        if (item.id.startsWith("local-")) { optimistic(); enqueue(PendingOp(type = "checked", itemId = item.id, checked = checked)); cacheItems(); return }
        queued(PendingOp(type = "checked", itemId = item.id, checked = checked), optimistic = optimistic) {
            replace(api.updateItem(item.id, checked = checked))
            if (item.isGroup) _items.update { list -> list.map { if (it.parentId == item.id) it.copy(checked = checked) else it } }
        }
    }

    suspend fun setQuantity(item: BasketItem, quantity: Int) {
        queued(PendingOp(type = "quantity", itemId = item.id, quantity = quantity), optimistic = { patchLocal(item.id) { it.copy(quantity = quantity) } }) {
            if (!item.id.startsWith("local-")) replace(api.updateItem(item.id, quantity = quantity)) else throw java.io.IOException("queued item")
        }
    }

    suspend fun rename(item: BasketItem, text: String) {
        queued(PendingOp(type = "rename", itemId = item.id, text = text), optimistic = { patchLocal(item.id) { it.copy(text = text) } }) {
            if (!item.id.startsWith("local-")) replace(api.updateItem(item.id, text = text)) else throw java.io.IOException("queued item")
        }
    }

    suspend fun delete(item: BasketItem) {
        val remove = { _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } } }
        if (item.id.startsWith("local-")) {
            // Never sent: drop it together with its queued creation.
            remove()
            pending.update { ops -> ops.filterNot { it.itemId == item.id } }
            offline?.saveOps(pending.value); cacheItems(); return
        }
        queued(PendingOp(type = "delete", itemId = item.id), optimistic = remove) {
            api.deleteItem(item.id); remove(); countsDirty()
        }
    }

    suspend fun clearChecked() {
        queued(PendingOp(type = "clearChecked", basketId = _currentBasketId.value), optimistic = { _items.update { list -> list.filterNot { it.checked } } }) {
            api.clearChecked(_currentBasketId.value); refresh(false); countsDirty()
        }
    }

    suspend fun choose(item: BasketItem, store: String, productId: String?) {
        queued(PendingOp(type = "choose", itemId = item.id, store = store, productId = productId), optimistic = {
            patchLocal(item.id) { current ->
                current.copy(matches = current.matches.map { match ->
                    if (match.store != store) match
                    else if (productId == null) match.copy(status = "NONE", chosen = null, chosenBy = "USER")
                    else match.copy(status = "CHOSEN", chosen = match.options.firstOrNull { it.id == productId } ?: match.chosen, chosenBy = "USER")
                })
            }
        }) { replace(api.choose(item.id, store, productId)) }
    }

    suspend fun reject(item: BasketItem, store: String) = guard { replace(api.reject(item.id, store)) }
    suspend fun unskip(item: BasketItem, store: String) = guard { replace(api.unskip(item.id, store)) }

    suspend fun feedback(item: BasketItem, store: String, productId: String, up: Boolean) = guard { replace(api.feedback(item.id, store, productId, up)) }

    suspend fun searchMore(item: BasketItem, store: String, query: String) = guard { replace(api.searchMore(item.id, store, query)) }

    suspend fun rematch(item: BasketItem) = guard { replace(api.rematch(item.id)) }

    suspend fun compare(): Comparison? {
        val key = "compare-${_currentBasketId.value}"
        return guard { api.compare(_currentBasketId.value).also { offline?.save(key, it) } } ?: offline?.load<Comparison>(key)
    }

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
        }) { _items.value = api.assign(_currentBasketId.value, mode); cacheItems() }
    }
    suspend fun assignItem(item: BasketItem, store: String?) {
        queued(PendingOp(type = "assign", itemId = item.id, store = store), optimistic = { patchLocal(item.id) { it.copy(assignedStore = store) } }) {
            replace(api.assignItem(item.id, store))
        }
    }

    suspend fun searchProducts(query: String, force: Boolean = false): ProductSearchResponse? = guard { api.searchProducts(query, force = force) }

    suspend fun deals(live: Boolean): DealsResponse? = guard { api.deals(_currentBasketId.value, live) }

    suspend fun interpret(text: String): List<VoiceItem>? = guard { api.interpret(text) }

    suspend fun confirm(items: List<VoiceItem>) = guard {
        api.confirm(items, _currentBasketId.value).forEach(::replace)
        startPolling()
    }

    suspend fun addGroupFromUrl(recipeUrl: String) = guard {
        val response = api.addGroupFromUrl(recipeUrl, _currentBasketId.value)
        replace(response.group)
        startPolling()
    }

    suspend fun barcode(gtin: String): BarcodeResponse? = guard { api.barcode(gtin) }

    suspend fun addFromProduct(product: Product, parentId: String? = null) = guard { replace(api.addFromProduct(product.id, _currentBasketId.value, parentId)) }

    suspend fun setEnabledStores(codes: List<String>) = guard {
        api.setEnabledStores(codes)
        refreshStores()
    }

    fun clearError() {
        _error.value = null
    }
}
