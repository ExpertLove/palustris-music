package ru.palustris.music

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient
import ru.palustris.music.media.AudioStorage
import timber.log.Timber

@HiltAndroidApp
class MusicApp : Application(), SingletonImageLoader.Factory {
  @Inject lateinit var okHttp: OkHttpClient
  @Inject lateinit var storage: AudioStorage

  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    storage.start()
  }

  /** Обложки: общий OkHttp и небольшой дисковый кэш, чтобы приложение не разрасталось. */
  override fun newImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
      .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
      .diskCache {
        DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(64L * 1024 * 1024).build()
      }
      .crossfade(true)
      .build()
}
