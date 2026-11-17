package ru.palustris.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.Session
import ru.palustris.music.core.prefs.SessionStore
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.data.AccountRepository
import ru.palustris.music.data.HistoryRepository
import ru.palustris.music.data.LibraryRepository
import ru.palustris.music.data.MixesRepository
import ru.palustris.music.data.Recommendations
import ru.palustris.music.data.TasteRepository
import ru.palustris.music.core.prefs.WaveSettings
import ru.palustris.music.media.QueueSource
import ru.palustris.music.media.audio.AudioLevels
import ru.palustris.music.data.OfflineFiles
import ru.palustris.music.data.Outbox
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.media.SleepState
import ru.palustris.music.media.SleepTimer
import ru.palustris.music.media.eq.EqualizerStore
import ru.palustris.music.media.AudioStorage
import ru.palustris.music.media.PlayerConnection
import ru.palustris.music.media.PlayerState
import ru.palustris.music.media.TrackDownload
import ru.palustris.music.ui.common.TrackActions
import ru.palustris.music.ui.common.TrackMarks
import ru.palustris.music.ui.player.PlayerControls

@HiltViewModel
class MainViewModel
@Inject
constructor(
  private val sessionStore: SessionStore,
  val player: PlayerConnection,
  private val library: LibraryRepository,
  private val storage: AudioStorage,
  private val settings: SettingsStore,
  private val api: MusicApi,
  private val playlistsRepo: PlaylistsRepository,
  private val sleepTimer: SleepTimer,
  private val account: AccountRepository,
  private val outbox: Outbox,
  private val files: OfflineFiles,
  private val history: HistoryRepository,
  private val taste: TasteRepository,
  private val recs: Recommendations,
  private val mixes: MixesRepository,
  /** Уровни звука для анимации плеера. */
  val levels: AudioLevels,
  equalizer: EqualizerStore,
) : ViewModel(), TrackActions, PlayerControls {

  /** Ссылка на плейлист (`/s/{slug}`), которую открыли из другого приложения. */
  private val _openLink = MutableStateFlow<String?>(null)
  val openLink: StateFlow<String?> = _openLink.asStateFlow()

  fun openLink(slug: String) {
    _openLink.value = slug
  }

  fun consumeLink() {
    _openLink.value = null
  }

  /**
   * ViewModel-и экранов живут, пока жива сессия: другой аккаунт не увидит чужую главную или историю.
   * Хранилище держим здесь, а не в композиции, чтобы оно пережило поворот экрана.
   */
  private var sessionVms: Pair<String, ViewModelStore>? = null

  fun viewModelStoreFor(token: String): ViewModelStore {
    sessionVms?.let { (t, store) -> if (t == token) return store else store.clear() }
    return ViewModelStore().also { sessionVms = token to it }
  }

  /** Вызывается, когда экраны сессии уже ушли из композиции (показан экран входа). */
  fun dropSessionViewModels() {
    sessionVms?.second?.clear()
    sessionVms = null
  }

  override fun onCleared() {
    dropSessionViewModels()
  }

  val sleep: StateFlow<SleepState> = sleepTimer.state

  val eqEnabled: StateFlow<Boolean> =
    equalizer.ui.map { it.enabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val playlists: StateFlow<List<PlaylistRow>?> = playlistsRepo.list

  /** Трек, для которого открыт выбор плейлиста. */
  private val _pickPlaylistFor = MutableStateFlow<Track?>(null)
  val pickPlaylistFor: StateFlow<Track?> = _pickPlaylistFor.asStateFlow()

  val session: StateFlow<Session> = sessionStore.session

  val playerState: StateFlow<PlayerState> = player.state

  val marks: StateFlow<TrackMarks> =
    combine(library.likedIds, storage.downloads, player.state, taste.dislikedIds) { liked, dls, ps, disliked ->
        TrackMarks(liked, dls, ps.track?.id, ps.isPlaying, disliked)
      }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackMarks())

  private val _messages = Channel<UiMessage>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  /** Нужно ли спросить разрешение на уведомления перед первой загрузкой. */
  private val _askNotifications = Channel<Unit>(Channel.CONFLATED)
  val askNotifications = _askNotifications.receiveAsFlow()
  private var askedNotifications = false

  init {
    viewModelScope.launch {
      session.filterIsInstance<Session.LoggedIn>().map { it.token }.distinctUntilChanged().collect {
        launch { account.refresh() }
        launch { taste.refresh() }
        library.refresh()
        playlistsRepo.refresh()
      }
    }
    viewModelScope.launch {
      player.state.map { it.error }.distinctUntilChanged().collect { e -> if (e != null) _messages.send(UiMessage(e)) }
    }
  }

  // TrackActions
  override fun play(tracks: List<Track>, index: Int, source: String?) = player.play(tracks, index, source)

  override fun toggleLike(track: Track) = library.toggleLike(track)

  override fun download(tracks: List<Track>) {
    if (!askedNotifications) {
      askedNotifications = true
      _askNotifications.trySend(Unit)
    }
    val fresh = tracks.filter { storage.downloads.value[it.id]?.state.let { s -> s == null || s == TrackDownload.State.Failed } }
    storage.download(fresh)
    if (fresh.size > 1) message("Скачиваем ${ru.palustris.music.ui.common.pluralTracks(fresh.size)}")
    if (settings.current.wifiOnly) message("Скачается, когда подключитесь к Wi‑Fi")
  }

  override fun removeDownload(track: Track) = storage.remove(track.id)

  override fun playNext(track: Track) {
    player.playNext(track)
    message("Сыграет следующим")
  }

  override fun enqueue(track: Track) {
    player.enqueue(track)
    message("Добавлен в очередь")
  }

  override fun startRadio(track: Track) {
    viewModelScope.launch {
      recs.radio(track).fold(
        { list ->
          if (list.isEmpty()) {
            message("Для этого трека радио не нашлось")
          } else {
            // Трек уже играет: он доигрывает, а дальше идёт радио.
            if (player.state.value.track?.id == track.id) player.replaceUpcoming(list, QueueSource.RADIO)
            else player.play(listOf(track.copy(why = null)) + list, 0, QueueSource.RADIO)
            message("Радио «${track.title}»")
          }
        },
        { message(it.message ?: "Не получилось включить радио") },
      )
    }
  }

  override fun artistRadio(id: String?, name: String) {
    viewModelScope.launch {
      recs.artistRadio(id, name).fold(
        { list -> if (list.isEmpty()) message("Радио не нашлось") else player.play(list, 0, QueueSource.RADIO) },
        { message(it.message ?: "Не получилось включить радио") },
      )
    }
  }

  override fun dislike(track: Track) {
    if (track.id in taste.dislikedIds.value) {
      taste.undislike(track.id)
      message("Убрали из «Не нравится»")
      return
    }
    taste.dislike(track)
    if (player.state.value.track?.id == track.id) player.next()
    player.removeUpcoming { it.id == track.id }
    _messages.trySend(UiMessage("Больше не предложим этот трек", "Отменить") { taste.undislike(track.id) })
  }

  override fun hideArtist(track: Track) {
    val name = track.artist.substringBefore(',').trim()
    taste.block(name)
    player.removeUpcoming { taste.isBlocked(it.artist) }
    if (player.state.value.track?.let { taste.isBlocked(it.artist) } == true) player.next()
    _messages.trySend(UiMessage("$name больше не будет в рекомендациях", "Отменить") { taste.unblock(name) })
  }

  /** Настройки волны. Если волна играет, всё впереди сразу пересобирается под новые настройки. */
  fun setWave(w: WaveSettings) {
    viewModelScope.launch {
      settings.setWave(w)
      if (player.state.value.source == QueueSource.WAVE) {
        recs.wave(w).onSuccess { list ->
          if (list.isNotEmpty()) {
            player.replaceUpcoming(list, QueueSource.WAVE)
            message("Волна перестроена")
          }
        }
      }
    }
  }

  /** «Анимация под музыку» в настройках. */
  val beatMotion: StateFlow<Boolean> =
    settings.settings.map { it?.beatMotion ?: true }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.beatMotion)

  val wave: StateFlow<WaveSettings> =
    settings.settings.map { it?.wave ?: WaveSettings() }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.wave)

  // PlayerControls
  override fun togglePlay() = player.togglePlay()

  override fun next() = player.next()

  override fun previous() = player.previous()

  override fun previousTrack() = player.previousTrack()

  override fun seekTo(ms: Long) = player.seekTo(ms)

  override fun skipTo(index: Int) = player.skipTo(index)

  override fun toggleShuffle() = player.toggleShuffle()

  override fun cycleRepeat() = player.cycleRepeat()

  override fun moveInQueue(from: Int, to: Int, id: String): Boolean = player.moveInQueue(from, to, id)

  override fun removeFromQueue(index: Int, id: String) = player.removeFromQueue(index, id)

  override fun clearUpNext() = player.clearUpNext()

  override fun setSpeed(percent: Int) {
    viewModelScope.launch { settings.setSpeed(percent) }
  }

  /** Скорость в процентах: в плеере видно, что она не обычная. */
  val speedPercent: StateFlow<Int> =
    settings.settings.map { it?.speedPercent ?: 100 }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.speedPercent)

  /** Таймер сна: минуты, -1 — до конца трека, 0 — выключить. */
  fun setSleep(minutes: Int) =
    when {
      minutes > 0 -> sleepTimer.start(minutes)
      minutes < 0 -> sleepTimer.stopAtEndOfTrack()
      else -> sleepTimer.cancel()
    }

  fun askPlaylistFor(track: Track?) {
    _pickPlaylistFor.value = track
    if (track != null && playlistsRepo.list.value == null) viewModelScope.launch { playlistsRepo.refresh() }
  }

  fun addToPlaylist(p: PlaylistRow, track: Track) {
    _pickPlaylistFor.value = null
    playlistsRepo.add(p.id, track)
    message("Добавлен в «${p.name}»")
  }

  fun createPlaylistWith(name: String, track: Track) {
    _pickPlaylistFor.value = null
    viewModelScope.launch {
      playlistsRepo.create(name, listOf(track)).fold({ message("Плейлист «${name.trim()}» создан") }, { message(it.message ?: "Не получилось") })
    }
  }

  fun message(text: String) {
    _messages.trySend(UiMessage(text))
  }

  fun logout() {
    viewModelScope.launch {
      apiCall { api.logout() }
      signOut(wipeDownloads = false)
    }
  }

  /** Аккаунт удалён на сервере: стираем с телефона и его данные, и скачанные треки. */
  fun onAccountDeleted() {
    viewModelScope.launch {
      signOut(wipeDownloads = true)
      message("Аккаунт удалён")
    }
  }

  private suspend fun signOut(wipeDownloads: Boolean) {
    player.stop()
    sleepTimer.cancel()
    outbox.clear()
    library.clear()
    taste.clear()
    mixes.clear()
    playlistsRepo.clear()
    account.clear()
    history.reset()
    files.clearAll()
    if (wipeDownloads) {
      storage.removeAll()
      storage.clearListeningCache()
    }
    sessionStore.clear()
  }
}

/** Сообщение внизу экрана; с [action] — кнопка, например «Отменить». */
data class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)
