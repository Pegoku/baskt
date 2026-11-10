package nl.baskt.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.baskt.data.AppSettings
import nl.baskt.data.LANGUAGE_OPTIONS
import nl.baskt.data.SettingsStore
import nl.baskt.data.StoreInfo
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ChoiceGroup
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.DropdownPicker
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.NavRow
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.SheetBody
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Stepper
import nl.baskt.ui.common.StoreLogo
import nl.baskt.ui.common.SwitchRow

/** Every settings row is at least a full touch target, also when it has a title only. */
private val RowHeight = Modifier.heightIn(min = 48.dp)

/**
 * Settings as six groups of rows, one coloured header each. A phone that has no server yet shows the server
 * group first, because nothing else on the screen works until that is filled in.
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel, onBack: () -> Unit, onMemory: () -> Unit = {}, onAhLogin: () -> Unit = {}) {
    val settings by viewModel.settings.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    // Decided once, when the screen opens: the group must not jump away under the user's finger after saving.
    val serverFirst = rememberSaveable { settings?.let { it.token.isBlank() && it.baseUrl == SettingsStore.DEFAULT_BASE_URL } == true }
    LaunchedEffect(Unit) { viewModel.refreshStock() }

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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.Screen, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (serverFirst) ServerGroup(viewModel, settings, intro = true)
            ShoppingGroup(viewModel, settings, stores, onMemory)
            SettingsGroup("Albert Heijn") { AhAccountSettings(viewModel, onAhLogin) }
            RecipesGroup(viewModel)
            SettingsGroup("Language & voice") {
                val currentLanguage = settings?.language ?: "system"
                val systemName = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
                DropdownPicker(
                    label = "Suggestion language",
                    options = LANGUAGE_OPTIONS,
                    selected = LANGUAGE_OPTIONS.firstOrNull { it.first == currentLanguage },
                    optionLabel = { (code, label) -> if (code == "system") "$label ($systemName)" else label },
                    onPick = { viewModel.setLanguage(it.first) },
                    modifier = Modifier.padding(top = 4.dp),
                )
                Hint("For suggestions and descriptions. The app itself is in English.")
                SpeechSettings(viewModel)
            }
            if (!serverFirst) ServerGroup(viewModel, settings, intro = false)
            ExperimentalGroup(viewModel, settings)
            AboutGroup()
        }
    }
}

/** One group of settings: its header, then its rows. Spacing and the header separate groups, so no dividers. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(title, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** The one short sentence a setting may carry when it has no row of its own to put it in. */
@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The label of a control that is not a row by itself (a choice group, a list). */
@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
}

/** Marks a row that opens another screen. */
@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ShoppingGroup(viewModel: AppViewModel, settings: AppSettings?, stores: List<StoreInfo>, onMemory: () -> Unit) {
    val serverSettings by viewModel.basket.serverSettings.collectAsState()
    val online by viewModel.online.collectAsState()
    SettingsGroup("Shopping") {
        when {
            stores.isNotEmpty() -> {
                val enabledCount = stores.count { it.enabled }
                for (store in stores) {
                    // The last store cannot be turned off; the row says so instead of ignoring the tap.
                    val last = store.enabled && enabledCount == 1
                    SwitchRow(
                        title = store.name,
                        body = when { last -> "At least one store"; store.coverage != "full" -> "Deals only"; else -> null },
                        checked = store.enabled,
                        modifier = RowHeight,
                        enabled = !last,
                        leading = { StoreLogo(store.code, stores) },
                    ) { enabled -> viewModel.setEnabledStores(stores.filter { if (it.code == store.code) enabled else it.enabled }.map { it.code }) }
                }
            }
            online -> LoadingRow("Loading stores…", modifier = RowHeight)
            else -> Hint("The stores show up once you’re connected")
        }
        Label("Compare by")
        ChoiceGroup(listOf("Pack price", "Unit price"), selected = if (serverSettings.rankBy == "unitPrice") 1 else 0, onSelect = { viewModel.setRankBy(if (it == 1) "unitPrice" else "price") })
        var editingCard by rememberSaveable { mutableStateOf<String?>(null) }
        for (store in stores.filter { it.enabled }) {
            val number = settings?.loyaltyCards?.get(store.code)
            NavRow(
                title = "${store.name} card",
                body = number?.chunked(4)?.joinToString(" ") ?: "No card yet",
                modifier = RowHeight,
                leading = { StoreLogo(store.code, stores) },
            ) { editingCard = store.code }
        }
        editingCard?.let { code ->
            nl.baskt.ui.shop.LoyaltyCardEditor(code, stores, settings?.loyaltyCards?.get(code), onDismiss = { editingCard = null }) { number -> viewModel.saveLoyaltyCard(code, number); editingCard = null }
        }
        NavRow("Learned preferences", "What you picked and rejected", modifier = RowHeight, trailing = { Chevron() }, onClick = onMemory)
    }
}

/** The server-side AH login behind personal Bonus offers, and whether matching offers activate themselves. */
@Composable
private fun AhAccountSettings(viewModel: AppViewModel, onLogin: () -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    val failed by viewModel.ahAccountError.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadAhAccount() }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val current = account
    when {
        current == null && failed != null -> {
            Hint("Couldn’t load your AH account: $failed", error = true)
            TextButton(onClick = { viewModel.loadAhAccount() }) { Text("Try again") }
        }
        current == null -> LoadingRow("Loading your AH account…", modifier = RowHeight)
        current.connected -> {
            Row(modifier = RowHeight.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Logged in" + (current.memberName?.let { " as $it" } ?: ""), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmLogout = true }) { Text("Log out") }
            }
            SwitchRow("Activate matching Bonus Box offers", "AH doesn’t let you undo an activation", checked = current.autoActivate, modifier = RowHeight) { viewModel.setAhAutoActivate(it) }
        }
        else -> {
            if (current.error != null) Hint("Your Albert Heijn login stopped working", error = true)
            else Hint("Log in to see your personal Bonus and Bonus Box offers")
            FilledTonalButton(onClick = onLogin) { Text(if (current.error != null) "Log in again" else "Log in to Albert Heijn") }
        }
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out of Albert Heijn?",
            body = "Your personal Bonus and Bonus Box offers stop showing.",
            confirmLabel = "Log out",
            onDismiss = { confirmLogout = false },
            onConfirm = { viewModel.disconnectAh() },
        )
    }
}

@Composable
private fun RecipesGroup(viewModel: AppViewModel) {
    val serverSettings by viewModel.basket.serverSettings.collectAsState()
    SettingsGroup("Recipes") {
        SwitchRow("Skip ingredients in stock", "Recipe folders leave out what you already have", checked = serverSettings.recipeSkipInStock ?: true, modifier = RowHeight) { viewModel.setSkipInStock(it) }
        val servings = serverSettings.defaultServings
        Row(modifier = RowHeight.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Default servings", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            // The value has a fixed width, so the buttons stay put when it changes between a word and a number.
            Stepper(
                value = servings?.toString() ?: "As written",
                onMinus = { viewModel.setDefaultServings(servings?.takeIf { it > 1 }?.minus(1)) },
                onPlus = { viewModel.setDefaultServings((servings ?: 0) + 1) },
                noun = "servings",
                minusEnabled = servings != null,
                valueWidth = 96.dp,
            )
        }
    }
}

/** Where the baskt server is, whether it answers, what is still waiting to be sent to it, and the WhatsApp link. */
@Composable
private fun ServerGroup(viewModel: AppViewModel, settings: AppSettings?, intro: Boolean) {
    val scope = rememberCoroutineScope()
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    // The outcome of the last "Save and test": whether it worked, the line that says so, and an optional note.
    var connected by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(settings) {
        val current = settings ?: return@LaunchedEffect
        if (!loaded) { baseUrl = current.baseUrl; token = current.token; loaded = true }
    }
    fun saveAndTest() {
        if (testing) return
        testing = true
        scope.launch {
            try {
                viewModel.saveSettings(baseUrl, token)
                val check = viewModel.testConnection()
                connected = check.connected; status = check.message; note = check.note
                viewModel.reload()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { connected = false; status = e.message ?: "Couldn’t save the server"; note = null }
            finally { testing = false }
        }
    }
    SettingsGroup("Server & sync") {
        if (intro) Text("Connect to your baskt server to start", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Server address") },
            placeholder = { Text("http://192.168.1.10:3000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Access token") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveAndTest() }),
            trailingIcon = { IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (showToken) "Hide token" else "Show token") } },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { saveAndTest() }, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Testing…")
                } else Text("Save and test")
            }
            if (!testing) status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (connected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)) }
        }
        if (!testing) note?.let { Hint(it) }
        PendingChanges(viewModel)
        WhatsAppSettings(viewModel)
    }
}

/** What was changed on this phone and has not reached the server yet; a change that failed says why. */
@Composable
private fun PendingChanges(viewModel: AppViewModel) {
    val pendingOps by viewModel.pending.collectAsState()
    val isOnline by viewModel.online.collectAsState()
    var discarding by rememberSaveable { mutableStateOf<String?>(null) }
    Label("Changes waiting to sync")
    if (pendingOps.isEmpty()) Hint(if (isOnline) "Everything is synced" else "You’re offline, so new changes wait here")
    for (op in pendingOps) {
        Row(modifier = RowHeight.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(op.label, style = MaterialTheme.typography.bodyMedium)
                op.failure?.let { Text("Didn’t sync: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = { discarding = op.id }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard") }
        }
    }
    if (pendingOps.isNotEmpty()) TextButton(onClick = { viewModel.retryConnection() }) { Text("Try again") }
    // The server never saw a discarded change, so there is nothing to bring it back from: ask first.
    pendingOps.firstOrNull { it.id == discarding }?.let { op ->
        ConfirmDialog(
            title = "Discard this change?",
            body = "“${op.label}” won’t reach the server, and anything that depends on it goes too. This can’t be undone.",
            confirmLabel = "Discard",
            onDismiss = { discarding = null },
            onConfirm = { viewModel.dropPending(op) },
        )
    }
}

/**
 * Linking the WhatsApp account that receives the basket. Shown only when the server has the WhatsApp link set
 * up; a phone user can do nothing about a server that has not.
 */
@Composable
private fun WhatsAppSettings(viewModel: AppViewModel) {
    val whatsapp by viewModel.whatsapp.collectAsState()
    val qr by viewModel.whatsappQr.collectAsState()
    val chats by viewModel.whatsappChats.collectAsState()
    val chatsLoading by viewModel.whatsappChatsLoading.collectAsState()
    val chatsFailed by viewModel.whatsappChatsFailed.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadWhatsApp() }
    val current = whatsapp?.takeIf { it.enabled } ?: return
    val linked = current.status == "connected"
    // While the code is waiting to be scanned, keep asking, so the screen moves on by itself once it has been.
    LaunchedEffect(linked) { while (!linked) { delay(5_000); viewModel.loadWhatsApp() } }
    Label("WhatsApp")
    when {
        linked -> {
            Hint("Linked" + (current.me?.let { " as $it" } ?: ""))
            DropdownPicker(
                label = "Send items to",
                options = chats,
                selected = chats.firstOrNull { it.id == current.chatId },
                optionLabel = { (it.name ?: it.id) + if (it.isGroup) " (group)" else "" },
                onPick = { viewModel.setWhatsAppChat(it) },
                enabled = chats.isNotEmpty(),
                placeholder = current.chatName ?: when { chatsLoading -> "Loading chats…"; chatsFailed -> "Couldn’t load your chats"; chats.isEmpty() -> "No chats found"; else -> "Choose a chat" },
                modifier = Modifier.padding(top = 4.dp),
            )
            if (chatsFailed) TextButton(onClick = { viewModel.loadWhatsApp() }) { Text("Try again") }
        }
        qr != null -> {
            Hint("In WhatsApp, open Linked devices and scan this code")
            val bitmap = remember(qr) {
                runCatching {
                    val bytes = android.util.Base64.decode(qr!!.substringAfter("base64,"), android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                // A code to be scanned stays black on white in either theme.
                if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = "WhatsApp linking code", modifier = Modifier.size(240.dp).clip(MaterialTheme.shapes.medium).background(Color.White).padding(8.dp))
                else Hint("Couldn’t show the code", error = true)
            }
            TextButton(onClick = { viewModel.loadWhatsApp() }) { Text(if (bitmap != null) "Show a new code" else "Try again") }
        }
        else -> {
            Hint("WhatsApp isn’t linked yet")
            TextButton(onClick = { viewModel.loadWhatsApp() }) { Text("Try again") }
        }
    }
}

@Composable
private fun ExperimentalGroup(viewModel: AppViewModel, settings: AppSettings?) {
    var explain by rememberSaveable { mutableStateOf(false) }
    SettingsGroup("Experimental") {
        SwitchRow("Scan with the phone, pay at the till", "Shows your scans as barcodes at the checkout so the till rings them up", checked = settings?.tillRelay == true, modifier = RowHeight) { viewModel.setTillRelay(it) }
        SwitchRow("AH self-scan", "Scan into AH’s own self-scan basket and pay at a betaalpaal, using the store Wi-Fi and your AH card", checked = settings?.selfScan == true, modifier = RowHeight) { viewModel.setSelfScan(it) }
        if (settings?.selfScan == true) {
            var wifiName by rememberSaveable(settings.selfScanWifi) { mutableStateOf(settings.selfScanWifi) }
            OutlinedTextField(
                value = wifiName,
                onValueChange = { wifiName = it },
                label = { Text("Store Wi-Fi name (optional)") },
                supportingText = { Text("Joined automatically when a trip starts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).onFocusChanged { if (!it.isFocused && wifiName.trim() != settings.selfScanWifi) viewModel.setSelfScanWifi(wifiName) },
                keyboardActions = KeyboardActions(onDone = { viewModel.setSelfScanWifi(wifiName) }),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            )
        }
        TextButton(onClick = { explain = true }) { Text("How this works") }
    }
    if (explain) {
        ModalBottomSheet(onDismissRequest = { explain = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SheetBody(title = "Experimental features", actions = { TextButton(onClick = { explain = false }) { Text("Close") } }) {
                Text("Scan with the phone, pay at the till", style = MaterialTheme.typography.titleSmall)
                Text("In shop mode every scan is kept as it was read. At the checkout, baskt shows your card and then each code in turn for the till’s scanner. Weighed items need their label scanned. Tested at Albert Heijn: check the till’s total before you pay.", style = MaterialTheme.typography.bodyMedium)
                Text("AH self-scan", style = MaterialTheme.typography.titleSmall)
                Text("In Albert Heijn shop mode, Start opens a real self-scan trip in the store. Each scan goes into AH’s basket with AH’s own prices and Bonus, and at the checkout baskt shows your AH card for the betaalpaal, where you pay with your own card. It only works on the store Wi-Fi and needs your AH card under Shopping.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** What used to be a paragraph at the bottom of the screen: where things run and which version this is. */
@Composable
private fun AboutGroup() {
    var open by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    SettingsGroup("About") {
        NavRow("About baskt", version?.let { "Version $it" }, modifier = RowHeight) { open = true }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SheetBody(title = "About baskt", actions = { TextButton(onClick = { open = false }) { Text("Close") } }) {
                Text("baskt remembers the products you pick and the ones you reject, and uses them to rank what it suggests next. Matching runs on your own server, and your loyalty cards stay on this phone.", style = MaterialTheme.typography.bodyMedium)
                if (version != null) Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
