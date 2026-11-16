package ru.palustris.music.ui.equalizer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import ru.palustris.music.media.eq.EQ_PRESETS
import ru.palustris.music.media.eq.EqMath
import ru.palustris.music.media.eq.EqualizerStore
import ru.palustris.music.ui.common.BackBar

@HiltViewModel
class EqualizerViewModel @Inject constructor(val store: EqualizerStore) : ViewModel()

@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit, vm: EqualizerViewModel = hiltViewModel()) {
  val ui by vm.store.ui.collectAsStateWithLifecycle()
  val scheme = MaterialTheme.colorScheme

  LazyColumn(contentPadding = contentPadding) {
    item { BackBar(onBack) }
    item {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Эквалайзер", style = MaterialTheme.typography.headlineMedium)
          Text(
            if (ui.enabled) "Тяните точки вверх и вниз" else "Выключен: звук как в записи",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
          )
        }
        Switch(checked = ui.enabled, onCheckedChange = vm.store::setEnabled)
      }
    }
    item {
      Surface(
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp)) {
          CurveEditor(
            gains = ui.gains,
            enabled = ui.enabled,
            onGain = vm.store::setGain,
            onCommit = vm.store::commit,
            modifier = Modifier.fillMaxWidth().height(260.dp),
          )
          Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            EqMath.LABELS.forEachIndexed { i, label ->
              Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                val g = ui.gains[i]
                Text(
                  if (g == 0f) "0" else (if (g > 0) "+" else "") + (if (g % 1f == 0f) g.toInt().toString() else g.toString()),
                  style = MaterialTheme.typography.labelSmall,
                  color = if (g == 0f) scheme.onSurfaceVariant.copy(alpha = 0.6f) else scheme.primary,
                )
              }
            }
          }
        }
      }
    }
    item {
      Text("Готовые настройки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
      FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EQ_PRESETS.forEach { p ->
          FilterChip(selected = ui.enabled && ui.presetId == p.id, onClick = { vm.store.selectPreset(p) }, label = { Text(p.title) })
        }
      }
      Text(
        "Эквалайзер работает внутри приложения, поэтому звучит одинаково на любом телефоне и в наушниках, " +
          "и совпадает с эквалайзером на сайте. Чтобы подъём частот не хрипел, общая громкость при этом чуть снижается.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
      )
    }
  }
}

/**
 * Кривая АЧХ с десятью точками-полосами. Точку берут пальцем и тянут по вертикали.
 * Кривая считается по тем же формулам, что и звук, так что видно ровно то, что слышно.
 */
@Composable
private fun CurveEditor(
  gains: List<Float>,
  enabled: Boolean,
  onGain: (Int, Float) -> Unit,
  onCommit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val haptics = LocalHapticFeedback.current
  val current by rememberUpdatedState(gains)
  var active by remember { mutableIntStateOf(-1) }
  val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "eqAlpha")
  val fMin = 20.0
  val fMax = 20_000.0
  fun xOf(f: Double, w: Float) = (ln(f / fMin) / ln(fMax / fMin) * w).toFloat()
  fun bandX(i: Int, w: Float) = xOf(EqMath.BANDS[i].toDouble(), w)
  fun yOf(db: Double, h: Float, pad: Float) = (h / 2 - db / EqMath.MAX_DB * (h / 2 - pad)).toFloat()
  fun dbOf(y: Float, h: Float, pad: Float) = ((h / 2 - y) / (h / 2 - pad) * EqMath.MAX_DB)

  Canvas(
    modifier
      .semantics { contentDescription = "Кривая эквалайзера: " + EqMath.LABELS.zip(gains).joinToString { (l, g) -> "$l Гц ${g.roundToInt()} дБ" } }
      .pointerInput(Unit) {
        val pad = 14.dp.toPx()
        detectDragGestures(
          onDragStart = { pos ->
            active = (0 until EqMath.BANDS.size).minBy { abs(bandX(it, size.width.toFloat()) - pos.x) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
          },
          onDragEnd = {
            active = -1
            onCommit()
          },
          onDragCancel = {
            active = -1
            onCommit()
          },
        ) { change, _ ->
          if (active >= 0) {
            val db = dbOf(change.position.y, size.height.toFloat(), pad)
            val before = current[active].roundToInt()
            onGain(active, db)
            // Лёгкий щелчок на каждом децибеле: чувствуется, насколько сдвинули.
            if (db.coerceIn(-EqMath.MAX_DB, EqMath.MAX_DB).roundToInt() != before) {
              haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
          }
        }
      }
      .pointerInput(Unit) {
        // Двойное касание точки — вернуть полосу в ноль.
        detectTapGestures(onDoubleTap = { pos ->
          val i = (0 until EqMath.BANDS.size).minBy { abs(bandX(it, size.width.toFloat()) - pos.x) }
          onGain(i, 0f)
          onCommit()
        })
      }
  ) {
    val w = size.width
    val h = size.height
    val pad = 14.dp.toPx()
    // Сетка: 0 дБ и ±6 дБ.
    for (db in listOf(-6.0, 0.0, 6.0)) {
      drawLine(
        scheme.outlineVariant.copy(alpha = if (db == 0.0) 0.9f else 0.4f),
        Offset(0f, yOf(db, h, pad)),
        Offset(w, yOf(db, h, pad)),
        strokeWidth = 1.dp.toPx(),
      )
    }
    val g = gains.toFloatArray()
    val path = Path()
    val steps = 160
    for (s in 0..steps) {
      val f = fMin * exp(ln(fMax / fMin) * s / steps)
      val y = yOf(if (enabled) EqMath.responseDb(g, f, withHeadroom = false) else 0.0, h, pad).coerceIn(0f, h)
      val x = w * s / steps
      if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val fill = Path().apply {
      addPath(path)
      lineTo(w, h / 2)
      lineTo(0f, h / 2)
      close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.35f * alpha), scheme.primary.copy(alpha = 0.02f))))
    drawPath(path, scheme.primary.copy(alpha = alpha), style = Stroke(width = 3.dp.toPx()))
    for (i in EqMath.BANDS.indices) {
      val c = Offset(bandX(i, w), yOf(gains[i].toDouble(), h, pad))
      val r = if (i == active) 13.dp.toPx() else 8.dp.toPx()
      drawCircle(scheme.surface, r + 2.dp.toPx(), c)
      drawCircle(scheme.primary.copy(alpha = alpha), r, c)
    }
  }
}
