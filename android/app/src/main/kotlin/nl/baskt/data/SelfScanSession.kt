package nl.baskt.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

enum class SelfScanPhase { OFF, CONNECTING, NO_WIFI, ACTIVE, FAILED }

private const val BARCODE_UNKNOWN = "BARCODE_UNKNOWN"

/** A scan AH refused or that never reached it, shown on its own row until dismissed or scanned again. */
data class SelfScanIssue(val id: Long, val barcode: String, val title: String, val detail: String? = null)

data class SelfScanCheckout(
    val loading: Boolean = true,
    val totals: SsPaymentTotals? = null,
    val message: SsMessage? = null,
    /** From payment/status: null (waiting for the betaalpaal), `paying`, `paid`. */
    val status: String? = null,
    val statusMessage: SsMessage? = null,
    val gateBarcode: String? = null,
    val gateMessage: SsMessage? = null,
    val error: String? = null,
) {
    val paid: Boolean get() = status == "paid"
}

data class SelfScanState(
    val phase: SelfScanPhase = SelfScanPhase.OFF,
    /** What connecting is doing right now. */
    val step: String? = null,
    val error: String? = null,
    val store: SsService? = null,
    val basket: SsBasket? = null,
    /** Barcodes on their way to AH. */
    val sending: Set<String> = emptySet(),
    val issues: List<SelfScanIssue> = emptyList(),
    /** AH's last informational message (not a refusal). */
    val notice: SsMessage? = null,
    val connectionLost: Boolean = false,
    /** True when baskt joined the store Wi-Fi itself (released again when the trip ends). */
    val joinedWifi: Boolean = false,
    val checkout: SelfScanCheckout? = null,
) {
    val active: Boolean get() = phase == SelfScanPhase.ACTIVE
}

/**
 * One AH self-scan trip on this phone. Lives in the app container, so it survives leaving shop mode; the AH basket
 * itself lives on the store server and comes back from `GET trips/{uuid}` whenever a trip is started again.
 */
class SelfScanSession(private val context: Context, private val scope: CoroutineScope, private val settings: SettingsStore) {
    private val _state = MutableStateFlow(SelfScanState())
    val state: StateFlow<SelfScanState> = _state

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var client: SelfScanClient? = null
    private var ranges: List<SsEanRange> = emptyList()
    private var bonuskaart: String = ""
    private var ssid: String = ""
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var checkoutJob: Job? = null
    /** Keeps a Wi-Fi baskt joined itself alive; unregistering it drops the network. */
    private var joinCallback: ConnectivityManager.NetworkCallback? = null
    /** While waiting for the shopper to join the store Wi-Fi: retries when a new Wi-Fi comes up. */
    private var wifiWatcher: ConnectivityManager.NetworkCallback? = null
    /** Networks already probed; the Wi-Fi watcher's callbacks arrive on a binder thread. */
    private val triedNetworks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var issueIds = 0L

    /** Connects to the store server (joining [wifiName] when set and needed) and starts or resumes the trip. */
    fun start(card: String, wifiName: String) {
        if (_state.value.phase == SelfScanPhase.CONNECTING || _state.value.active) return
        bonuskaart = card.filter { it.isDigit() }
        ssid = wifiName.trim()
        stopWatching()
        connectJob = scope.launch { connect() }
    }

    private suspend fun connect() {
        _state.update { it.copy(phase = SelfScanPhase.CONNECTING, step = "Looking for the store", error = null) }
        val device = SelfScanDevice.current(settings.selfScanDeviceUuid())
        var found = probeAll(device)
        if (found == null && ssid.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _state.update { it.copy(step = "Joining “$ssid”") }
            val joined = joinWifi(ssid)
            if (joined != null) {
                found = probe(joined, device)
                if (found != null) _state.update { it.copy(joinedWifi = true) } else releaseJoined()
            }
        }
        if (found == null) {
            _state.update { it.copy(phase = SelfScanPhase.NO_WIFI, step = null) }
            watchWifi()
            return
        }
        val (connected, service) = found
        try {
            _state.update { it.copy(step = "Starting your trip", store = service) }
            ranges = connected.session().eanRis
            client = connected
            val basket = connected.basket()
            _state.update { it.copy(phase = SelfScanPhase.ACTIVE, step = null, basket = basket.result?.basket, notice = basket.message, connectionLost = false) }
            startHeartbeat()
        } catch (e: SelfScanHttpException) {
            fail(e.ahMessage?.message ?: "The store refused the trip (HTTP ${e.status})")
        } catch (e: IOException) {
            fail("Lost the connection while starting: ${e.message ?: "no answer"}")
        }
    }

    private fun fail(message: String) {
        client = null
        releaseJoined()
        _state.update { it.copy(phase = SelfScanPhase.FAILED, step = null, error = message, joinedWifi = false) }
    }

    /** Every Wi-Fi the phone is on, then the default network (a VPN or an odd setup may still route the LAN). */
    private suspend fun probeAll(device: SelfScanDevice): Pair<SelfScanClient, SsService>? {
        val wifi = wifiNetworks()
        for (network in wifi + listOf<Network?>(null)) {
            if (network != null) triedNetworks += network.toString()
            probe(network, device)?.let { return it }
        }
        return null
    }

    private suspend fun probe(network: Network?, device: SelfScanDevice): Pair<SelfScanClient, SsService>? {
        val candidate = SelfScanClient(network, device, bonuskaart)
        val service = withTimeoutOrNull(8_000) { runCatching { candidate.service(probe = true) }.getOrNull() } ?: return null
        // Anything that answers the service call with a store is the self-scan server.
        return candidate to (service.result ?: SsService())
    }

    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> = connectivity.allNetworks.filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    /**
     * Asks Android to join [name] for this app only (the system shows its own "Connect" dialog). The network has no
     * internet as far as Android is concerned, so it never replaces mobile data for the rest of the phone.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private suspend fun joinWifi(name: String): Network? = suspendCancellableCoroutine { continuation ->
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(name).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (continuation.isActive) continuation.resume(network) }
            override fun onUnavailable() { joinCallback = null; if (continuation.isActive) continuation.resume(null) }
            override fun onLost(network: Network) { if (_state.value.active) _state.update { it.copy(connectionLost = true) } }
        }
        releaseJoined()
        joinCallback = callback
        runCatching { connectivity.requestNetwork(request, callback, 45_000) }.onFailure { joinCallback = null; if (continuation.isActive) continuation.resume(null) }
        continuation.invokeOnCancellation { releaseJoined() }
    }

    private fun releaseJoined() {
        joinCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        joinCallback = null
    }

    /** Waiting for the shopper to join the store Wi-Fi in the system settings: tries again on each new Wi-Fi. */
    private fun watchWifi() {
        stopWatching()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network.toString() in triedNetworks) return
                scope.launch {
                    delay(1_500) // let DHCP and DNS settle
                    if (_state.value.phase == SelfScanPhase.NO_WIFI) { stopWatching(); connectJob = scope.launch { connect() } }
                }
            }
        }
        runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback) }
            .onSuccess { wifiWatcher = callback }
    }

    private fun stopWatching() {
        wifiWatcher?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        wifiWatcher = null
    }

    /** Appie polls `service` all through the trip; a missed answer shows "no connection" and tries another network. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var misses = 0
            while (isActive) {
                delay(if (misses == 0) 20_000 else 6_000)
                val current = client ?: break
                val ok = runCatching { current.service() }.isSuccess
                misses = if (ok) 0 else misses + 1
                _state.update { it.copy(connectionLost = !ok) }
                if (misses >= 2) rebind()
            }
        }
    }

    /** The Wi-Fi came back as a new network (or the phone roamed): moves the session to whichever one answers. */
    private suspend fun rebind() {
        val old = client ?: return
        val device = SelfScanDevice.current(settings.selfScanDeviceUuid())
        for (network in wifiNetworks() + listOf<Network?>(null)) {
            if (network == old.network) continue
            val (candidate, _) = probe(network, device) ?: continue
            candidate.token = old.token
            client = candidate
            _state.update { it.copy(connectionLost = false) }
            return
        }
    }

    /** One retry with a fresh token when AH says the session is gone. */
    private suspend fun <T> authed(block: suspend (SelfScanClient) -> T): T {
        val current = client ?: throw IOException("Self-scan is not started")
        return try {
            block(current)
        } catch (e: SelfScanHttpException) {
            if (e.status != 401 && e.status != 403) throw e
            ranges = current.session().eanRis
            block(current)
        }
    }

    private fun issue(barcode: String, title: String, detail: String? = null) {
        _state.update { s -> s.copy(issues = s.issues.filterNot { it.barcode == barcode } + SelfScanIssue(++issueIds, barcode, title, detail)) }
    }

    fun dismissIssue(id: Long) = _state.update { s -> s.copy(issues = s.issues.filterNot { it.id == id }) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Adds one scanned code to the AH basket. True when AH took it; false when it refused (the reason is on an
     * issue row) or it never got there. A Bonuskaart is not sent: the member's own card is already on the trip.
     */
    suspend fun add(raw: String): Boolean {
        if (!_state.value.active) return false
        val code = raw.trim()
        if (isBonuskaart(code, ranges)) {
            _state.update { it.copy(notice = SsMessage(level = "info", message = "That is a Bonuskaart. Yours is already on this trip.")) }
            return false
        }
        _state.update { it.copy(sending = it.sending + code) }
        return try {
            val envelope = addFirstKnown(selfScanCodes(code).ifEmpty { listOf(code) })
            envelope.result?.basket?.let { basket -> _state.update { it.copy(basket = basket) } }
            val message = envelope.message
            if (message?.isProblem == true) {
                issue(code, message.shopperText() ?: message.message ?: "AH did not add this", message.code)
                false
            } else {
                _state.update { s -> s.copy(issues = s.issues.filterNot { it.barcode == code }, notice = message?.takeIf { !it.message.isNullOrBlank() } ?: s.notice, connectionLost = false) }
                envelope.result?.basket != null
            }
        } catch (e: SelfScanHttpException) {
            issue(code, e.ahMessage?.shopperText() ?: e.ahMessage?.message ?: "AH did not add this (HTTP ${e.status})", e.ahMessage?.code)
            false
        } catch (e: IOException) {
            _state.update { it.copy(connectionLost = true) }
            issue(code, "Not sent: couldn’t reach the store", "Scan it again once the connection is back.")
            false
        } finally {
            _state.update { it.copy(sending = it.sending - code) }
        }
    }

    /** Tries each form of a code until AH knows one; the last answer stands when none is known. */
    private suspend fun addFirstKnown(codes: List<String>): SsEnvelope<SsBasketResult> {
        for ((index, candidate) in codes.withIndex()) {
            val last = index == codes.lastIndex
            val envelope = try {
                authed { it.add(candidate) }
            } catch (e: SelfScanHttpException) {
                if (last || e.ahMessage?.code != BARCODE_UNKNOWN) throw e
                continue
            }
            if (last || envelope.message?.code != BARCODE_UNKNOWN) return envelope
        }
        error("no codes")
    }

    /** Sets a line's quantity; 0 removes it. True when AH took the change. */
    suspend fun setQuantity(barcode: String, quantity: Int): Boolean {
        if (!_state.value.active) return false
        _state.update { it.copy(sending = it.sending + barcode) }
        return try {
            val envelope = authed { it.setQuantity(barcode, quantity.coerceAtLeast(0)) }
            envelope.result?.basket?.let { basket -> _state.update { it.copy(basket = basket) } }
            val message = envelope.message
            if (message?.isProblem == true) { issue(barcode, message.message ?: "AH did not change this", message.code); false } else true
        } catch (e: SelfScanHttpException) {
            issue(barcode, e.ahMessage?.message ?: "AH did not change this (HTTP ${e.status})", e.ahMessage?.code)
            false
        } catch (e: IOException) {
            _state.update { it.copy(connectionLost = true) }
            issue(barcode, "Not changed: couldn’t reach the store")
            false
        } finally {
            _state.update { it.copy(sending = it.sending - barcode) }
        }
    }

    fun refresh() = scope.launch {
        runCatching { authed { it.basket() } }.onSuccess { envelope -> envelope.result?.basket?.let { basket -> _state.update { it.copy(basket = basket, connectionLost = false) } } }
    }

    /**
     * Checkout, mirroring Appie's read-only calls: `payment/totals` (what the betaalpaal will ask, and its code),
     * then `payment/status` until the betaalpaal reports `paid`, then the exit gate. Paying happens at the betaalpaal
     * with the shopper's own card; nothing here moves money.
     */
    fun checkout() {
        if (!_state.value.active) return
        checkoutJob?.cancel()
        _state.update { it.copy(checkout = SelfScanCheckout()) }
        checkoutJob = scope.launch {
            try {
                val totals = authed { it.paymentTotals() }
                _state.update { it.copy(checkout = it.checkout?.copy(loading = false, totals = totals.result, message = totals.message)) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val text = (e as? SelfScanHttpException)?.ahMessage?.message ?: e.message ?: "Couldn’t reach the store"
                _state.update { it.copy(checkout = it.checkout?.copy(loading = false, error = text)) }
            }
            while (isActive && _state.value.checkout?.paid != true) {
                delay(2_500)
                val status = runCatching { authed { it.paymentStatus() } }.getOrNull() ?: continue
                _state.update { s ->
                    s.copy(checkout = s.checkout?.copy(status = status.result?.status ?: s.checkout.status, statusMessage = status.message ?: s.checkout.statusMessage, gateBarcode = status.result?.barcode ?: s.checkout.gateBarcode))
                }
            }
            // Paid: Appie then watches the gate. Its answer only matters for the message it may carry.
            repeat(40) {
                delay(3_000)
                runCatching { authed { it.exitgate() } }.getOrNull()?.message?.let { message -> _state.update { s -> s.copy(checkout = s.checkout?.copy(gateMessage = message)) } }
            }
        }
    }

    /** Back to scanning (before paying), or the end of the trip once paid. */
    fun closeCheckout() {
        val paid = _state.value.checkout?.paid == true
        checkoutJob?.cancel()
        checkoutJob = null
        if (paid) stop() else { _state.update { it.copy(checkout = null) }; refresh() }
    }

    /** Leaves self-scan on this phone. An unpaid AH basket stays on the store server and comes back on the next start. */
    fun stop() {
        connectJob?.cancel(); heartbeatJob?.cancel(); checkoutJob?.cancel()
        connectJob = null; heartbeatJob = null; checkoutJob = null
        stopWatching()
        releaseJoined()
        client = null
        ranges = emptyList()
        triedNetworks.clear()
        _state.value = SelfScanState()
    }
}
