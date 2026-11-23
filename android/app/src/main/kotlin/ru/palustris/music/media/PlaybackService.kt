package ru.palustris.music.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.future
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
import ru.palustris.music.data.Outbox
import ru.palustris.music.data.PendingOp
import ru.palustris.music.data.Recommendations
import ru.palustris.music.media.audio.CrossfadePlan
import ru.palustris.music.media.audio.CrossfadeProcessor
import ru.palustris.music.media.audio.AudioLevels
import ru.palustris.music.media.audio.LevelProcessor
import ru.palustris.music.media.audio.LoudnessProcessor
import ru.palustris.music.media.audio.LoudnessStore
import ru.palustris.music.media.eq.EqualizerProcessor
import ru.palustris.music.media.eq.EqualizerStore
import ru.palustris.music.widget.NowPlayingStore
import timber.log.Timber

/**
 * Воспроизведение в фоне: ExoPlayer внутри MediaLibraryService (это MediaSessionService плюс
 * каталог для Android Auto, см. [LibraryTree]). Media3 сама держит сервис
 * на переднем плане, пока играет музыка, и показывает уведомление с управлением, которое
 * работает и на экране блокировки, и с кнопок гарнитуры.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
  @Inject lateinit var storage: AudioStorage
  @Inject lateinit var api: MusicApi
  @Inject lateinit var equalizer: EqualizerStore
  @Inject lateinit var queueStore: QueueStore
  @Inject lateinit var sleepTimer: SleepTimer
  @Inject lateinit var loudness: LoudnessStore
  @Inject lateinit var settings: SettingsStore
  @Inject lateinit var outbox: Outbox
  @Inject lateinit var nowPlaying: NowPlayingStore
  @Inject lateinit var recs: Recommendations
  @Inject lateinit var levels: AudioLevels

  @Inject lateinit var tree: LibraryTree

  private var session: MediaLibrarySession? = null
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
    // Виджет на главном экране узнаёт о смене трека и паузе отсюда.
    player.addListener(
      object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
          if (
            events.containsAny(
              Player.EVENT_MEDIA_ITEM_TRANSITION,
              Player.EVENT_PLAY_WHEN_READY_CHANGED,
              Player.EVENT_PLAYBACK_STATE_CHANGED,
              Player.EVENT_MEDIA_METADATA_CHANGED,
              Player.EVENT_TIMELINE_CHANGED,
            )
          ) nowPlaying.onPlayer(p)
        }
      }
    )
    sleepTimer.player = player
    applySpeed(player)
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
    session = MediaLibrarySession.Builder(this, SessionPlayer(player), Callback()).setSessionActivity(open).build()
    setMediaNotificationProvider(
      DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_stat_music) }
    )
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

  /**
   * Часы, машина и шторка прячут «назад/вперёд», если плеер не объявляет команды перехода:
   * у Media3 они пропадают, когда впереди нет трека. Объявляем всегда и на конце очереди
   * повторяем поведение плеера (ничего не делаем), а не отдаём чужой трек.
   */
  private class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
      super
        .getAvailableCommands()
        .buildUpon()
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .build()

    override fun seekToNext() = seekToNextMediaItem()

    override fun seekToNextMediaItem() {
      if (hasNextMediaItem()) super.seekToNextMediaItem()
    }

    override fun seekToPrevious() = seekToPreviousMediaItem()

    override fun seekToPreviousMediaItem() {
      // Как кнопка «назад» в приложении: после трёх секунд — начало трека, иначе предыдущий.
      if (currentPosition > 3_000 || currentMediaItemIndex <= 0) {
        seekTo(0)
        if (!isPlaying) play()
      } else {
        super.seekToPreviousMediaItem()
      }
    }
  }

  private fun audioProcessors(): Array<AudioProcessor> {
    val chain =
      mutableListOf<AudioProcessor>(
        LoudnessProcessor(loudness::gain),
        EqualizerProcessor { equalizer.snapshot },
        // Спектр для анимации плеера: после эквалайзера (что слышно), до перехода (время трека).
        LevelProcessor(levels),
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

  /**
   * Скорость из настроек, в том числе после перезапуска. `setPlaybackSpeed` оставляет тон прежним:
   * Sonic в конце цепочки меняет темп, а не высоту. Наши процессоры стоят до него и считают
   * время трека, поэтому переход в 6 с на 1,5× звучит 4 с.
   */
  private fun applySpeed(player: ExoPlayer) {
    scope.launch {
      settings.settings.filterNotNull().map { it.speed }.distinctUntilChanged().collect { player.setPlaybackSpeed(it) }
    }
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
    nowPlaying.onStopped()
    scope.cancel()
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }

  /**
   * Сессия для приложения, системы и Android Auto. Библиотеку (лайки, плейлисты) видят только
   * доверенные клиенты: само приложение, система и машина. Остальным — только управление.
   */
  private inner class Callback : MediaLibrarySession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
      val trusted = controller.packageName == packageName || controller.isTrusted || controller.packageName in LIBRARY_CLIENTS
      Log.i("MusicSession", "controller ${controller.packageName} trusted=$trusted")
      return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        .setAvailableSessionCommands(
          if (trusted) MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
          else MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
        )
        // Media3 по умолчанию даёт недоверенным контроллерам только чтение: на часах кнопки
        // есть, а нажатия отклоняются. Само управление воспроизведением разрешаем всем.
        .setAvailablePlayerCommands(Player.Commands.Builder().addAllCommands().build())
        .build()
    }

    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
      scope.future {
        // Трек из списка в машине: в очередь встаёт сам трек, а не элемент каталога.
        mediaItems
          .flatMap { m ->
            val resolved = if (m.mediaId.startsWith(LibraryTree.ITEM) || m.mediaId == LibraryTree.WAVE) tree.resolve(listOf(m)) else null
            if (resolved != null) {
              val t = resolved.tracks[resolved.index]
              listOf(t.toMediaItem(resolved.source))
            } else listOf(m.withPlayableUri())
          }
          .toMutableList()
      }

    /** Нажали трек в машине: весь список с этого трека; «Моя волна» и голосовой поиск — тоже тут. */
    override fun onSetMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
      scope.future {
        val r = tree.resolve(mediaItems)
        if (r != null) {
          MediaSession.MediaItemsWithStartPosition(r.tracks.map { it.toMediaItem(r.source) }, r.index, C.TIME_UNSET)
        } else {
          MediaSession.MediaItemsWithStartPosition(mediaItems.map { it.withPlayableUri() }, startIndex, startPositionMs)
        }
      }

    /**
     * Кнопка «играть» на гарнитуре или в системной шторке, когда приложение не запущено:
     * продолжаем с того места, где остановились.
     */
    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
      scope.future {
        val q = withTimeoutOrNull(2_000) { queueStore.load() } ?: throw UnsupportedOperationException("нет сохранённой очереди")
        val items = with(queueStore) { q.toMediaItems() }
        // Системе для карточки «продолжить» (например, после перезагрузки) нужен только текущий трек.
        if (isForPlayback) MediaSession.MediaItemsWithStartPosition(items, q.index, q.positionMs)
        else MediaSession.MediaItemsWithStartPosition(listOf(items[q.index]), 0, q.positionMs)
      }

    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

    override fun onGetItem(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
      scope.future {
        tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
      }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
      scope.future { LibraryResult.ofItemList(paged(tree.children(parentId), page, pageSize), params) }

    override fun onSearch(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      query: String,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
      scope.future {
        val found = tree.search(query)
        session.notifySearchResultChanged(browser, query, found.size, params)
        LibraryResult.ofVoid()
      }

    override fun onGetSearchResult(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      query: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
      scope.future { LibraryResult.ofItemList(paged(tree.searchItems(), page, pageSize), params) }

    private fun paged(all: List<MediaItem>, page: Int, size: Int): ImmutableList<MediaItem> {
      if (size <= 0 || size == Int.MAX_VALUE) return ImmutableList.copyOf(all)
      val from = page.toLong() * size
      if (from >= all.size) return ImmutableList.of()
      return ImmutableList.copyOf(all.subList(from.toInt(), minOf(all.size, from.toInt() + size)))
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
    private var extending = false
    /** Пропуски подряд в волне: после двух волна перестраивается, как в Яндекс Музыке. */
    private var skipsInRow = 0

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
      extend()
      requestLoudness(player)
    }

    /** Из очереди волны или радио убрали треки или очистили «Далее»: продолжение догружается сразу. */
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) extend()
    }

    private fun report(item: MediaItem, played: Long, manual: Boolean) {
      val track = item.toTrack()
      val dur = track.duration * 1000
      val listened = played >= 30_000 || (dur > 0 && played >= dur / 2)
      if (!listened && !(manual && played < 15_000)) return
      if (listened) {
        skipsInRow = 0
        // Прослушивание ждёт сети в очереди: без него «Моя волна» не узнает, что слушали в метро.
        val at = System.currentTimeMillis() / 1000 - played / 1000
        outbox.add(PendingOp.Listened(track, at = at, secs = played / 1000))
        return
      }
      val inWave = item.queueSource == QueueSource.WAVE
      if (inWave) skipsInRow++
      val rebuild = inWave && skipsInRow >= 2
      if (rebuild) skipsInRow = 0
      scope.launch {
        apiCall { api.skip(track) }
        // Два пропуска подряд — волна не попадает: сервер уже знает о пропусках, берём свежую.
        if (rebuild) rebuildWaveAhead()
      }
    }

    /** Заменить всё, что в волне впереди, свежей подборкой. Текущий трек доигрывает. */
    private suspend fun rebuildWaveAhead() {
      val current = player.currentMediaItem ?: return
      if (current.queueSource != QueueSource.WAVE) return
      val fresh = recs.wave().getOrNull()?.takeIf { it.size >= 5 } ?: return
      val idx = player.currentMediaItemIndex
      val keep = (0..idx).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
      val items = fresh.filter { it.id !in keep }.map { it.toMediaItem(QueueSource.WAVE) }
      if (items.isEmpty() || player.currentMediaItem?.mediaId != current.mediaId) return
      // Одной заменой, иначе пустое «Далее» запустило бы догрузку старой волны.
      player.replaceMediaItems(idx + 1, player.mediaItemCount, items)
      Timber.i("wave rebuilt after skips: %d new", items.size)
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

    /**
     * «Моя волна» и радио бесконечные: когда впереди меньше трёх треков, догружаем новые. Волна —
     * с текущими настройками, радио — похожее на то, что играет сейчас (так оно плавно дрейфует).
     */
    private fun extend() {
      val current = player.currentMediaItem ?: return
      val source = current.queueSource
      if ((source != QueueSource.WAVE && source != QueueSource.RADIO) || extending) return
      if (player.mediaItemCount - player.currentMediaItemIndex > 3) return
      extending = true
      scope.launch {
        val more = if (source == QueueSource.WAVE) recs.wave() else recs.radio(current.toTrack())
        more.onSuccess { list ->
          val have = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
          player.addMediaItems(list.filter { it.id !in have }.map { it.toMediaItem(source) })
        }
        extending = false
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
    /** Кому показываем библиотеку, кроме доверенных системой: Android Auto, Ассистент, часы. */
    val LIBRARY_CLIENTS =
      setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.carassistant",
        "com.google.android.googlequicksearchbox",
        "com.google.android.wearable.app",
        "com.android.systemui",
      )
    const val MIN_CROSSFADE_TRACK_MS = 20_000L
    const val NEXT_BUFFERED_MS = 2_000L
  }
}
