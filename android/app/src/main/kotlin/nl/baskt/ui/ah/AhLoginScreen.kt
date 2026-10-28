package nl.baskt.ui.ah

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import nl.baskt.ui.AppViewModel

/**
 * AH's own login page in a WebView. It finishes by redirecting to appie://login-exit?code=..., which only
 * the Appie app could open; we catch that URL and hand the code to the server, which keeps the session.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AhLoginScreen(viewModel: AppViewModel, onDone: () -> Unit) {
    val account by viewModel.ahAccount.collectAsState()
    LaunchedEffect(Unit) { if (account == null) viewModel.loadAhAccount() }
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val loginUrl = account?.loginUrl?.takeIf { it.isNotBlank() }
    val redirect = account?.redirectPrefix ?: "appie://login-exit"

    fun finish(url: String) {
        if (connecting) return
        connecting = true
        scope.launch {
            viewModel.connectAh(url)
                .onSuccess { viewModel.notify("Logged in to Albert Heijn" + (it.memberName?.let { name -> " as $name" } ?: "")); onDone() }
                .onFailure { error = it.message ?: "Login failed"; connecting = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to Albert Heijn") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                connecting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator()
                    Text("Connecting your account…")
                }
                error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { error = null; attempt += 1 }) { Text("Try again") }
                }
                loginUrl == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                else -> androidx.compose.runtime.key(attempt) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        if (url.startsWith(redirect) || url.startsWith("appie:")) {
                                            if (request.url.getQueryParameter("code") != null) finish(url) else error = "AH did not return a login code"
                                            return true
                                        }
                                        return false
                                    }
                                }
                                loadUrl(loginUrl)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
