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
import ru.palustris.music.data.LibraryRepository
import ru.palustris.music.media.AudioStorage
import ru.palustris.music.media.TrackDownload

@HiltViewModel
class LibraryViewModel @Inject constructor(private val library: LibraryRepository, private val storage: AudioStorage) :
  ViewModel() {
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
      _refreshing.value = false
    }
  }
}
