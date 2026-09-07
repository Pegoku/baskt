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
class BasketRepository(private val api: BasktApi, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<BasketItem>>(emptyList())
    val items: StateFlow<List<BasketItem>> = _items

    private val _stores = MutableStateFlow<List<StoreInfo>>(emptyList())
    val stores: StateFlow<List<StoreInfo>> = _stores

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var pollJob: Job? = null

    val enabledStores: List<StoreInfo> get() = _stores.value.filter { it.enabled }

    suspend fun refresh(showSpinner: Boolean = true) {
        if (showSpinner) _loading.value = true
        try {
            val response = api.basket()
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
                val response = runCatching { api.basket() }.getOrNull() ?: continue
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

    suspend fun add(text: String, quantity: Int, parentId: String? = null) = guard { replace(api.addItem(text, quantity, parentId)) }

    suspend fun addGroup(text: String, items: List<String>? = null) = guard {
        val response = api.addGroup(text, items)
        replace(response.group)
        response.items.forEach(::replace)
        if (response.group.isProcessing || response.items.any { it.isProcessing }) startPolling()
    }

    suspend fun addFromText(text: String) = guard {
        api.addFromText(text).forEach(::replace)
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
        api.clearChecked()
        refresh(false)
    }

    suspend fun choose(item: BasketItem, store: String, productId: String?) = guard { replace(api.choose(item.id, store, productId)) }

    suspend fun reject(item: BasketItem, store: String) = guard { replace(api.reject(item.id, store)) }

    suspend fun searchMore(item: BasketItem, store: String, query: String) = guard { replace(api.searchMore(item.id, store, query)) }

    suspend fun rematch(item: BasketItem) = guard { replace(api.rematch(item.id)) }

    suspend fun compare(): Comparison? = guard { api.compare() }

    suspend fun setEnabledStores(codes: List<String>) = guard {
        api.setEnabledStores(codes)
        refreshStores()
    }

    fun clearError() {
        _error.value = null
    }
}
