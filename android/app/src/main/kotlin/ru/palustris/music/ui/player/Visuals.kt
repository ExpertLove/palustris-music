package ru.palustris.music.ui.player

import android.provider.Settings as SystemSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import ru.palustris.music.media.audio.AudioLevels

/**
 * Ритм музыки для анимаций. Каждый кадр берёт спектр из [AudioLevels] на текущей позиции
 * трека и сглаживает его: быстрый подъём, плавный спад, как у стрелочного индикатора.
 * Значения читаются в фазе отрисовки (`graphicsLayer`, `Canvas`), так что перекомпоновки нет.
 */
@Stable
class Beat {
  internal val bands = FloatArray(AudioLevels.BANDS)
  private val raw = FloatArray(AudioLevels.BANDS)
  /** Растёт каждый кадр: читающие его рисовалки перерисовываются. */
  var frame by mutableLongStateOf(0L)
    private set
  /** Бас 0..1 со сглаживанием. */
  var bass by mutableFloatStateOf(0f)
    private set
  /** Общая громкость 0..1. */
  var energy by mutableFloatStateOf(0f)
    private set
  private var bassFloor = 0.3f

  internal fun tick(levels: AudioLevels, id: Int, positionMs: Long, playing: Boolean) {
    val fresh = playing && levels.read(id, positionMs, raw)
    for (i in bands.indices) {
      val target = if (fresh) raw[i] else 0f
      val k = if (target > bands[i]) 0.55f else 0.12f
      bands[i] += (target - bands[i]) * k
    }
    // Бас относительно своего среднего: удар бочки заметен и в тихом, и в громком треке.
    val b = (bands[0] + bands[1] + bands[2]) / 3f
    bassFloor += (b - bassFloor) * 0.02f
    val punch = ((b - bassFloor) * 3.2f).coerceIn(0f, 1f)
    bass += (punch - bass) * (if (punch > bass) 0.6f else 0.15f)
    energy = bands.average().toFloat()
    frame++
  }
}

/** Системная настройка «Убрать анимацию»: тогда всё стоит на месте. */
@Composable
fun animationsEnabled(): Boolean {
  val context = LocalContext.current
  return remember { SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/**
 * Ритм, пока [active] (полный плеер виден и включена «Анимация под музыку»). На это время
 * аудиоцепочка считает спектр; после — перестаёт.
 */
@Composable
fun rememberBeat(levels: AudioLevels, active: Boolean, playing: () -> Boolean, idHash: () -> Int, position: () -> Long): Beat {
  val beat = remember { Beat() }
  val motion = animationsEnabled()
  DisposableEffect(active, motion) {
    levels.enabled = active && motion
    onDispose { levels.enabled = false }
  }
  LaunchedEffect(active, motion) {
    if (!active || !motion) return@LaunchedEffect
    while (true) withFrameNanos { beat.tick(levels, idHash(), position(), playing()) }
  }
  return beat
}

/**
 * Живой фон плеера: четыре мягких пятна в цветах обложки медленно плавают, а на ударах баса
 * чуть разгораются. Цвета плавно перетекают при смене трека.
 */
@Composable
fun LiveBackground(beat: Beat?, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val spec = tween<Color>(900)
  val base by animateColorAsState(scheme.surface, spec, label = "bg0")
  val c1 by animateColorAsState(scheme.primary, spec, label = "bg1")
  val c2 by animateColorAsState(scheme.tertiary, spec, label = "bg2")
  val c3 by animateColorAsState(scheme.secondary, spec, label = "bg3")
  val c4 by animateColorAsState(scheme.primaryContainer, spec, label = "bg4")
  val time = remember { mutableFloatStateOf(0f) }
  val motion = animationsEnabled()
  LaunchedEffect(motion) {
    if (!motion) return@LaunchedEffect
    val start = withFrameNanos { it }
    while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
  }
  Canvas(modifier) {
    val t = time.floatValue * 0.11f
    val pulse = beat?.bass ?: 0f
    drawRect(base)
    val r = size.maxDimension * (0.62f + 0.05f * pulse)
    val blobs =
      listOf(
        Triple(c1, Offset(0.25f + 0.2f * cos(t), 0.2f + 0.12f * sin(t * 1.3f)), 0.7f),
        Triple(c2, Offset(0.8f + 0.15f * sin(t * 0.9f), 0.35f + 0.18f * cos(t * 0.7f)), 0.55f),
        Triple(c3, Offset(0.3f + 0.25f * sin(t * 0.6f + 2f), 0.75f + 0.12f * cos(t * 1.1f)), 0.5f),
        Triple(c4, Offset(0.75f + 0.2f * cos(t * 0.8f + 1f), 0.9f + 0.08f * sin(t)), 0.6f),
      )
    for ((c, p, a) in blobs) {
      val center = Offset(size.width * p.x, size.height * p.y)
      drawCircle(Brush.radialGradient(listOf(c.copy(alpha = (a + 0.2f * pulse).coerceAtMost(0.95f)), c.copy(alpha = 0f)), center, r), r, center)
    }
    // Вуаль снизу: текст и кнопки читаются на любом фоне.
    drawRect(Brush.verticalGradient(0.35f to base.copy(alpha = 0.25f), 1f to base.copy(alpha = 0.92f)))
  }
}

/**
 * Спектр: симметричные столбики от центра, низкие частоты в середине. Высота — из [Beat].
 */
@Composable
fun Spectrum(beat: Beat, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
  Canvas(modifier) {
    beat.frame // перерисовка каждый кадр
    val n = AudioLevels.BANDS
    val bars = n * 2
    val gap = size.width / bars * 0.35f
    val w = size.width / bars - gap
    for (i in 0 until bars) {
      val band = if (i < n) n - 1 - i else i - n
      val v = beat.bands[band]
      val h = max(w, size.height * v)
      val x = i * (w + gap) + gap / 2
      drawRoundRect(
        color.copy(alpha = 0.35f + 0.5f * v),
        Offset(x, (size.height - h) / 2),
        Size(w, h),
        CornerRadius(w / 2),
      )
    }
  }
}
