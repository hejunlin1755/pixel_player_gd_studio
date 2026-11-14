package com.theveloper.pixelplay.data.gdstudio

import android.net.Uri
import com.theveloper.pixelplay.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * GD Studio online music API (music.gdstudio.xyz).
 * Credit: GD音乐台(music.gdstudio.xyz).
 */
object GdStudioApi {
    const val BASE = "https://music.gdstudio.org/api.php"
    val endpoints = listOf(
        "https://music.gdstudio.org/api.php",
        "https://music.gdstudio.xyz/api.php",
        "https://music-api.gdstudio.xyz/api.php",
        "https://music-api-cn.gdstudio.xyz/api.php",
        "https://music-api-hk.gdstudio.xyz/api.php",
        "https://music-api-us.gdstudio.xyz/api.php"
    )
    const val ATTRIBUTION = "GD音乐台(music.gdstudio.xyz)"
    const val SCHEME = "gdstudio"

    val stableSources = listOf("netease", "joox", "bilibili")
    val qualities = listOf("128", "192", "320", "740", "999")

    fun playbackSource(source: String): String = source.removeSuffix("_album")

    fun isStableSource(source: String): Boolean = playbackSource(source) in stableSources

    fun normalizeQuality(br: String?): String = br?.takeIf { it in qualities } ?: "320"

    fun trackUri(source: String, trackId: String, lyricId: String, br: String): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority("track")
            .appendQueryParameter("source", playbackSource(source))
            .appendQueryParameter("id", trackId)
            .appendQueryParameter("lyric", lyricId.ifBlank { trackId })
            .appendQueryParameter("br", normalizeQuality(br))
            .build()
            .toString()
        // Extra query keys such as pic are ignored by playback and kept for download.
    }

    fun parseTrackUri(raw: String): TrackRef? {
        val uri = Uri.parse(raw)
        if (uri.scheme != SCHEME) return null
        val source = uri.getQueryParameter("source")?.let(::playbackSource) ?: return null
        if (!isStableSource(source)) return null
        val id = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
        val lyric = uri.getQueryParameter("lyric")?.takeIf { it.isNotBlank() } ?: id
        val br = normalizeQuality(uri.getQueryParameter("br"))
        return TrackRef(source, id, lyric, br)
    }

    fun parseSearch(body: String): List<Track> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        if (trimmed.startsWith("{")) {
            val message = JSONObject(trimmed).optString("error")
                .ifBlank { JSONObject(trimmed).optString("msg") }
                .ifBlank { "GD音乐台没有返回曲目" }
            throw GdStudioException(message)
        }
        if (!trimmed.startsWith("[")) return emptyList()
        val array = JSONArray(trimmed)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val source = playbackSource(item.optString("source"))
                val id = item.optString("id")
                if (!isStableSource(source) || id.isBlank()) continue
                val artists = item.optJSONArray("artist")
                val artistNames = buildList {
                    if (artists != null) {
                        for (artistIndex in 0 until artists.length()) {
                            val name = artists.optString(artistIndex)
                            if (name.isNotBlank()) add(name)
                        }
                    }
                }
                add(
                    Track(
                        id = id,
                        name = item.optString("name").ifBlank { "未知曲目" },
                        artists = artistNames,
                        album = item.optString("album"),
                        picId = item.optString("pic_id"),
                        lyricId = item.optString("lyric_id").ifBlank { id },
                        source = source
                    )
                )
            }
        }
    }

    fun parseUrl(body: String): StreamLink? {
        val json = body.trim().takeIf { it.startsWith("{") }?.let(::JSONObject) ?: return null
        val url = json.optString("url").takeIf { it.startsWith("https://") } ?: return null
        return StreamLink(
            url = url,
            br = normalizeQuality(json.optString("br").takeIf { it.isNotBlank() }),
            size = json.optLong("size", -1L)
        )
    }

    fun parsePic(body: String): String? {
        val json = body.trim().takeIf { it.startsWith("{") }?.let(::JSONObject) ?: return null
        return json.optString("url").takeIf { it.startsWith("https://") }
    }

    fun parseLyrics(body: String): String? {
        val json = body.trim().takeIf { it.startsWith("{") }?.let(::JSONObject) ?: return null
        return mergeLyrics(json.optString("lyric"), json.optString("tlyric"))
    }

    fun mergeLyrics(lyric: String, tlyric: String): String? {
        val original = lyric.trim()
        val translation = tlyric.trim()
        if (original.isEmpty() && translation.isEmpty()) return null
        if (translation.isEmpty()) return original
        if (original.isEmpty()) return translation
        val translatedByStamp = translation.lineSequence().mapNotNull { line ->
            val stamp = stampOf(line) ?: return@mapNotNull null
            val text = line.substringAfter(']').trim()
            if (text.isEmpty()) null else stamp to text
        }.toMap()
        return original.lineSequence().joinToString("\n") { line ->
            val stamp = stampOf(line)
            val extra = stamp?.let { translatedByStamp[it] }
            if (extra.isNullOrBlank()) line else "$line\n$stamp]$extra"
        }
    }

    private fun stampOf(line: String): String? {
        if (!line.startsWith("[")) return null
        val end = line.indexOf(']')
        if (end <= 1) return null
        return line.substring(0, end)
    }
}

data class TrackRef(
    val source: String,
    val trackId: String,
    val lyricId: String,
    val br: String
)

data class Track(
    val id: String,
    val name: String,
    val artists: List<String>,
    val album: String,
    val picId: String,
    val lyricId: String,
    val source: String
) {
    fun toSong(br: String, coverUrl: String? = null): Song {
        val uri = GdStudioApi.trackUri(source, id, lyricId, br)
        val artistLabel = artists.joinToString(", ").ifBlank { "未知歌手" }
        return Song(
            id = "gdstudio:$source:$id",
            title = name,
            artist = artistLabel,
            artistId = -1L,
            album = album.ifBlank { "未知专辑" },
            albumId = -1L,
            path = uri,
            contentUriString = uri,
            albumArtUriString = coverUrl,
            duration = 0L,
            mimeType = null,
            bitrate = null,
            sampleRate = null
        )
    }
}

data class StreamLink(
    val url: String,
    val br: String,
    val size: Long
)

class GdStudioException(message: String) : Exception(message)
