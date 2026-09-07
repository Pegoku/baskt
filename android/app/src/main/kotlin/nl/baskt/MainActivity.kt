package nl.baskt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.baskt.data.SettingsStore
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.BasktNavigation
import nl.baskt.ui.theme.BasktTheme

const val EXTRA_FOCUS_INPUT = "nl.baskt.FOCUS_INPUT"
const val EXTRA_DICTATE = "nl.baskt.DICTATE"

class MainActivity : ComponentActivity() {
    private var viewModelRef: AppViewModel? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val viewModel = viewModelRef ?: return
        if (intent?.getBooleanExtra(EXTRA_FOCUS_INPUT, false) == true) viewModel.focusInputRequest.value = true
        if (intent?.getBooleanExtra(EXTRA_DICTATE, false) == true) viewModel.dictateRequest.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BasktApp).container
        setContent {
            val viewModel: AppViewModel = viewModel { AppViewModel(container) }
            androidx.compose.runtime.LaunchedEffect(viewModel) { viewModelRef = viewModel; handleIntent(intent) }
            val settings by viewModel.settings.collectAsState()
            BasktTheme {
                if (settings != null) {
                    BasktNavigation(viewModel, startAtSettings = settings!!.token.isBlank() && settings!!.baseUrl == SettingsStore.DEFAULT_BASE_URL)
                }
            }
        }
    }
}
