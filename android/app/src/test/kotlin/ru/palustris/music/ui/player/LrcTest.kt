package ru.palustris.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcTest {
  @Test
  fun parsesTimestampsFractionsAndRepeatedTags() {
    val lines =
      parseLrc(
        """
        [ar:Someone]
        [00:12.34] Первая строка
        [01:02.5]Вторая
        [00:05.00][00:30.000] Припев
        """.trimIndent()
      )
    assertEquals(
      listOf(5_000L to "Припев", 12_340L to "Первая строка", 30_000L to "Припев", 62_500L to "Вторая"),
      lines.map { it.timeMs to it.text },
    )
  }
}
