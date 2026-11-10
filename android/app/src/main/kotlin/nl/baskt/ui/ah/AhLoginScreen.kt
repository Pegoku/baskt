package nl.baskt.ui.ah

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.LoadingRow

/**
 * AH's own login page in a WebView. It finishes by redirecting to appie://login-exit?code=..., which only
 * the Appie app could open; we catch that URL and hand the code to the server, which keeps the session.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AhLoginScreen(viewModel: AppViewModel, onDone: () -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    val accountError by viewModel.ahAccountError.collectAsState()
    LaunchedEffect(Unit) { if (account == null) viewModel.loadAhAccount() }
    val scope = rememberCoroutineScope()
    // Not saved across a rotation on purpose: the request it stands for does not survive one either.
    var connecting by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    // Where the login pages had got to, so a rotation continues there instead of starting over.
    val pageState = rememberSaveable { Bundle() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var pageLoading by remember { mutableStateOf(false) }
    val loginUrl = account?.loginUrl?.takeIf { it.isNotBlank() }
    val redirect = account?.redirectPrefix ?: "appie://login-exit"

    fun finish(url: String) {
        if (connecting) return
        connecting = true
        scope.launch {
            viewModel.connectAh(url)
                .onSuccess { viewModel.notify("Logged in to Albert Heijn" + (it.memberName?.let { name -> " as $name" } ?: "")); onDone() }
                .onFailure { error = "Albert Heijn didn’t accept the login" + (it.message?.takeIf { message -> message.isNotBlank() }?.let { message -> ": $message" } ?: ""); connecting = false }
        }
    }
    /** AH sends the app's own address at the end: with a code the login worked, without one it did not. */
    fun exit(uri: android.net.Uri) {
        if (uri.getQueryParameter("code") != null) finish(uri.toString()) else error = "Albert Heijn didn’t finish the login"
    }

    // Back first steps back through AH's pages; only from the first page does it leave the login.
    BackHandler(enabled = connecting) {}
    BackHandler(enabled = !connecting && error == null && loginUrl != null && canGoBack) { webView?.goBack() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Log in to Albert Heijn") },
                navigationIcon = { IconButton(onClick = onDone, enabled = !connecting) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        // The safe-drawing insets include the keyboard, so AH's password field is never under it.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                connecting -> LoadingRow("Logging in…", modifier = Modifier.align(Alignment.Center))
                error != null -> EmptyState(
                    "Couldn’t log in",
                    error,
                    icon = Icons.Default.CloudOff,
                    modifier = Modifier.fillMaxSize(),
                    action = { FilledTonalButton(onClick = { error = null; pageState.clear(); attempt += 1 }) { Text("Try again") } },
                )
                loginUrl == null && accountError != null -> EmptyState(
                    "Couldn’t open the login page",
                    accountError?.replaceFirstChar { it.uppercase() },
                    icon = Icons.Default.CloudOff,
                    modifier = Modifier.fillMaxSize(),
                    action = { FilledTonalButton(onClick = { viewModel.loadAhAccount() }) { Text("Try again") } },
                )
                loginUrl == null && account != null -> EmptyState(
                    "Albert Heijn login isn’t available",
                    "Your baskt server didn’t give a login page",
                    icon = Icons.Default.CloudOff,
                    modifier = Modifier.fillMaxSize(),
                    action = { FilledTonalButton(onClick = { viewModel.loadAhAccount() }) { Text("Try again") } },
                )
                loginUrl == null -> LoadingRow("Opening the login page…", modifier = Modifier.align(Alignment.Center))
                else -> androidx.compose.runtime.key(attempt) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Lets the page follow the app's dark theme where AH's page allows it, instead of a white sheet at night.
                                if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        if (url.startsWith(redirect) || url.startsWith("appie:")) { exit(request.url); return true }
                                        return false
                                    }
                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { pageLoading = true; progress = 0 }
                                    override fun onPageFinished(view: WebView, url: String?) { pageLoading = false; canGoBack = view.canGoBack(); view.saveState(pageState) }
                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canGoBack = view.canGoBack() }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                        if (!request.isForMainFrame) return
                                        val url = request.url.toString()
                                        // A redirect to the app's own address can also arrive here, as "unknown scheme".
                                        if (url.startsWith(redirect) || url.startsWith("appie:")) { exit(request.url); return }
                                        pageLoading = false
                                        error = "The Albert Heijn page didn’t load. Check the connection and try again."
                                    }
                                }
                                if (pageState.isEmpty || restoreState(pageState) == null) loadUrl(loginUrl)
                                webView = this
                            }
                        },
                        onRelease = { it.saveState(pageState); if (webView === it) webView = null; it.destroy() },
                    )
                    if (pageLoading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}
