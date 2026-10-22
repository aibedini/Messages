package com.autonomousone.messages.evechat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.ui.components.MessagesBottomNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveChatScreen(navController: NavController) {
    val viewModel: EveChatViewModel = viewModel()
    val loginState by viewModel.loginState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()

    Scaffold(
        topBar = {
            if (loginState is EveChatViewModel.LoginState.Success) {
                TopAppBar(
                    title = { Text("پشتیبانی") },
                    actions = {
                        IconButton(onClick = { viewModel.logout() }) {
                            Icon(
                                Icons.Default.Logout,
                                contentDescription = "خروج"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        bottomBar = {
            MessagesBottomNav(navController = navController)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (loginState) {
                is EveChatViewModel.LoginState.Success -> {
                    EveChatWebView(
                        serverUrl = serverUrl,
                        accessToken = viewModel.prefs.accessToken,
                        refreshToken = viewModel.prefs.refreshToken
                    )
                }
                else -> {
                    EveChatLoginSheet(
                        loginState = loginState,
                        savedServerUrl = serverUrl,
                        onLogin = viewModel::login
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EveChatWebView(
    serverUrl: String,
    accessToken: String,
    refreshToken: String
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        userAgentString = settings.userAgentString + " EveChatAndroid"
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val host = request?.url?.host ?: return false
                            val serverHost = android.net.Uri.parse(serverUrl).host
                            return host != serverHost
                        }
                    }

                    webChromeClient = WebChromeClient()

                    webView = this

                    val safeAccess = accessToken.replace("\\", "\\\\").replace("'", "\\'")
                    val safeRefresh = refreshToken.replace("\\", "\\\\").replace("'", "\\'")
                    val baseUrl = serverUrl.trimEnd('/')
                    val bootstrapHtml = """
                        <!DOCTYPE html>
                        <html><head><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <style>body{display:flex;align-items:center;justify-content:center;
                        height:100vh;margin:0;font-family:sans-serif;background:#fafafa}
                        </style></head><body><p>Loading...</p>
                        <script>
                        try{localStorage.setItem('evechat:access','$safeAccess');
                        localStorage.setItem('evechat:refresh','$safeRefresh');
                        }catch(e){}
                        window.location.replace('/inbox');
                        </script></body></html>
                    """.trimIndent()

                    loadDataWithBaseURL(
                        baseUrl,
                        bootstrapHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
