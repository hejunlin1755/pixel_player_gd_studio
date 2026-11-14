package com.theveloper.pixelplay.data.identify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class IdentifyRecord(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val at: Long
)

@Singleton
class IdentifyHistoryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, "identify-history.json")
    private val gate = Mutex()
    private val _records = MutableStateFlow(read())
    val records: StateFlow<List<IdentifyRecord>> = _records.asStateFlow()

    suspend fun add(song: RecognizedSong) {
        gate.withLock {
            val next = (listOf(
                IdentifyRecord(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    artworkUrl = song.artworkUrl,
                    at = System.currentTimeMillis()
                )
            ) + _records.value).take(MAX_RECORDS)
            _records.value = next
            runCatching { write(next) }
        }
    }

    private fun read(): List<IdentifyRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val title = item.optString("title")
                    if (title.isBlank()) continue
                    add(
                        IdentifyRecord(
                            title = title,
                            artist = item.optString("artist"),
                            album = item.optNullable("album"),
                            artworkUrl = item.optNullable("artwork"),
                            at = item.optLong("at")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<IdentifyRecord>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("artist", item.artist)
                    .put("album", item.album ?: JSONObject.NULL)
                    .put("artwork", item.artworkUrl ?: JSONObject.NULL)
                    .put("at", item.at)
            )
        }
        file.writeText(array.toString(), Charsets.UTF_8)
    }

    private fun JSONObject.optNullable(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).ifBlank { null }
    }

    private companion object {
        const val MAX_RECORDS = 80
    }
}
