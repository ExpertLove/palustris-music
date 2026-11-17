package ru.palustris.music.data

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.palustris.music.core.AppModule
import ru.palustris.music.core.model.BlockedArtist
import ru.palustris.music.core.model.Dislikes
import ru.palustris.music.core.model.Track

/** «Не нравится», скрытые исполнители и их ключи (должны совпадать с серверными, `recs::artist_key`). */
class TasteTest {
  private fun t(id: String, artist: String = "A") = Track(id, "title $id", artist)

  @Test
  fun artistKeysMatchAcrossScripts() {
    // Те же пары, что в серверном тесте artist_keys_match_across_scripts.
    assertEquals(artistKey("Шакира"), artistKey("Shakira"))
    assertEquals(artistKey("Адам Порт"), artistKey("Adam Port"))
    assertEquals(artistKey("Фейд"), artistKey("Feid"))
    assertEquals(artistKey("Тейлор Свифт"), artistKey("Taylor Swift"))
    assertNotEquals(artistKey("Кино"), artistKey("ДДТ"))
    assertEquals("kino", translit("Кино"))
  }

  @Test
  fun featuredArtistsAreSplit() {
    assertEquals(listOf(artistKey("Marshmello"), artistKey("Halsey")), artistKeys("Marshmello & Halsey"))
    assertEquals(2, artistKeys("The Kid LAROI и Justin Bieber").size)
    assertEquals(3, artistKeys("Shakira, Burna Boy feat. Ed Sheeran").size)
  }

  @Test
  fun pendingDislikesAndBlocksOverServerAnswer() {
    val server = Dislikes(tracks = listOf(t("a")), artists = listOf(BlockedArtist("Eagles")))
    val pending =
      listOf(
        PendingOp.Dislike(t("b")),
        PendingOp.Undislike("a"),
        PendingOp.UnblockArtist("Eagles"),
        PendingOp.BlockArtist("Шакира"),
        PendingOp.Like(t("z")),
      )
    val merged = TasteRepository.applyPending(server, pending)
    assertEquals(listOf("b"), merged.tracks.map { it.id })
    assertEquals(listOf("Шакира"), merged.artists.map { it.name })
  }

  @Test
  fun dislikeAndUndoCancelInOutbox() {
    val list = mutableListOf<PendingOp>()
    Outbox.compact(list, PendingOp.Dislike(t("a")))
    Outbox.compact(list, PendingOp.Undislike("a"))
    Outbox.compact(list, PendingOp.BlockArtist("Eagles"))
    Outbox.compact(list, PendingOp.UnblockArtist("Eagles"))
    assertTrue(list.isEmpty())
  }

  @Test
  fun newOpsSurviveJsonRoundTrip() {
    val ops =
      listOf(
        PendingOp.Listened(t("a"), at = 1_794_000_000, secs = 187),
        PendingOp.Dislike(t("b")),
        PendingOp.Undislike("b"),
        PendingOp.BlockArtist("Кино"),
        PendingOp.UnblockArtist("Кино"),
      )
    val s = ListSerializer(PendingOp.serializer())
    assertEquals(ops, AppModule.json.decodeFromString(s, AppModule.json.encodeToString(s, ops)))
    // Старые записи очереди (без времени и секунд) читаются.
    val old = AppModule.json.decodeFromString(s, """[{"type":"history","track":{"id":"x","title":"t","artist":"a"}}]""")
    assertEquals(PendingOp.Listened(Track("x", "t", "a")), old.single())
  }

  @Test
  fun listenBodyCarriesTimeAndSeconds() {
    val body = PendingOp.Listened(t("a"), at = 100, secs = 42).toBody()
    assertEquals(100L, body.at)
    assertEquals(42L, body.secs)
    assertEquals("a", body.id)
  }
}
