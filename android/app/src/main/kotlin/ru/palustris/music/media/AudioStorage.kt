package ru.palustris.music.media

import android.content.Context
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.media.audio.LoudnessStore
import timber.log.Timber

/** Состояние загрузки одного трека для интерфейса. */
data class TrackDownload(
  val track: Track,
  val state: State,
  /** 0..1, `null` пока размер неизвестен. */
  val progress: Float?,
  val bytes: Long,
  val cacheKey: String,
  val uri: String,
  val updatedAt: Long,
) {
  enum class State { Queued, Downloading, Done, Failed }
}

data class StorageUsage(val downloadsBytes: Long, val cacheBytes: Long, val freeBytes: Long, val downloadsCount: Int)

/**
 * Хранилище аудио на телефоне.
 *
 * Всё лежит в одном [SimpleCache] в приватной папке приложения (`noBackupFilesDir/audio`):
 * система не чистит её сама, в бэкап она не попадает и удаляется вместе с приложением.
 * Как экономится место, см. [PinnedLruEvictor] и [ru.palustris.music.core.prefs.DownloadQuality].
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioStorage
@Inject
constructor(
  @ApplicationContext private val context: Context,
  okHttp: OkHttpClient,
  private val settings: SettingsStore,
  private val json: Json,
  private val loudness: LoudnessStore,
  private val substitutes: SubstituteTracker,
  @AppScope private val scope: CoroutineScope,
) {
  private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }
  private val evictor = PinnedLruEvictor()
  private val dir = File(context.noBackupFilesDir, "audio")

  val cache: SimpleCache by lazy { SimpleCache(dir, evictor, databaseProvider) }

  private val upstream: DataSource.Factory = OkHttpDataSource.Factory(okHttp)

  private val _downloads = MutableStateFlow<Map<String, TrackDownload>>(emptyMap())
  /** Загрузки по id трека. */
  val downloads: StateFlow<Map<String, TrackDownload>> = _downloads.asStateFlow()

  private var progressJob: Job? = null

  /** Создаётся лениво на главном потоке: колбэки DownloadManager приходят в поток создания. */
  val downloadManager: DownloadManager by lazy {
    DownloadManager(context, databaseProvider, cache, upstream, Executors.newFixedThreadPool(2)).apply {
      maxParallelDownloads = 2
      addListener(listener)
    }
  }

  /** Вызывается из Application.onCreate на главном потоке. */
  fun start() {
    downloadManager
    scope.launch {
      settings.settings.filterNotNull().collect { s ->
        evictor.setMaxBytes(s.cacheLimitMb * 1024L * 1024L)
        withContext(Dispatchers.Main) {
          downloadManager.requirements =
            Requirements(if (s.wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        }
        trim()
      }
    }
  }

  /** Источник данных плеера: сначала локальный кэш, затем сервер. */
  fun playbackDataSourceFactory(): DataSource.Factory {
    val cached =
      CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        // Запись из запасного источника не кэшируем под id оригинала, см. [SkipSubstituteSinkFactory].
        .setCacheWriteDataSinkFactory(
          SkipSubstituteSinkFactory(CacheDataSink.Factory().setCache(cache), substitutes::isSubstituted)
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    // Плееру всегда даём адрес потока «как на сервере». Если трек скачан, подменяем ключ и
    // адрес на скачанную копию: она уже целиком в кэше, и сеть не нужна.
    return ResolvingDataSource.Factory(cached) { spec ->
      val id = Streams.trackId(spec.uri) ?: return@Factory spec
      val dl = _downloads.value[id]?.takeIf { it.state == TrackDownload.State.Done }
      if (dl != null) spec.buildUpon().setUri(dl.uri).setKey(dl.cacheKey).build()
      else spec.buildUpon().setKey(Streams.cacheKey(id)).build()
    }
  }

  fun isDownloaded(trackId: String): Boolean =
    _downloads.value[trackId]?.state == TrackDownload.State.Done

  fun download(tracks: List<Track>) {
    val quality = settings.current.quality
    // Громкость нужна и без интернета: запоминаем её вместе со скачиванием.
    tracks.map { it.id }.chunked(100).forEach(loudness::request)
    for (t in tracks) {
      val existing = _downloads.value[t.id]
      if (existing != null && existing.state != TrackDownload.State.Failed) continue
      val request =
        DownloadRequest.Builder(t.id, Streams.uri(t, quality, download = true))
          .setCustomCacheKey(Streams.cacheKey(t.id, quality))
          .setData(json.encodeToString(Track.serializer(), t.copy(why = null)).encodeToByteArray())
          .build()
      evictor.pin(request.customCacheKey!!)
      DownloadService.sendAddDownload(context, MusicDownloadService::class.java, request, false)
    }
  }

  fun remove(trackId: String) {
    DownloadService.sendRemoveDownload(context, MusicDownloadService::class.java, trackId, false)
  }

  fun removeAll() {
    DownloadService.sendRemoveAllDownloads(context, MusicDownloadService::class.java, false)
  }

  suspend fun clearListeningCache() = withContext(Dispatchers.IO) { evictor.clearUnpinned(cache) }

  suspend fun usage(): StorageUsage =
    withContext(Dispatchers.IO) {
      cache // ждём инициализацию, чтобы цифры были полными
      val u = evictor.usage()
      val free = runCatching { StatFs(context.noBackupFilesDir.path).availableBytes }.getOrDefault(0L)
      StorageUsage(u.pinnedBytes, u.unpinnedBytes, free, _downloads.value.size)
    }

  private suspend fun trim() = withContext(Dispatchers.IO) { evictor.trim(cache) }

  private fun Download.toTrackDownload(): TrackDownload? {
    val track =
      runCatching { json.decodeFromString(Track.serializer(), request.data.decodeToString()) }.getOrNull()
        ?: return null
    val st =
      when (state) {
        Download.STATE_COMPLETED -> TrackDownload.State.Done
        Download.STATE_DOWNLOADING -> TrackDownload.State.Downloading
        Download.STATE_FAILED -> TrackDownload.State.Failed
        else -> TrackDownload.State.Queued
      }
    val pct = percentDownloaded
    return TrackDownload(
      track = track,
      state = st,
      progress = if (pct >= 0) pct / 100f else null,
      bytes = bytesDownloaded,
      cacheKey = request.customCacheKey ?: request.id,
      uri = request.uri.toString(),
      updatedAt = updateTimeMs,
    )
  }

  private val listener =
    object : DownloadManager.Listener {
      override fun onInitialized(downloadManager: DownloadManager) {
        scope.launch(Dispatchers.IO) {
          val all = mutableListOf<Download>()
          downloadManager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) all += c.download }
          evictor.setPinned(all.map { it.request.customCacheKey ?: it.request.id })
          _downloads.value = all.mapNotNull { it.toTrackDownload() }.associateBy { it.track.id }
          _downloads.value.keys.filterNot(loudness::isKnown).chunked(100).forEach(loudness::request)
          Timber.d("downloads: %d, cache %s", all.size, evictor.usage())
          trim()
        }
      }

      override fun onDownloadChanged(dm: DownloadManager, download: Download, finalException: Exception?) {
        val key = download.request.customCacheKey ?: download.request.id
        if (download.state == Download.STATE_REMOVING) {
          _downloads.update { it - download.request.id }
          return
        }
        evictor.pin(key)
        val td = download.toTrackDownload() ?: return
        _downloads.update { it + (td.track.id to td) }
        if (download.state == Download.STATE_DOWNLOADING) watchProgress()
        if (download.state == Download.STATE_COMPLETED && key != download.request.id) {
          // Скачали компактную копию: исходник из кэша прослушивания больше не нужен.
          scope.launch(Dispatchers.IO) { runCatching { cache.removeResource(download.request.id) } }
        }
        if (finalException != null) Timber.w(finalException, "download %s failed", download.request.id)
      }

      override fun onDownloadRemoved(dm: DownloadManager, download: Download) {
        evictor.unpin(download.request.customCacheKey ?: download.request.id)
        _downloads.update { it - download.request.id }
        scope.launch { trim() }
      }
    }

  /** DownloadManager не сообщает о прогрессе сам, поэтому опрашиваем, пока что-то качается. */
  private fun watchProgress() {
    if (progressJob?.isActive == true) return
    progressJob =
      scope.launch(Dispatchers.Main) {
        while (isActive) {
          val active = downloadManager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
          if (active.isEmpty()) break
          val updated = active.mapNotNull { it.toTrackDownload() }
          _downloads.update { m -> m + updated.associateBy { it.track.id } }
          delay(500)
        }
      }
  }
}
