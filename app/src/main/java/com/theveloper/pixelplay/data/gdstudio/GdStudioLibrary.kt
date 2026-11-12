package com.theveloper.pixelplay.data.gdstudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts GD Studio results into the normal search box and saves a chosen track
 * on disk with its cover and lyrics.
 */
@Singleton
class GdStudioLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GdStudioRepository,
    private val musicDao: MusicDao,
    okHttpClient: OkHttpClient
) {
    private val downloadClient = okHttpClient.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val tracks = mutableListOf<Track>()
        for (source in GdStudioApi.stableSources) {
            val page = runCatching {
                repository.search(source, query, count = 8)
            }.getOrDefault(emptyList())
            tracks += page
        }
        tracks.distinctBy { "${it.source}:${it.id}" }.map { track ->
            val songId = stableId("song:${track.source}:${track.id}")
            musicDao.getSongByIdOnce(songId)?.toSong() ?: remoteSong(track, songId)
        }
    }

    suspend fun ensureLocal(song: Song): Song = withContext(Dispatchers.IO) {
        val ref = GdStudioApi.parseTrackUri(song.contentUriString) ?: return@withContext song
        val songId = song.id.toLongOrNull() ?: stableId("song:${ref.source}:${ref.trackId}")
        musicDao.getSongByIdOnce(songId)?.let { existing ->
            if (existing.filePath.isNotBlank() && File(existing.filePath).exists()) {
                return@withContext existing.toSong()
            }
        }

        val streamUrl = repository.streamUrl(ref.source, ref.trackId, ref.br)
            ?: throw GdStudioException("没有拿到音频地址")
        val directory = File(context.getExternalFilesDir(null), "gdstudio").apply { mkdirs() }
        val audioFile = File(directory, "$songId${extensionOf(streamUrl, ref.br)}")
        download(streamUrl, audioFile)

        val coverFile = repository.coverUrl(ref.source, picIdOf(song))?.let { coverUrl ->
            val file = File(directory, "$songId.jpg")
            runCatching { download(coverUrl, file) }.getOrNull()
            file.takeIf { it.exists() && it.length() > 0L }
        }
        val lyrics = runCatching { repository.lyrics(ref.source, ref.lyricId) }.getOrNull()
        val duration = readDuration(audioFile)
        val artistName = song.artist.ifBlank { "未知歌手" }
        val albumName = song.album.ifBlank { "未知专辑" }
        val artistId = stableId("artist:$artistName")
        val albumId = stableId("album:$artistName:$albumName")
        val now = System.currentTimeMillis()

        musicDao.insertArtists(
            listOf(ArtistEntity(id = artistId, name = artistName, trackCount = 1))
        )
        musicDao.insertAlbums(
            listOf(
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    artistName = artistName,
                    artistId = artistId,
                    albumArtUriString = coverFile?.let { Uri.fromFile(it).toString() },
                    songCount = 1,
                    dateAdded = now,
                    year = 0
                )
            )
        )
        val entity = SongEntity(
            id = songId,
            title = song.title,
            artistName = artistName,
            artistId = artistId,
            albumArtist = artistName,
            albumName = albumName,
            albumId = albumId,
            contentUriString = Uri.fromFile(audioFile).toString(),
            albumArtUriString = coverFile?.let { Uri.fromFile(it).toString() },
            duration = duration,
            genre = null,
            filePath = audioFile.absolutePath,
            parentDirectoryPath = directory.absolutePath,
            lyrics = lyrics,
            dateAdded = now,
            mimeType = mimeOf(audioFile),
            artistsJson = """[{"id":$artistId,"name":${jsonString(artistName)},"primary":true}]"""
        )
        musicDao.insertSongs(listOf(entity))
        musicDao.insertSongArtistCrossRefs(
            listOf(SongArtistCrossRef(songId = songId, artistId = artistId, isPrimary = true))
        )
        entity.toSong()
    }

    private fun remoteSong(track: Track, songId: Long): Song {
        val artistName = track.artists.joinToString(", ").ifBlank { "未知歌手" }
        val albumName = track.album.ifBlank { "未知专辑" }
        val artistId = stableId("artist:$artistName")
        val albumId = stableId("album:$artistName:$albumName")
        return Song(
            id = songId.toString(),
            title = track.name,
            artist = artistName,
            artistId = artistId,
            artists = listOf(ArtistRef(id = artistId, name = artistName, isPrimary = true)),
            album = albumName,
            albumId = albumId,
            path = "",
            contentUriString = GdStudioApi.trackUri(track.source, track.id, track.lyricId, "320") +
                "&pic=${Uri.encode(track.picId)}",
            albumArtUriString = null,
            duration = 0L,
            mimeType = null,
            bitrate = null,
            sampleRate = null
        )
    }

    private fun picIdOf(song: Song): String {
        return Uri.parse(song.contentUriString).getQueryParameter("pic").orEmpty()
    }

    private fun download(url: String, destination: File) {
        val request = Request.Builder().url(url).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GdStudioException("下载失败（${response.code}）")
            val body = response.body ?: throw GdStudioException("下载失败")
            destination.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
        if (destination.length() == 0L) {
            destination.delete()
            throw GdStudioException("下载到的文件是空的")
        }
    }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extensionOf(url: String, br: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        if (ext.length in 2..4) return ".$ext"
        return if (br == "740" || br == "999") ".flac" else ".mp3"
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    private fun jsonString(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    companion object {
        fun stableId(key: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            var value = 0L
            for (index in 0 until 7) {
                value = (value shl 8) or (digest[index].toLong() and 0xff)
            }
            return value and Long.MAX_VALUE
        }
    }
}
