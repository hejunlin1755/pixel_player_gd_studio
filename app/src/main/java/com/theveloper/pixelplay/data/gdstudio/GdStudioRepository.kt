package com.theveloper.pixelplay.data.gdstudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.webkit.CookieManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GdStudioRepository @Inject constructor(
    okHttpClient: OkHttpClient,
    private val webGate: GdStudioWebGate,
    private val page: GdStudioPage
) {
    private val client = okHttpClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.host.contains("gdstudio")) {
                chain.proceed(request)
            } else {
                val next = request.newBuilder()
                    .header("User-Agent", GdStudioApi.WEB_UA)
                    .header("Referer", "https://music.gdstudio.org/")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                val cookie = runCatching { CookieManager.getInstance().getCookie(request.url.toString()) }.getOrNull()
                if (!cookie.isNullOrBlank()) next.header("Cookie", cookie)
                chain.proceed(next.build())
            }
        }
        .callTimeout(12, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gate = Mutex()
    private val requestTimes = ArrayDeque<Long>()
    private val fallbackNotices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val fallbackNotice = fallbackNotices.asSharedFlow()
    private val fallbackNoticeAt = ConcurrentHashMap<String, Long>()
    private val coverCache = ConcurrentHashMap<String, String>()
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    @Volatile private var workingBase: String? = null

    suspend fun search(
        source: String,
        keyword: String,
        page: Int = 1,
        count: Int = 20,
        album: Boolean = false
    ): List<Track> = withContext(Dispatchers.IO) {
        val stable = GdStudioApi.playbackSource(source)
        if (!GdStudioApi.isStableSource(stable)) {
            throw GdStudioException("这个音乐源当前不可用")
        }
        val name = keyword.trim()
        if (name.isEmpty()) return@withContext emptyList()
        val apiSource = if (album) "${stable}_album" else stable
        val body = get(
            mapOf(
                "types" to "search",
                "source" to apiSource,
                "name" to name,
                "count" to count.coerceIn(1, 50).toString(),
                "pages" to page.coerceAtLeast(1).toString()
            ),
            essential = false
        )
        GdStudioApi.parseSearch(body)
    }

    suspend fun streamUrl(source: String, trackId: String, br: String): String? = withContext(Dispatchers.IO) {
        val stable = GdStudioApi.playbackSource(source)
        val quality = GdStudioApi.normalizeQuality(br)
        if (!GdStudioApi.isStableSource(stable) || trackId.isBlank()) return@withContext null
        val cacheKey = "$stable|$trackId|$quality"
        streamCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < STREAM_CACHE_MS) return@withContext cached.url
        }
        val fallbacks = listOf(quality, "320", "192", "128").distinct()
        for (br in fallbacks) {
            val body = get(
                mapOf(
                    "types" to "url",
                    "source" to stable,
                    "id" to trackId,
                    "br" to br
                ),
                essential = true
            )
            val url = GdStudioApi.parseUrl(body)?.url
            if (!url.isNullOrBlank()) {
                remember(streamCache, cacheKey, CachedStream(url, System.currentTimeMillis()), 32)
                return@withContext url
            }
        }
        null
    }

    suspend fun coverUrl(source: String, picId: String, size: String = "300"): String? = withContext(Dispatchers.IO) {
        val stable = GdStudioApi.playbackSource(source)
        if (!GdStudioApi.isStableSource(stable) || picId.isBlank()) return@withContext null
        val cacheKey = "$stable|$picId|$size"
        coverCache[cacheKey]?.let { return@withContext it }
        val body = get(
            mapOf(
                "types" to "pic",
                "source" to stable,
                "id" to picId,
                "size" to if (size == "500") "500" else "300"
            ),
            essential = false
        )
        GdStudioApi.parsePic(body)?.also { remember(coverCache, cacheKey, it, 64) }
    }

    suspend fun lyrics(source: String, lyricId: String): String? = withContext(Dispatchers.IO) {
        val stable = GdStudioApi.playbackSource(source)
        if (!GdStudioApi.isStableSource(stable) || lyricId.isBlank()) return@withContext null
        val body = get(
            mapOf(
                "types" to "lyric",
                "source" to stable,
                "id" to lyricId
            ),
            essential = false
        )
        GdStudioApi.parseLyrics(body)
    }

    private suspend fun get(query: Map<String, String>, essential: Boolean): String {
        val webFallback = query["types"] == "search" || query["types"] == "search_album" || query["types"] == "url"
        try {
            return getFromApi(query, essential)
        } catch (error: HumanCheckException) {
            if (!webFallback) throw error
            webGate.awaitClearance()
            page.discard()
            try {
                return getFromApi(query, essential)
            } catch (again: Exception) {
                if (again is kotlinx.coroutines.CancellationException) throw again
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error.message == "请求太频繁，请稍后再试") throw error
            if (!webFallback) throw error
        }
        announceFallback(query)
        return try {
            page.query(query)
        } catch (_: HumanCheckException) {
            webGate.awaitClearance()
            page.discard()
            page.query(query)
        }
    }

    private suspend fun getFromApi(query: Map<String, String>, essential: Boolean): String {
        var last: Exception? = null
        var sawChallenge = false
        for (base in orderedEndpoints()) {
            if (!awaitSlot(essential)) {
                throw GdStudioException("请求太频繁，请稍后再试")
            }
            try {
                val body = fetch(base, query)
                workingBase = base
                return body
            } catch (_: HumanCheckException) {
                sawChallenge = true
                break
            } catch (error: Exception) {
                last = error
                if (workingBase == base) workingBase = null
            }
        }
        if (sawChallenge) throw HumanCheckException(GdStudioApi.SITE)
        throw last ?: GdStudioException("GD音乐台请求失败")
    }

    private fun announceFallback(query: Map<String, String>) {
        val type = query["types"] ?: return
        val message = when (type) {
            "search" -> "正在改用网站页面获取列表"
            "url" -> "正在改用网站页面获取下载地址"
            else -> return
        }
        val now = System.currentTimeMillis()
        val previous = fallbackNoticeAt[type] ?: 0L
        if (now - previous < FALLBACK_NOTICE_MS) return
        fallbackNoticeAt[type] = now
        fallbackNotices.tryEmit(message)
    }

    private fun <V> remember(cache: ConcurrentHashMap<String, V>, key: String, value: V, limit: Int) {
        if (cache.size >= limit) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[key] = value
    }

    private fun orderedEndpoints(): List<String> {
        val preferred = workingBase
        return if (preferred == null) {
            GdStudioApi.endpoints
        } else {
            listOf(preferred) + GdStudioApi.endpoints.filter { it != preferred }
        }
    }

    private fun fetch(base: String, query: Map<String, String>): String {
        val url = base.toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (isHumanCheck(response, body)) {
                throw HumanCheckException(url.toString())
            }
            if (!response.isSuccessful) {
                throw GdStudioException("GD音乐台请求失败（${response.code}）")
            }
            return body
        }
    }

    private fun isHumanCheck(response: Response, body: String): Boolean {
        if (GdStudioApi.looksLikePayload(body)) return false
        if (body.contains("503 Service Temporarily Unavailable")) return false
        if (body.contains("Invalid request")) return false
        if (response.header("cf-mitigated") != null) return true
        return body.contains("Just a moment", ignoreCase = true) ||
            body.contains("cf-turnstile", ignoreCase = true) ||
            body.contains("请完成以下操作")
    }

    private suspend fun awaitSlot(essential: Boolean): Boolean {
        val waitMs = gate.withLock {
            val now = System.currentTimeMillis()
            while (requestTimes.isNotEmpty() && now - requestTimes.first() >= WINDOW_MS) {
                requestTimes.removeFirst()
            }
            if (requestTimes.size < MAX_REQUESTS || essential) {
                if (requestTimes.size >= MAX_REQUESTS) {
                    requestTimes.removeFirst()
                }
                requestTimes.addLast(now)
                0L
            } else {
                -1L
            }
        }
        if (waitMs == 0L) return true
        delay(300)
        return false
    }

    private data class CachedStream(val url: String, val at: Long)

    companion object {
        private const val MAX_REQUESTS = 50
        private const val WINDOW_MS = 5 * 60 * 1000L
        private const val STREAM_CACHE_MS = 10 * 60 * 1000L
        private const val FALLBACK_NOTICE_MS = 10_000L
    }
}
