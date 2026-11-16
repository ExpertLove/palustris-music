package ru.palustris.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
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
import ru.palustris.music.data.LibraryRepository
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
) : ViewModel(), TrackActions, PlayerControls {

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

  fun logout() {
    viewModelScope.launch {
      apiCall { api.logout() }
      player.stop()
      library.clear()
      sessionStore.clear()
    }
  }
}
