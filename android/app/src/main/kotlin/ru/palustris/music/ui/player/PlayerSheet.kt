package ru.palustris.music.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.palustris.music.ui.common.Artwork

/**
 * Полный плеер как лист, который ездит за пальцем: [progress] 0 — свёрнут в мини-плеер, 1 —
 * раскрыт. Тянуть можно и мини-плеер вверх, и полный вниз; «Назад» жестом уменьшает плеер вслед
 * за пальцем (predictive back). Обложка во время перехода перелетает из мини-плеера на своё место.
 */
@Stable
class PlayerSheetState internal constructor(private val scope: CoroutineScope, initiallyExpanded: Boolean) {
  val progress = Animatable(if (initiallyExpanded) 1f else 0f)
  var expanded by mutableStateOf(initiallyExpanded)
    private set
  /** Жест «Назад» в процессе: 0..1. */
  var back by mutableFloatStateOf(0f)
    internal set
  /** Высота экрана, пикселей: для перевода жеста в долю. */
  var heightPx = 1f

  /** Где обложка мини-плеера и полного плеера (в координатах окна); `null` — пока не знаем. */
  var miniArt by mutableStateOf<Rect?>(null)
  var fullArt by mutableStateOf<Rect?>(null)

  /** Плеер хоть немного виден: его стоит компоновать. */
  val visible by derivedStateOf { progress.value > 0f }
  /** Обложка в полёте: обе настоящие прячутся, рисуется одна общая. */
  val morphing by derivedStateOf { progress.value in 0.001f..0.999f && miniArt != null && fullArt != null && back == 0f }

  fun expand() {
    expanded = true
    scope.launch { progress.animateTo(1f, SPRING) }
  }

  fun collapse() {
    expanded = false
    scope.launch { progress.animateTo(0f, SPRING) }
  }

  /** Сдвиг пальца: вверх — положительный (раскрыть). */
  fun dragBy(deltaPx: Float) {
    scope.launch { progress.snapTo((progress.value + deltaPx / heightPx).coerceIn(0f, 1f)) }
  }

  /** Палец отпущен: доезжаем туда, куда он тянул, с учётом скорости. */
  fun settle(velocityPx: Float) {
    val v = velocityPx / heightPx
    when {
      v > 1.1f -> expand()
      v < -1.1f -> collapse()
      progress.value > 0.5f -> expand()
      else -> collapse()
    }
  }

  private companion object {
    val SPRING = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
  }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
  val scope = rememberCoroutineScope()
  var saved by rememberSaveable { mutableStateOf(false) }
  val state = remember { PlayerSheetState(scope, saved) }
  saved = state.expanded
  return state
}

/** «Назад» жестом: плеер уменьшается за пальцем и сворачивается, если жест довели до конца. */
@Composable
fun PlayerBackHandler(state: PlayerSheetState) {
  PredictiveBackHandler(enabled = state.expanded) { events ->
    try {
      events.collect { e -> state.back = e.progress }
      state.back = 0f
      state.collapse()
    } catch (e: CancellationException) {
      state.back = 0f
      throw e
    }
  }
}

/** Модификатор листа полного плеера: выезжает снизу и уменьшается при жесте «Назад». */
fun Modifier.playerSheet(state: PlayerSheetState): Modifier =
  graphicsLayer {
    val p = state.progress.value
    translationY = (1f - p) * size.height
    val b = state.back
    if (b > 0f) {
      val s = 1f - 0.1f * b
      scaleX = s
      scaleY = s
      shape = RoundedCornerShape((36 * b).dp)
      clip = true
    }
  }

/**
 * Обложка в полёте между мини-плеером и полным: позиция, размер и скругление — по прогрессу листа.
 * Прогресс читается в фазах раскладки и отрисовки, без перекомпоновки на каждый кадр.
 */
@Composable
fun FlyingArtwork(state: PlayerSheetState, thumb: String?) {
  if (!state.morphing) return
  Box(
    Modifier.layout { measurable, constraints ->
        val from = state.miniArt
        val p = state.progress.value
        // Обложка полного плеера едет вместе с листом: её место сдвинуто на непройденную часть экрана.
        val to = state.fullArt?.translate(0f, (1f - p) * state.heightPx)
        val t = FastOutSlowInEasing.transform(p)
        if (from == null || to == null) return@layout layout(0, 0) {}
        val w = lerp(from.width, to.width, t).roundToInt().coerceAtLeast(1)
        val h = lerp(from.height, to.height, t).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) {
          placeable.place(lerp(from.left, to.left, t).roundToInt(), lerp(from.top, to.top, t).roundToInt())
        }
      }
      .graphicsLayer {
        val t = FastOutSlowInEasing.transform(state.progress.value)
        shape = RoundedCornerShape(lerp(14f, 32f, t).dp)
        clip = true
        shadowElevation = (28 * t).dp.toPx()
      }
  ) {
    Artwork(thumb, Modifier.fillMaxSize(), size = 720, shape = RectangleShape)
  }
}
