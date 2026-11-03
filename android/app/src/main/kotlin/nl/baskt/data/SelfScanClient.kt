package nl.baskt.data

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A non-2xx answer from the store server, with AH's own message when it sent one. */
class SelfScanHttpException(val status: Int, val ahMessage: SsMessage?) : Exception(ahMessage?.message ?: "HTTP $status")

/** What the `AH-Device-*` headers say about this phone; [uuid] is also the trip id in every path. */
data class SelfScanDevice(val uuid: String, val manufacturer: String, val model: String, val release: String, val sdk: Int) {
    companion object {
        fun current(uuid: String) = SelfScanDevice(uuid, android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT)
    }
}

/**
 * HTTP client for one store server. Bound to [network] (the store Wi-Fi) when given: a Wi-Fi without internet is
 * never Android's default network, so unbound requests would leave over mobile data and never reach the LAN-only
 * 172.16.8.3. DNS goes through the same network (AH's public DNS hands out that private address anyway).
 *
 * Auth is only the `AH-*` headers, as the Appie app sends them; no `Authorization`. Never calls anything payment.
 */
class SelfScanClient(val network: Network?, private val device: SelfScanDevice, private val bonuskaart: String) {
    /** From `GET sessions/{uuid}`; sent on every call after it. */
    @Volatile var token: String? = null

    private val http: OkHttpClient = OkHttpClient.Builder().apply {
        if (network != null) {
            socketFactory(network.socketFactory)
            dns { host -> network.getAllByName(host).toList() }
        }
    }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val trip get() = "trips/${device.uuid}"

    /** Heartbeat and "is the store server here" probe. [probe] leaves `AH-Uid` empty, as Appie's very first call does. */
    suspend fun service(probe: Boolean = false): SsEnvelope<SsService> = call("GET", "service", null, SsService.serializer(), uid = !probe)

    /** Hands out the session token and the store's Bonuskaart ranges; the device needs no registration first. */
    suspend fun session(): SsSession {
        val envelope = call("GET", "sessions/${device.uuid}?terms=", null, SsSession.serializer())
        val session = envelope.result ?: throw SelfScanHttpException(200, envelope.message ?: SsMessage(message = "The store server gave no session"))
        token = session.token ?: throw SelfScanHttpException(200, envelope.message ?: SsMessage(message = "The store server gave no session token"))
        return session
    }

    suspend fun basket(): SsEnvelope<SsBasketResult> = call("GET", trip, null, SsBasketResult.serializer())

    suspend fun add(barcode: String, quantity: Int = 1): SsEnvelope<SsBasketResult> =
        call("POST", "$trip/items", JsonObject(mapOf("barcode" to JsonPrimitive(barcode), "quantity" to JsonPrimitive(quantity))), SsBasketResult.serializer())

    /** Sets the line's quantity; 0 takes it out of the basket. */
    suspend fun setQuantity(barcode: String, quantity: Int): SsEnvelope<SsBasketResult> =
        call("PUT", "$trip/items/${java.net.URLEncoder.encode(barcode, "UTF-8").replace("+", "%20")}", JsonObject(mapOf("quantity" to JsonPrimitive(quantity))), SsBasketResult.serializer())

    /** What the betaalpaal will ask, and the code to scan there (the Bonuskaart). Read-only: pays nothing. */
    suspend fun paymentTotals(): SsEnvelope<SsPaymentTotals> = call("GET", "$trip/payment/totals", null, SsPaymentTotals.serializer())

    /** `paying` / `paid`, as the betaalpaal reports it. */
    suspend fun paymentStatus(): SsEnvelope<SsPaymentStatus> = call("GET", "$trip/payment/status?lastAttempt=false", null, SsPaymentStatus.serializer())

    suspend fun exitgate(): SsEnvelope<SsExitgate> = call("GET", "$trip/exitgate/status", null, SsExitgate.serializer())

    private suspend fun <T> call(method: String, path: String, body: JsonObject?, result: KSerializer<T>, uid: Boolean = true): SsEnvelope<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(SELF_SCAN_BASE + path)
            .header("Accept", "application/json")
            .header("User-Agent", "Appie/$SELF_SCAN_APP_VERSION (Android ${device.release}-API${device.sdk}; ${device.manufacturer} ${device.model})")
            .header("AH-App-Name", "Albert Heijn")
            .header("AH-App-Version", SELF_SCAN_APP_VERSION)
            .header("AH-Device-Platform", "Android")
            .header("AH-Device-Name", device.model)
            .header("AH-Device-Version", device.release)
            .header("AH-Device-Uuid", device.uuid)
            .header("AH-Uid", if (uid) bonuskaart else "")
            .apply { token?.let { header("AH-Token", it) } }
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val envelope = decodeSelfScan(response.body.string(), result)
            if (!response.isSuccessful) throw SelfScanHttpException(response.code, envelope.message)
            envelope
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
