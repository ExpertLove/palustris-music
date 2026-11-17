package ru.palustris.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Track

/**
 * Обложка, которую можно смахнуть: влево — следующий трек, вправо — предыдущий. Соседняя обложка
 * въезжает с края, как страница. Если соседа нет, обложка пружинит на месте.
 */
@Composable
fun SwipeArtwork(
  current: Track,
  prev: Track?,
  next: Track?,
  onNext: () -> Unit,
  onPrev: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable (Track, Modifier) -> Unit,
) {
  val offset = remember { Animatable(0f) }
  var width by remember { mutableIntStateOf(1) }
  val gap = with(LocalDensity.current) { 24.dp.toPx() }
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val latestPrev by rememberUpdatedState(prev)
  val latestNext by rememberUpdatedState(next)

  // Трек сменился (нашим жестом или кнопкой): соседняя обложка уже в центре — встаём на место.
  LaunchedEffect(current.id) { offset.snapTo(0f) }

  fun settle(velocity: Float) {
    scope.launch {
      val v = offset.value
      val far = width + gap
      val goNext = (v < -width * 0.28f || velocity < -1400f) && latestNext != null && v < 0
      val goPrev = (v > width * 0.28f || velocity > 1400f) && latestPrev != null && v > 0
      when {
        goNext -> {
          haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
          offset.animateTo(-far, tween(220))
          onNext()
          // Если плеер не переключился (нет сети и т. п.), возвращаемся.
          delay(1200)
          if (offset.value != 0f) offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
        }
        goPrev -> {
          haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
          offset.animateTo(far, tween(220))
          onPrev()
          delay(1200)
          if (offset.value != 0f) offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
        }
        else -> offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
      }
    }
  }

  Box(
    modifier
      .onSizeChanged { width = it.width.coerceAtLeast(1) }
      .draggable(
        orientation = Orientation.Horizontal,
        state =
          rememberDraggableState { dx ->
            val v = offset.value
            // Без соседа — тугая резинка.
            val free = (dx < 0 && latestNext != null) || (dx > 0 && latestPrev != null) || (v != 0f && (v < 0) != (dx < 0))
            scope.launch { offset.snapTo(v + if (free) dx else dx * 0.25f) }
          },
        onDragStopped = { velocity -> settle(velocity) },
      )
  ) {
    content(
      current,
      Modifier.graphicsLayer {
        val f = (abs(offset.value) / width).coerceIn(0f, 1f)
        translationX = offset.value
        scaleX = 1f - 0.08f * f
        scaleY = 1f - 0.08f * f
        alpha = 1f - 0.4f * f
      },
    )
    // Только знак смещения: пересобирать содержимое на каждый пиксель жеста незачем.
    val side by remember { derivedStateOf { sign(offset.value) } }
    val neighbor = if (side < 0) next else if (side > 0) prev else null
    if (neighbor != null) {
      content(
        neighbor,
        Modifier.graphicsLayer {
          val o = offset.value
          translationX = if (o < 0) o + width + gap else o - width - gap
          val f = 1f - (abs(o) / (width + gap)).coerceIn(0f, 1f)
          scaleX = 1f - 0.08f * f
          scaleY = 1f - 0.08f * f
        },
      )
    }
  }
}

/** Смахивание мини-плеера вбок: содержимое уезжает, трек переключается, новое въезжает с другой стороны. */
@Composable
fun rememberMiniSwipe(trackId: String?, hasNext: Boolean, hasPrev: Boolean, onNext: () -> Unit, onPrev: () -> Unit): MiniSwipe {
  val scope = rememberCoroutineScope()
  val swipe = remember { MiniSwipe(Animatable(0f)) }
  val haptics = LocalHapticFeedback.current
  swipe.bind(hasNext, hasPrev) { dir ->
    scope.launch {
      haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
      swipe.offset.animateTo(dir * -swipe.width.toFloat(), tween(160))
      swipe.pendingFrom = dir
      if (dir > 0) onNext() else onPrev()
      delay(1200)
      if (swipe.pendingFrom != 0) {
        swipe.pendingFrom = 0
        swipe.offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
      }
    }
  }
  LaunchedEffect(trackId) {
    val from = swipe.pendingFrom
    if (from != 0) {
      swipe.pendingFrom = 0
      swipe.offset.snapTo(from * swipe.width * 0.6f)
    }
    swipe.offset.animateTo(0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
  }
  return swipe
}

class MiniSwipe internal constructor(val offset: Animatable<Float, *>) {
  var width = 1
  internal var pendingFrom = 0
  private var hasNext = false
  private var hasPrev = false
  private var go: (Int) -> Unit = {}

  internal fun bind(hasNext: Boolean, hasPrev: Boolean, go: (Int) -> Unit) {
    this.hasNext = hasNext
    this.hasPrev = hasPrev
    this.go = go
  }

  suspend fun drag(dx: Float) {
    val free = (dx < 0 && hasNext) || (dx > 0 && hasPrev) || offset.value != 0f
    offset.snapTo(offset.value + if (free) dx else dx * 0.2f)
  }

  suspend fun release(velocity: Float) {
    val v = offset.value
    when {
      (v < -width * 0.3f || velocity < -1400f) && hasNext && v < 0 -> go(1)
      (v > width * 0.3f || velocity > 1400f) && hasPrev && v > 0 -> go(-1)
      else -> offset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
    }
  }
}
