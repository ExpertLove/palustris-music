package ru.palustris.music.ui.common

import java.util.Locale

fun formatTime(ms: Long): String {
  val total = (ms.coerceAtLeast(0) / 1000).toInt()
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String {
  val mb = bytes / (1024.0 * 1024.0)
  return when {
    mb >= 1024 -> String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", mb / 1024)
    mb >= 10 -> "${mb.toInt()} МБ"
    mb >= 0.1 -> String.format(Locale.forLanguageTag("ru"), "%.1f МБ", mb)
    else -> "0 МБ"
  }
}

/** Длина списка: «37 мин», «2 ч 5 мин», «3 ч». */
fun formatLength(seconds: Long): String {
  val min = (seconds.coerceAtLeast(0) + 30) / 60
  val h = min / 60
  val m = min % 60
  return when {
    h == 0L -> "$m мин"
    m == 0L -> "$h ч"
    else -> "$h ч $m мин"
  }
}

fun formatMb(mb: Int): String = if (mb >= 1024) "${mb / 1024} ГБ" else "$mb МБ"

/** Русское склонение после числа: 1 трек, 2 трека, 5 треков. */
fun plural(n: Int, one: String, few: String, many: String): String {
  val mod10 = n % 10
  val mod100 = n % 100
  val word =
    when {
      mod10 == 1 && mod100 != 11 -> one
      mod10 in 2..4 && mod100 !in 12..14 -> few
      else -> many
    }
  return "$n $word"
}

fun pluralTracks(n: Int): String = plural(n, "трек", "трека", "треков")
