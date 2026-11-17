package ru.palustris.music.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Запись, которая играет вместо трека: YouTube его не отдал, и сервер нашёл такую же запись
 * исполнителя в другом каталоге (Audius, SoundCloud, archive.org). См. server/src/sources.rs.
 */
data class Substitute(val source: String, val sourceName: String, val title: String, val artist: String) {
  companion object {
    private val NAMES = mapOf("audius" to "Audius", "soundcloud" to "SoundCloud", "archive" to "archive.org")

    /** Из заголовков ответа `/api/stream/{id}`: `X-Substitute`, `X-Substitute-Title`, `X-Substitute-Artist`. */
    fun fromHeaders(h: Headers): Substitute? {
      val source = h["X-Substitute"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      fun text(name: String) = h[name]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
      return Substitute(source, NAMES[source] ?: source, text("X-Substitute-Title"), text("X-Substitute-Artist"))
    }
  }
}

/** Какие треки сейчас играют из запасного источника. Узнаём по заголовкам ответа сервера. */
@Singleton
class SubstituteTracker @Inject constructor() {
  private val _current = MutableStateFlow<Map<String, Substitute>>(emptyMap())
  /** id трека → что играет вместо него. */
  val current: StateFlow<Map<String, Substitute>> = _current.asStateFlow()

  fun isSubstituted(trackId: String): Boolean = trackId in _current.value

  fun update(trackId: String, sub: Substitute?) {
    if (_current.value[trackId] == sub) return
    _current.update { if (sub == null) it - trackId else it + (trackId to sub) }
  }
}

/** id трека из адреса потока `/api/stream/{id}`; `null` для загрузок (`q=`, `dl=1`) и прочих запросов. */
internal fun streamTrackId(url: HttpUrl): String? {
  val seg = url.pathSegments
  val i = seg.indexOf("stream")
  if (i < 1 || seg[i - 1] != "api" || i + 1 >= seg.size) return null
  if (url.queryParameter("q") != null || url.queryParameter("dl") == "1") return null
  return seg[i + 1].takeIf { it.isNotEmpty() }
}

/** Смотрит на ответы потока и отмечает подменённые треки в [SubstituteTracker]. */
class SubstituteInterceptor(private val tracker: SubstituteTracker) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val res = chain.proceed(chain.request())
    val id = streamTrackId(chain.request().url) ?: return res
    if (res.isSuccessful) tracker.update(id, Substitute.fromHeaders(res.headers))
    return res
  }
}

/**
 * Кэш плеера не записывает подменённый звук: он лёг бы под id оригинала и играл бы дальше
 * без интернета и без пометки, даже когда YouTube снова отдаёт трек.
 *
 * Работает потому, что `TeeDataSource` открывает сеть (интерцептор уже отметил трек) раньше,
 * чем этот приёмник.
 */
@OptIn(UnstableApi::class)
class SkipSubstituteSinkFactory(
  private val inner: DataSink.Factory,
  private val isSubstituted: (String) -> Boolean,
) : DataSink.Factory {
  override fun createDataSink(): DataSink = Sink(inner.createDataSink())

  private inner class Sink(private val target: DataSink) : DataSink {
    private var skip = false

    override fun open(dataSpec: DataSpec) {
      val id = dataSpec.key?.substringBefore('@')
      skip = id != null && isSubstituted(id)
      if (!skip) target.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      if (!skip) target.write(buffer, offset, length)
    }

    override fun close() {
      if (!skip) target.close()
      skip = false
    }
  }
}
