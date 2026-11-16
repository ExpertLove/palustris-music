package ru.palustris.music.ui

import androidx.lifecycle.ViewModel
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
import ru.palustris.music.data.LibraryRepository
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
  equalizer: EqualizerStore,
) : ViewModel(), TrackActions, PlayerControls {

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
    combine(library.likedIds, storage.downloads, player.state) { liked, dls, ps ->
        TrackMarks(liked, dls, ps.track?.id, ps.isPlaying)
      }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackMarks())

  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  /** Нужно ли спросить разрешение на уведомления перед первой загрузкой. */
  private val _askNotifications = Channel<Unit>(Channel.CONFLATED)
  val askNotifications = _askNotifications.receiveAsFlow()
  private var askedNotifications = false

  init {
    viewModelScope.launch {
      session.filterIsInstance<Session.LoggedIn>().map { it.token }.distinctUntilChanged().collect {
        library.refresh()
        playlistsRepo.refresh()
      }
    }
    viewModelScope.launch {
      player.state.map { it.error }.distinctUntilChanged().collect { e -> if (e != null) _messages.send(e) }
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
    if (fresh.size > 1) _messages.trySend("Скачиваем ${ru.palustris.music.ui.common.pluralTracks(fresh.size)}")
    if (settings.current.wifiOnly) _messages.trySend("Скачается, когда подключитесь к Wi‑Fi")
  }

  override fun removeDownload(track: Track) = storage.remove(track.id)

  override fun playNext(track: Track) {
    player.playNext(track)
    _messages.trySend("Сыграет следующим")
  }

  override fun enqueue(track: Track) {
    player.enqueue(track)
    _messages.trySend("Добавлен в очередь")
  }

  // PlayerControls
  override fun togglePlay() = player.togglePlay()

  override fun next() = player.next()

  override fun previous() = player.previous()

  override fun seekTo(ms: Long) = player.seekTo(ms)

  override fun skipTo(index: Int) = player.skipTo(index)

  override fun toggleShuffle() = player.toggleShuffle()

  override fun cycleRepeat() = player.cycleRepeat()

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
    viewModelScope.launch {
      playlistsRepo.add(p.id, track).fold({ _messages.send("Добавлен в «${p.name}»") }, { _messages.send(it.message ?: "Не получилось") })
    }
  }

  fun createPlaylistWith(name: String, track: Track) {
    _pickPlaylistFor.value = null
    viewModelScope.launch {
      playlistsRepo.create(name, listOf(track)).fold({ _messages.send("Плейлист «${name.trim()}» создан") }, { _messages.send(it.message ?: "Не получилось") })
    }
  }

  fun message(text: String) {
    _messages.trySend(text)
  }

  fun logout() {
    viewModelScope.launch {
      apiCall { api.logout() }
      player.stop()
      library.clear()
      playlistsRepo.clear()
      sleepTimer.cancel()
      sessionStore.clear()
    }
  }
}
