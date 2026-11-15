package com.theveloper.pixelplay.data.gdstudio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the GD Studio website and lets that page run its own search and download request.
 */
@Singleton
class GdStudioPage @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    private val bridge = PageBridge()

    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    @Volatile private var delivery: ((String) -> Unit)? = null

    suspend fun query(params: Map<String, String>): String = lock.withLock {
        repeat(4) { attempt ->
            ensurePage()
            val body = ask(params)
            if (body != NOT_READY) return@withLock interpret(body)
            pageReady = false
            if (attempt < 3) delay(600)
        }
        throw GdStudioException("网页还没准备好")
    }

    private suspend fun ensurePage() {
        if (pageReady && webView != null) return
        if (webView == null) createWebView()
        val deadline = System.currentTimeMillis() + PAGE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (eval(READY_SCRIPT, 4_000)) {
                "READY" -> {
                    pageReady = true
                    return
                }
                "CHALLENGE" -> throw HumanCheckException(GdStudioApi.SITE)
                else -> delay(500)
            }
        }
        throw GdStudioException("网页没有打开")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val created = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                val instance = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = GdStudioApi.WEB_UA
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(bridge, BRIDGE)
                    loadUrl(GdStudioApi.SITE)
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(instance, true)
                created.set(instance)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS) || created.get() == null) {
            throw GdStudioException("网页没有打开")
        }
        webView = created.get()
    }

    private fun ask(params: Map<String, String>): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("")
        delivery = { value ->
            result.set(value)
            latch.countDown()
        }
        val script = requestScript(params)
        mainHandler.post {
            val view = webView
            if (view == null) {
                delivery?.invoke(NOT_READY)
                return@post
            }
            view.evaluateJavascript(script, null)
        }
        if (!latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            delivery = null
            throw GdStudioException("网页查询超时")
        }
        delivery = null
        return result.get()
    }

    private fun eval(script: String, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        mainHandler.post {
            val view = webView
            if (view == null) {
                latch.countDown()
                return@post
            }
            view.evaluateJavascript(script) { value ->
                result.set(decodeJs(value))
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result.get()
    }

    private fun interpret(body: String): String {
        if (body.startsWith(HTTP_MARK)) {
            val status = body.removePrefix(HTTP_MARK).substringBefore("__")
            val text = body.substringAfter("${status}__", "")
            if (isChallenge(text)) throw HumanCheckException(GdStudioApi.SITE)
            throw GdStudioException("网页查询失败（$status）")
        }
        if (body.startsWith(ERR_MARK)) {
            throw GdStudioException("网页查询失败")
        }
        if (isChallenge(body)) throw HumanCheckException(GdStudioApi.SITE)
        if (!GdStudioApi.looksLikePayload(body)) {
            throw GdStudioException("网页没有返回歌曲")
        }
        return body
    }

    private fun isChallenge(body: String): Boolean {
        return body.contains("Just a moment", ignoreCase = true) ||
            body.contains("cf-turnstile", ignoreCase = true) ||
            body.contains("请完成以下操作")
    }

    private fun requestScript(params: Map<String, String>): String {
        val quoted = JSONObject.quote(JSONObject(params).toString())
        return """
            (function(){
              try {
                var q = JSON.parse(__QUERY__);
                if (typeof crc32 !== 'function' || typeof urlEncode !== 'function' || typeof sourceNode !== 'function' || typeof ${'$'} === 'undefined') {
                  $BRIDGE.onResult('$NOT_READY');
                  return;
                }
                sourceNode(String(q.source || 'netease'));
                var kind = String(q.types || '');
                var signed = (kind === 'search' || kind === 'search_album') ? urlEncode(q.name || '') : urlEncode(String(q.id || ''));
                var data = 'types=' + kind + '&source=' + (q.source || '') + '&s=' + crc32(String(signed));
                if (kind === 'search' || kind === 'search_album') {
                  data += '&count=' + (q.count || '20') + '&pages=' + (q.pages || '1') + '&name=' + signed;
                } else {
                  data += '&id=' + signed;
                  if (q.br) data += '&br=' + q.br;
                  if (q.size) data += '&size=' + q.size;
                }
                var api = (typeof mkPlayer !== 'undefined' && mkPlayer.api) ? mkPlayer.api : (location.origin + '/api.php');
                var asJson = (typeof isSameOrigin === 'function' && isSameOrigin(api)) ? 'json' : 'jsonp';
                ${'$'}.ajax({
                  type: 'GET',
                  url: api,
                  data: data,
                  dataType: asJson,
                  success: function(payload) {
                    $BRIDGE.onResult(typeof payload === 'string' ? payload : JSON.stringify(payload));
                  },
                  error: function(xhr) {
                    var text = xhr && xhr.responseText ? String(xhr.responseText).slice(0, 400) : '';
                    $BRIDGE.onResult('$HTTP_MARK' + (xhr ? xhr.status : 0) + '__' + text);
                  }
                });
              } catch (error) {
                $BRIDGE.onResult('$ERR_MARK' + error);
              }
            })();
        """.trimIndent().replace("__QUERY__", quoted)
    }

    private fun decodeJs(raw: String?): String? {
        if (raw == null || raw == "null" || raw.isBlank()) return null
        return if (raw.startsWith("\"")) JSONArray("[$raw]").getString(0) else raw
    }

    private inner class PageBridge {
        @JavascriptInterface
        fun onResult(value: String?) {
            delivery?.invoke(value.orEmpty())
        }
    }

    companion object {
        private const val BRIDGE = "GdPage"
        private const val NOT_READY = "__NOT_READY__"
        private const val HTTP_MARK = "__HTTP__"
        private const val ERR_MARK = "__ERR__"
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private val READY_SCRIPT = """
            (function(){
              var title = document.title || '';
              var text = document.body ? (document.body.innerText || '').slice(0, 400) : '';
              if (title.indexOf('Just a moment') >= 0 || text.indexOf('请完成以下操作') >= 0 || text.indexOf('cf-turnstile') >= 0) return 'CHALLENGE';
              if (typeof crc32 === 'function' && typeof urlEncode === 'function' && typeof sourceNode === 'function' && typeof ${'$'} !== 'undefined') return 'READY';
              return 'WAIT';
            })();
        """.trimIndent()
    }
}
