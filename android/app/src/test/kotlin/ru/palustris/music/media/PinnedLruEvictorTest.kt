package ru.palustris.music.media

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedLruEvictorTest {
  private val mb = 1024L * 1024L

  /** Минимальный кэш: умеет только удалять фрагменты, сообщая об этом эвиктору. */
  private class FakeCache(val evictor: PinnedLruEvictor) {
    val removed = mutableListOf<String>()
    val cache: Cache =
      Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { proxy, m, args ->
        when (m.name) {
          "removeSpan" -> {
            val span = args!![0] as CacheSpan
            removed += span.key
            evictor.onSpanRemoved(proxy as Cache, span)
            null
          }
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args!![0]
          else -> throw UnsupportedOperationException(m.name)
        }
      } as Cache
  }

  private fun span(key: String, sizeMb: Long, touchedAt: Long) = CacheSpan(key, 0, sizeMb * mb, touchedAt, null)

  private fun ready(limitMb: Long, pinned: Set<String> = emptySet()) =
    PinnedLruEvictor().apply {
      setPinned(pinned)
      setMaxBytes(limitMb * mb)
    }

  @Test
  fun evictsLeastRecentlyPlayedFirst() {
    val e = ready(limitMb = 10)
    val c = FakeCache(e)
    e.onSpanAdded(c.cache, span("old", 4, 1))
    e.onSpanAdded(c.cache, span("mid", 4, 2))
    e.onSpanAdded(c.cache, span("new", 4, 3))
    assertEquals(listOf("old"), c.removed)
    assertEquals(8 * mb, e.usage().unpinnedBytes)
  }

  @Test
  fun neverEvictsDownloadsAndTheyDoNotCountTowardsLimit() {
    val e = ready(limitMb = 10, pinned = setOf("dl"))
    val c = FakeCache(e)
    e.onSpanAdded(c.cache, span("dl", 50, 0))
    e.onSpanAdded(c.cache, span("a", 6, 1))
    e.onSpanAdded(c.cache, span("b", 3, 2))
    assertTrue(c.removed.isEmpty())
    e.onSpanAdded(c.cache, span("c", 3, 3))
    assertEquals(listOf("a"), c.removed)
    assertEquals(PinnedLruEvictor.Usage(pinnedBytes = 50 * mb, unpinnedBytes = 6 * mb), e.usage())
  }

  @Test
  fun waitsForPinsBeforeEvictingAnything() {
    // Пока SimpleCache поднимает файлы с диска, список скачанного ещё не прочитан.
    val e = PinnedLruEvictor().apply { setMaxBytes(1 * mb) }
    val c = FakeCache(e)
    e.onSpanAdded(c.cache, span("dl", 30, 0))
    e.onSpanAdded(c.cache, span("x", 5, 1))
    assertTrue(c.removed.isEmpty())
    e.setPinned(setOf("dl"))
    e.trim(c.cache)
    assertEquals(listOf("x"), c.removed)
  }

  @Test
  fun lowerLimitAndClearOnlyTouchUnpinned() {
    val e = ready(limitMb = 100, pinned = setOf("dl"))
    val c = FakeCache(e)
    listOf("a" to 1L, "b" to 2L, "dl" to 3L, "d" to 4L).forEach { (k, t) -> e.onSpanAdded(c.cache, span(k, 10, t)) }
    e.setMaxBytes(15 * mb)
    e.trim(c.cache)
    assertEquals(listOf("a", "b"), c.removed)
    e.clearUnpinned(c.cache)
    assertEquals(listOf("a", "b", "d"), c.removed)
    assertEquals(PinnedLruEvictor.Usage(10 * mb, 0), e.usage())
  }

  @Test
  fun touchMovesSpanToTheEnd() {
    val e = ready(limitMb = 10)
    val c = FakeCache(e)
    val a = span("a", 4, 1)
    e.onSpanAdded(c.cache, a)
    e.onSpanAdded(c.cache, span("b", 4, 2))
    e.onSpanTouched(c.cache, a, span("a", 4, 3)) // переслушали «a»
    e.onSpanAdded(c.cache, span("c", 4, 4))
    assertEquals(listOf("b"), c.removed)
  }

  @Test
  fun downloadsInCompactQualityGetTheirOwnCacheKey() {
    assertEquals("abc@o64", Streams.cacheKey("abc", ru.palustris.music.core.prefs.DownloadQuality.Compact))
    assertEquals("abc", Streams.cacheKey("abc", ru.palustris.music.core.prefs.DownloadQuality.Original))
  }
}
