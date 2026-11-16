package ru.palustris.music.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Один кэш на всё аудио: и прослушанное, и скачанное.
 *
 * - Прослушанные треки живут по принципу LRU: когда их суммарный размер превышает лимит,
 *   первыми удаляются те, что играли давнее всего.
 * - Скачанные треки «закреплены»: в лимит не входят и сами никогда не удаляются.
 *
 * Так один файл не хранится дважды. Если трек уже прослушан целиком, скачивание в том же
 * качестве не тратит ни трафика, ни места: остаётся только закрепить уже готовый файл.
 *
 * Пока не загружен список закреплённых ключей и лимит, ничего не удаляем: иначе при старте,
 * когда SimpleCache читает файлы с диска, можно было бы стереть скачанное.
 *
 * Порядок блокировок: SimpleCache вызывает колбэки под своим монитором, а здесь берётся монитор
 * эвиктора. Код снаружи должен брать их в том же порядке, см. [trim].
 */
@OptIn(UnstableApi::class)
class PinnedLruEvictor : CacheEvictor {
  private val spans =
    TreeSet<CacheSpan>(
      Comparator { a, b ->
        val t = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (t != 0) t else a.compareTo(b)
      }
    )
  private val pinned: MutableSet<String> = ConcurrentHashMap.newKeySet()

  @Volatile private var maxBytes = Long.MAX_VALUE
  @Volatile private var pinsLoaded = false
  @Volatile private var limitLoaded = false

  fun setPinned(keys: Collection<String>) {
    pinned.clear()
    pinned.addAll(keys)
    pinsLoaded = true
  }

  fun pin(key: String) {
    pinned.add(key)
  }

  fun unpin(key: String) {
    pinned.remove(key)
  }

  fun isPinned(key: String): Boolean = key in pinned

  fun setMaxBytes(bytes: Long) {
    maxBytes = bytes
    limitLoaded = true
  }

  override fun requiresCacheSpanTouches(): Boolean = true

  override fun onCacheInitialized() {}

  override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
    if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
  }

  @Synchronized
  override fun onSpanAdded(cache: Cache, span: CacheSpan) {
    spans.add(span)
    evict(cache, 0)
  }

  @Synchronized
  override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
    spans.remove(span)
  }

  @Synchronized
  override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
    spans.remove(oldSpan)
    spans.add(newSpan)
    evict(cache, 0)
  }

  /** Применить новый лимит или новый набор закреплённых ключей. Вызывать не с главного потока. */
  fun trim(cache: Cache) {
    synchronized(cache) { evict(cache, 0) }
  }

  /** Удалить всё незакреплённое: «Очистить кэш прослушивания». */
  fun clearUnpinned(cache: Cache) {
    synchronized(cache) {
      val victims = synchronized(this) { spans.filter { it.key !in pinned } }
      victims.forEach { cache.removeSpan(it) }
    }
  }

  data class Usage(val pinnedBytes: Long, val unpinnedBytes: Long)

  @Synchronized
  fun usage(): Usage {
    var p = 0L
    var u = 0L
    for (s in spans) if (s.key in pinned) p += s.length else u += s.length
    return Usage(p, u)
  }

  @Synchronized
  private fun evict(cache: Cache, requiredSpace: Long) {
    if (!pinsLoaded || !limitLoaded) return
    var unpinned = 0L
    for (s in spans) if (s.key !in pinned) unpinned += s.length
    if (unpinned + requiredSpace <= maxBytes) return
    val victims = ArrayList<CacheSpan>()
    for (s in spans) {
      if (unpinned + requiredSpace <= maxBytes) break
      if (s.key in pinned) continue
      victims += s
      unpinned -= s.length
    }
    // removeSpan снова зайдёт в onSpanRemoved: удаляем по копии, а не во время обхода.
    victims.forEach { cache.removeSpan(it) }
  }
}
