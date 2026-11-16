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

fun formatMb(mb: Int): String = if (mb >= 1024) "${mb / 1024} ГБ" else "$mb МБ"

/** 1 трек, 2 трека, 5 треков. */
fun pluralTracks(n: Int): String {
  val mod10 = n % 10
  val mod100 = n % 100
  val word =
    when {
      mod10 == 1 && mod100 != 11 -> "трек"
      mod10 in 2..4 && mod100 !in 12..14 -> "трека"
      else -> "треков"
    }
  return "$n $word"
}
