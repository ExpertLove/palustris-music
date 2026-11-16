package ru.palustris.music.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.PlayerState
import ru.palustris.music.media.QueueSource
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.SectionTitle
import ru.palustris.music.ui.common.TrackRow

@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  player: PlayerState,
  contentPadding: PaddingValues,
  onOpenSettings: () -> Unit,
  onOpenDownloads: () -> Unit,
  onToggle: () -> Unit,
  vm: HomeViewModel = hiltViewModel(),
) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val actions = LocalTrackActions.current
  val scope = rememberCoroutineScope()
  var startingWave by remember { mutableStateOf(false) }
  val waveIsCurrent = player.source == QueueSource.WAVE

  PullToRefreshBox(isRefreshing = ui.loading && ui.top.isNotEmpty(), onRefresh = vm::refresh) {
    LazyColumn(contentPadding = contentPadding) {
      item {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Музыка", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
          IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Настройки") }
        }
      }
      item {
        WaveCard(
          preview = ui.wave,
          playing = waveIsCurrent && player.playWhenReady,
          busy = startingWave,
          onPlay = {
            if (waveIsCurrent) onToggle()
            else {
              startingWave = true
              scope.launch {
                val tracks = vm.freshWave().getOrNull()?.takeIf { it.isNotEmpty() } ?: ui.wave
                if (tracks.isNotEmpty()) actions.play(tracks, 0, QueueSource.WAVE)
                startingWave = false
              }
            }
          },
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      if (ui.error != null && ui.top.isEmpty()) {
        item {
          MessageState(
            "Нет связи с сервером",
            "Скачанные треки играют и без интернета",
            action = "Открыть скачанные",
            onAction = onOpenDownloads,
          )
        }
      } else if (ui.loading && ui.top.isEmpty()) {
        item { LoadingState() }
      } else if (ui.top.isNotEmpty()) {
        item {
          SectionTitle("Популярное сейчас") {
            TextButton(onClick = { actions.play(ui.top, 0) }) { Text("Слушать всё") }
          }
        }
        itemsIndexed(ui.top, key = { _, t -> "top:" + t.id }) { i, t ->
          TrackRow(t, onClick = { actions.play(ui.top, i) }, modifier = Modifier.padding(horizontal = 8.dp), leading = {
            Text(
              "${i + 1}",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.width(28.dp),
            )
          })
        }
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
private fun WaveCard(preview: List<Track>, playing: Boolean, busy: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val corner by animateDpAsState(if (playing) 22.dp else 36.dp, spring(0.45f, Spring.StiffnessMediumLow), label = "waveBtn")
  Box(
    modifier
      .fillMaxWidth()
      .height(236.dp)
      .clip(RoundedCornerShape(36.dp))
      .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer)))
      .clickable(onClick = onPlay)
  ) {
    Box(
      Modifier.align(Alignment.TopStart).padding(20.dp).clip(CircleShape).background(scheme.surface.copy(alpha = 0.28f)).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
      Text(if (playing) "Играет" else "Подборка для вас", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
    }
    Box(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp)) {
      preview.take(3).reversed().forEachIndexed { i, t ->
        val k = 2 - i
        Artwork(
          t.thumb,
          Modifier.offset(x = -(k * 34).dp, y = (k * 5).dp)
            .size(88.dp - (k * 12).dp)
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
