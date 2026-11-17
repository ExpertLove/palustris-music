package ru.palustris.music.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.PlayerState
import ru.palustris.music.media.QueueSource
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.PlayingBars
import ru.palustris.music.ui.common.formatLength
import ru.palustris.music.ui.common.pluralTracks
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Трек в «Далее»: [index] — место в очереди плеера, [key] не меняется, пока трек перетаскивают. */
@Immutable
data class QueueEntry(val key: String, val index: Int, val track: Track)

/**
 * Ключи вида «id#n»: n-й раз этот трек в очереди. Один и тот же трек может стоять в очереди дважды,
 * а после перестановки у трека должен остаться прежний ключ, иначе список мигнёт.
 */
fun queueEntries(queue: List<Track>, upNext: List<Int>): List<QueueEntry> {
  val seen = HashMap<String, Int>()
  val keys = queue.map { t -> "q:${t.id}#${seen.merge(t.id, 1, Int::plus)}" }
  return upNext.mapNotNull { i -> queue.getOrNull(i)?.let { QueueEntry(keys[i], i, it) } }
}

/**
 * Очередь, как на сайте: что играет и что дальше. Трек можно перетащить за ручку, убрать крестиком,
 * включить нажатием; «Очистить» убирает всё, что дальше. С перемешиванием порядок выбирает плеер,
 * поэтому перетаскивать нельзя, а список показан в том порядке, в каком треки сыграют.
 */
@Composable
fun QueueSheet(state: PlayerState, controls: PlayerControls) {
  val current = state.track ?: return
  val haptics = LocalHapticFeedback.current
  val latest by rememberUpdatedState(state)
  val entries = remember(state.queue, state.upNext) { queueEntries(state.queue, state.upNext) }
  val canReorder = !state.shuffle

  // Пока палец тянет трек, порядок живёт здесь; в плеер уходит, когда трек отпустили.
  var dragged by remember { mutableStateOf<List<QueueEntry>?>(null) }
  var dragging by remember { mutableStateOf<QueueEntry?>(null) }
  var dragBase by remember { mutableIntStateOf(-1) }
  LaunchedEffect(entries) { if (dragging == null) dragged = null }
  val shown = dragged ?: entries

  val listState = rememberLazyListState()
  val reorder =
    rememberReorderableLazyListState(listState) { from, to ->
      val list = (dragged ?: entries).toMutableList()
      val fi = list.indexOfFirst { it.key == from.key }
      val ti = list.indexOfFirst { it.key == to.key }
      if (fi < 0 || ti < 0) return@rememberReorderableLazyListState
      list.add(ti, list.removeAt(fi))
      dragged = list
      haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

  fun drop() {
    val e = dragging ?: return
    dragging = null
    val pos = dragged?.indexOfFirst { it.key == e.key } ?: -1
    // Очередь могла сдвинуться, пока тянули (трек сменился): тогда плеер откажется, и список вернётся.
    val moved = pos >= 0 && controls.moveInQueue(e.index, dragBase + 1 + pos, e.track.id)
    if (!moved) dragged = null
  }

  val wave = state.source == QueueSource.WAVE
  val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Очередь", style = MaterialTheme.typography.titleLarge)
        if (entries.isNotEmpty()) {
          // У треков «Моей волны» сервер не присылает длину: тогда без неё, чем «0 мин».
          val length = if (entries.all { it.track.duration > 0 }) " · " + formatLength(entries.sumOf { it.track.duration }) else ""
          Text(
            "Дальше ${pluralTracks(entries.size)}$length",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (entries.isNotEmpty()) TextButton(onClick = controls::clearUpNext) { Text("Очистить") }
    }

    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      item(key = "now-label") { Label("Сейчас играет") }
      item(key = "now") { QueueRow(current, Modifier.padding(horizontal = 12.dp), isCurrent = true, playing = state.isPlaying) }
      item(key = "next-label") {
        Column {
          Label(if (entries.isEmpty()) "Дальше ничего нет" else "Далее")
          if (state.shuffle && entries.isNotEmpty()) {
            Hint("Включено перемешивание, порядок выбирает плеер. Чтобы переставлять треки, выключите его")
          }
        }
      }
      items(shown, key = { it.key }) { e ->
        ReorderableItem(reorder, key = e.key, enabled = canReorder) { isDragging ->
          val lift by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "lift")
          val pos = shown.indexOf(e)
          Surface(
            shadowElevation = lift,
            shape = RoundedCornerShape(16.dp),
            color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            modifier = Modifier.padding(horizontal = 12.dp),
          ) {
            QueueRow(
              e.track,
              onClick = { controls.skipTo(e.index) },
              // TalkBack: перетаскивание пальцем ему недоступно, поэтому те же действия списком.
              modifier =
                Modifier.semantics {
                  customActions =
                    listOfNotNull(
                      CustomAccessibilityAction("Выше") { controls.moveInQueue(e.index, e.index - 1, e.track.id) }
                        .takeIf { canReorder && pos > 0 },
                      CustomAccessibilityAction("Ниже") { controls.moveInQueue(e.index, e.index + 1, e.track.id) }
                        .takeIf { canReorder && pos in 0 until shown.lastIndex },
                    )
                },
            ) {
              IconButton(onClick = { controls.removeFromQueue(e.index, e.track.id) }) {
                Icon(Icons.Rounded.Close, "Убрать из очереди", tint = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              if (canReorder) {
                IconButton(
                  onClick = {},
                  modifier =
                    Modifier.draggableHandle(
                        onDragStarted = {
                          dragging = e
                          dragBase = latest.index
                          haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        },
                        onDragStopped = {
                          drop()
                          haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        },
                      )
                      .semantics { contentDescription = "Перетащить, чтобы поменять порядок" },
                ) {
                  Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
        }
      }
      if (wave) item(key = "wave") { Hint("«Моя волна» бесконечная: новые треки подберутся сами") }
    }
    Box(Modifier.navigationBarsPadding())
  }
}

@Composable
private fun Label(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
  )
}

@Composable
private fun Hint(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 8.dp),
  )
}

@Composable
private fun QueueRow(
  track: Track,
  modifier: Modifier = Modifier,
  isCurrent: Boolean = false,
  playing: Boolean = false,
  onClick: (() -> Unit)? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .then(if (isCurrent) Modifier.background(scheme.primary.copy(alpha = 0.12f)) else Modifier)
      .then(if (onClick != null) Modifier.clickable(onClickLabel = "Играть", onClick = onClick) else Modifier)
      .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(44.dp)) {
      Artwork(track.thumb, Modifier.fillMaxSize(), size = 120, shape = RoundedCornerShape(12.dp))
      if (isCurrent) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(0.45f)), Alignment.Center) {
          PlayingBars(playing)
        }
      }
    }
    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
      Text(
        track.title,
        style = MaterialTheme.typography.titleSmall,
        color = if (isCurrent) scheme.primary else scheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(track.artist, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    trailing()
  }
}
