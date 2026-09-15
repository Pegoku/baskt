package nl.baskt

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.baskt.data.AppSettings
import nl.baskt.data.BasketRepository
import nl.baskt.data.BasktApi
import nl.baskt.data.OfflineStore
import nl.baskt.data.SettingsStore

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsStore = SettingsStore(app)
    val speechPlayback = nl.baskt.data.SpeechPlayback(app)

    @Volatile
    var currentSettings = AppSettings(SettingsStore.DEFAULT_BASE_URL, "")
        private set

    val api = BasktApi(settingsProvider = { currentSettings })
    val offline = OfflineStore(app) { currentSettings.baseUrl.trimEnd('/') + "|" + currentSettings.token }
    val basket = BasketRepository(api, scope, offline, onBasketSwitched = { id -> settingsStore.saveCurrentBasket(id); nl.baskt.widget.BasketWidget.refreshAll(app) }, onQueued = { nl.baskt.data.SyncWorker.schedule(app) })

    val recipes = nl.baskt.data.RecipeLibrary(api, offline, basket, cacheImages = { urls ->
        if (basket.online.value) for (url in urls.distinct()) SingletonImageLoader.get(app).enqueue(coil3.request.ImageRequest.Builder(app).data(url).build())
    }).also { basket.recipeLibrary = it }

    init {
        // Replay queued changes as soon as a network is available again.
        val connectivity = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch { awaitSettings(); basket.tryReconnect() }
            }
        })
        // Automatic connectivity monitor: ticks every 5 s and re-reads the state each time, so a drop is
        // re-checked within 10 s (reconnect + replay) while a healthy server is only probed once a minute.
        scope.launch {
            var lastProbe = 0L
            while (true) {
                kotlinx.coroutines.delay(5_000)
                val interval = if (basket.online.value) 60_000 else 10_000
                if (System.currentTimeMillis() - lastProbe < interval) continue
                lastProbe = System.currentTimeMillis()
                if (!basket.online.value || basket.pending.value.isNotEmpty()) basket.tryReconnect() else if (!basket.probe()) basket.online.value = false
            }
        }
        scope.launch {
            settingsStore.settings.collect { settings ->
                val changed = currentSettings.baseUrl != settings.baseUrl || currentSettings.token != settings.token
                currentSettings = settings
                if (changed) { offline.migrateLegacy(); basket.reloadLocal(); recipes.reloadLocal() }
                basket.restoreBasket(settings.currentBasketId)
            }
        }
    }

    suspend fun awaitSettings(): AppSettings = settingsStore.settings.first().also {
        if (currentSettings.baseUrl != it.baseUrl || currentSettings.token != it.token) {
            currentSettings = it; offline.migrateLegacy(); basket.reloadLocal(); recipes.reloadLocal()
        }
    }
}

class BasktApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Jumbo's CDN rejects the default "okhttp/x" user agent, so product images are fetched with a browser UA. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8,*/*;q=0.5")
                        .build(),
                )
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            .build()
    }

    companion object {
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
    }
}
