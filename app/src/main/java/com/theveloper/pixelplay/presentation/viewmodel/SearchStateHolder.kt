package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.gdstudio.GdStudioLibrary
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.repository.MusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

enum class SearchStatus {
    Idle,
    Searching,
    Failed,
    Ready
}

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val gdStudioLibrary: GdStudioLibrary
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
        val fetchOnline: Boolean
    )

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow(SearchStatus.Idle)
    val searchStatus = _searchStatus.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        _searchStatus.value = SearchStatus.Idle
                        return@collectLatest
                    }

                    try {
                        val currentFilter = _selectedSearchFilter.value
                        val local = withContext(Dispatchers.IO) {
                            musicRepository.searchAll(normalizedQuery, currentFilter).first()
                        }.sortedWith(
                            compareBy { result ->
                                when (result) {
                                    is SearchResultItem.SongItem -> 0
                                    is SearchResultItem.AlbumItem -> 1
                                    is SearchResultItem.ArtistItem -> 2
                                    is SearchResultItem.PlaylistItem -> 3
                                }
                            }
                        )
                        if (request.requestId != latestSearchRequestId.get()) return@collectLatest
                        _searchResults.value = local.toImmutableList()

                        val fetchesOnline = request.fetchOnline &&
                            (currentFilter == SearchFilterType.ALL || currentFilter == SearchFilterType.SONGS)
                        if (!fetchesOnline) {
                            _searchStatus.value = SearchStatus.Ready
                            return@collectLatest
                        }
                        val online = try {
                            gdStudioLibrary.search(normalizedQuery)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (request.requestId == latestSearchRequestId.get()) {
                                Timber.e(error, "Online search failed for query: $normalizedQuery")
                                _searchStatus.value = SearchStatus.Failed
                            }
                            return@collectLatest
                        }
                        if (request.requestId != latestSearchRequestId.get()) return@collectLatest
                        if (online.isNotEmpty()) {
                            val seen = local.mapNotNull { item ->
                                (item as? SearchResultItem.SongItem)?.song?.id
                            }.toSet()
                            val merged = local + online
                                .filter { it.id !in seen }
                                .map { SearchResultItem.SongItem(it) }
                            _searchResults.value = merged.toImmutableList()
                        }
                        _searchStatus.value = SearchStatus.Ready
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            _searchStatus.value = SearchStatus.Failed
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Timber.e(e, "Error loading search history")
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Timber.e(e, "Error adding search history item")
                }
            }
        }
    }

    fun performSearch(query: String, fetchOnline: Boolean = false) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
            _searchStatus.value = SearchStatus.Idle
        } else if (fetchOnline) {
            _searchStatus.value = SearchStatus.Searching
        }

        searchRequests.tryEmit(SearchRequest(normalizedQuery, requestId, fetchOnline && normalizedQuery.isNotBlank()))
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Timber.e(e, "Error deleting search history item")
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing search history")
            }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
