package ru.palustris.music

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.palustris.music.media.PlaybackService

/**
 * Каталог для Android Auto: подключаемся к сервису как машина (MediaBrowser) и ходим по дереву.
 * Нужен вошедший аккаунт на устройстве. Запуск без удаления приложения:
 * `adb shell am instrument -w -e class ru.palustris.music.LibraryBrowseTest ru.palustris.music.debug.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class LibraryBrowseTest {
  @Test
  fun browseTreeAndPlayFromTheMiddleOfAList() = runBlocking {
    withContext(Dispatchers.Main) {
      val ctx = InstrumentationRegistry.getInstrumentation().targetContext
      val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
      val browser = MediaBrowser.Builder(ctx, token).buildAsync().await()
      try {
        val root = browser.getLibraryRoot(null).await()
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        val top = browser.getChildren(root.value!!.mediaId, 0, 100, null).await().value!!.map { it.mediaId }
        assertEquals(listOf("wave", "likes", "playlists", "downloads", "recent", "mixes", "moods"), top)

        val moods = browser.getChildren("moods", 0, 100, null).await().value!!
        assertEquals(20, moods.size)

        // Миксы дня: у тестового аккаунта есть история, значит, есть и миксы.
        val mixes = browser.getChildren("mixes", 0, 100, null).await().value!!
        assertTrue("миксы дня собрались", mixes.isNotEmpty())
        val mixTracks = browser.getChildren(mixes.first().mediaId, 0, 100, null).await().value!!
        assertTrue(mixTracks.size >= 10)

        val playlists = browser.getChildren("playlists", 0, 100, null).await().value!!
        assertTrue("у тестового аккаунта есть плейлисты", playlists.isNotEmpty())
        val big = playlists.first()
        val tracks = browser.getChildren(big.mediaId, 0, 500, null).await().value!!
        assertTrue(tracks.size >= 2)
        assertTrue(tracks.all { it.mediaMetadata.isPlayable == true && it.mediaId.startsWith("item|") })
        // Обложки идут через content://, иначе машина их не покажет.
        assertTrue(tracks.first().mediaMetadata.artworkUri.toString().startsWith("content://"))

        // Постраничная выдача, как просит Android Auto.
        val page2 = browser.getChildren(big.mediaId, 1, 1, null).await().value!!
        assertEquals(tracks[1].mediaId, page2.single().mediaId)

        // Нажали второй трек: играет весь плейлист со второго трека.
        browser.setMediaItem(tracks[1])
        browser.prepare()
        browser.play()
        withTimeout(10_000) { while (browser.mediaItemCount < tracks.size) delay(100) }
        assertEquals(tracks.size, browser.mediaItemCount)
        assertEquals(1, browser.currentMediaItemIndex)
        // В очереди обычные id треков, как из приложения: работают загрузки и громкость.
        assertEquals(tracks[1].mediaId.substringAfterLast('|'), browser.currentMediaItem!!.mediaId)
        browser.pause()

        // Поиск из машины.
        assertEquals(LibraryResult.RESULT_SUCCESS, browser.search("shakira", null).await().resultCode)
        val found = browser.getSearchResult("shakira", 0, 50, null).await().value!!
        assertTrue(found.isNotEmpty())

        // «Моя волна» — сразу играет и сама продолжается.
        val wave = browser.getItem("wave").await().value!!
        assertEquals(true, wave.mediaMetadata.isPlayable)
        browser.setMediaItem(wave)
        browser.prepare()
        withTimeout(15_000) { while (browser.mediaItemCount < 2) delay(100) }
        assertTrue(browser.playbackState != Player.STATE_IDLE || browser.mediaItemCount > 1)
        browser.pause()
      } finally {
        browser.release()
      }
    }
  }
}
