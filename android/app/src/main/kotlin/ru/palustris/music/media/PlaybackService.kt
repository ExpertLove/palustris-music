package ru.palustris.music.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.palustris.music.BuildConfig
import ru.palustris.music.MainActivity
import ru.palustris.music.R
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.media.audio.CrossfadePlan
import ru.palustris.music.media.audio.CrossfadeProcessor
import ru.palustris.music.media.audio.LoudnessProcessor
import ru.palustris.music.media.audio.LoudnessStore
import ru.palustris.music.media.eq.EqualizerProcessor
import ru.palustris.music.media.eq.EqualizerStore
import timber.log.Timber

/**
 * Воспроизведение в фоне: ExoPlayer внутри MediaSessionService. Media3 сама держит сервис
 * на переднем плане, пока играет музыка, и показывает уведомление с управлением, которое
 * работает и на экране блокировки, и с кнопок гарнитуры.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
  @Inject lateinit var storage: AudioStorage
  @Inject lateinit var api: MusicApi
  @Inject lateinit var equalizer: EqualizerStore
  @Inject lateinit var queueStore: QueueStore
  @Inject lateinit var sleepTimer: SleepTimer
  @Inject lateinit var loudness: LoudnessStore
  @Inject lateinit var settings: SettingsStore

  private var session: MediaSession? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /** Пишется на главном потоке, читается аудиопотоком в [CrossfadeProcessor]. */
  @Volatile private var crossfadePlan = CrossfadePlan.OFF

  override fun onCreate() {
    super.onCreate()
    // Своя цепочка обработки звука: выравнивание громкости → эквалайзер → плавный переход.
    val renderers =
      object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
          DefaultAudioSink.Builder(context)
            .setAudioProcessors(audioProcessors())
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .build()
      }
    val player =
      ExoPlayer.Builder(this, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(storage.playbackDataSourceFactory()))
        .setAudioAttributes(
          AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
          /* handleAudioFocus= */ true,
        )
        // Выдернули наушники: пауза, а не музыка на весь автобус.
        .setHandleAudioBecomingNoisy(true)
        // Держим Wi-Fi и процессор, пока играет поток с экраном выключенным.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
    player.addListener(Reporter(player))
    sleepTimer.player = player
    restoreQueue(player)
    persistQueue(player)
    watchTransitions(player)

    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(Callback()).build()
    setMediaNotificationProvider(
      DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_stat_music) }
    )
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  private fun audioProcessors(): Array<AudioProcessor> {
    val chain =
      mutableListOf<AudioProcessor>(
        LoudnessProcessor(loudness::gain),
        EqualizerProcessor { equalizer.snapshot },
        CrossfadeProcessor({ crossfadePlan }),
      )
    // Отладка: `adb shell run-as ru.palustris.music.debug touch files/pcm_dump` — и всё, что уходит
    // в динамик, пишется в files/pcm/*.wav (новый файл на каждый трек и перемотку).
    if (BuildConfig.DEBUG && File(filesDir, "pcm_dump").exists()) {
      val dir = File(filesDir, "pcm").apply { mkdirs() }
      chain += TeeAudioProcessor(TeeAudioProcessor.WavFileAudioBufferSink(File(dir, "out").path))
    }
    return chain.toTypedArray()
  }

  /**
   * Раз в 250 мс решаем, можно ли плавно перейти из текущего трека, и публикуем это для
   * [CrossfadeProcessor]. Тут видно то, чего не видно аудиопотоку: очередь, повтор, таймер сна,
   * загружен ли следующий трек. Заодно спрашиваем громкость ближайших треков.
   */
  private fun watchTransitions(player: ExoPlayer) {
    scope.launch {
      var tick = 0
      while (isActive) {
        crossfadePlan = planCrossfade(player)
        if (tick++ % 16 == 0) requestLoudness(player)
        delay(250)
      }
    }
  }

  private fun planCrossfade(p: ExoPlayer): CrossfadePlan {
    val item = p.currentMediaItem ?: return CrossfadePlan.OFF
    val fadeMs = settings.current.crossfadeSec * 1000
    if (fadeMs <= 0) return CrossfadePlan(item.mediaId, 0, false)
    val duration = p.duration
    val next = p.nextMediaItemIndex
    val allowed =
      duration != C.TIME_UNSET &&
        // Короткий трек весь ушёл бы в переход: треть трека всегда звучит сама по себе.
        duration >= max(MIN_CROSSFADE_TRACK_MS, 3L * fadeMs) &&
        next != C.INDEX_UNSET &&
        p.getMediaItemAt(next).mediaId != item.mediaId &&
        p.repeatMode != Player.REPEAT_MODE_ONE &&
        // «Остановить в конце трека»: трек должен доиграть до конца сам.
        !p.pauseAtEndOfMediaItems &&
        // Следующий трек уже загружается: иначе вместо перехода была бы тишина.
        p.totalBufferedDuration - (duration - p.currentPosition) >= NEXT_BUFFERED_MS
    return CrossfadePlan(item.mediaId, fadeMs, allowed)
  }

  /** Текущий и три следующих трека в порядке воспроизведения (с учётом перемешивания). */
  private fun requestLoudness(p: ExoPlayer) {
    if (!loudness.enabled || p.mediaItemCount == 0) return
    val timeline = p.currentTimeline
    val ids = ArrayList<String>(4)
    var i = p.currentMediaItemIndex
    while (i != C.INDEX_UNSET && ids.size < 4 && i < p.mediaItemCount) {
      ids += p.getMediaItemAt(i).mediaId
      i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
    }
    loudness.request(ids)
  }

  /** Смахнули приложение из недавних: если музыка играет, продолжаем, иначе закрываемся. */
  override fun onTaskRemoved(rootIntent: Intent?) {
    if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
  }

  override fun onDestroy() {
    session?.player?.let { p -> queueStore.snapshot(p).let { q -> runBlocking { queueStore.save(q) } } }
    sleepTimer.cancel()
    sleepTimer.player = null
    scope.cancel()
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }

  private inner class Callback : MediaSession.Callback {
    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
      Futures.immediateFuture(mediaItems.map { it.withPlayableUri() }.toMutableList())

    /**
     * Кнопка «играть» на гарнитуре или в системной шторке, когда приложение не запущено:
     * продолжаем с того места, где остановились.
     */
    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      val q = runBlocking { withTimeoutOrNull(2_000) { queueStore.load() } }
        ?: return Futures.immediateFailedFuture(UnsupportedOperationException("нет сохранённой очереди"))
      return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(with(queueStore) { q.toMediaItems() }, q.index, q.positionMs)
      )
    }
  }

  /** Открыли приложение после выгрузки: показываем прошлую очередь на паузе, на том же месте. */
  private fun restoreQueue(player: ExoPlayer) {
    scope.launch {
      val q = queueStore.load() ?: return@launch
      if (player.mediaItemCount > 0) return@launch // пользователь уже что-то включил
      player.setMediaItems(with(queueStore) { q.toMediaItems() }, q.index, q.positionMs)
      player.prepare()
    }
  }

  /** Сохраняем очередь при её изменении и раз в 10 секунд позицию, пока играет. */
  private fun persistQueue(player: ExoPlayer) {
    player.addListener(
      object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
          if (
            events.containsAny(
              Player.EVENT_TIMELINE_CHANGED,
              Player.EVENT_MEDIA_ITEM_TRANSITION,
              Player.EVENT_IS_PLAYING_CHANGED,
              Player.EVENT_POSITION_DISCONTINUITY,
            )
          ) {
            val q = queueStore.snapshot(p)
            scope.launch { queueStore.save(q) }
          }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
          if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepTimer.onTrackEndedPause()
        }
      }
    )
    scope.launch {
      while (isActive) {
        delay(10_000)
        if (player.isPlaying) queueStore.snapshot(player).let { q -> launch { queueStore.save(q) } }
      }
    }
  }

  /**
   * Что слушали и что пропускали: из этого сервер строит «Мою волну». Прослушанным считаем трек,
   * который играл хотя бы 30 секунд или половину длины, пропущенным — переключённый вручную
   * раньше 15 секунд.
   */
  private inner class Reporter(private val player: ExoPlayer) : Player.Listener {
    private var item: MediaItem? = null
    private var playedMs = 0L
    private var playingSince = 0L
    private var errorsInRow = 0
    private var extendingWave = false

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      val now = SystemClock.elapsedRealtime()
      if (isPlaying) {
        playingSince = now
        errorsInRow = 0
      } else if (playingSince > 0) {
        playedMs += now - playingSince
        playingSince = 0
      }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      val now = SystemClock.elapsedRealtime()
      if (playingSince > 0) playedMs += now - playingSince
      item?.let { report(it, playedMs, manual = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) }
      item = mediaItem
      playedMs = 0
      playingSince = if (player.isPlaying) now else 0
      prefetchNext()
      extendWave()
      requestLoudness(player)
    }

    private fun report(item: MediaItem, played: Long, manual: Boolean) {
      val track = item.toTrack()
      val dur = track.duration * 1000
      val listened = played >= 30_000 || (dur > 0 && played >= dur / 2)
      if (!listened && !(manual && played < 15_000)) return
      scope.launch { apiCall { if (listened) api.history(track) else api.skip(track) } }
    }

    /** Сервер заранее скачивает следующие треки, чтобы они стартовали без паузы. */
    private fun prefetchNext() {
      val ids =
        (1..2).mapNotNull { off ->
            val i = player.currentMediaItemIndex + off
            if (i < player.mediaItemCount) player.getMediaItemAt(i).mediaId else null
          }
          .filterNot { storage.isDownloaded(it) }
      if (ids.isNotEmpty()) scope.launch { apiCall { api.prefetch(IdsBody(ids)) } }
    }

    /** «Моя волна» бесконечная: когда впереди меньше трёх треков, догружаем новые. */
    private fun extendWave() {
      val current = player.currentMediaItem ?: return
      if (current.queueSource != QueueSource.WAVE || extendingWave) return
      if (player.mediaItemCount - player.currentMediaItemIndex > 3) return
      extendingWave = true
      scope.launch {
        apiCall { api.wave() }
          .onSuccess { more ->
            val have = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
            player.addMediaItems(more.filter { it.id !in have }.map { it.toMediaItem(QueueSource.WAVE) })
          }
        extendingWave = false
      }
    }

    /** Трек не открылся: переходим к следующему, но не перебираем всю очередь без сети. */
    override fun onPlayerError(error: PlaybackException) {
      Timber.w(error, "playback error on %s", player.currentMediaItem?.mediaId)
      errorsInRow++
      if (errorsInRow <= 3 && player.hasNextMediaItem()) {
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
      }
    }
  }

  private companion object {
    const val MIN_CROSSFADE_TRACK_MS = 20_000L
    const val NEXT_BUFFERED_MS = 2_000L
  }
}
