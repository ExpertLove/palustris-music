package ru.palustris.music.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.LibraryRepository
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.media.AudioStorage
import ru.palustris.music.media.TrackDownload

@HiltViewModel
class LibraryViewModel
@Inject
constructor(
  private val library: LibraryRepository,
  private val storage: AudioStorage,
  private val playlistsRepo: PlaylistsRepository,
  private val api: MusicApi,
) : ViewModel() {
  val playlists: StateFlow<List<PlaylistRow>?> = playlistsRepo.list

  private val _history = MutableStateFlow<List<HistoryItem>?>(null)
  val history: StateFlow<List<HistoryItem>?> = _history.asStateFlow()

  fun refreshHistory() {
    viewModelScope.launch {
      apiCall { api.history(150) }.onSuccess { _history.value = it }.onFailure { if (_history.value == null) _error.value = it.message }
    }
  }

  fun createPlaylist(name: String, onCreated: (Long) -> Unit) {
    viewModelScope.launch { playlistsRepo.create(name).onSuccess(onCreated).onFailure { _error.value = it.message } }
  }

  val likes: StateFlow<List<Track>?> = library.likes

  /** Скачанные и качающиеся, свежие сверху. */
  val downloads: StateFlow<List<TrackDownload>> =
    storage.downloads
      .map { m -> m.values.sortedByDescending { it.updatedAt } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _refreshing = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /** Сколько места занято, для плашки над списком скачанного. */
  private val _usage = MutableStateFlow<Long?>(null)
  val downloadsBytes: StateFlow<Long?> = _usage.asStateFlow()

  init {
    viewModelScope.launch { storage.downloads.collect { _usage.value = storage.usage().downloadsBytes } }
  }

  fun refresh() {
    viewModelScope.launch {
      _refreshing.value = true
      _error.value = library.refresh().exceptionOrNull()?.message
      playlistsRepo.refresh()
      _refreshing.value = false
    }
  }
}
