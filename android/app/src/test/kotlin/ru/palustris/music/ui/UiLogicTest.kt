package ru.palustris.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.LibraryTree
import ru.palustris.music.ui.account.initials
import ru.palustris.music.ui.chart.REGIONS
import ru.palustris.music.ui.chart.resolveRegion
import ru.palustris.music.ui.moods.MOODS
import ru.palustris.music.ui.moods.MoodViewModel
import ru.palustris.music.ui.moods.moodsForHour

class UiLogicTest {
  @Test
  fun initialsFromNameOrEmail() {
    assertEquals("АП", initials("Анна Петрова"))
    assertEquals("А", initials("Анна"))
    assertEquals("A", initials("anna.petrova@mail.ru"))
    assertEquals("?", initials("   "))
  }

  @Test
  fun moodsInterleaveWithoutRepeats() {
    fun t(id: String) = Track(id, id, "")
    val merged = MoodViewModel.interleave(listOf(listOf(t("a"), t("b"), t("c")), listOf(t("x"), t("a")), listOf(t("y"))))
    assertEquals(listOf("a", "x", "y", "b", "c"), merged.map { it.id })
  }

  @Test
  fun everyHourHasAllMoodsOnce() {
    for (h in 0..23) {
      val list = moodsForHour(h)
      assertEquals(MOODS.size, list.size)
      assertEquals(MOODS.map { it.id }.toSet(), list.map { it.id }.toSet())
    }
    assertEquals("night", moodsForHour(2).first().id)
    assertEquals("morning", moodsForHour(8).first().id)
  }

  @Test
  fun moodsHaveThreeQueriesAndUniqueIds() {
    assertEquals(MOODS.size, MOODS.map { it.id }.toSet().size)
    assertTrue(MOODS.all { it.queries.size == 3 && it.queries.toSet().size == 3 })
  }

  @Test
  fun autoListItemIdsRoundTrip() {
    assertEquals("pl:5" to "abc_-1", LibraryTree.parseItem("item|pl:5|abc_-1"))
    assertEquals("mood:night" to "x", LibraryTree.parseItem("item|mood:night|x"))
    // Обычный id трека, даже начинающийся с «item», — не элемент каталога.
    assertEquals(null, LibraryTree.parseItem("itemAbc1234"))
    assertEquals(null, LibraryTree.parseItem("item||x"))
  }

  @Test
  fun savedRegionWinsAndUnknownFallsBack() {
    assertEquals("DE", resolveRegion("DE"))
    assertEquals("", resolveRegion(""))
    // Неизвестный код: страна телефона, если она есть в списке, иначе Россия.
    assertTrue(REGIONS.any { it.code == resolveRegion("ZZ") })
  }
}
