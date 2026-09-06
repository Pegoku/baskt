package nl.baskt

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.baskt.data.AppSettings
import nl.baskt.data.BasketRepository
import nl.baskt.data.BasktApi
import nl.baskt.data.SettingsStore

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsStore = SettingsStore(app)

    @Volatile
    var currentSettings = AppSettings(SettingsStore.DEFAULT_BASE_URL, "")
        private set

    val api = BasktApi { currentSettings }
    val basket = BasketRepository(api, scope)

    init {
        scope.launch {
            settingsStore.settings.collect { currentSettings = it }
        }
    }

    suspend fun awaitSettings(): AppSettings = settingsStore.settings.first().also { currentSettings = it }
}

class BasktApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
