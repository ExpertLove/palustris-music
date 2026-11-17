package ru.palustris.music.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import ru.palustris.music.media.PlayerState
import ru.palustris.music.media.Progress
import ru.palustris.music.ui.common.Artwork

/**
 * Мини-плеер над нижней панелью. Нажатие раскрывает полный плеер, тяга вверх — тоже, но вслед за
 * пальцем. Смахивание вбок переключает трек: влево — следующий, вправо — предыдущий.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayer(
  state: PlayerState,
  progress: State<Progress>,
  onToggle: () -> Unit,
  onNext: () -> Unit,
  onPrev: () -> Unit,
  onExpand: () -> Unit,
  modifier: Modifier = Modifier,
  sheet: PlayerSheetState? = null,
) {
  val track = state.track ?: return
  val accent = MaterialTheme.colorScheme.primary
  val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
  val scope = rememberCoroutineScope()
  val swipe = rememberMiniSwipe(track.id, state.hasNext, state.index > 0, onNext, onPrev)
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp)
        // Пока полный плеер раскрывается, мини-плеер растворяется.
        .graphicsLayer { alpha = 1f - ((sheet?.progress?.value ?: 0f) * 3f).coerceIn(0f, 1f) }
        .then(
          if (sheet != null)
            Modifier.draggable(
              orientation = Orientation.Vertical,
              state = rememberDraggableState { dy -> sheet.dragBy(-dy) },
              onDragStopped = { v -> sheet.settle(-v) },
            )
          else Modifier
        )
        .semantics {
          contentDescription = "Мини-плеер: ${track.title}, ${track.artist}"
          customActions =
            listOf(
              CustomAccessibilityAction("Следующий трек") { onNext(); true },
              CustomAccessibilityAction("Предыдущий трек") { onPrev(); true },
            )
        },
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    tonalElevation = 3.dp,
    shadowElevation = 8.dp,
    onClick = onExpand,
  ) {
    Box(
      // Полоска прогресса рисуется в фазе отрисовки: позиция меняется 4 раза в секунду,
      // а перекомпоновка мини-плеера от этого не запускается.
      Modifier.drawBehind {
        val p = progress.value
        val f = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
        val h = 3.dp.toPx()
        val inset = 18.dp.toPx()
        val w = size.width - inset * 2
        drawRoundRect(trackColor, Offset(inset, size.height - h - 3.dp.toPx()), Size(w, h), CornerRadius(h))
        drawRoundRect(accent, Offset(inset, size.height - h - 3.dp.toPx()), Size(w * f, h), CornerRadius(h))
      }
    ) {
      Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        // Обложка и название смахиваются вбок; кнопки стоят на месте.
        Row(
          Modifier.weight(1f)
            .onSizeChanged { swipe.width = it.width.coerceAtLeast(1) }
            .draggable(
              orientation = Orientation.Horizontal,
              state = rememberDraggableState { dx -> scope.launch { swipe.drag(dx) } },
              onDragStopped = { v -> scope.launch { swipe.release(v) } },
            )
            .graphicsLayer {
              translationX = swipe.offset.value
              alpha = 1f - (abs(swipe.offset.value) / swipe.width).coerceIn(0f, 0.7f)
            },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Artwork(
            track.thumb,
            Modifier.size(46.dp)
              .onGloballyPositioned { c -> sheet?.miniArt = c.boundsInWindow() }
              .graphicsLayer { alpha = if (sheet?.morphing == true) 0f else 1f },
            size = 120,
            shape = RoundedCornerShape(14.dp),
          )
          Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
              track.title,
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              modifier = Modifier.basicMarquee(initialDelayMillis = 2500),
            )
            Text(
              track.artist,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
          if (state.isBuffering) LoadingIndicator(Modifier.size(34.dp))
          Icon(
            if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (state.playWhenReady) "Пауза" else "Играть",
            modifier = Modifier.size(if (state.isBuffering) 18.dp else 30.dp),
          )
        }
        IconButton(onClick = onNext, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, "Следующий") }
      }
    }
  }
}
