package ru.palustris.music.ui.moods

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nightlife
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Подборка по настроению. Треки берутся из поиска по [query] (как на сайте, `web/src/lib/data.ts`)
 * и ещё двум запросам [more],
 * а цвета карточки — градиент [from] → [to].
 */
@Immutable
data class Mood(
  val id: String,
  val title: String,
  val sub: String,
  val icon: ImageVector,
  val query: String,
  val from: Color,
  val to: Color,
  /** Ещё запросы: подборка собирается из трёх поисков вперемешку, так она длиннее и разнообразнее. */
  val more: List<String> = emptyList(),
) {
  val queries: List<String>
    get() = listOf(query) + more
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

val MOODS: List<Mood> =
  listOf(
    Mood("night", "Ночная", "после полуночи", Icons.Rounded.Bedtime, "night drive music", c(0x3b3a9e), c(0x101326), listOf("late night vibes songs", "midnight chill")),
    Mood("morning", "Утренняя", "мягкий старт дня", Icons.Rounded.WbTwilight, "morning music playlist", c(0xe0812f), c(0x2a1d14), listOf("good morning songs", "morning acoustic")),
    Mood("energy", "Энергия", "заряд на весь день", Icons.Rounded.Bolt, "workout energy music", c(0xc3299f), c(0x24113a), listOf("gym motivation songs", "high energy hits")),
    Mood("calm", "Спокойствие", "тишина внутри", Icons.Rounded.Waves, "calm relaxing music", c(0x1690a8), c(0x0b2532), listOf("chill acoustic songs", "relaxing piano")),
    Mood("sad", "Грусть", "побыть одному", Icons.Rounded.WaterDrop, "sad songs", c(0x55607a), c(0x15171f), listOf("heartbreak songs", "sad piano songs")),
    Mood("love", "Любовь", "о главном", Icons.Rounded.Favorite, "love songs", c(0xd8336f), c(0x2b0f2a), listOf("romantic love hits", "love ballads")),
    Mood("party", "Вечеринка", "громко и вместе", Icons.Rounded.Celebration, "party hits", c(0xe8562b), c(0x3a1450), listOf("dance party songs", "club hits")),
    Mood("focus", "Фокус", "работа без отвлечений", Icons.Rounded.CenterFocusStrong, "focus study music", c(0x139184), c(0x0d1d24), listOf("lofi study beats", "deep focus instrumental")),
    Mood("retro", "Ретро", "хиты прошлых лет", Icons.Rounded.Radio, "80s hits", c(0xc08a1c), c(0x2b2318), listOf("90s hits", "70s classics")),
    Mood("russian", "Русское", "родные хиты", Icons.Rounded.MusicNote, "русские хиты", c(0x2f62e6), c(0x151a3d), listOf("русская поп музыка хиты", "русский рок хиты")),
    Mood("rap", "Рэп", "биты и панчи", Icons.Rounded.Mic, "rap hits", c(0x6b7487), c(0x14161c), listOf("hip hop hits", "русский рэп хиты")),
    Mood("rock", "Рок", "гитары на максимум", Icons.Rounded.Speaker, "rock hits", c(0xd0322f), c(0x22140f), listOf("classic rock songs", "alternative rock hits")),
    Mood("electronic", "Электроника", "синтез и ритм", Icons.Rounded.GraphicEq, "electronic dance music", c(0x7b47e8), c(0x120d29), listOf("edm hits", "house music hits")),
    Mood("indie", "Инди", "не как у всех", Icons.Rounded.Headphones, "indie music", c(0x2c9a52), c(0x0b2214), listOf("indie pop songs", "indie rock hits")),
    Mood("jazz", "Джаз", "вечерний лаунж", Icons.Rounded.Nightlife, "jazz music", c(0xa8561a), c(0x21170f), listOf("smooth jazz", "jazz classics")),
    Mood("classical", "Классика", "вне времени", Icons.Rounded.Piano, "classical music", c(0x8c3fd6), c(0x1b1438), listOf("piano classical masterpieces", "orchestra classical music")),
    Mood("summer", "Летняя", "солнце и море", Icons.Rounded.WbSunny, "summer hits", c(0xf07a26), c(0x0f5d70), listOf("summer vibes songs", "beach party songs")),
    Mood("winter", "Зимняя", "снег за окном", Icons.Rounded.AcUnit, "winter playlist", c(0x2aa2e0), c(0x111d33), listOf("cozy winter songs", "christmas songs")),
    Mood("road", "Дорога", "для долгой поездки", Icons.Rounded.DirectionsCar, "road trip music", c(0x5150d8), c(0x121633), listOf("driving songs", "highway rock songs")),
    Mood("romance", "Романтика", "для двоих", Icons.Rounded.LocalFlorist, "romantic songs", c(0xb8285f), c(0x21122e), listOf("romantic ballads", "slow dance songs")),
  )

fun moodById(id: String): Mood? = MOODS.firstOrNull { it.id == id }

/** Настроения по времени суток: утром — «Утренняя», ночью — «Ночная». Первыми на главной. */
fun moodsForHour(hour: Int): List<Mood> {
  val first =
    when (hour) {
      in 5..10 -> listOf("morning", "energy", "focus")
      in 11..16 -> listOf("focus", "energy", "summer")
      in 17..21 -> listOf("party", "calm", "road")
      else -> listOf("night", "calm", "sad")
    }
  return first.mapNotNull(::moodById) + MOODS.filter { it.id !in first }
}
