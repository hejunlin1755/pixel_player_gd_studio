package com.theveloper.pixelplay.data.gdstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
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
import java.io.ByteArrayOutputStream
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
            val reason = outcomes.mapNotNull { it.exceptionOrNull()?.message?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("；")
            throw GdStudioException(reason.ifBlank { "在线搜索失败" })
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
        val covers = mutableMapOf<Pair<String, String>, String>()
        for (batch in needed.chunked(4)) {
            val found = batch.map { (source, pic) ->
                async {
                    (source to pic) to runCatching { repository.coverUrl(source, pic) }.getOrNull()
                }
            }.awaitAll()
            found.forEach { (key, url) ->
                if (!url.isNullOrBlank()) covers[key] = url
            }
        }
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

        val stored = musicDao.getSongByIdOnce(songId)
        val publishedUri = stored?.contentUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" && canRead(it) }
        if (stored != null && publishedUri != null) {
            val cover = cacheCover(File(context.cacheDir, "gdstudio").apply { mkdirs() }, songId, ref.source, picIdOf(playable))
            val lyrics = stored.lyrics?.takeIf { it.isNotBlank() }
                ?: runCatching { repository.lyrics(ref.source, ref.lyricId) }.getOrNull()
            val durableCover = keepCover(songId, cover)
            if (needsEmbeddedTags(publishedUri, lyrics)) {
                writeTags(publishedUri, playable.title, playable.artist, playable.album, lyrics, durableCover ?: cover, force = true)
                writeColumns(publishedUri, playable.title, playable.artist, playable.album)
            }
            val refreshed = stored.copy(
                title = playable.title.ifBlank { stored.title },
                artistName = playable.artist.ifBlank { stored.artistName },
                albumName = playable.album.ifBlank { stored.albumName },
                albumArtist = playable.artist.ifBlank { stored.albumArtist },
                lyrics = lyrics ?: stored.lyrics,
                albumArtUriString = durableCover?.let { Uri.fromFile(it).toString() } ?: stored.albumArtUriString
            )
            if (refreshed != stored) musicDao.insertSongs(listOf(refreshed))
            return@withContext refreshed.toSong()
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
        val coverFile = cacheCover(File(context.cacheDir, "gdstudio").apply { mkdirs() }, songId, ref.source, picIdOf(playable))
        val lyrics = runCatching { repository.lyrics(ref.source, ref.lyricId) }.getOrNull()
        val durableCover = keepCover(songId, coverFile)
        writeTags(staged, playable.title, playable.artist, playable.album, lyrics, durableCover ?: coverFile)
        val published = publishToMusic(staged, musicFileName(playable, staged.extension.ifBlank { "mp3" }), playable, lyrics, durableCover ?: coverFile)
        if (staged.absolutePath != published.file.absolutePath) staged.delete()

        val duration = readDuration(published.uri)
        saveSong(
            song = playable,
            audio = published.file,
            cover = durableCover ?: coverFile,
            lyrics = lyrics,
            duration = duration,
            contentUri = published.uri.toString(),
            parent = published.file.parent.orEmpty(),
            sourceType = SourceType.LOCAL
        )
    }

    private data class PublishedSong(val uri: Uri, val file: File)

    private fun publishToMusic(
        source: File,
        displayName: String,
        song: Song,
        lyrics: String?,
        cover: File?
    ): PublishedSong {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.ALBUM, song.album)
            put(MediaStore.Audio.Media.ALBUM_ARTIST, song.artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(source))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$MUSIC_FOLDER/")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw GdStudioException("无法保存到 Music 文件夹")
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw GdStudioException("无法保存到 Music 文件夹")
        writeTags(uri, song.title, song.artist, song.album, lyrics, cover)
        writeColumns(uri, song.title, song.artist, song.album)
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

    suspend fun repairPublishedSongs() {
        val rows = musicDao.getGdPublishedSongIds().mapNotNull { musicDao.getSongByIdOnce(it) }
        rows.groupBy { it.filePath }.forEach { (path, group) ->
            val uri = group.firstNotNullOfOrNull { row ->
                runCatching { Uri.parse(row.contentUriString) }.getOrNull()
                    ?.takeIf { it.scheme == "content" && canRead(it) }
            } ?: return@forEach
            val lyrics = group.firstNotNullOfOrNull { it.lyrics?.takeIf { text -> text.isNotBlank() } }
            if (!needsEmbeddedTags(uri, lyrics)) return@forEach
            val best = group.maxBy { metadataScore(it) }
            val (fileTitle, fileArtist) = namesFromFile(File(path).name)
            val title = best.title.takeUnless { blankMeta(it) } ?: fileTitle
            val artist = best.artistName.takeUnless { blankMeta(it) } ?: fileArtist
            val album = best.albumName.takeUnless { blankMeta(it) }.orEmpty()
            val cover = group.firstNotNullOfOrNull { coverFileOf(it) }
            val durableCover = keepCover(best.id, cover)
            writeTags(uri, title, artist, album, lyrics, durableCover ?: cover, force = true)
            writeColumns(uri, title, artist, album)
            val artUri = durableCover?.let { Uri.fromFile(it).toString() }
            group.forEach { row ->
                val updated = row.copy(
                    title = title.ifBlank { row.title },
                    artistName = artist.ifBlank { row.artistName },
                    albumName = album.ifBlank { row.albumName },
                    albumArtist = artist.ifBlank { row.albumArtist },
                    lyrics = lyrics ?: row.lyrics,
                    albumArtUriString = artUri ?: row.albumArtUriString,
                    artistsJson = if (artist.isBlank()) row.artistsJson else {
                        """[{"id":${row.artistId},"name":${jsonString(artist)},"primary":true}]"""
                    }
                )
                if (updated != row) musicDao.insertSongs(listOf(updated))
            }
        }
    }

    private fun canRead(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0L } ?: false
        }.getOrDefault(false)
    }

    private fun needsEmbeddedTags(uri: Uri, lyrics: String?): Boolean {
        val retriever = MediaMetadataRetriever()
        val missingCore = try {
            retriever.setDataSource(context, uri)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val picture = retriever.embeddedPicture
            blankMeta(artist) || blankMeta(title) || picture == null
        } catch (_: Exception) {
            true
        } finally {
            runCatching { retriever.release() }
        }
        if (missingCore || lyrics.isNullOrBlank()) return missingCore
        return embeddedLyricsMissing(uri)
    }

    private fun embeddedLyricsMissing(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                TagLib.getMetadata(fd.detachFd())?.propertyMap?.get("LYRICS")?.firstOrNull().isNullOrBlank()
            } ?: true
        }.getOrDefault(true)
    }

    private fun writeColumns(uri: Uri, title: String, artist: String, album: String) {
        val values = ContentValues().apply {
            if (title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, title)
            if (artist.isNotBlank()) {
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, artist)
            }
            if (album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    private fun writeTags(
        target: Uri,
        title: String,
        artist: String,
        album: String,
        lyrics: String?,
        cover: File?,
        force: Boolean = false
    ) {
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(target, "rw") }.getOrNull() ?: return
        writeTags(descriptor, title, artist, album, lyrics, cover, force)
    }

    private fun writeTags(
        target: File,
        title: String,
        artist: String,
        album: String,
        lyrics: String?,
        cover: File?,
        force: Boolean = false
    ) {
        val descriptor = runCatching {
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE)
        }.getOrNull() ?: return
        writeTags(descriptor, title, artist, album, lyrics, cover, force)
    }

    private fun writeTags(
        descriptor: ParcelFileDescriptor,
        title: String,
        artist: String,
        album: String,
        lyrics: String?,
        cover: File?,
        force: Boolean
    ) {
        descriptor.use { fd ->
            val existing = runCatching { TagLib.getMetadata(fd.dup().detachFd())?.propertyMap }.getOrNull()
            val map = HashMap<String, Array<String>>()
            existing?.forEach { (key, value) -> map[key] = value }
            fun fill(key: String, value: String) {
                val current = map[key]?.firstOrNull().orEmpty()
                if (!blankMeta(value) && (force || blankMeta(current))) map[key] = arrayOf(value)
            }
            fill("TITLE", title)
            fill("ARTIST", artist)
            fill("ALBUMARTIST", artist)
            fill("ALBUM", album)
            fill("LYRICS", lyrics.orEmpty())
            runCatching { TagLib.savePropertyMap(fd.dup().detachFd(), map) }
            val jpeg = cover?.let { jpegBytes(it) } ?: return
            runCatching {
                TagLib.savePictures(
                    fd.dup().detachFd(),
                    arrayOf(
                        Picture(
                            data = jpeg,
                            description = "Front Cover",
                            pictureType = "Front Cover",
                            mimeType = "image/jpeg"
                        )
                    )
                )
            }
        }
    }

    private fun jpegBytes(cover: File): ByteArray? {
        val raw = runCatching { cover.readBytes() }.getOrNull() ?: return null
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) return raw
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val output = ByteArrayOutputStream()
        val saved = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return if (saved) output.toByteArray() else null
    }

    private fun keepCover(songId: Long, cover: File?): File? {
        if (cover == null || !cover.isFile || cover.length() == 0L) return null
        val destination = File(File(context.filesDir, "gdstudio-covers").apply { mkdirs() }, "$songId.jpg")
        if (cover.absolutePath == destination.absolutePath) return destination
        return runCatching {
            cover.copyTo(destination, overwrite = true)
            destination
        }.getOrNull()
    }

    private fun coverFileOf(song: SongEntity): File? {
        val stored = song.albumArtUriString?.takeIf { it.startsWith("file:") }?.let { Uri.parse(it).path }?.let(::File)
        if (stored != null && stored.isFile && stored.length() > 0L) return stored
        val cached = File(context.cacheDir, "gdstudio/${song.id}.jpg")
        if (cached.isFile && cached.length() > 0L) return cached
        val durable = File(context.filesDir, "gdstudio-covers/${song.id}.jpg")
        return durable.takeIf { it.isFile && it.length() > 0L }
    }

    private fun namesFromFile(fileName: String): Pair<String, String> {
        val parts = fileName.substringBeforeLast('.').split(" - ", limit = 2)
        return parts.getOrElse(0) { "" }.trim() to parts.getOrElse(1) { "" }.trim()
    }

    private fun metadataScore(song: SongEntity): Int {
        var score = 0
        if (!blankMeta(song.artistName)) score += 4
        if (!blankMeta(song.title)) score += 2
        if (!song.lyrics.isNullOrBlank()) score += 1
        if (!song.albumArtUriString.isNullOrBlank()) score += 1
        return score
    }

    private fun blankMeta(value: String?): Boolean {
        val text = value.orEmpty().trim().lowercase()
        return text.isEmpty() || text == "<unknown>" || text == "unknown" ||
            text == "unknown artist" || text == "unknown album" || text == "unknown title"
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

    private fun readDuration(file: File): Long = readDuration { retriever ->
        retriever.setDataSource(file.absolutePath)
    }

    private fun readDuration(uri: Uri): Long = readDuration { retriever ->
        retriever.setDataSource(context, uri)
    }

    private fun readDuration(open: (MediaMetadataRetriever) -> Unit): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
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
