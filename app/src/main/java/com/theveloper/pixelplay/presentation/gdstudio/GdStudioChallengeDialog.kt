package com.theveloper.pixelplay.presentation.gdstudio

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.gdstudio.GdStudioApi
import com.theveloper.pixelplay.data.gdstudio.GdStudioWebGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import javax.inject.Inject
import kotlin.coroutines.resume

@HiltViewModel
class GdStudioChallengeViewModel @Inject constructor(
    val gate: GdStudioWebGate
) : ViewModel()

@Composable
fun GdStudioChallengeHost(viewModel: GdStudioChallengeViewModel = hiltViewModel()) {
    val prompt by viewModel.gate.prompt.collectAsStateWithLifecycle()
    val current = prompt ?: return
    Dialog(
        onDismissRequest = { viewModel.gate.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "请完成人机验证",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp)
                )
                Text(
                    text = "点完验证后这个窗口会自己关掉。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ChallengeWebView(
                    url = current.url,
                    onPayload = viewModel.gate::submit,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                TextButton(
                    onClick = { viewModel.gate.cancel() },
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text("取消")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChallengeWebView(
    url: String,
    onPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var holder by remember(url) {
        mutableStateOf<WebView?>(null)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = GdStudioApi.WEB_UA
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                holder = this
                loadUrl(url)
            }
        }
    )
    LaunchedEffect(holder, url) {
        val view = holder ?: return@LaunchedEffect
        while (isActive) {
            delay(800)
            val text = readPage(view)
            if (GdStudioApi.looksLikePayload(text)) {
                CookieManager.getInstance().flush()
                onPayload(text)
                break
            }
        }
    }
}

private suspend fun readPage(view: WebView): String = suspendCancellableCoroutine { continuation ->
    view.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { encoded ->
        if (continuation.isActive) continuation.resume(decodeJsString(encoded))
    }
}

private fun decodeJsString(encoded: String?): String {
    if (encoded.isNullOrBlank() || encoded == "null") return ""
    return runCatching { JSONArray("[$encoded]").getString(0) }.getOrDefault("")
}
