package ru.palustris.music.media

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import ru.palustris.music.core.model.Track

data class PlayerState(
  val track: Track? = null,
  val isPlaying: Boolean = false,
  /** Пользователь хочет, чтобы играло: от этого зависит значок кнопки, даже пока идёт буферизация. */
  val playWhenReady: Boolean = false,
  /** Ждём данные: первый запуск трека, пока сервер его качает, занимает несколько секунд. */
  val isBuffering: Boolean = false,
  val durationMs: Long = 0,
  val shuffle: Boolean = false,
  val repeatMode: Int = Player.REPEAT_MODE_OFF,
  val hasNext: Boolean = false,
  /** Откуда очередь, см. [QueueSource]. */
  val source: String? = null,
  val queue: List<Track> = emptyList(),
  val index: Int = -1,
  val error: String? = null,
)

data class Progress(val positionMs: Long, val bufferedMs: Long, val durationMs: Long)

/**
 * Интерфейс управляет плеером через MediaController, а сам плеер живёт в [PlaybackService].
 * Подключаемся, пока активити видна, и отключаемся, когда она уходит: музыка от этого
 * не останавливается.
 */
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val context: Context) {
  private var future: ListenableFuture<MediaController>? = null
  private var controller: MediaController? = null
  private val pending = ArrayDeque<(MediaController) -> Unit>()

  private val _state = MutableStateFlow(PlayerState())
  val state: StateFlow<PlayerState> = _state.asStateFlow()

  fun connect() {
    if (future != null) return
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val f = MediaController.Builder(context, token).buildAsync()
    future = f
    f.addListener(
      {
        val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
        controller = c
        c.addListener(listener)
        sync(c)
        while (pending.isNotEmpty()) pending.removeFirst()(c)
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  fun disconnect() {
    future?.let { MediaController.releaseFuture(it) }
    future = null
    controller = null
    pending.clear()
  }

  private fun withController(block: (MediaController) -> Unit) {
    val c = controller
    if (c != null) block(c)
    else {
      pending.addLast(block)
      connect()
    }
  }

  /** Позиция для полосы прогресса: опрашиваем часто, пока играет, и редко на паузе. */
  val progress: Flow<Progress> =
    flow {
        while (true) {
          val c = controller
          if (c == null) emit(Progress(0, 0, 0))
          else emit(Progress(c.currentPosition, c.bufferedPosition, c.duration.coerceAtLeast(0)))
          delay(if (c?.isPlaying == true) 250 else 1000)
        }
      }
      .distinctUntilChanged()

  fun play(tracks: List<Track>, startIndex: Int = 0, source: String? = null) {
    if (tracks.isEmpty()) return
    playNonEmpty(tracks, startIndex, source)
  }

  private fun playNonEmpty(tracks: List<Track>, startIndex: Int, source: String?) = withController { c ->
    c.setMediaItems(tracks.map { it.toMediaItem(source) }, startIndex.coerceIn(0, tracks.lastIndex), 0)
    c.shuffleModeEnabled = false
    c.prepare()
    c.play()
  }

  /** Остановить и очистить очередь, например при выходе из аккаунта. */
  fun stop() = withController { c ->
    c.stop()
    c.clearMediaItems()
  }

  fun togglePlay() = withController { c ->
    if (c.playbackState == Player.STATE_IDLE) c.prepare()
    if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
    if (c.playWhenReady) c.pause() else c.play()
  }

  fun next() = withController { it.seekToNext() }

  fun previous() = withController { it.seekToPrevious() }

  fun seekTo(ms: Long) = withController { it.seekTo(ms) }

  fun skipTo(index: Int) = withController {
    it.seekTo(index, 0)
    it.play()
  }

  fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

  fun cycleRepeat() = withController {
    it.repeatMode =
      when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
      }
  }

  fun playNext(track: Track) = withController { c ->
    if (c.mediaItemCount == 0) play(listOf(track))
    else c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
  }

  fun enqueue(track: Track) = withController { c ->
    if (c.mediaItemCount == 0) play(listOf(track)) else c.addMediaItem(track.toMediaItem())
  }

  private val listener =
    object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        controller?.let { sync(it) }
      }
    }

  private fun sync(c: MediaController) {
    val prev = _state.value
    val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
    val queueChanged = items.size != prev.queue.size || items.indices.any { items[it].mediaId != prev.queue[it].id }
    _state.value =
      PlayerState(
        track = c.currentMediaItem?.toTrack(),
        isPlaying = c.isPlaying,
        playWhenReady = c.playWhenReady,
        isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
        durationMs = c.duration.coerceAtLeast(0),
        shuffle = c.shuffleModeEnabled,
        repeatMode = c.repeatMode,
        hasNext = c.hasNextMediaItem(),
        source = c.currentMediaItem?.queueSource,
        queue = if (queueChanged) items.map(MediaItem::toTrack) else prev.queue,
        index = c.currentMediaItemIndex,
        error = c.playerError?.let(::describe),
      )
  }

  private fun describe(e: PlaybackException): String =
    when (e.errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет связи. Скачанные треки играют и без интернета"
      PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Этот трек сейчас недоступен"
      else -> "Не получилось воспроизвести трек"
    }
}
