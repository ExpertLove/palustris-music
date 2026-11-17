package ru.palustris.music.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import ru.palustris.music.core.model.ArtistStat
import ru.palustris.music.core.model.Stats
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.OfflineFiles
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalNavigator
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.SectionTitle
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.plural
import ru.palustris.music.ui.home.tzMinutes

enum class Period(val key: String, val title: String, val prevText: String) {
  Week("week", "Неделя", "к прошлой неделе"),
  Month("month", "Месяц", "к прошлому месяцу"),
  Year("year", "Год", "к прошлому году"),
  All("all", "Всё время", ""),
}

data class StatsUi(val period: Period = Period.Week, val data: Map<Period, Stats> = emptyMap(), val error: String? = null, val loading: Boolean = true)

@HiltViewModel
class StatsViewModel @Inject constructor(private val api: MusicApi, private val files: OfflineFiles) : ViewModel() {
  private val _ui = MutableStateFlow(StatsUi())
  val ui: StateFlow<StatsUi> = _ui.asStateFlow()

  init {
    viewModelScope.launch {
      // Последняя копия — чтобы экран открывался мгновенно и без сети.
      files.read(FILE, MapSerializer(String.serializer(), Stats.serializer()))?.let { cached ->
        val map = cached.mapNotNull { (k, v) -> Period.entries.firstOrNull { it.key == k }?.let { it to v } }.toMap()
        _ui.update { it.copy(data = map + it.data, loading = map.isEmpty()) }
      }
    }
    load(Period.Week)
  }

  fun select(p: Period) {
    _ui.update { it.copy(period = p) }
    load(p)
  }

  private fun load(p: Period) {
    viewModelScope.launch {
      _ui.update { it.copy(loading = it.data[p] == null, error = null) }
      apiCall { api.stats(p.key, tzMinutes()) }
        .onSuccess { s ->
          _ui.update { it.copy(data = it.data + (p to s), loading = false) }
          files.write(FILE, MapSerializer(String.serializer(), Stats.serializer()), _ui.value.data.mapKeys { it.key.key })
        }
        .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
    }
  }

  private companion object {
    const val FILE = "stats.json"
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, vm: StatsViewModel = hiltViewModel()) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val s = ui.data[ui.period]
  val nav = LocalNavigator.current
  val actions = LocalTrackActions.current
  val year = remember { Calendar.getInstance().get(Calendar.YEAR) }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    item { BackBar(onBack) }
    item {
      Text("Статистика", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Period.entries.forEachIndexed { i, p ->
          SegmentedButton(
            selected = ui.period == p,
            onClick = { vm.select(p) },
            shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
            label = { Text(p.title, maxLines = 1) },
          )
        }
      }
    }
    when {
      s == null && ui.loading -> item { LoadingState() }
      s == null -> item { MessageState("Не получилось загрузить", ui.error) }
      s.plays == 0 ->
        item {
          MessageState(
            "Пока пусто",
            "Слушайте музыку, и здесь появятся любимые исполнители, треки и минуты",
            icon = Icons.Rounded.Insights,
          )
        }
      else -> {
        item(key = "hero") { Hero(s, ui.period) }
        item(key = "chart") {
          StatCard(if (s.bucketKind == "month") "По месяцам" else "По дням") {
            BarChart(
              s.buckets.map { it.minutes },
              s.buckets.mapIndexed { i, b -> bucketLabel(b.start, s.bucketKind, s.buckets.size, i) },
              description = "Минуты музыки по ${if (s.bucketKind == "month") "месяцам" else "дням"}",
            )
          }
        }
        s.topArtists.firstOrNull()?.let { top ->
          item(key = "top-artist") { TopArtistCard(top, onOpen = { nav.openArtist(top.artistId, top.name) }) }
        }
        if (s.topArtists.size > 1) {
          item(key = "artists-title") { SectionTitle("Исполнители") }
          itemsIndexed(s.topArtists.drop(1), key = { _, a -> "a:" + a.name }) { i, a ->
            ArtistLine(i + 2, a, s.topArtists.first().minutes, onOpen = { nav.openArtist(a.artistId, a.name) })
          }
        }
        if (s.topTracks.isNotEmpty()) {
          item(key = "tracks-title") { SectionTitle("Треки") }
          val tracks = s.topTracks.map { it.toTrack() }
          itemsIndexed(s.topTracks, key = { _, t -> "t:" + t.id }) { i, t ->
            TrackRow(
              t.toTrack(),
              onClick = { actions.play(tracks, i) },
              subtitle = "${t.artist} · ${plural(t.plays, "раз", "раза", "раз")}",
              modifier = Modifier.padding(horizontal = 8.dp),
              leading = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
          }
        }
        persona(s.hours)?.let { p ->
          item(key = "hours") {
            StatCard(p.title, p.text) {
              BarChart(
                s.hours,
                (0 until 24).map { if (it % 6 == 0) "$it" else "" },
                height = 100.dp,
                description = "Минуты музыки по часам суток",
              )
            }
          }
        }
        item(key = "week") {
          StatCard("По дням недели") { BarChart(s.weekdays, WEEKDAYS, height = 90.dp, description = "Минуты музыки по дням недели") }
        }
        item(key = "streak") { StreakRow(s) }
      }
    }
    item(key = "wrapped") {
      Surface(
        onClick = nav::openWrapped,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Row(
          Modifier.background(Brush.linearGradient(listOf(Color(0xFFFF4D8D), Color(0xFF7B47E8)))).padding(18.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
          Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text("Итоги $year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Ваш год в музыке, можно поделиться", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
          }
          Icon(Icons.Rounded.ChevronRight, null, tint = Color.White)
        }
      }
    }
  }
}

@Composable
private fun Hero(s: Stats, period: Period) {
  val scheme = MaterialTheme.colorScheme
  val count = remember(s.minutes, period) { Animatable(0f) }
  LaunchedEffect(s.minutes, period) { count.animateTo(s.minutes.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
  Column(
    Modifier.fillMaxWidth()
      .padding(horizontal = 16.dp)
      .clip(RoundedCornerShape(28.dp))
      .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
      .padding(20.dp)
  ) {
    Text(groupDigits(count.value.roundToInt()), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, color = scheme.onPrimaryContainer)
    Text(
      plural(s.minutes, "минута", "минуты", "минут").substringAfter(' ') + " музыки",
      style = MaterialTheme.typography.titleMedium,
      color = scheme.onPrimaryContainer,
    )
    val prev = s.prevMinutes
    if (prev != null && prev > 0 && period.prevText.isNotEmpty()) {
      val pct = ((s.minutes - prev) * 100f / prev).roundToInt()
      Text(
        (if (pct >= 0) "+$pct %" else "−${-pct} %") + " " + period.prevText,
        style = MaterialTheme.typography.labelLarge,
        color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 4.dp),
      )
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Mini(plural(s.plays, "прослушивание", "прослушивания", "прослушиваний"))
      Mini(plural(s.tracks, "трек", "трека", "треков"))
      Mini(plural(s.artists, "исполнитель", "исполнителя", "исполнителей"))
    }
    if (s.newArtists > 0) {
      Text(
        "Из них новых для вас: ${s.newArtists}",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

@Composable
private fun Mini(text: String) {
  val n = text.substringBefore(' ')
  Column {
    Text(n, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    Text(text.substringAfter(' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
  }
}

@Composable
private fun StatCard(title: String, sub: String? = null, content: @Composable () -> Unit) {
  Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
    Column(Modifier.padding(18.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(14.dp))
      content()
    }
  }
}

/** Главный исполнитель периода: фото на всю карточку. */
@Composable
private fun TopArtistCard(a: ArtistStat, onOpen: () -> Unit) {
  Box(
    Modifier.fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp)
      .height(200.dp)
      .clip(RoundedCornerShape(28.dp))
      .clickable(onClick = onOpen, role = Role.Button)
  ) {
    Artwork(a.image ?: a.thumb, Modifier.fillMaxSize(), size = 600, shape = RoundedCornerShape(0.dp))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
      Text("Чаще всего", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
      Text(a.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${minutesLong(a.minutes)} · ${plural(a.plays, "прослушивание", "прослушивания", "прослушиваний")}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
    }
  }
}

@Composable
private fun ArtistLine(rank: Int, a: ArtistStat, maxMinutes: Int, onOpen: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onOpen, role = Role.Button).padding(horizontal = 20.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("$rank", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
    Artwork(a.image ?: a.thumb, Modifier.size(48.dp), size = 160, shape = CircleShape)
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Box(
        Modifier.padding(top = 6.dp)
          .fillMaxWidth((a.minutes.toFloat() / maxMinutes.coerceAtLeast(1)).coerceIn(0.05f, 1f))
          .height(6.dp)
          .clip(CircleShape)
          .background(scheme.primary.copy(alpha = 0.6f))
      )
    }
    Text(minutesLong(a.minutes), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
  }
}

@Composable
private fun StreakRow(s: Stats) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    StreakTile("Сейчас подряд", plural(s.streak, "день", "дня", "дней"), Modifier.weight(1f))
    StreakTile("Рекорд", plural(s.bestStreak, "день", "дня", "дней"), Modifier.weight(1f))
  }
}

@Composable
private fun StreakTile(title: String, value: String, modifier: Modifier) {
  Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color(0xFFFF7A2F))
      Column(Modifier.padding(start = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
