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
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val viewModel = viewModelRef ?: return
        if (intent?.getBooleanExtra(EXTRA_FOCUS_INPUT, false) == true) viewModel.focusInputRequest.value = true
        if (intent?.getBooleanExtra(EXTRA_DICTATE, false) == true) viewModel.dictateRequest.value = true
        if (intent?.action == android.content.Intent.ACTION_SEND || intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
            val uris = sharedUris(intent)
            // Read straight away: the share grant on these URIs only lasts while this intent is being handled.
            val files = nl.baskt.ui.purchases.readReceiptUris(this, uris)
            if (files.isNotEmpty()) {
                viewModel.scanReceipt(files, null)
                viewModel.openReceiptsRequest.value = true
            }
            // A rotation recreates the activity with the same intent; make sure the receipt is not read twice.
            intent.action = null
            intent.removeExtra(android.content.Intent.EXTRA_STREAM)
        }
    }

    private fun sharedUris(intent: android.content.Intent): List<android.net.Uri> = when (intent.action) {
        android.content.Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java))
        android.content.Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java) ?: emptyList()
        else -> emptyList()
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
