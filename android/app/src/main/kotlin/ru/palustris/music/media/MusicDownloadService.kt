package ru.palustris.music.media

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ru.palustris.music.MainActivity
import ru.palustris.music.R

/**
 * Фоновое скачивание. Пока что-то качается, сервис держится на переднем плане с уведомлением
 * о прогрессе, поэтому загрузка не обрывается, если свернуть приложение.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicDownloadService :
  DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    R.string.downloads_channel_description,
  ) {
  @Inject lateinit var storage: AudioStorage

  private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

  override fun getDownloadManager(): DownloadManager = storage.downloadManager

  /** Без планировщика: загрузки продолжатся при следующем запуске приложения. */
  override fun getScheduler(): Scheduler? = null

  override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
    val message = if (active > 0) resources.getQuantityString(R.plurals.downloads_left, active, active) else null
    return notifications.buildProgressNotification(
      this,
      R.drawable.ic_stat_download,
      open,
      message,
      downloads,
      notMetRequirements,
    )
  }

  private companion object {
    const val NOTIFICATION_ID = 2
    const val CHANNEL_ID = "downloads"
  }
}
