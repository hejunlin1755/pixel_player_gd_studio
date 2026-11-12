package com.theveloper.pixelplay.presentation.gdstudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.gdstudio.GdStudioApi
import com.theveloper.pixelplay.data.gdstudio.GdStudioException
import com.theveloper.pixelplay.data.gdstudio.GdStudioRepository
import com.theveloper.pixelplay.data.gdstudio.Track
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GdStudioUiState(
    val query: String = "",
    val source: String = "netease",
    val albumMode: Boolean = false,
    val quality: String = "320",
    val loading: Boolean = false,
    val error: String? = null,
    val tracks: List<Track> = emptyList(),
    val songs: List<Song> = emptyList(),
    val page: Int = 1,
    val endReached: Boolean = false
)

@HiltViewModel
class GdStudioViewModel @Inject constructor(
    private val repository: GdStudioRepository
) : ViewModel() {
    private val _state = MutableStateFlow(GdStudioUiState())
    val state: StateFlow<GdStudioUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var coverJob: Job? = null

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun setSource(source: String) {
        if (!GdStudioApi.isStableSource(source)) return
        _state.update { it.copy(source = source) }
    }

    fun setAlbumMode(enabled: Boolean) {
        _state.update { it.copy(albumMode = enabled) }
    }

    fun setQuality(br: String) {
        val quality = GdStudioApi.normalizeQuality(br)
        _state.update { current ->
            current.copy(
                quality = quality,
                songs = current.tracks.map { track ->
                    track.toSong(quality, current.songs.firstOrNull { it.id == "gdstudio:${track.source}:${track.id}" }?.albumArtUriString)
                }
            )
        }
    }

    fun search() {
        val snapshot = _state.value
        searchJob?.cancel()
        coverJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, page = 1, endReached = false) }
            loadPage(snapshot.query, snapshot.source, snapshot.albumMode, snapshot.quality, page = 1, append = false)
        }
    }

    fun loadNextPage() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.endReached || snapshot.tracks.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            loadPage(
                query = snapshot.query,
                source = snapshot.source,
                album = snapshot.albumMode,
                quality = snapshot.quality,
                page = snapshot.page + 1,
                append = true
            )
        }
    }

    private suspend fun loadPage(
        query: String,
        source: String,
        album: Boolean,
        quality: String,
        page: Int,
        append: Boolean
    ) {
        try {
            val found = repository.search(source, query, page = page, album = album)
            _state.update { current ->
                val tracks = if (append) current.tracks + found else found
                val previousArt = current.songs.associate { it.id to it.albumArtUriString }
                current.copy(
                    loading = false,
                    page = page,
                    endReached = found.isEmpty(),
                    tracks = tracks,
                    songs = tracks.map { track ->
                        val id = "gdstudio:${track.source}:${track.id}"
                        track.toSong(quality, previousArt[id])
                    },
                    error = if (!append && found.isEmpty()) "没有找到曲目" else null
                )
            }
            loadCovers(found)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GdStudioException) {
            _state.update { it.copy(loading = false, error = error.message) }
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = "无法连接 GD音乐台") }
        }
    }

    private fun loadCovers(tracks: List<Track>) {
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            tracks.filter { it.picId.isNotBlank() }.forEach { track ->
                val cover = runCatching { repository.coverUrl(track.source, track.picId) }.getOrNull()
                    ?: return@forEach
                _state.update { current ->
                    current.copy(
                        songs = current.songs.map { song ->
                            if (song.id == "gdstudio:${track.source}:${track.id}") {
                                song.copy(albumArtUriString = cover)
                            } else {
                                song
                            }
                        }
                    )
                }
            }
        }
    }
}
