package ru.palustris.music.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Столбики с подписями снизу. Высота — доля от максимума; столбик [highlight] ярче. Вырастают
 * при появлении. Пустые значения рисуются тонкой чертой, чтобы было видно «ноль».
 */
@Composable
fun BarChart(
  values: List<Int>,
  labels: List<String>,
  modifier: Modifier = Modifier,
  height: Dp = 140.dp,
  color: Color = MaterialTheme.colorScheme.primary,
  dim: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
  highlight: Int = values.indices.maxByOrNull { values[it] } ?: -1,
  /** Подпись для TalkBack: что за график. */
  description: String = "",
) {
  val grow = remember(values) { Animatable(0f) }
  LaunchedEffect(values) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
  val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
  val zero = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
  Column(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
      if (values.isEmpty()) return@Canvas
      val gap = (size.width / values.size) * 0.25f
      val w = size.width / values.size - gap
      values.forEachIndexed { i, v ->
        val x = i * (w + gap) + gap / 2
        val h = if (v <= 0) 3.dp.toPx() else (size.height * v / max * grow.value).coerceAtLeast(3.dp.toPx())
        val c = if (v <= 0) zero else if (i == highlight) color else dim
        drawRoundRect(c, Offset(x, size.height - h), Size(w, h), CornerRadius(w.coerceAtMost(8.dp.toPx()) / 2))
      }
    }
    if (labels.isNotEmpty()) {
      Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        labels.forEach { l ->
          Text(
            l,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            // Узкие ячейки (24 часа): подпись может выйти за свою ячейку, соседние пустые.
            overflow = TextOverflow.Visible,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

val MONTHS_SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
val MONTHS_FULL = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

private fun cal(unix: Long): Calendar =
  Calendar.getInstance(TimeZone.getDefault(), Locale.forLanguageTag("ru")).apply { timeInMillis = unix * 1000 }

/** Подпись столбика: день недели, число месяца или месяц. */
fun bucketLabel(start: Long, kind: String, total: Int, index: Int): String {
  val c = cal(start)
  return when {
    kind == "month" -> if (total <= 12 || index % 3 == 0) MONTHS_SHORT[c.get(Calendar.MONTH)] else ""
    total <= 7 -> WEEKDAYS[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]
    // Месяц по дням: подпись у каждого пятого, иначе не помещается.
    index % 5 == 0 || index == total - 1 -> c.get(Calendar.DAY_OF_MONTH).toString()
    else -> ""
  }
}

/** «17 ноября». */
fun dayTitle(unix: Long): String {
  val c = cal(unix)
  return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_GEN[c.get(Calendar.MONTH)]}"
}

/** Кто вы по времени прослушивания: по часам суток с самым большим числом минут. */
data class Persona(val title: String, val text: String)

fun persona(hours: List<Int>): Persona? {
  if (hours.size < 24 || hours.sum() == 0) return null
  val windows =
    listOf(
      Persona("Ночная сова", "Больше всего музыки — после полуночи") to (0..4),
      Persona("Жаворонок", "Больше всего музыки — по утрам") to (5..10),
      Persona("Дневной слушатель", "Больше всего музыки — днём") to (11..16),
      Persona("Вечерний меломан", "Больше всего музыки — вечером") to (17..23),
    )
  return windows.maxBy { (_, r) -> r.sumOf { hours[it] } }.first
}

/** «1 234» с неразрывным пробелом. */
fun groupDigits(n: Int): String = "%,d".format(Locale.forLanguageTag("ru"), n).replace(',', '\u00a0').replace(' ', '\u00a0')

/** «20 ч 34 мин» для больших значений, иначе «57 мин». */
fun minutesLong(min: Int): String = if (min < 120) "$min мин" else "${min / 60} ч ${min % 60} мин"
