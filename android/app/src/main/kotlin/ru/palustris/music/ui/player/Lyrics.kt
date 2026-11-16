package ru.palustris.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Lyrics
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.media.Progress
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.MessageState

data class LrcLine(val timeMs: Long, val text: String)

/** «[01:23.45] строка» → строки по времени. Несколько меток на одной строке тоже поддерживаются. */
fun parseLrc(lrc: String): List<LrcLine> {
  val tag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
  return lrc.lineSequence()
    .flatMap { line ->
      val stamps = tag.findAll(line).toList()
      val text = line.replace(tag, "").trim()
      stamps.map { m ->
        val (mm, ss, frac) = m.destructured
        val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0
        LrcLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, text)
      }
    }
    .sortedBy { it.timeMs }
    .toList()
}

sealed interface LyricsState {
  data object Loading : LyricsState

  data class Synced(val lines: List<LrcLine>) : LyricsState

  data class Plain(val text: String) : LyricsState

  data class None(val instrumental: Boolean, val error: String? = null) : LyricsState
}

@HiltViewModel
class LyricsViewModel @Inject constructor(private val api: MusicApi) : ViewModel() {
  private val cache = HashMap<String, LyricsState>()
  private val _state = MutableStateFlow<LyricsState>(LyricsState.Loading)
  val state: StateFlow<LyricsState> = _state.asStateFlow()
  private var trackId: String? = null

  fun load(t: Track) {
    if (trackId == t.id) return
    trackId = t.id
    cache[t.id]?.let {
      _state.value = it
      return
    }
    _state.value = LyricsState.Loading
    viewModelScope.launch {
      val s = apiCall { api.lyrics(t.artist.substringBefore(',').trim(), t.title, t.duration) }
        .fold(::toState) { LyricsState.None(false, it.message) }
      if (s !is LyricsState.None || s.error == null) cache[t.id] = s
      if (trackId == t.id) _state.value = s
    }
  }

  private fun toState(l: Lyrics): LyricsState {
    val lines = l.synced?.let(::parseLrc)?.filter { it.text.isNotEmpty() }.orEmpty()
    return when {
      lines.size >= 3 -> LyricsState.Synced(lines)
      !l.plain.isNullOrBlank() -> LyricsState.Plain(l.plain)
      else -> LyricsState.None(l.instrumental)
    }
  }
}

/** Текст песни вместо обложки. Синхронный текст подсвечивает текущую строку и сам прокручивается. */
@Composable
fun LyricsPanel(state: LyricsState, progress: State<Progress>, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.clip(RoundedCornerShape(28.dp))) {
    when (state) {
      LyricsState.Loading -> LoadingState(Modifier.align(Alignment.Center))
      is LyricsState.None ->
        MessageState(
          if (state.instrumental) "Здесь нет слов" else "Текст не нашёлся",
          state.error ?: if (state.instrumental) "Это инструментальный трек" else "Для этого трека слов пока нет",
          icon = if (state.instrumental) Icons.Rounded.MusicNote else Icons.Rounded.Lyrics,
          modifier = Modifier.align(Alignment.Center),
        )
      is LyricsState.Plain ->
        Text(
          state.text,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        )
      is LyricsState.Synced -> SyncedLyrics(state.lines, progress, onSeek)
    }
  }
}

@Composable
private fun SyncedLyrics(lines: List<LrcLine>, progress: State<Progress>, onSeek: (Long) -> Unit) {
  // Текущая строка меняется раз в несколько секунд, а позиция — 4 раза в секунду.
  val current by remember(lines) {
    derivedStateOf {
      val pos = progress.value.positionMs + 250
      lines.indexOfLast { it.timeMs <= pos }
    }
  }
  // Открываем сразу на текущей строке: анимированная прокрутка до первой раскладки не срабатывает.
  val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
  LaunchedEffect(current) {
    if (current < 0) return@LaunchedEffect
    val target = (current - 2).coerceAtLeast(0)
    if (list.layoutInfo.totalItemsCount == 0) list.scrollToItem(target) else list.animateScrollToItem(target)
  }
  LazyColumn(state = list, contentPadding = PaddingValues(vertical = 120.dp), modifier = Modifier.fillMaxSize()) {
    itemsIndexed(lines, key = { i, l -> "$i:${l.timeMs}" }) { i, line ->
      val active = i == current
      val passed = i < current
      val color by animateColorAsState(
        when {
          active -> MaterialTheme.colorScheme.onSurface
          passed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
          else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        },
        label = "lineColor",
      )
      val scale by animateFloatAsState(if (active) 1f else 0.94f, label = "lineScale")
      Text(
        line.text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
        color = color,
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSeek(line.timeMs) }
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
      )
    }
  }
}
