package com.theveloper.pixelplay.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Lyrics and cover files sitting next to a song, either as real files or as
 * MediaStore entries in the same folder.
 */
object SidecarMedia {
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
    private val roots = listOf("/storage/emulated/0/", "/storage/self/primary/", "/sdcard/")
    private val indexes = HashMap<String, Map<String, Uri>>()
    private val lock = Any()

    fun coverBytes(context: Context, audioPath: String, songId: Long): ByteArray? {
        return readFirst(context, audioPath, names(audioPath, songId, imageExtensions))
    }

    fun lyricsText(context: Context, audioPath: String, songId: Long): String? {
        val bytes = readFirst(context, audioPath, names(audioPath, songId, LyricsImportSecurity.supportedFileExtensions()))
            ?: return null
        return bytes.toString(Charsets.UTF_8).trim().ifBlank { null }
    }

    private fun names(audioPath: String, songId: Long, extensions: List<String>): List<String> {
        val base = File(audioPath).nameWithoutExtension.takeIf { it.isNotBlank() }
        return extensions.flatMap { extension ->
            listOfNotNull(base?.let { "$it.$extension" }, "$songId.$extension")
        }.distinct()
    }

    private fun readFirst(context: Context, audioPath: String, names: List<String>): ByteArray? {
        for (name in names) {
            val bytes = readNamed(context, audioPath, name) ?: continue
            if (bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private fun readNamed(context: Context, audioPath: String, name: String): ByteArray? {
        val direct = File(audioPath).parentFile?.let { File(it, name) }
        if (direct != null && direct.isFile && direct.length() > 0L) {
            val bytes = runCatching { direct.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        val uri = index(context, audioPath)[name.lowercase()] ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun index(context: Context, audioPath: String): Map<String, Uri> {
        val relative = relativePath(audioPath) ?: return emptyMap()
        synchronized(lock) {
            indexes[relative]?.let { return it }
        }
        val found = HashMap<String, Uri>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? OR ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
        val args = arrayOf(relative, relative.removeSuffix("/"))
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)?.lowercase() ?: continue
                    found[name] = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                }
            }
        }
        synchronized(lock) {
            indexes[relative] = found
        }
        return found
    }

    private fun relativePath(audioPath: String): String? {
        val normalized = audioPath.replace('\\', '/')
        val root = roots.firstOrNull { normalized.startsWith(it) } ?: return null
        val rest = normalized.removePrefix(root)
        val slash = rest.lastIndexOf('/')
        if (slash <= 0) return null
        return rest.substring(0, slash + 1)
    }
}
