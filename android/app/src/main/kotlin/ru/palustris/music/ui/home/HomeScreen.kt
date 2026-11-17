package ru.palustris.music.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.widthIn
import ru.palustris.music.core.prefs.WaveSettings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Me
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.PlayerState
import ru.palustris.music.media.QueueSource
import ru.palustris.music.ui.account.Avatar
import ru.palustris.music.ui.chart.ChartNumber
import ru.palustris.music.ui.chart.regionTitle
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalNavigator
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlaylistCover
import ru.palustris.music.ui.common.SectionTitle
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.plural
import ru.palustris.music.ui.common.pluralTracks
import ru.palustris.music.ui.library.LibraryTab
import ru.palustris.music.ui.moods.MoodRow
import ru.palustris.music.ui.moods.moodsForHour

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  player: PlayerState,
  contentPadding: PaddingValues,
  onToggle: () -> Unit,
  onWaveChange: (WaveSettings) -> Unit,
  vm: HomeViewModel = hiltViewModel(),
) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val me by vm.me.collectAsStateWithLifecycle()
  val lib by vm.library.collectAsStateWithLifecycle()
  val mixes by vm.mixes.collectAsStateWithLifecycle()
  val wave by vm.wave.collectAsStateWithLifecycle()
  var waveSheet by remember { mutableStateOf(false) }
  val wrappedYear = remember { wrappedSeason() }
  val actions = LocalTrackActions.current
  val nav = LocalNavigator.current
  val scope = rememberCoroutineScope()
  var startingWave by remember { mutableStateOf(false) }
  val waveIsCurrent = player.source == QueueSource.WAVE
  val data = ui.data
  val history = remember(data.history) { data.history.map { it.toTrack() } }
  val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
  val moods = remember(hour) { moodsForHour(hour) }

  PullToRefreshBox(isRefreshing = ui.refreshing && !ui.loading, onRefresh = vm::refresh) {
    LazyColumn(contentPadding = contentPadding) {
      item(key = "header") { HomeHeader(me, hour, onAccount = nav::openAccount, onSettings = nav::openSettings) }
      if (ui.offline) item(key = "offline") { OfflineBanner(onOpenDownloads = { nav.openLibrary(LibraryTab.Downloads) }) }
      item(key = "wave") {
        WaveCard(
          preview = data.wave,
          playing = waveIsCurrent && player.playWhenReady,
          busy = startingWave,
          label = wave.label,
          onSettings = { waveSheet = true },
          onPlay = {
            if (waveIsCurrent) onToggle()
            else {
              startingWave = true
              scope.launch {
                val tracks = vm.freshWave().getOrNull()?.takeIf { it.isNotEmpty() } ?: data.wave
                if (tracks.isNotEmpty()) actions.play(tracks, 0, QueueSource.WAVE)
                startingWave = false
              }
            }
          },
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
      item(key = "shortcuts") { Shortcuts(lib, onOpen = nav::openLibrary) }
      if (wrappedYear != null) {
        item(key = "wrapped") { WrappedBanner(wrappedYear, onOpen = nav::openWrapped, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
      }

      mixes?.mixes?.takeIf { it.isNotEmpty() }?.let { list ->
        item(key = "mixes-title") { SectionTitle("Миксы дня") }
        item(key = "mixes") { MixShelf(list, onOpen = { nav.openMix(it.id) }) }
      }

      if (history.isNotEmpty()) {
        item(key = "recent-title") {
          SectionTitle("Недавно играло") { TextButton(onClick = { nav.openLibrary(LibraryTab.History) }) { Text("История") } }
        }
        item(key = "recent") { RecentRow(history, onPlay = { i -> actions.play(history, i) }) }
      }

      item(key = "moods-title") { SectionTitle("Настроение") { TextButton(onClick = nav::openMoods) { Text("Все") } } }
      item(key = "moods") { MoodRow(moods, onOpen = { nav.openMood(it.id) }) }

      mixes?.repeat?.takeIf { it.size >= 4 }?.let { list ->
        item(key = "repeat-title") { SectionTitle("На повторе") }
        item(key = "repeat") { TrackShelf(list, onPlay = { i -> actions.play(list, i) }) }
      }
      data.week?.takeIf { it.minutes > 0 }?.let { w ->
        item(key = "week") { WeekTeaser(w, onOpen = nav::openStats, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
      }

      if (data.recs.isNotEmpty()) {
        item(key = "recs-title") {
          SectionTitle("Для вас") {
            IconButton(onClick = vm::refresh, enabled = !ui.refreshing) { Icon(Icons.Rounded.Refresh, "Обновить подборку") }
          }
        }
        item(key = "recs") { RecsPager(data.recs, onPlay = { i -> actions.play(data.recs, i, "recs") }) }
      }

      mixes?.forgotten?.takeIf { it.size >= 4 }?.let { list ->
        item(key = "forgotten-title") { SectionTitle("Забытое любимое") }
        item(key = "forgotten") { TrackShelf(list, onPlay = { i -> actions.play(list, i) }) }
      }

      lib.playlists?.takeIf { it.isNotEmpty() }?.let { pls ->
        item(key = "pl-title") {
          SectionTitle("Ваши плейлисты") { TextButton(onClick = { nav.openLibrary(LibraryTab.Playlists) }) { Text("Все") } }
        }
        item(key = "pl") { PlaylistShelf(pls, onOpen = { nav.openPlaylist(it.id) }) }
      }

      when {
        data.top.isNotEmpty() -> {
          item(key = "top-title") {
            SectionTitle("Чарт · ${regionTitle(data.region)}") { TextButton(onClick = nav::openChart) { Text("Весь чарт") } }
          }
          val top = data.top
          itemsIndexed(top.take(TOP_ON_HOME), key = { i, t -> "top:$i:${t.id}" }) { i, t ->
            TrackRow(
              t,
              onClick = { actions.play(top, i, "chart:${data.region}") },
              modifier = Modifier.padding(horizontal = 8.dp),
              leading = { ChartNumber(i) },
            )
          }
        }
        ui.loading -> item(key = "loading") { LoadingState() }
        ui.offline && data.wave.isEmpty() ->
          item(key = "empty") {
            MessageState(
              "Нет связи с сервером",
              "Скачанные треки играют и без интернета",
              action = "Открыть скачанные",
              onAction = { nav.openLibrary(LibraryTab.Downloads) },
            )
          }
      }
    }
  }
  if (waveSheet) WaveSettingsSheet(wave, onChange = onWaveChange, onDismiss = { waveSheet = false })
}

private const val TOP_ON_HOME = 5

private fun greeting(hour: Int) =
  when (hour) {
    in 5..11 -> "Доброе утро"
    in 12..16 -> "Добрый день"
    in 17..22 -> "Добрый вечер"
    else -> "Доброй ночи"
  }

@Composable
private fun HomeHeader(me: Me?, hour: Int, onAccount: () -> Unit, onSettings: () -> Unit) {
  val today = remember {
    SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru")).format(Date()).replaceFirstChar { it.uppercase() }
  }
  val name = me?.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
  Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(today, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        greeting(hour) + (name?.let { ", $it" } ?: ""),
        style = MaterialTheme.typography.headlineMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Настройки") }
    Avatar(
      me?.name?.ifBlank { null } ?: me?.email ?: "?",
      Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onAccount, role = Role.Button, onClickLabel = "Открыть профиль"),
    )
  }
}

@Composable
private fun OfflineBanner(onOpenDownloads: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
  ) {
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.CloudOff, null, Modifier.size(20.dp))
      Text(
        "Нет связи. Показываем то, что сохранено на телефоне",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      )
      TextButton(onClick = onOpenDownloads) { Text("Скачанные") }
    }
  }
}

/** Плитки быстрого доступа к «Моей музыке», два столбца. */
@Composable
private fun Shortcuts(lib: HomeLibrary, onOpen: (LibraryTab) -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val pl = lib.playlists?.size
  Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Shortcut(
        "Мне нравится",
        lib.likes?.let(::pluralTracks) ?: "…",
        Icons.Rounded.Favorite,
        Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
        Modifier.weight(1f),
      ) { onOpen(LibraryTab.Likes) }
      Shortcut(
        "Скачанные",
        if (lib.downloads > 0) pluralTracks(lib.downloads) else "Без интернета",
        Icons.Rounded.DownloadDone,
        Brush.linearGradient(listOf(scheme.secondary, scheme.primary)),
        Modifier.weight(1f),
      ) { onOpen(LibraryTab.Downloads) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Shortcut(
        "Плейлисты",
        pl?.let { if (it > 0) plural(it, "плейлист", "плейлиста", "плейлистов") else "Соберите свой" } ?: "…",
        Icons.AutoMirrored.Rounded.QueueMusic,
        Brush.linearGradient(listOf(scheme.tertiary, scheme.secondary)),
        Modifier.weight(1f),
      ) { onOpen(LibraryTab.Playlists) }
      Shortcut(
        "История",
        "Что играло",
        Icons.Rounded.History,
        Brush.linearGradient(listOf(scheme.surfaceContainerHighest, scheme.secondaryContainer)),
        Modifier.weight(1f),
      ) { onOpen(LibraryTab.History) }
    }
  }
}

@Composable
private fun Shortcut(title: String, sub: String, icon: ImageVector, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier.height(64.dp).clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh).clickable(onClick = onClick, role = Role.Button),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.fillMaxHeight().aspectRatio(1f).background(brush), contentAlignment = Alignment.Center) {
      Icon(icon, null, tint = scheme.onPrimary, modifier = Modifier.size(26.dp))
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun RecentRow(tracks: List<Track>, onPlay: (Int) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
      Column(Modifier.width(136.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = { onPlay(i) }, role = Role.Button).padding(4.dp)) {
        Artwork(t.thumb, Modifier.fillMaxWidth().aspectRatio(1f), size = 300, shape = RoundedCornerShape(18.dp))
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
          t.artist,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** «Для вас»: страницы по четыре трека, листаются вбок с доводкой до страницы. */
@Composable
private fun RecsPager(tracks: List<Track>, onPlay: (Int) -> Unit) {
  val pages = remember(tracks) { tracks.withIndex().chunked(RECS_PER_PAGE) }
  val state = rememberLazyListState()
  LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(horizontal = 8.dp)) {
    items(pages.size, key = { it }) { p ->
      Column(Modifier.fillParentMaxWidth(if (pages.size > 1) 0.9f else 1f)) {
        pages[p].forEach { (i, t) -> TrackRow(t, onClick = { onPlay(i) }) }
      }
    }
  }
}

private const val RECS_PER_PAGE = 4

@Composable
private fun PlaylistShelf(playlists: List<PlaylistRow>, onOpen: (PlaylistRow) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    items(playlists, key = { it.id }) { p ->
      Column(Modifier.width(136.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = { onOpen(p) }, role = Role.Button).padding(4.dp)) {
        PlaylistCover(p.thumbs, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)))
        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(pluralTracks(p.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/**
 * «Моя волна»: большая карточка в цветах темы. Сверху справа веер обложек ближайших треков,
 * снизу название и кнопка, чтобы обложки не наезжали на текст на узких экранах.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaveCard(
  preview: List<Track>,
  playing: Boolean,
  busy: Boolean,
  label: String,
  onSettings: () -> Unit,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val corner by animateDpAsState(if (playing) 22.dp else 36.dp, spring(0.45f, Spring.StiffnessMediumLow), label = "waveBtn")
  Box(
    modifier
      .fillMaxWidth()
      .height(220.dp)
      .clip(RoundedCornerShape(36.dp))
      .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer)))
      .clickable(onClick = onPlay)
  ) {
    // Настройки волны: подпись показывает, что выбрано.
    Row(
      Modifier.align(Alignment.TopStart)
        .padding(16.dp)
        .clip(CircleShape)
        .background(scheme.surface.copy(alpha = 0.32f))
        .clickable(onClick = onSettings, role = Role.Button, onClickLabel = "Настроить волну")
        .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = scheme.onPrimaryContainer)
      Text(
        label.ifEmpty { if (playing) "Играет" else "Настроить" },
        style = MaterialTheme.typography.labelLarge,
        color = scheme.onPrimaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp),
      )
    }
    Box(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp)) {
      preview.take(3).reversed().forEachIndexed { i, t ->
        val k = 2 - i
        Artwork(
          t.thumb,
          Modifier.offset(x = -(k * 34).dp, y = (k * 5).dp)
            .size(84.dp - (k * 12).dp)
            .border(2.dp, scheme.surface.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
          size = 226,
          shape = RoundedCornerShape(20.dp),
        )
      }
    }
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 22.dp, end = 18.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
      Column(Modifier.weight(1f)) {
        Text("Моя волна", style = MaterialTheme.typography.displaySmall, color = scheme.onPrimaryContainer, maxLines = 1)
        Text(
          preview.firstOrNull()?.why ?: "Бесконечный поток под ваш вкус",
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      FilledIconButton(
        onClick = onPlay,
        modifier = Modifier.padding(start = 12.dp).size(64.dp),
        shape = RoundedCornerShape(corner),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
      ) {
        if (busy) LoadingIndicator(Modifier.size(44.dp), color = scheme.onPrimary)
        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Слушать волну", Modifier.size(34.dp))
      }
    }
  }
}
