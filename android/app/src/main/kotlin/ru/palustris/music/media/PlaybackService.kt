package ru.palustris.music.media

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.palustris.music.MainActivity
import ru.palustris.music.R
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
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

  private var session: MediaSession? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onCreate() {
    super.onCreate()
    val player =
      ExoPlayer.Builder(this)
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

  /** Смахнули приложение из недавних: если музыка играет, продолжаем, иначе закрываемся. */
  override fun onTaskRemoved(rootIntent: Intent?) {
    if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
  }

  override fun onDestroy() {
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
}
