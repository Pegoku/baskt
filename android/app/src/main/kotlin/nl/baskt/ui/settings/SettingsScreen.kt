package nl.baskt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.baskt.data.LANGUAGE_OPTIONS
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge

@Composable
fun SettingsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val current = settings ?: return@LaunchedEffect
        if (!loaded) {
            baseUrl = current.baseUrl
            token = current.token
            loaded = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, placeholder = { Text("http://192.168.1.10:3000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token (APP_API_TOKEN)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        viewModel.saveSettings(baseUrl, token)
                        status = viewModel.testConnection().fold({ it }, { "Saved, but: ${it.message}" })
                        viewModel.reload()
                    }
                }) { Text("Save & test") }
                OutlinedButton(onClick = { scope.launch { status = viewModel.testConnection().fold({ it }, { "Failed: ${it.message}" }) } }) { Text("Test") }
            }
            if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Text("Used for suggestions and for how the AI describes your ideas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var languageMenu by remember { mutableStateOf(false) }
            val currentLanguage = settings?.language ?: "system"
            val systemName = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
            OutlinedButton(onClick = { languageMenu = true }) {
                Text(LANGUAGE_OPTIONS.firstOrNull { it.first == currentLanguage }?.second?.let { if (currentLanguage == "system") "$it ($systemName)" else it } ?: currentLanguage)
            }
            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                for ((code, label) in LANGUAGE_OPTIONS) {
                    DropdownMenuItem(
                        text = { Text(if (code == "system") "$label ($systemName)" else label) },
                        onClick = { languageMenu = false; viewModel.setLanguage(code) },
                    )
                }
            }

            HorizontalDivider()
            Text("Supermarkets", style = MaterialTheme.typography.titleMedium)
            if (stores.isEmpty()) Text("Connect to the server to load the store list.", style = MaterialTheme.typography.bodySmall)
            for (store in stores) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StoreBadge(store.code, stores)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(store.name)
                        if (store.coverage != "full") Text("Offers only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = store.enabled,
                        onCheckedChange = { enabled ->
                            val codes = stores.filter { if (it.code == store.code) enabled else it.enabled }.map { it.code }
                            if (codes.isNotEmpty()) viewModel.setEnabledStores(codes)
                        },
                    )
                }
            }
            HorizontalDivider()
            Text(
                "baskt keeps the products you pick and the ones you reject, and uses them to rank future suggestions. Matching runs on your own server; the app never talks to the supermarkets directly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
