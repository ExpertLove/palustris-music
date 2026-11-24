package ru.palustris.music.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.model.LibraryArtist
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.ImportPreview
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.HistoryRepository
import ru.palustris.music.data.LibraryRepository
import ru.palustris.music.data.OfflineFiles
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
  private val historyRepo: HistoryRepository,
  private val api: MusicApi,
  private val files: OfflineFiles,
) : ViewModel() {
  val playlists: StateFlow<List<PlaylistRow>?> = playlistsRepo.list

  val history: StateFlow<List<HistoryItem>?> = historyRepo.items

  fun refreshHistory() {
    viewModelScope.launch { historyRepo.refresh() }
  }

  private val _clearingHistory = MutableStateFlow(false)
  val clearingHistory: StateFlow<Boolean> = _clearingHistory.asStateFlow()

  /** Короткие сообщения для снекбара. */
  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  fun clearHistory() {
    if (_clearingHistory.value) return
    viewModelScope.launch {
      _clearingHistory.value = true
      historyRepo
        .clearAll()
        .onSuccess {
          _messages.send("История очищена")
          // Исполнители собраны и из истории: список тоже поменялся.
          refreshArtists()
        }
        .onFailure { _messages.send(it.message ?: "Не получилось очистить историю") }
      _clearingHistory.value = false
    }
  }

  private val _artists = MutableStateFlow<List<LibraryArtist>?>(null)
  /** Исполнители из лайков и истории; `null` — ещё не загрузили. */
  val artists: StateFlow<List<LibraryArtist>?> = _artists.asStateFlow()

  fun refreshArtists() {
    viewModelScope.launch {
      if (_artists.value == null) _artists.value = files.read(ARTISTS_FILE, ARTISTS)
      apiCall { api.libraryArtists() }
        .onSuccess {
          _artists.value = it
          files.write(ARTISTS_FILE, ARTISTS, it)
        }
        .onFailure { if (_artists.value == null) _artists.value = emptyList() }
    }
  }

  suspend fun importPreview(link: String?, text: String?): Result<ImportPreview> = playlistsRepo.previewImport(link, text)

  suspend fun createImported(name: String, tracks: List<Track>): Result<Long> = playlistsRepo.create(name, tracks)

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

  private companion object {
    const val ARTISTS_FILE = "artists.json"
    val ARTISTS = ListSerializer(LibraryArtist.serializer())
  }
}
