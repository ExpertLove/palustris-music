package ru.palustris.music.media

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
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
  /**
   * Откуда очередь, см. [QueueSource]. Если сейчас играет добавленное вручную, — источник очереди
   * вокруг него: волна остаётся волной.
   */
  val source: String? = null,
  val queue: List<Track> = emptyList(),
  /** Источник каждого элемента [queue]: «Вы добавили» в очереди показываются отдельно. */
  val sources: List<String?> = emptyList(),
  val index: Int = -1,
  /**
   * Что сыграет после текущего: индексы в [queue] в порядке воспроизведения. Без перемешивания это
   * просто хвост очереди, с ним — порядок, который выбрал плеер.
   */
  val upNext: List<Int> = emptyList(),
  val error: String? = null,
)

data class Progress(val positionMs: Long, val bufferedMs: Long, val durationMs: Long)

/** [1, 2, 3, 7, 8] → [1..3, 7..8]. Вход в любом порядке, выход по возрастанию. */
fun contiguousRanges(indices: Collection<Int>): List<IntRange> {
  val sorted = indices.toSortedSet()
  val out = ArrayList<IntRange>()
  var start = -1
  var end = -1
  for (i in sorted) {
    if (start >= 0 && i == end + 1) end = i
    else {
      if (start >= 0) out += start..end
      start = i
      end = i
    }
  }
  if (start >= 0) out += start..end
  return out
}

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

  fun previousTrack() = withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }

  /** Позиция прямо сейчас: MediaController досчитывает её сам, без запроса к сервису. */
  fun positionNow(): Long = controller?.currentPosition ?: 0

  /** Хэш id текущего трека: так его помечает измеритель звука. */
  fun currentIdHash(): Int = controller?.currentMediaItem?.mediaId?.hashCode() ?: 0

  fun isPlayingNow(): Boolean = controller?.isPlaying == true

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

  /** Сразу после текущего, но после уже добавленных вручную: «следующими» играют в порядке добавления. */
  fun playNext(track: Track) = withController { c ->
    if (c.mediaItemCount == 0) play(listOf(track))
    else {
      var at = c.currentMediaItemIndex + 1
      while (at < c.mediaItemCount && c.getMediaItemAt(at).queueSource == QueueSource.USER) at++
      c.addMediaItem(at, track.toMediaItem(QueueSource.USER))
    }
  }

  fun enqueue(track: Track) = withController { c ->
    if (c.mediaItemCount == 0) play(listOf(track)) else c.addMediaItem(track.toMediaItem(QueueSource.USER))
  }

  /**
   * Заменить всё после текущего трека (волна с новыми настройками). Добавленное вручную остаётся
   * впереди.
   */
  fun replaceUpcoming(tracks: List<Track>, source: String) = withController { c ->
    val cur = c.currentMediaItemIndex
    if (cur == C.INDEX_UNSET) return@withController
    val user = (cur + 1 until c.mediaItemCount).map { c.getMediaItemAt(it) }.filter { it.queueSource == QueueSource.USER }
    val keep = (0..cur).mapTo(HashSet()) { c.getMediaItemAt(it).mediaId } + user.map { it.mediaId }
    // Одной заменой: если убирать по одному, сервис увидит почти пустое «Далее» и догрузит
    // старую волну, которая окажется в хвосте после новых треков.
    c.replaceMediaItems(cur + 1, c.mediaItemCount, user + tracks.filter { it.id !in keep }.map { it.toMediaItem(source) })
  }

  /** Убрать из «Далее» треки, которые больше не нужны (например, скрытого исполнителя). */
  fun removeUpcoming(predicate: (Track) -> Boolean) = withController { c ->
    val cur = c.currentMediaItemIndex
    var i = c.mediaItemCount - 1
    while (i > cur) {
      if (predicate(c.getMediaItemAt(i).toTrack())) c.removeMediaItem(i)
      i--
    }
  }

  /**
   * Переставить трек очереди с места [from] на [to]. Только среди треков после текущего. [id] защищает
   * от устаревших индексов: пока палец тянул трек, очередь могла сдвинуться. `false` — ничего не сделано.
   */
  fun moveInQueue(from: Int, to: Int, id: String): Boolean {
    val c = controller ?: return false
    val cur = c.currentMediaItemIndex
    if (from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return false
    if (from <= cur || to <= cur || c.getMediaItemAt(from).mediaId != id) return false
    if (from != to) c.moveMediaItem(from, to)
    return true
  }

  /** Убрать из очереди не играющий сейчас трек. */
  fun removeFromQueue(index: Int, id: String) = withController { c ->
    if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex && c.getMediaItemAt(index).mediaId == id) {
      c.removeMediaItem(index)
    }
  }

  /** Убрать всё, что сыграло бы после текущего. Сыгранное остаётся: кнопка «назад» работает. */
  fun clearUpNext() = withController { c ->
    val remove = upNextOf(c)
    // С конца и кусками подряд идущих индексов: индексы ещё не удалённых не съезжают.
    for (range in contiguousRanges(remove).asReversed()) c.removeMediaItems(range.first, range.last + 1)
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
    val queueChanged =
      items.size != prev.queue.size ||
        items.indices.any { items[it].mediaId != prev.queue[it].id || items[it].queueSource != prev.sources.getOrNull(it) }
    val sources = if (queueChanged) items.map { it.queueSource } else prev.sources
    val cur = c.currentMediaItemIndex
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
        source = queueSourceAround(sources, cur),
        queue = if (queueChanged) items.map(MediaItem::toTrack) else prev.queue,
        sources = sources,
        index = c.currentMediaItemIndex,
        upNext = upNextOf(c).let { if (it == prev.upNext) prev.upNext else it },
        error = c.playerError?.let(::describe),
      )
  }

  /** Источник очереди: у добавленного вручную — источник ближайшего трека до него. */
  private fun queueSourceAround(sources: List<String?>, index: Int): String? {
    var i = index
    while (i >= 0 && i < sources.size) {
      if (sources[i] != QueueSource.USER) return sources[i]
      i--
    }
    return sources.getOrNull(index)
  }

  private fun upNextOf(c: MediaController): List<Int> {
    val timeline = c.currentTimeline
    if (timeline.isEmpty || c.currentMediaItemIndex == C.INDEX_UNSET) return emptyList()
    val out = ArrayList<Int>()
    var i = c.currentMediaItemIndex
    // Без повтора: иначе очередь замкнулась бы в кольцо. Ограничение — на случай сломанного таймлайна.
    while (out.size < timeline.windowCount) {
      i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
      if (i == C.INDEX_UNSET || i >= c.mediaItemCount) break
      out += i
    }
    return out
  }

  private fun describe(e: PlaybackException): String =
    when (e.errorCode) {
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет связи. Скачанные треки играют и без интернета"
      PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Этот трек сейчас недоступен"
      else -> "Не получилось воспроизвести трек"
    }
}
