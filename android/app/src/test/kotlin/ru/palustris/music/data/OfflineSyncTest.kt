package ru.palustris.music.data

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.palustris.music.core.AppModule
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.Track

/** Офлайн-правки: схлопывание очереди и наложение неотправленного на ответ сервера. */
class OfflineSyncTest {
  private fun t(id: String, thumb: String? = "https://i/$id.jpg") = Track(id, "title $id", "artist", thumb = thumb)

  @Test
  fun likeThenUnlikeCancelEachOther() {
    val list = mutableListOf<PendingOp>()
    Outbox.compact(list, PendingOp.Like(t("a")))
    Outbox.compact(list, PendingOp.Unlike("a"))
    assertTrue(list.isEmpty())
  }

  @Test
  fun opInFlightIsNotCompacted() {
    val like = PendingOp.Like(t("a"))
    val list = mutableListOf<PendingOp>(like)
    // Лайк уже летит на сервер: снятие лайка должно уйти следом, а не отменить его.
    Outbox.compact(list, PendingOp.Unlike("a"), inFlight = like)
    assertEquals(listOf(like, PendingOp.Unlike("a")), list)
  }

  @Test
  fun onlyLastOrderAndNameAreKept() {
    val list = mutableListOf<PendingOp>()
    Outbox.compact(list, PendingOp.PlaylistOrder(1, listOf("a", "b")))
    Outbox.compact(list, PendingOp.PlaylistRename(1, "Старое"))
    Outbox.compact(list, PendingOp.PlaylistOrder(2, listOf("x")))
    Outbox.compact(list, PendingOp.PlaylistOrder(1, listOf("b", "a")))
    Outbox.compact(list, PendingOp.PlaylistRename(1, "Новое"))
    assertEquals(
      listOf(PendingOp.PlaylistOrder(2, listOf("x")), PendingOp.PlaylistOrder(1, listOf("b", "a")), PendingOp.PlaylistRename(1, "Новое")),
      list,
    )
  }

  @Test
  fun removeAfterAddDropsTheAddButStillRemoves() {
    val list = mutableListOf<PendingOp>()
    Outbox.compact(list, PendingOp.PlaylistAdd(1, t("a")))
    Outbox.compact(list, PendingOp.PlaylistRemove(1, "a"))
    assertEquals(listOf<PendingOp>(PendingOp.PlaylistRemove(1, "a")), list)
  }

  @Test
  fun queueSurvivesJsonRoundTrip() {
    val ops =
      listOf(
        PendingOp.Like(t("a")),
        PendingOp.Unlike("b"),
        PendingOp.Listened(t("c")),
        PendingOp.PlaylistAdd(3, t("d")),
        PendingOp.PlaylistRemove(3, "e"),
        PendingOp.PlaylistOrder(3, listOf("d", "f")),
        PendingOp.PlaylistRename(3, "Дорога"),
      )
    val s = ListSerializer(PendingOp.serializer())
    assertEquals(ops, AppModule.json.decodeFromString(s, AppModule.json.encodeToString(s, ops)))
  }

  @Test
  fun pendingLikesAreLaidOverServerList() {
    val server = listOf(t("a"), t("b"))
    val merged = LibraryRepository.applyPending(server, listOf(PendingOp.Like(t("c")), PendingOp.Unlike("a"), PendingOp.Listened(t("z"))))
    assertEquals(listOf("c", "b"), merged.map { it.id })
  }

  @Test
  fun pendingPlaylistEditsAreLaidOverServerCopy() {
    val server = PlaylistFull(1, "Имя", null, listOf(t("a"), t("b"), t("c")))
    val pending =
      listOf(
        PendingOp.PlaylistAdd(1, t("a")), // повторное добавление переносит в конец, как на сервере
        PendingOp.PlaylistRemove(1, "b"),
        PendingOp.PlaylistAdd(2, t("x")), // чужой плейлист не трогаем
        PendingOp.PlaylistRename(1, "Новое"),
      )
    val p = PlaylistsRepository.applyPending(server, pending)
    assertEquals("Новое", p.name)
    assertEquals(listOf("c", "a"), p.items.map { it.id })
  }

  @Test
  fun reorderKeepsUnknownTracksAtTheEnd() {
    val items = listOf(t("a"), t("b"), t("c"), t("d"))
    assertEquals(listOf("c", "a", "b", "d"), PlaylistsRepository.reorderBy(items, listOf("c", "a", "b")).map { it.id })
  }

  @Test
  fun rowCountsAndCoversFollowContent() {
    val p = PlaylistFull(1, "Имя", "slug", listOf(t("a"), t("b", thumb = null), t("c", thumb = "https://i/a.jpg"), t("d"), t("e"), t("f")))
    val row = PlaylistsRepository.rowFrom(PlaylistRow(1, "Старое"), p)
    assertEquals("Имя", row.name)
    assertEquals(6, row.count)
    assertEquals("slug", row.share)
    // Без пустых и без повторов, не больше четырёх: как playlist_thumbs на сервере.
    assertEquals(listOf("https://i/a.jpg", "https://i/d.jpg", "https://i/e.jpg", "https://i/f.jpg"), row.thumbs)
  }
}
