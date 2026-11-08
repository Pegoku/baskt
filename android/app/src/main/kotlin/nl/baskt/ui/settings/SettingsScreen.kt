package nl.baskt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import nl.baskt.data.LANGUAGE_OPTIONS
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.StoreBadge

@Composable
fun SettingsScreen(viewModel: AppViewModel, onBack: () -> Unit, onMemory: () -> Unit = {}, onAhLogin: () -> Unit = {}) {
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val serverSettings by viewModel.basket.serverSettings.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshStock() }
            SettingsSection("Shopping")
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
            Spacer(Modifier.height(4.dp))
            Text("Ranking", style = MaterialTheme.typography.titleMedium)
            Text("What counts as the better option when products are equally suitable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val current = serverSettings.rankBy ?: "price"
                SegmentedButton(selected = current == "price", onClick = { viewModel.setRankBy("price") }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pack price") }
                SegmentedButton(selected = current == "unitPrice", onClick = { viewModel.setRankBy("unitPrice") }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Price per kg / l") }
            }
            Spacer(Modifier.height(4.dp))
            Text("Loyalty cards", style = MaterialTheme.typography.titleMedium)
            Text("Shown as a barcode from the in-store checklist for the checkout scanner. Cards stay on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var editingCard by remember { mutableStateOf<String?>(null) }
            for (store in stores.filter { it.enabled }) {
                val number = settings?.loyaltyCards?.get(store.code)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    nl.baskt.ui.common.StoreLogo(store.code, stores)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(store.name)
                        Text(number?.chunked(4)?.joinToString(" ") ?: "No card yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { editingCard = store.code }) { Text(if (number == null) "Add" else "Edit") }
                }
            }
            editingCard?.let { code ->
                nl.baskt.ui.shop.LoyaltyCardEditor(code, stores, settings?.loyaltyCards?.get(code), onDismiss = { editingCard = null }) { number -> viewModel.saveLoyaltyCard(code, number); editingCard = null }
            }
            Spacer(Modifier.height(4.dp))
            Text("Learned preferences", style = MaterialTheme.typography.titleMedium)
            Text("Every pick, thumbs up and thumbs down the matching learns from. Remove anything set by mistake.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onMemory) { Text("View and manage") }
            HorizontalDivider()
            SettingsSection("Albert Heijn")
            AhAccountSettings(viewModel, onAhLogin)
            HorizontalDivider()
            SettingsSection("Recipes")
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Skip ingredients I have in stock")
                    Text("Recipe folders leave out what is in your stock list; you can add them back per folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = serverSettings.recipeSkipInStock ?: true, onCheckedChange = { viewModel.setSkipInStock(it) })
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default servings")
                    Text("Recipe folders are scaled to this many people. Change it per folder any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val servings = serverSettings.defaultServings
                IconButton(onClick = { viewModel.setDefaultServings(if (servings == null) null else if (servings <= 1) null else servings - 1) }) { Icon(Icons.Default.Remove, contentDescription = "Fewer") }
                Text(servings?.toString() ?: "as written", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { viewModel.setDefaultServings((servings ?: 1) + 1) }) { Icon(Icons.Default.Add, contentDescription = "More") }
            }
            HorizontalDivider()
            SettingsSection("Language & voice")
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Text("Used for suggestions and for how the AI describes your ideas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var languageMenu by remember { mutableStateOf(false) }
            val currentLanguage = settings?.language ?: "system"
            val systemName = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
            // The menu must share a Box with its anchor; as a Column child it would reserve layout space and push content down.
            Box {
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
            }
            Spacer(Modifier.height(4.dp))
            SpeechSettings(viewModel)
            HorizontalDivider()
            SettingsSection("Server & sync")
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
                        runCatching { viewModel.saveSettings(baseUrl, token) }
                            .onSuccess { status = viewModel.testConnection().fold({ it }, { "Saved, but: ${it.message}" }); viewModel.reload() }
                            .onFailure { status = it.message ?: "Could not save settings" }
                    }
                }) { Text("Save & test") }
                OutlinedButton(onClick = { scope.launch { status = viewModel.testConnection().fold({ it }, { "Failed: ${it.message}" }) } }) { Text("Test") }
            }
            if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Offline changes", style = MaterialTheme.typography.titleMedium)
            val pendingOps by viewModel.pending.collectAsState()
            val isOnline by viewModel.online.collectAsState()
            if (pendingOps.isEmpty()) Text(if (isOnline) "Everything is synced." else "Offline: saved data is shown; changes you make are queued here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (op in pendingOps) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(op.label + (op.failure?.let { "\n$it" } ?: ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { viewModel.dropPending(op) }) { Text("Discard") }
                }
            }
            if (pendingOps.isNotEmpty()) OutlinedButton(onClick = { viewModel.retryConnection() }) { Text(if (isOnline) "Send now" else "Retry connection") }
            Spacer(Modifier.height(4.dp))
            Text("WhatsApp bot", style = MaterialTheme.typography.titleMedium)
            val whatsapp by viewModel.whatsapp.collectAsState()
            val qr by viewModel.whatsappQr.collectAsState()
            val chats by viewModel.whatsappChats.collectAsState()
            LaunchedEffect(Unit) { viewModel.loadWhatsApp() }
            when {
                whatsapp == null || whatsapp?.enabled == false -> Text("Not set up. Run backend/whatsapp and set WHATSAPP_URL on the server; then the basket menu gets “Send items to WhatsApp bot”: one message per item, react ✅ to check it off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                whatsapp?.status == "connected" -> {
                    Text("Connected as ${whatsapp?.me ?: "?"} · sending to: ${whatsapp?.chatName ?: "no chat chosen"}", style = MaterialTheme.typography.bodySmall)
                    var pick by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { pick = true; viewModel.loadWhatsApp() }) { Text("Choose chat") }
                        DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                            if (chats.isEmpty()) DropdownMenuItem(text = { Text("Loading chats…") }, onClick = {})
                            for (chat in chats) DropdownMenuItem(text = { Text((chat.name ?: chat.id) + if (chat.isGroup) "  (group)" else "") }, onClick = { pick = false; viewModel.setWhatsAppChat(chat) })
                        }
                    }
                }
                qr != null -> {
                    Text("Scan this code in WhatsApp → Linked devices to connect the bot.", style = MaterialTheme.typography.bodySmall)
                    val bitmap = remember(qr) {
                        runCatching {
                            val bytes = android.util.Base64.decode(qr!!.substringAfter("base64,"), android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    }
                    if (bitmap != null) androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = "WhatsApp QR", modifier = Modifier.size(240.dp))
                    OutlinedButton(onClick = { viewModel.loadWhatsApp() }) { Text("Refresh") }
                }
                else -> {
                    Text("Bridge status: ${whatsapp?.status}${whatsapp?.lastError?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { viewModel.loadWhatsApp() }) { Text("Refresh") }
                }
            }
            HorizontalDivider()
            SettingsSection("Experimental")
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Scan with the phone, pay at the till")
                    Text(
                        "In shop mode, every scan is kept as read. At the checkout, a till button shows your card and then each code in turn for the scanner, so the till rings up what you scanned. Weighed items need their label scanned. Tested for Albert Heijn; check the till's total before paying.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings?.tillRelay == true, onCheckedChange = { viewModel.setTillRelay(it) })
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AH Zelfscan in the store")
                    Text(
                        "In Albert Heijn shop mode, a Start button opens a real self-scan trip on the store's server, like the Appie app: each scan goes into the AH basket with AH's own prices and Bonus, and at checkout baskt shows your Bonuskaart for the betaalpaal. You pay there with your own card. Works only on the store Wi-Fi, and needs your AH card above.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings?.selfScan == true, onCheckedChange = { viewModel.setSelfScan(it) })
            }
            if (settings?.selfScan == true) {
                var wifiName by remember(settings?.selfScanWifi) { mutableStateOf(settings?.selfScanWifi.orEmpty()) }
                OutlinedTextField(
                    value = wifiName,
                    onValueChange = { wifiName = it },
                    label = { Text("Store Wi-Fi name (optional)") },
                    supportingText = { Text("The network's name as Wi-Fi settings shows it. With it, Start joins the store Wi-Fi by itself for the trip; without it, Start opens the Wi-Fi panel when you are not on it yet.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && wifiName.trim() != settings?.selfScanWifi.orEmpty()) viewModel.setSelfScanWifi(wifiName) },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { viewModel.setSelfScanWifi(wifiName) }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                )
            }
            HorizontalDivider()
            Text(
                "baskt keeps the products you pick and the ones you reject, and uses them to rank future suggestions. Matching runs on your own server; the app never talks to the supermarkets directly, except AH Zelfscan, which talks to the store's own server over its Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The server-side AH login behind personal Bonus offers, and whether matching offers activate themselves. */
@Composable
private fun AhAccountSettings(viewModel: AppViewModel, onLogin: () -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadAhAccount() }
    var confirmLogout by remember { mutableStateOf(false) }
    Text("Albert Heijn account", style = MaterialTheme.typography.titleMedium)
    val current = account
    when {
        current == null -> Text("Connect to the server to see the account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        current.connected -> {
            Text("Logged in" + (current.memberName?.let { " as $it" } ?: "") + ". Your personal Bonus and Bonus Box offers show under Deals → My Bonus, and activated ones count in prices.", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-activate matching offers")
                    Text("Bonus Box offers for something on a basket are activated for you, best saving first, until the weekly limit. AH does not let you undo an activation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = current.autoActivate, onCheckedChange = { viewModel.setAhAutoActivate(it) })
            }
            OutlinedButton(onClick = { confirmLogout = true }) { Text("Log out") }
        }
        else -> {
            Text(current.error ?: "Log in with your AH account to see and activate your personal Bonus offers. The server keeps the session; your password is only typed into AH's own page.", style = MaterialTheme.typography.bodySmall, color = if (current.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onLogin) { Text(if (current.error != null) "Log in again" else "Log in to Albert Heijn") }
        }
    }
    if (confirmLogout) {
        nl.baskt.ui.common.ConfirmDialog(
            title = "Log out of Albert Heijn?",
            body = "Personal offers stop showing and prices go back to the regular Bonus.",
            confirmLabel = "Log out",
            onDismiss = { confirmLogout = false },
            onConfirm = { viewModel.disconnectAh() },
        )
    }
}

/** Groups related settings under one coloured label, like the system Settings app. */
@Composable
private fun SettingsSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
