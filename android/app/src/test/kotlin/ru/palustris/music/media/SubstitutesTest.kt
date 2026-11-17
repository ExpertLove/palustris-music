package ru.palustris.music.media

import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubstitutesTest {
  @Test
  fun headersAreDecoded() {
    // Так их кодирует сервер (sources::pct): UTF-8 в процентах, пробел — %20.
    val h =
      headersOf(
        "x-substitute", "archive",
        "x-substitute-title", "%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%B0%20%D0%BA%D1%80%D0%BE%D0%B2%D0%B8",
        "x-substitute-artist", "%D0%9A%D0%B8%D0%BD%D0%BE",
      )
    assertEquals(Substitute("archive", "archive.org", "Группа крови", "Кино"), Substitute.fromHeaders(h))
    assertEquals("SoundCloud", Substitute.fromHeaders(headersOf("X-Substitute", "soundcloud"))?.sourceName)
    assertNull(Substitute.fromHeaders(headersOf("content-type", "audio/mp4")))
  }

  @Test
  fun onlyPlaybackStreamsAreWatched() {
    val base = "https://music.palustris.ru/api/stream"
    assertEquals("dQw4w9WgXcQ", streamTrackId("$base/dQw4w9WgXcQ?a=A&t=T&d=212".toHttpUrl()))
    // Скачивание (компактная копия или `dl=1`) замену не получает.
    assertNull(streamTrackId("$base/dQw4w9WgXcQ?a=A&t=T&q=o64&dl=1".toHttpUrl()))
    assertNull(streamTrackId("$base/dQw4w9WgXcQ?a=A&t=T&dl=1".toHttpUrl()))
    assertNull(streamTrackId("https://music.palustris.ru/api/likes".toHttpUrl()))
    assertNull(streamTrackId("$base/".toHttpUrl()))
  }

  @Test
  fun trackerKeepsOnlyCurrentSubstitutes() {
    val t = SubstituteTracker()
    val sub = Substitute("audius", "Audius", "Song", "Artist")
    t.update("a", sub)
    assertTrue(t.isSubstituted("a"))
    // Тот же трек снова отдаётся из YouTube — пометка снимается.
    t.update("a", null)
    assertFalse(t.isSubstituted("a"))
    assertEquals(emptyMap<String, Substitute>(), t.current.value)
  }
}
