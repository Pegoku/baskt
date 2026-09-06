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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BasktApp).container
        setContent {
            val viewModel: AppViewModel = viewModel { AppViewModel(container) }
            val settings by viewModel.settings.collectAsState()
            BasktTheme {
                if (settings != null) {
                    BasktNavigation(viewModel, startAtSettings = settings!!.token.isBlank() && settings!!.baseUrl == SettingsStore.DEFAULT_BASE_URL)
                }
            }
        }
    }
}
