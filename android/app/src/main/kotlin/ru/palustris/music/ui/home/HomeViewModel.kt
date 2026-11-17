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
import ru.palustris.music.core.model.Mixes
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.Stats
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.prefs.WaveSettings
import ru.palustris.music.data.MixesRepository
import ru.palustris.music.data.Recommendations
import ru.palustris.music.data.TasteRepository
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.data.AccountRepository
import ru.palustris.music.data.HistoryRepository
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
  /** Минуты и главный исполнитель за неделю: короткая плашка со ссылкой на статистику. */
  val week: Stats? = null,
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
  private val recs: Recommendations,
  private val mixesRepo: MixesRepository,
  private val taste: TasteRepository,
  history: HistoryRepository,
  account: AccountRepository,
  library: LibraryRepository,
  playlists: PlaylistsRepository,
  storage: AudioStorage,
) : ViewModel() {
  private val _ui = MutableStateFlow(HomeUi())
  val ui: StateFlow<HomeUi> = _ui.asStateFlow()

  val me: StateFlow<Me?> = account.me

  /** Миксы дня, забытые лайки и «на повторе»; скрытое пропадает сразу. */
  val mixes: StateFlow<Mixes?> =
    combine(mixesRepo.mixes, taste.state) { m, _ ->
        m?.let {
          it.copy(
            mixes = it.mixes.map { mix -> mix.copy(tracks = mix.tracks.filterNot(taste::isHidden)) },
            forgotten = it.forgotten.filterNot(taste::isHidden),
            repeat = it.repeat.filterNot(taste::isHidden),
          )
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  val wave: StateFlow<WaveSettings> =
    settings.settings.map { it?.wave ?: WaveSettings() }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.wave)

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
    // Сменили настройки волны: превью на карточке — уже из новой волны.
    viewModelScope.launch { wave.drop(1).collect { w -> recs.wave(w).onSuccess { list -> _ui.update { it.copy(data = it.data.copy(wave = list)) } } } }
    // Историю очистили в «Моей музыке»: «Недавно играло» пустеет сразу, и в копии на телефоне тоже.
    viewModelScope.launch {
      history.cleared.collect {
        _ui.update { it.copy(data = it.data.copy(history = emptyList())) }
        files.write(FILE, HomeData.serializer(), _ui.value.data)
      }
    }
  }

  fun refresh() {
    if (_ui.value.refreshing) return
    viewModelScope.launch { load() }
  }

  private suspend fun load() {
    coroutineScope {
      _ui.update { it.copy(refreshing = true) }
      val r = region.value
      val wave = async { recs.wave() }
      val forYou = async { apiCall { api.recs() }.map { l -> l.filterNot(taste::isHidden) } }
      val top = async { apiCall { api.top(r.ifEmpty { null }) } }
      val history = async { apiCall { api.history(20) } }
      val week = async { apiCall { api.stats("week", tzMinutes()) } }
      launch { mixesRepo.refresh() }
      val results = listOf(wave.await(), forYou.await(), top.await(), history.await())
      val old = _ui.value.data
      val data =
        HomeData(
          wave = wave.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: old.wave,
          recs = forYou.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: old.recs,
          top = top.await().getOrNull() ?: old.top,
          region = if (top.await().isSuccess) r else old.region,
          history = history.await().getOrNull() ?: old.history,
          week = week.await().getOrNull() ?: old.week,
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
    recs.wave().onSuccess { w -> _ui.update { it.copy(data = it.data.copy(wave = w)) } }

  private companion object {
    const val FILE = "home.json"
  }
}

/** Сдвиг местного времени от UTC в минутах: статистика считает часы и дни по-местному. */
fun tzMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
