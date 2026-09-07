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
    private var suggestJob: Job? = null

    /** Debounced autocomplete for the idea input; history answers instantly, AI interpretations follow. */
    fun onIdeaTextChanged(text: String) {
        suggestJob?.cancel()
        val query = text.trim()
        if (query.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(400)
            val result = runCatching { container.api.suggest(query) }.getOrDefault(emptyList())
            _suggestions.value = result.filter { it.trim().lowercase() != query.lowercase() }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    init {
        viewModelScope.launch {
            _settings.value = container.awaitSettings()
            container.settingsStore.settings.collect { _settings.value = it }
        }
        reload()
    }

    fun reload() = viewModelScope.launch {
        container.awaitSettings()
        basket.refreshStores()
        basket.refresh()
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
