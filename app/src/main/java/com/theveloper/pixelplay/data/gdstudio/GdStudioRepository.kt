package com.theveloper.pixelplay.data.gdstudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GdStudioRepository @Inject constructor(
    okHttpClient: OkHttpClient
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(25, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gate = Mutex()
    private val requestTimes = ArrayDeque<Long>()
    private val coverCache = ConcurrentHashMap<String, String>()
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

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
            )
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
        val body = get(
            mapOf(
                "types" to "url",
                "source" to stable,
                "id" to trackId,
                "br" to quality
            )
        )
        GdStudioApi.parseUrl(body)?.url?.also { url ->
            streamCache[cacheKey] = CachedStream(url, System.currentTimeMillis())
        }
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
            )
        )
        GdStudioApi.parsePic(body)?.also { coverCache[cacheKey] = it }
    }

    suspend fun lyrics(source: String, lyricId: String): String? = withContext(Dispatchers.IO) {
        val stable = GdStudioApi.playbackSource(source)
        if (!GdStudioApi.isStableSource(stable) || lyricId.isBlank()) return@withContext null
        val body = get(
            mapOf(
                "types" to "lyric",
                "source" to stable,
                "id" to lyricId
            )
        )
        GdStudioApi.parseLyrics(body)
    }

    private suspend fun get(query: Map<String, String>): String {
        awaitSlot()
        val url = GdStudioApi.BASE.toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GdStudioException("GD音乐台请求失败（${response.code}）")
            }
            return body
        }
    }

    private suspend fun awaitSlot() {
        while (true) {
            val waitMs = gate.withLock {
                val now = System.currentTimeMillis()
                while (requestTimes.isNotEmpty() && now - requestTimes.first() >= WINDOW_MS) {
                    requestTimes.removeFirst()
                }
                if (requestTimes.size < MAX_REQUESTS) {
                    requestTimes.addLast(now)
                    0L
                } else {
                    (WINDOW_MS - (now - requestTimes.first()) + 200L).coerceAtLeast(200L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    private data class CachedStream(val url: String, val at: Long)

    companion object {
        private const val MAX_REQUESTS = 50
        private const val WINDOW_MS = 5 * 60 * 1000L
        private const val STREAM_CACHE_MS = 10 * 60 * 1000L
    }
}
