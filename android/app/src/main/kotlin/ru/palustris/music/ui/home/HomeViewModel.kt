package ru.palustris.music.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.model.Me
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.data.AccountRepository
import ru.palustris.music.data.LibraryRepository
import ru.palustris.music.data.OfflineFiles
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.media.AudioStorage
import ru.palustris.music.media.TrackDownload
import ru.palustris.music.ui.chart.resolveRegion

/** Всё, что главная берёт с сервера. Последняя копия лежит на телефоне: без сети главная не пустая. */
@Immutable
@Serializable
data class HomeData(
  val wave: List<Track> = emptyList(),
  val recs: List<Track> = emptyList(),
  val top: List<Track> = emptyList(),
  val region: String = "RU",
  val history: List<HistoryItem> = emptyList(),
)

data class HomeUi(
  val data: HomeData = HomeData(),
  /** Первый запуск: ни копии, ни ответа сервера. */
  val loading: Boolean = true,
  val refreshing: Boolean = false,
  /** Последнее обновление не прошло: показываем сохранённое. */
  val offline: Boolean = false,
)

/** Счётчики для плиток быстрого доступа. `null` — ещё не знаем. */
@Immutable
data class HomeLibrary(
  val likes: Int? = null,
  val downloads: Int = 0,
  val playlists: List<PlaylistRow>? = null,
)

@HiltViewModel
class HomeViewModel
@Inject
constructor(
  private val api: MusicApi,
  private val files: OfflineFiles,
  private val settings: SettingsStore,
  account: AccountRepository,
  library: LibraryRepository,
  playlists: PlaylistsRepository,
  storage: AudioStorage,
) : ViewModel() {
  private val _ui = MutableStateFlow(HomeUi())
  val ui: StateFlow<HomeUi> = _ui.asStateFlow()

  val me: StateFlow<Me?> = account.me

  val library: StateFlow<HomeLibrary> =
    combine(library.likes, playlists.list, storage.downloads) { likes, pls, dls ->
        HomeLibrary(likes?.size, dls.values.count { it.state == TrackDownload.State.Done }, pls)
      }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLibrary())

  private val region: StateFlow<String> =
    settings.settings.filterNotNull().map { resolveRegion(it.region) }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.Eagerly, resolveRegion(settings.current.region))

  init {
    // Сначала копия с телефона, потом сеть: без сети запрос падает мгновенно и обогнал бы чтение файла.
    viewModelScope.launch {
      files.read(FILE, HomeData.serializer())?.let { cached -> _ui.update { it.copy(data = cached, loading = false) } }
      load()
    }
    // Сменили страну на экране чарта: обновляем и блок на главной.
    viewModelScope.launch { region.drop(1).collect { refreshTop(it) } }
  }

  fun refresh() {
    if (_ui.value.refreshing) return
    viewModelScope.launch { load() }
  }

  private suspend fun load() {
    coroutineScope {
      _ui.update { it.copy(refreshing = true) }
      val r = region.value
      val wave = async { apiCall { api.wave() } }
      val recs = async { apiCall { api.recs() } }
      val top = async { apiCall { api.top(r.ifEmpty { null }) } }
      val history = async { apiCall { api.history(20) } }
      val results = listOf(wave.await(), recs.await(), top.await(), history.await())
      val old = _ui.value.data
      val data =
        HomeData(
          wave = wave.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: old.wave,
          recs = recs.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: old.recs,
          top = top.await().getOrNull() ?: old.top,
          region = if (top.await().isSuccess) r else old.region,
          history = history.await().getOrNull() ?: old.history,
        )
      // Без сети падают все запросы разом, а один упавший из четырёх — это не «офлайн».
      val offline = results.all { it.isFailure }
      _ui.value = HomeUi(data = data, loading = false, refreshing = false, offline = offline)
      if (!offline) files.write(FILE, HomeData.serializer(), data)
    }
  }

  private fun refreshTop(r: String) {
    viewModelScope.launch {
      apiCall { api.top(r.ifEmpty { null }) }.onSuccess { top -> _ui.update { it.copy(data = it.data.copy(top = top, region = r)) } }
    }
  }

  /** Свежая волна при каждом запуске: сервер учитывает то, что слушали только что. */
  suspend fun freshWave(): Result<List<Track>> =
    apiCall { api.wave() }.onSuccess { w -> _ui.update { it.copy(data = it.data.copy(wave = w)) } }

  private companion object {
    const val FILE = "home.json"
  }
}
