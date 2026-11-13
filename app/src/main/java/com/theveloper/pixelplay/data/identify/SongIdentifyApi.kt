package com.theveloper.pixelplay.data.identify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class SongIdentifyApi @Inject constructor(
    okHttpClient: OkHttpClient
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(file: File): RecognizedSong? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("source", "cli")
            .addFormDataPart("file", "clip.wav", file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://songfinder.dev/api/music/recognize")
            .header("X-SongFinder-Client", "cli")
            .post(body)
            .build()
        val payload = execute(request)
        val data = payload.optJSONObject("data") ?: return@withContext null
        val title = data.optString("title").trim()
        val matched = if (data.has("matched")) data.optBoolean("matched") else title.isNotBlank()
        if (!matched || title.isBlank()) return@withContext null
        val artist = data.optString("artist").trim()
        RecognizedSong(
            title = title,
            artist = artist.ifBlank { "未知歌手" },
            album = data.optString("album").trim().ifBlank { null },
            artworkUrl = data.optString("artworkUrl").trim().ifBlank { null }
        )
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 429) {
                    throw IllegalStateException("识别太频繁，等几秒再试")
                }
                val json = runCatching { JSONObject(text) }.getOrNull()
                val message = json?.optString("message").orEmpty().ifBlank { "识别失败（${response.code}）" }
                if (!response.isSuccessful || json == null || json.optInt("code") != 0) {
                    throw IllegalStateException(message)
                }
                if (continuation.isActive) continuation.resume(json)
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
