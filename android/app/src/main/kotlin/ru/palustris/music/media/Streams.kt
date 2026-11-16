package ru.palustris.music.media

import android.net.Uri
import androidx.core.net.toUri
import ru.palustris.music.BuildConfig
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.prefs.DownloadQuality

/**
 * Адреса потоков и ключи кэша.
 *
 * Ключ кэша зависит только от трека и качества, а не от URL: так один и тот же файл находится
 * и плеером, и загрузчиком, и не зависит от параметров `a`/`t` в адресе.
 */
object Streams {
  private const val PATH = "api/stream/"

  fun uri(track: Track, quality: DownloadQuality? = null): Uri =
    BuildConfig.API_BASE_URL.toUri()
      .buildUpon()
      .appendEncodedPath(PATH + track.id)
      // По исполнителю и названию сервер ищет замену, если исходное видео недоступно.
      .appendQueryParameter("a", track.artist)
      .appendQueryParameter("t", track.title)
      .apply { quality?.param?.let { appendQueryParameter("q", it) } }
      .build()

  fun trackId(uri: Uri): String? {
    val path = uri.path ?: return null
    val i = path.indexOf("/$PATH")
    return if (i < 0) null else path.substring(i + PATH.length + 1).takeIf { it.isNotEmpty() && '/' !in it }
  }

  /** Поток «как на сервере» хранится под id трека, компактные копии под `id@o64` и т. п. */
  fun cacheKey(trackId: String, quality: DownloadQuality? = null): String =
    quality?.param?.let { "$trackId@$it" } ?: trackId

  /** Обложка покрупнее: у googleusercontent размер задаётся суффиксом (как на сайте). */
  fun artwork(url: String?, px: Int = 544): String? {
    if (url == null) return null
    if ("googleusercontent.com" in url || "ggpht.com" in url) {
      return url.replace(Regex("=w\\d+-h\\d+[^&]*$|=s\\d+[^&]*$"), "=w$px-h$px-l90-rj")
    }
    if ("i.ytimg.com/vi/" in url) return url.replace(Regex("/(hq|mq|sd)?default\\.jpg.*$"), "/hqdefault.jpg")
    return url
  }
}
