package ru.palustris.music.ui.moods

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.OfflineFiles
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.ui.catalog.Page
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlayActions
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.pluralTracks

/** Карточка настроения: градиент, значок и название. */
@Composable
fun MoodCard(mood: Mood, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
  Box(
    modifier
      .clip(RoundedCornerShape(28.dp))
      .background(Brush.linearGradient(listOf(mood.from, mood.to)))
      .clickable(onClick = onClick, role = Role.Button, onClickLabel = "Открыть подборку «${mood.title}»")
  ) {
    // Крупный полупрозрачный значок в углу: карточки узнаются по силуэту.
    Icon(
      mood.icon,
      null,
      tint = Color.White.copy(alpha = 0.16f),
      modifier = Modifier.align(Alignment.TopEnd).size(if (compact) 72.dp else 96.dp).offset(x = 14.dp, y = (-10).dp),
    )
    Box(
      Modifier.padding(14.dp).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(mood.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
    Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 12.dp, bottom = 14.dp)) {
      Text(
        mood.title,
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        mood.sub,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.78f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@HiltViewModel
class MoodViewModel
@Inject
constructor(private val api: MusicApi, private val files: OfflineFiles, private val playlists: PlaylistsRepository) : ViewModel() {
  private val _page = MutableStateFlow(Page<List<Track>>())
  val page: StateFlow<Page<List<Track>>> = _page.asStateFlow()
  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()
  private var mood: Mood? = null

  fun open(m: Mood) {
    if (mood?.id == m.id) return
    mood = m
    viewModelScope.launch {
      // Сначала прошлая копия с телефона, чтобы подборка открывалась и без сети.
      files.read(file(m), ListSerializer(Track.serializer()))?.let { if (_page.value.data == null) _page.value = Page(it) }
      load(m)
    }
  }

  fun reload() {
    mood?.let { m -> viewModelScope.launch { load(m) } }
  }

  private suspend fun load(m: Mood) {
    val results = coroutineScope { m.queries.map { q -> async { apiCall { api.searchTracks(q, SONGS) } } }.awaitAll() }
    val lists = results.mapNotNull { it.getOrNull() }
    if (lists.isEmpty()) {
      val e = results.firstNotNullOfOrNull { it.exceptionOrNull() }
      if (_page.value.data == null) _page.value = Page(error = e?.message) else _messages.send(e?.message ?: "Нет связи")
      return
    }
    val list = interleave(lists)
    _page.value = Page(list)
    files.write(file(m), ListSerializer(Track.serializer()), list)
  }

  fun saveAsPlaylist() {
    val m = mood ?: return
    val tracks = _page.value.data.orEmpty()
    if (tracks.isEmpty()) return
    viewModelScope.launch {
      _messages.send(
        playlists.create("${m.title} · настроение", tracks).fold({ "Сохранено в плейлисты" }, { it.message ?: "Не получилось сохранить" })
      )
    }
  }

  private fun file(m: Mood) = "mood-${m.id}.json"

  companion object {
    /** Поиск только песен (`/api/search?kind=songs`). */
    const val SONGS = "songs"
    const val MAX_SONG_SEC = 720L

    /** По одному треку из каждого поиска по очереди, без повторов: подборка звучит разнообразно. */
    fun interleave(lists: List<List<Track>>): List<Track> {
      val seen = HashSet<String>()
      val out = ArrayList<Track>()
      val longest = lists.maxOfOrNull { it.size } ?: 0
      for (i in 0 until longest) {
        for (l in lists) {
          // Дольше 12 минут — это часовая «музыка для сна», а не песня.
          l.getOrNull(i)?.let { if (it.duration <= MAX_SONG_SEC && seen.add(it.id)) out += it }
        }
      }
      return out
    }
  }
}

@Composable
fun MoodScreen(id: String, contentPadding: PaddingValues, onBack: () -> Unit, onMessage: (String) -> Unit) {
  val mood = moodById(id)
  if (mood == null) {
    MessageState("Нет такого настроения")
    return
  }
  val vm: MoodViewModel = hiltViewModel(key = "mood:$id")
  LaunchedEffect(id) { vm.open(mood) }
  LaunchedEffect(vm) { vm.messages.collect(onMessage) }
  val page by vm.page.collectAsStateWithLifecycle()
  val actions = LocalTrackActions.current
  val tracks = page.data

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    item { MoodHeader(mood, tracks?.size, onBack, onSave = vm::saveAsPlaylist, onReload = vm::reload) }
    when {
      tracks == null && page.error != null ->
        item { MessageState("Подборка не загрузилась", page.error, action = "Повторить", onAction = vm::reload) }
      tracks == null -> item { LoadingState() }
      tracks.isEmpty() -> item { MessageState("Пока пусто", "Попробуйте другое настроение") }
      else -> {
        item { PlayActions(tracks, source = "mood:${mood.id}") }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
          TrackRow(t, onClick = { actions.play(tracks, i, "mood:${mood.id}") }, modifier = Modifier.padding(horizontal = 8.dp))
        }
      }
    }
  }
}

@Composable
private fun MoodHeader(mood: Mood, count: Int?, onBack: () -> Unit, onSave: () -> Unit, onReload: () -> Unit) {
  val buttons = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
  Box(
    Modifier.fillMaxWidth()
      .background(Brush.verticalGradient(listOf(mood.from, mood.to, MaterialTheme.colorScheme.surface)))
  ) {
    Icon(
      mood.icon,
      null,
      tint = Color.White.copy(alpha = 0.12f),
      modifier = Modifier.align(Alignment.TopEnd).size(220.dp).offset(x = 40.dp, y = 30.dp),
    )
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
      androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(4.dp)) {
        IconButton(onClick = onBack, colors = buttons) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onReload, colors = buttons) { Icon(Icons.Rounded.Refresh, "Обновить подборку") }
        IconButton(onClick = onSave, enabled = (count ?: 0) > 0, colors = buttons) {
          Icon(Icons.Rounded.LibraryAdd, "Сохранить в мои плейлисты")
        }
      }
      Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp)) {
        Box(
          Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(mood.icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Настроение", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        Text(mood.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
          mood.sub.replaceFirstChar { it.uppercase() } + (count?.let { " · " + pluralTracks(it) } ?: ""),
          style = MaterialTheme.typography.bodyLarge,
          color = Color.White.copy(alpha = 0.85f),
        )
      }
    }
  }
}

/** Все настроения сеткой. */
@Composable
fun MoodsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (Mood) -> Unit) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(160.dp),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding()),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item(span = { GridItemSpan(maxLineSpan) }) {
      Column(Modifier.statusBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Text("Настроение", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
          "Подборки под момент: дорога, работа, вечеринка или тихий вечер",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
      }
    }
    items(MOODS, key = { it.id }) { m -> MoodCard(m, onClick = { onOpen(m) }, modifier = Modifier.fillMaxWidth().aspectRatio(1.35f)) }
  }
}

/** Ряд карточек для главной и поиска. */
@Composable
fun MoodRow(moods: List<Mood>, onOpen: (Mood) -> Unit, cardWidth: Dp = 156.dp) {
  androidx.compose.foundation.lazy.LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(moods.size, key = { moods[it].id }) { i ->
      MoodCard(moods[i], onClick = { onOpen(moods[i]) }, modifier = Modifier.size(cardWidth, 112.dp), compact = true)
    }
  }
}
