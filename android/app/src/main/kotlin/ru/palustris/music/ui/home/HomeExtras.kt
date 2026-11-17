package ru.palustris.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import ru.palustris.music.core.model.Mix
import ru.palustris.music.core.model.Stats
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.prefs.WaveSettings
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.PlaylistCover
import ru.palustris.music.ui.common.plural

private val MODE_ICONS =
  mapOf("" to Icons.Rounded.AllInclusive, "favorite" to Icons.Rounded.Favorite, "discover" to Icons.Rounded.Explore, "popular" to Icons.AutoMirrored.Rounded.TrendingUp)
private val MOOD_ICONS =
  mapOf("energetic" to Icons.Rounded.Bolt, "happy" to Icons.Rounded.SentimentVerySatisfied, "calm" to Icons.Rounded.Spa, "sad" to Icons.Rounded.WaterDrop)
private val ACTIVITY_ICONS =
  mapOf(
    "wakeup" to Icons.Rounded.WbTwilight,
    "road" to Icons.Rounded.DirectionsCar,
    "work" to Icons.Rounded.Laptop,
    "workout" to Icons.Rounded.FitnessCenter,
    "sleep" to Icons.Rounded.Bedtime,
    "party" to Icons.Rounded.Celebration,
  )

/**
 * Настройки «Моей волны», как в Яндекс Музыке. Применяются сразу: если волна играет, всё впереди
 * пересобирается.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveSettingsSheet(wave: WaveSettings, onChange: (WaveSettings) -> Unit, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Настроить волну", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (!wave.isDefault) TextButton(onClick = { onChange(WaveSettings()) }) { Text("Сбросить") }
      }
      Text(
        "Волна сразу перестроится. Ваш вкус учитывается всегда",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
      )
      SheetGroup("Характер")
      ChipRow(WaveSettings.MODES, MODE_ICONS, wave.mode, allowNone = false) { onChange(wave.copy(mode = it)) }
      SheetGroup("Настроение")
      ChipRow(WaveSettings.MOODS, MOOD_ICONS, wave.mood) { onChange(wave.copy(mood = it)) }
      SheetGroup("Занятие")
      ChipRow(WaveSettings.ACTIVITIES, ACTIVITY_ICONS, wave.activity) { onChange(wave.copy(activity = it)) }
    }
  }
}

@Composable
private fun SheetGroup(title: String) {
  Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

/** Выбор одного варианта; повторное нажатие снимает выбор (кроме характера). */
@Composable
private fun ChipRow(options: Map<String, String>, icons: Map<String, ImageVector>, selected: String, allowNone: Boolean = true, onPick: (String) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    options.forEach { (key, label) ->
      val on = key == selected
      FilterChip(
        selected = on,
        onClick = { onPick(if (on && allowNone) "" else key) },
        label = { Text(label) },
        leadingIcon = { icons[key]?.let { Icon(it, null, Modifier.size(FilterChipDefaults.IconSize)) } },
      )
    }
  }
}

/** «Миксы дня»: карточки с мозаикой обложек и полосой цвета снизу. */
@Composable
fun MixShelf(mixes: List<Mix>, onOpen: (Mix) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    itemsIndexed(mixes, key = { _, m -> m.id }) { i, m ->
      Column(Modifier.width(156.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = { onOpen(m) }, role = Role.Button).padding(4.dp)) {
        MixCover(m, i, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)))
        Text(
          m.sub,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
  }
}

/** Цвет полосы микса: свой у каждого номера, чтобы миксы различались с первого взгляда. */
fun mixColor(index: Int): Color =
  listOf(Color(0xFF7B47E8), Color(0xFFE0612F), Color(0xFF1690A8), Color(0xFFD8336F), Color(0xFF2C9A52), Color(0xFFC08A1C))[index % 6]

@Composable
fun MixCover(mix: Mix, index: Int, modifier: Modifier = Modifier) {
  val color = mixColor(index)
  Box(modifier) {
    PlaylistCover(mix.thumbs, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to color.copy(alpha = 0f), 1f to color.copy(alpha = 0.95f))))
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(color))
    Text(
      mix.title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = Color.White,
      modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp),
    )
  }
}

/** Горизонтальная полка треков: нажатие играет всю полку с этого места. */
@Composable
fun TrackShelf(tracks: List<Track>, onPlay: (Int) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
      Column(Modifier.width(136.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = { onPlay(i) }, role = Role.Button).padding(4.dp)) {
        Artwork(t.thumb, Modifier.fillMaxWidth().aspectRatio(1f), size = 300, shape = RoundedCornerShape(18.dp))
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** Плашка «Неделя в музыке»: минуты, треки и главный исполнитель недели. */
@Composable
fun WeekTeaser(week: Stats, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val top = week.topArtists.firstOrNull()
  Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(52.dp).clip(CircleShape).background(scheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        if (top?.image != null || top?.thumb != null) Artwork(top.image ?: top.thumb, Modifier.fillMaxSize(), size = 160, shape = CircleShape)
        else Icon(Icons.Rounded.Insights, null, tint = scheme.onTertiaryContainer)
      }
      Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
        Text("Неделя в музыке", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          buildString {
            append(plural(week.minutes, "минута", "минуты", "минут"))
            if (top != null) append(" · больше всего ${top.name}")
          },
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(Icons.Rounded.ChevronRight, "Статистика", tint = scheme.onSurfaceVariant)
    }
  }
}

/** «Итоги года» показываются на главной в декабре и январе. */
fun wrappedSeason(): Int? {
  val c = Calendar.getInstance()
  return when (c.get(Calendar.MONTH)) {
    Calendar.DECEMBER -> c.get(Calendar.YEAR)
    Calendar.JANUARY -> c.get(Calendar.YEAR) - 1
    else -> null
  }
}

@Composable
fun WrappedBanner(year: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .fillMaxWidth()
      .height(120.dp)
      .clip(RoundedCornerShape(28.dp))
      .background(Brush.linearGradient(listOf(Color(0xFFFF4D8D), Color(0xFF7B47E8), Color(0xFF1C1B4B))))
      .clickable(onClick = onOpen, role = Role.Button)
      .padding(20.dp)
  ) {
    Column(Modifier.align(Alignment.CenterStart)) {
      Text("Итоги $year", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White)
      Text("Ваш год в музыке: главные треки, исполнители и минуты", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
    }
    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).size(28.dp))
  }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
