package ru.palustris.music.ui.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Festival
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nightlife
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Жанры профиля: те же id, что на сайте и на сервере (`GENRES` в main.rs). */
data class Genre(val id: String, val title: String, val icon: ImageVector)

val GENRES =
  listOf(
    Genre("pop", "Поп", Icons.Rounded.AutoAwesome),
    Genre("rock", "Рок", Icons.Rounded.Speaker),
    Genre("rap", "Рэп", Icons.Rounded.Mic),
    Genre("electronic", "Электроника", Icons.Rounded.GraphicEq),
    Genre("indie", "Инди", Icons.Rounded.Headphones),
    Genre("jazz", "Джаз", Icons.Rounded.Nightlife),
    Genre("classical", "Классика", Icons.Rounded.Piano),
    Genre("metal", "Метал", Icons.Rounded.LocalFireDepartment),
    Genre("punk", "Панк", Icons.Rounded.Bolt),
    Genre("rnb", "R&B и соул", Icons.Rounded.Album),
    Genre("reggae", "Регги", Icons.Rounded.BeachAccess),
    Genre("folk", "Фолк", Icons.Rounded.Festival),
    Genre("russian", "Русское", Icons.Rounded.MusicNote),
    Genre("kpop", "K-pop", Icons.Rounded.Star),
    Genre("soundtrack", "Саундтреки", Icons.Rounded.Movie),
  )

const val MAX_GENRES = 8

/** Жанры чипами, не больше [MAX_GENRES]. Одни и те же в профиле и при регистрации. */
@Composable
fun GenreChips(selected: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    GENRES.forEach { g ->
      val on = g.id in selected
      FilterChip(
        selected = on,
        onClick = { onChange(if (on) selected - g.id else if (selected.size >= MAX_GENRES) selected else selected + g.id) },
        label = { Text(g.title) },
        leadingIcon = { Icon(g.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
      )
    }
  }
}

/** Две буквы для аватара: «Анна Петрова» → «АП», «anna@mail.ru» → «A». */
fun initials(nameOrEmail: String): String {
  val words = nameOrEmail.substringBefore('@').split(' ', '.', '_', '-').filter { it.isNotBlank() }
  return when {
    words.isEmpty() -> "?"
    nameOrEmail.contains('@') || words.size == 1 -> words.first().take(1).uppercase()
    else -> (words[0].take(1) + words[1].take(1)).uppercase()
  }
}
