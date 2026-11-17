package ru.palustris.music.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.media3.common.Player
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.palustris.music.core.AppScope
import ru.palustris.music.media.QueueStore
import ru.palustris.music.media.Streams
import ru.palustris.music.media.toTrack
import timber.log.Timber

/** Что показывает виджет. */
data class NowPlaying(
  val id: String? = null,
  val title: String = "",
  val artist: String = "",
  val thumb: String? = null,
  val playing: Boolean = false,
)

/**
 * Состояние для виджета: сервис воспроизведения пишет сюда при каждой смене трека и паузе,
 * виджет читает. Обложка декодируется один раз на трек (300 px, RGB_565, около 180 КБ), так что
 * все размеры виджета делят одну картинку и не упираются в лимит памяти RemoteViews.
 */
@Singleton
class NowPlayingStore
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val queueStore: QueueStore,
  @AppScope private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow(NowPlaying())
  val state: StateFlow<NowPlaying> = _state.asStateFlow()

  private val _art = MutableStateFlow<Bitmap?>(null)
  val art: StateFlow<Bitmap?> = _art.asStateFlow()

  private var artFor: String? = null
  private var artJob: Job? = null
  private var updateJob: Job? = null
  private val initLock = Mutex()
  @Volatile private var initialized = false

  /** Вызывается сервисом на главном потоке после событий плеера. */
  fun onPlayer(player: Player) {
    val item = player.currentMediaItem
    val t = item?.toTrack()
    set(
      NowPlaying(
        id = t?.id,
        title = t?.title.orEmpty(),
        artist = t?.artist.orEmpty(),
        thumb = t?.thumb,
        playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && item != null,
      )
    )
  }

  /** Сервис закрылся: музыка больше не играет, трек оставляем на виджете. */
  fun onStopped() = set(_state.value.copy(playing = false))

  private fun set(np: NowPlaying) {
    initialized = true
    if (np == _state.value) return
    _state.value = np
    loadArt(np.thumb)
    requestUpdate()
  }

  /**
   * Процесс запущен ради виджета (например, после перезагрузки), а музыка не играла:
   * показываем последний трек из сохранённой очереди.
   */
  suspend fun ensureInitialized() =
    initLock.withLock {
      if (initialized) return@withLock
      initialized = true
      val q = queueStore.load() ?: return@withLock
      val t = q.items.getOrNull(q.index)?.track ?: return@withLock
      _state.value = NowPlaying(t.id, t.title, t.artist, t.thumb, playing = false)
      loadArtNow(t.thumb)
    }

  private fun loadArt(thumb: String?) {
    if (thumb == artFor) return
    artJob?.cancel()
    artJob = scope.launch { loadArtNow(thumb) }
  }

  private suspend fun loadArtNow(thumb: String?) {
    artFor = thumb
    if (thumb == null) {
      _art.value = null
      return
    }
    val request =
      ImageRequest.Builder(context)
        .data(Streams.artwork(thumb, ART_PX))
        .size(ART_PX)
        // RemoteViews не умеет аппаратные картинки, а RGB_565 вдвое легче ARGB.
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .build()
    val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    if (artFor == thumb) {
      _art.value = bitmap
      requestUpdate()
    }
  }

  /** События плеера идут пачками: перерисовываем виджет не чаще раза в 300 мс. */
  private fun requestUpdate() {
    updateJob?.cancel()
    updateJob = scope.launch {
      delay(300)
      runCatching {
        if (GlanceAppWidgetManager(context).getGlanceIds(MusicWidget::class.java).isNotEmpty()) MusicWidget().updateAll(context)
      }.onFailure { Timber.w(it, "widget update failed") }
    }
  }

  private companion object {
    const val ART_PX = 300
  }
}
