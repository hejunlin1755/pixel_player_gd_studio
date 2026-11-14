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
import androidx.compose.runtime.getValue
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
import javax.inject.Inject

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
                    text = "这是网站自己的验证。点完后按完成，再继续搜索。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ChallengeWebView(
                    url = current.url,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                TextButton(
                    onClick = { viewModel.gate.finished() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text("完成")
                }
                TextButton(
                    onClick = { viewModel.gate.cancel() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
    modifier: Modifier = Modifier
) {
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
                loadUrl(url)
            }
        }
    )
}
