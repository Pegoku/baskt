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
class BasketRepository(private val api: BasktApi, private val scope: CoroutineScope, private val onBasketSwitched: suspend (String) -> Unit = {}) {
    private val _items = MutableStateFlow<List<BasketItem>>(emptyList())
    val items: StateFlow<List<BasketItem>> = _items

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

    private var pollJob: Job? = null

    val enabledStores: List<StoreInfo> get() = _stores.value.filter { it.enabled }

    suspend fun refreshStock() = guard { _stock.value = api.stock() }
    suspend fun addStock(text: String) = guard { api.addStock(text); refreshStock() }
    suspend fun removeStock(item: StockItem) = guard { api.removeStock(item.id); _stock.update { list -> list.filterNot { it.id == item.id } } }
    suspend fun refreshServerSettings() = guard { _serverSettings.value = api.serverSettings() }
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
            if (list.none { it.id == _currentBasketId.value } && list.isNotEmpty()) _currentBasketId.value = list.first().id
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not load baskets"
        }
    }

    suspend fun switchBasket(id: String) {
        if (_currentBasketId.value == id) return
        _currentBasketId.value = id
        _items.value = emptyList()
        onBasketSwitched(id)
        refresh()
    }

    fun restoreBasket(id: String) {
        _currentBasketId.value = id
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

    suspend fun transfer(item: BasketItem, basketId: String, copy: Boolean) = guard {
        api.transferItem(item.id, basketId, copy)
        if (!copy) _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } }
        refreshBaskets()
    }

    suspend fun refresh(showSpinner: Boolean = true) {
        if (showSpinner) _loading.value = true
        try {
            val basketId = _currentBasketId.value
            val response = api.basket(basketId)
            if (response.basketId != _currentBasketId.value) return
            _items.value = response.items
            if (response.processing || response.items.any { it.isProcessing }) startPolling()
            _error.value = null
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not reach the server"
        } finally {
            _loading.value = false
        }
    }

    suspend fun refreshStores() {
        try {
            _stores.value = api.stores()
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not load stores"
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
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block().also { _error.value = null }
    } catch (e: Exception) {
        _error.value = e.message ?: "Request failed"
        null
    }

    suspend fun add(text: String, quantity: Int, parentId: String? = null) = guard { replace(api.addItem(text, quantity, _currentBasketId.value, parentId)) }

    suspend fun addGroup(text: String, items: List<String>? = null) = guard {
        val response = api.addGroup(text, items, _currentBasketId.value)
        replace(response.group)
        response.items.forEach(::replace)
        if (response.group.isProcessing || response.items.any { it.isProcessing }) startPolling()
    }

    suspend fun addFromText(text: String) = guard {
        api.addFromText(text, _currentBasketId.value).forEach(::replace)
    }

    suspend fun setChecked(item: BasketItem, checked: Boolean) = guard {
        replace(api.updateItem(item.id, checked = checked))
        if (item.isGroup) _items.update { list -> list.map { if (it.parentId == item.id) it.copy(checked = checked) else it } }
    }

    suspend fun setQuantity(item: BasketItem, quantity: Int) = guard { replace(api.updateItem(item.id, quantity = quantity)) }

    suspend fun rename(item: BasketItem, text: String) = guard { replace(api.updateItem(item.id, text = text)) }

    suspend fun delete(item: BasketItem) = guard {
        api.deleteItem(item.id)
        _items.update { list -> list.filterNot { it.id == item.id || it.parentId == item.id } }
    }

    suspend fun clearChecked() = guard {
        api.clearChecked(_currentBasketId.value)
        refresh(false)
    }

    suspend fun choose(item: BasketItem, store: String, productId: String?) = guard { replace(api.choose(item.id, store, productId)) }

    suspend fun reject(item: BasketItem, store: String) = guard { replace(api.reject(item.id, store)) }

    suspend fun feedback(item: BasketItem, store: String, productId: String, up: Boolean) = guard { replace(api.feedback(item.id, store, productId, up)) }

    suspend fun searchMore(item: BasketItem, store: String, query: String) = guard { replace(api.searchMore(item.id, store, query)) }

    suspend fun rematch(item: BasketItem) = guard { replace(api.rematch(item.id)) }

    suspend fun compare(): Comparison? = guard { api.compare(_currentBasketId.value) }

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
