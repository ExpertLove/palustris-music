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
import ru.palustris.music.core.prefs.Settings
import ru.palustris.music.media.contiguousRanges
import ru.palustris.music.ui.auth.isFormError
import ru.palustris.music.ui.common.formatLength
import ru.palustris.music.ui.player.queueEntries

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

  @Test
  fun queueKeysSurviveMovesAndRepeats() {
    fun t(id: String) = Track(id, id, "")
    val queue = listOf(t("a"), t("b"), t("a"), t("c"))
    val before = queueEntries(queue, upNext = listOf(1, 2, 3))
    assertEquals(listOf("q:b#1", "q:a#2", "q:c#1"), before.map { it.key })
    assertEquals(listOf(1, 2, 3), before.map { it.index })
    // «c» переставили выше «b»: у всех тот же ключ, меняются только места.
    val after = queueEntries(listOf(t("a"), t("c"), t("b"), t("a")), upNext = listOf(1, 2, 3))
    assertEquals(listOf("q:c#1", "q:b#1", "q:a#2"), after.map { it.key })
    // Порядок перемешивания: индексы идут как сыграют, лишние отбрасываются.
    assertEquals(listOf(3, 0), queueEntries(queue, upNext = listOf(3, 0, 9)).map { it.index })
  }

  @Test
  fun rangesForClearingUpNext() {
    assertEquals(listOf(1..3, 7..8), contiguousRanges(listOf(8, 2, 1, 7, 3)))
    assertEquals(listOf(5..5), contiguousRanges(listOf(5)))
    assertEquals(emptyList<IntRange>(), contiguousRanges(emptyList()))
  }

  @Test
  fun speedLabelsLikeOnTheSite() {
    assertEquals(listOf("0,75×", "1×", "1,25×", "1,5×"), Settings.SPEED_OPTIONS_PERCENT.map(Settings::speedLabel))
    assertEquals(1.25f, Settings(speedPercent = 125).speed)
  }

  @Test
  fun lengthOfList() {
    assertEquals("37 мин", formatLength(37 * 60L))
    assertEquals("2 ч 5 мин", formatLength(125 * 60L))
    assertEquals("3 ч", formatLength(3 * 3600L))
    assertEquals("0 мин", formatLength(10))
  }

  @Test
  fun registrationErrorsAboutTheFormGoBackToFirstStep() {
    assertTrue(isFormError("Этот email уже зарегистрирован"))
    assertTrue(isFormError("Проверь email"))
    assertTrue(isFormError("Пароль — минимум 6 символов"))
    assertTrue(isFormError("Нужно согласие на обработку данных"))
    assertEquals(false, isFormError("Нет связи с сервером. Проверьте интернет"))
    assertEquals(false, isFormError("Слишком много попыток, подождите немного"))
    assertEquals(false, isFormError(null))
  }
}
