package com.theveloper.pixelplay.data.gdstudio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val userPreferences: UserPreferencesRepository,
    private val musicDao: MusicDao,
    okHttpClient: OkHttpClient
) {
    val fallbackNotice = repository.fallbackNotice

    private val downloadClient = okHttpClient.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun search(query: String): List<Song> {
        if (query.isBlank()) return emptyList()
        val outcomes = coroutineScope {
            GdStudioApi.stableSources.map { source ->
                async(Dispatchers.IO) {
                    runCatching { repository.search(source, query, count = 8) }
                }
            }.awaitAll()
        }
        if (outcomes.all { it.isFailure }) {
            throw outcomes.firstNotNullOf { it.exceptionOrNull() }
        }
        val tracks = outcomes.mapNotNull { it.getOrNull() }.flatten()
        val quality = userPreferences.gdCacheQualityFlow.first()
        val songs = mutableListOf<Song>()
        for (track in tracks.distinctBy { "${it.source}:${it.id}" }) {
            val songId = stableId("song:${track.source}:${track.id}")
            val saved = musicDao.getSongByIdOnce(songId)?.toSong()
            songs += saved ?: remoteSong(track, songId, quality)
        }
        return attachCovers(songs)
    }

    private suspend fun attachCovers(songs: List<Song>): List<Song> = coroutineScope {
        val needed = songs.mapNotNull { song ->
            if (!song.albumArtUriString.isNullOrBlank()) return@mapNotNull null
            val ref = GdStudioApi.parseTrackUri(song.contentUriString) ?: return@mapNotNull null
            val pic = picIdOf(song)
            if (pic.isBlank()) null else ref.source to pic
        }.distinct()
        val covers = needed.take(8).map { (source, pic) ->
            async {
                (source to pic) to runCatching { repository.coverUrl(source, pic) }.getOrNull()
            }
        }.awaitAll().toMap()
        songs.map { song ->
            if (!song.albumArtUriString.isNullOrBlank()) return@map song
            val ref = GdStudioApi.parseTrackUri(song.contentUriString) ?: return@map song
            val url = covers[ref.source to picIdOf(song)]
            if (url.isNullOrBlank()) song else song.copy(albumArtUriString = url)
        }
    }

    fun asPlayable(song: Song): Song {
        val ref = GdStudioApi.parseTrackUri(song.contentUriString) ?: return song
        val songId = stableId("song:${ref.source}:${ref.trackId}")
        if (song.id == songId.toString()) return song
        val artistName = song.artist.ifBlank { "未知歌手" }
        val albumName = song.album.ifBlank { "未知专辑" }
        val artistId = stableId("artist:$artistName")
        val albumId = stableId("album:$artistName:$albumName")
        return song.copy(
            id = songId.toString(),
            artistId = artistId,
            albumId = albumId,
            artists = listOf(ArtistRef(id = artistId, name = artistName, isPrimary = true))
        )
    }

    fun withQuality(song: Song, br: String): Song {
        val ref = GdStudioApi.parseTrackUri(song.contentUriString) ?: return song
        val pic = picIdOf(song)
        val uri = GdStudioApi.trackUri(ref.source, ref.trackId, ref.lyricId, br) +
            "&pic=${Uri.encode(pic)}"
        return song.copy(contentUriString = uri)
    }

    /** Keeps a favorite visible in the library before the audio is saved. */
    suspend fun remember(song: Song): Song = withContext(Dispatchers.IO) {
        val playable = asPlayable(song)
        val songId = playable.id.toLong()
        val existing = musicDao.getSongByIdOnce(songId)
        if (existing != null && isLibraryFile(existing.filePath)) {
            return@withContext existing.toSong()
        }
        val cache = playable.path.takeIf { isCacheFile(it) }?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        saveSong(
            song = playable,
            audio = cache,
            cover = null,
            lyrics = playable.lyrics,
            duration = playable.duration,
            contentUri = playable.contentUriString,
            parent = CLOUD_PARENT,
            sourceType = SourceType.LOCAL
        )
        playable
    }

    suspend fun ensureCached(song: Song): Song = withContext(Dispatchers.IO) {
        val quality = userPreferences.gdCacheQualityFlow.first()
        val playable = withQuality(asPlayable(song), quality)
        val songId = playable.id.toLong()
        musicDao.getSongByIdOnce(songId)?.let { existing ->
            if (isLibraryFile(existing.filePath)) return@withContext existing.toSong()
            if (isCacheFile(existing.filePath) &&
                cachedAtQuality(existing.filePath, songId, quality) &&
                File(existing.filePath).let { it.isFile && it.length() > 0L }
            ) {
                return@withContext existing.toSong().copy(contentUriString = playable.contentUriString)
            }
        }
        val ready = playable.path.takeIf { isCacheFile(it) && cachedAtQuality(it, songId, quality) }?.let(::File)
        if (ready != null && ready.isFile && ready.length() > 0L) {
            return@withContext playable
        }
        val ref = GdStudioApi.parseTrackUri(playable.contentUriString)
            ?: throw GdStudioException("无法识别这首歌")
        val directory = File(context.cacheDir, "gdstudio").apply { mkdirs() }
        val audioFile = findCachedAudio(songId, ref.br) ?: run {
            val streamUrl = repository.streamUrl(ref.source, ref.trackId, ref.br)
                ?: throw GdStudioException("没有拿到音频地址")
            val created = File(directory, "$songId-${ref.br}${extensionOf(streamUrl, ref.br)}")
            if (!created.isFile || created.length() == 0L) {
                download(streamUrl, created)
            }
            created
        }
        val coverFile = cacheCover(directory, songId, ref.source, picIdOf(playable))
        val lyrics = runCatching { repository.lyrics(ref.source, ref.lyricId) }.getOrNull()
        val duration = readDuration(audioFile)
        val cached = playable.copy(
            path = audioFile.absolutePath,
            albumArtUriString = coverFile?.let { Uri.fromFile(it).toString() } ?: playable.albumArtUriString,
            lyrics = lyrics ?: playable.lyrics,
            duration = duration,
            mimeType = mimeOf(audioFile)
        )
        if (musicDao.getSongByIdOnce(songId) != null) {
            saveSong(
                song = cached,
                audio = audioFile,
                cover = coverFile,
                lyrics = cached.lyrics,
                duration = duration,
                contentUri = cached.contentUriString,
                parent = CLOUD_PARENT,
                sourceType = SourceType.LOCAL
            )
        }
        cached
    }

    suspend fun ensureLocal(song: Song): Song = withContext(Dispatchers.IO) {
        val playable = asPlayable(song)
        val ref = GdStudioApi.parseTrackUri(playable.contentUriString) ?: return@withContext playable
        val songId = playable.id.toLong()

        val existingFile = musicDao.getSongByIdOnce(songId)?.filePath?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L && isPublicMusic(it.absolutePath) }
        if (existingFile != null) {
            return@withContext musicDao.getSongByIdOnce(songId)?.toSong() ?: playable
        }
        val privateCopy = musicDao.getSongByIdOnce(songId)?.filePath?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L && isLibraryFile(it.absolutePath) }
        val cachedAudio = privateCopy ?: findCachedAudio(songId, ref.br)
        val staged = if (cachedAudio != null) {
            cachedAudio
        } else {
            val streamUrl = repository.streamUrl(ref.source, ref.trackId, ref.br)
                ?: throw GdStudioException("没有拿到音频地址")
            val temp = File(context.cacheDir, "gdstudio").apply { mkdirs() }
            File(temp, "$songId-download${extensionOf(streamUrl, ref.br)}").also { download(streamUrl, it) }
        }
        val published = publishToMusic(staged, musicFileName(playable, staged.extension.ifBlank { "mp3" }))
        if (staged.absolutePath != published.file.absolutePath) staged.delete()

        val coverFile = cacheCover(File(context.cacheDir, "gdstudio").apply { mkdirs() }, songId, ref.source, picIdOf(playable))
        val lyrics = runCatching { repository.lyrics(ref.source, ref.lyricId) }.getOrNull()
        val duration = readDuration(published.file)
        saveSong(
            song = playable,
            audio = published.file,
            cover = coverFile,
            lyrics = lyrics,
            duration = duration,
            contentUri = published.uri.toString(),
            parent = published.file.parent.orEmpty(),
            sourceType = SourceType.LOCAL
        )
    }

    private data class PublishedSong(val uri: Uri, val file: File)

    private fun publishToMusic(source: File, displayName: String): PublishedSong {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(source))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$MUSIC_FOLDER/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw GdStudioException("无法保存到 Music 文件夹")
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw GdStudioException("无法保存到 Music 文件夹")
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val file = File(path ?: "/storage/emulated/0/$MUSIC_FOLDER/$displayName")
        return PublishedSong(uri, file)
    }

    private fun musicFileName(song: Song, extension: String): String {
        val raw = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" - ")
        val safe = raw.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(80).ifBlank { song.id }
        return "$safe.$extension"
    }

    private suspend fun saveSong(
        song: Song,
        audio: File?,
        cover: File?,
        lyrics: String?,
        duration: Long,
        contentUri: String,
        parent: String,
        sourceType: Int
    ): Song {
        val songId = song.id.toLong()
        val artistName = song.artist.ifBlank { "未知歌手" }
        val albumName = song.album.ifBlank { "未知专辑" }
        val artistId = song.artistId.takeIf { it > 0L } ?: stableId("artist:$artistName")
        val albumId = song.albumId.takeIf { it > 0L } ?: stableId("album:$artistName:$albumName")
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
                    albumArtUriString = cover?.let { Uri.fromFile(it).toString() },
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
            contentUriString = contentUri,
            albumArtUriString = cover?.let { Uri.fromFile(it).toString() } ?: song.albumArtUriString,
            duration = duration,
            genre = null,
            filePath = audio?.absolutePath.orEmpty(),
            parentDirectoryPath = parent,
            lyrics = lyrics,
            dateAdded = now,
            mimeType = audio?.let(::mimeOf),
            sourceType = sourceType,
            artistsJson = """[{"id":$artistId,"name":${jsonString(artistName)},"primary":true}]"""
        )
        musicDao.insertSongs(listOf(entity))
        musicDao.insertSongArtistCrossRefs(
            listOf(SongArtistCrossRef(songId = songId, artistId = artistId, isPrimary = true))
        )
        return entity.toSong()
    }

    private suspend fun cacheCover(directory: File, songId: Long, source: String, picId: String): File? {
        if (picId.isBlank()) return null
        val file = File(directory, "$songId.jpg")
        if (file.isFile && file.length() > 0L) return file
        val coverUrl = runCatching { repository.coverUrl(source, picId) }.getOrNull() ?: return null
        return runCatching { download(coverUrl, file) }.fold(
            onSuccess = { file.takeIf { it.isFile && it.length() > 0L } },
            onFailure = { null }
        )
    }

    private fun findCachedAudio(songId: Long, br: String): File? {
        val directory = File(context.cacheDir, "gdstudio")
        val files = directory.listFiles()?.filter { file ->
            file.isFile && file.length() > 0L && file.extension.lowercase() !in setOf("jpg", "jpeg", "lrc", "txt")
        }.orEmpty()
        files.firstOrNull { it.name.startsWith("$songId-$br.") }?.let { return it }
        return files.firstOrNull { it.name.startsWith("$songId.") && sameCachedQuality(it, br) }
    }

    private fun sameCachedQuality(file: File, br: String): Boolean {
        if (file.name.contains("-$br.")) return true
        val kbps = metadata(file, MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000)
        val bits = metadata(file, MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
        return when (br) {
            "128" -> kbps != null && kbps in 96..160
            "192" -> kbps != null && kbps in 161..250
            "320" -> kbps != null && kbps in 251..450
            "740" -> bits == 16 || (file.extension.equals("flac", true) && bits == null && (kbps ?: 0) > 450)
            "999" -> bits != null && bits >= 24
            else -> false
        }
    }

    private fun metadata(file: File, key: Int): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(key)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isPublicMusic(path: String): Boolean {
        return path.contains("${File.separator}Music${File.separator}GD音乐台${File.separator}")
    }

    private fun isLibraryFile(path: String): Boolean {
        return isPublicMusic(path) ||
            path.contains("${File.separator}files${File.separator}gdstudio${File.separator}")
    }

    private fun isCacheFile(path: String): Boolean {
        return path.contains("${File.separator}cache${File.separator}") && path.contains("${File.separator}gdstudio${File.separator}")
    }

    private fun cachedAtQuality(path: String, songId: Long, br: String): Boolean {
        return File(path).name.startsWith("$songId-$br")
    }

    private fun remoteSong(track: Track, songId: Long, quality: String): Song {
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
            contentUriString = GdStudioApi.trackUri(track.source, track.id, track.lyricId, quality) +
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
        private const val CLOUD_PARENT = "/Cloud/GD"
        private const val MUSIC_FOLDER = "Music/GD音乐台"

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
