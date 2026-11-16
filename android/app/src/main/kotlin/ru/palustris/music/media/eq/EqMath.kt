package ru.palustris.music.media.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Полосы и формулы эквалайзера — те же, что на сайте (web/src/lib/data.ts, player/engine.ts):
 * 10 полос, крайние — полки, остальные — пики с Q 1.1, ±12 дБ. Формулы — Audio EQ Cookbook (RBJ).
 */
object EqMath {
  val BANDS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
  val LABELS = listOf("31", "62", "125", "250", "500", "1к", "2к", "4к", "8к", "16к")
  const val MAX_DB = 12f
  private const val Q = 1.1

  /** Коэффициенты одной секции, нормированные на a0: b0, b1, b2, a1, a2. */
  fun coefficients(band: Int, gainDb: Float, sampleRate: Int, out: DoubleArray, offset: Int = 0) {
    val f = BANDS[band].toDouble()
    // Выше Найквиста или при нулевом усилении — тождественная секция: размер массивов
    // не меняется, и состояние фильтров не сбрасывается при перетаскивании полосы.
    if (gainDb == 0f || f >= sampleRate / 2.0 - 1) {
      out[offset] = 1.0; out[offset + 1] = 0.0; out[offset + 2] = 0.0; out[offset + 3] = 0.0; out[offset + 4] = 0.0
      return
    }
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2 * PI * f / sampleRate
    val cw = cos(w0)
    val sw = sin(w0)
    val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
    when (band) {
      0 -> { // low shelf, S = 1
        val alpha = sw / 2 * sqrt(2.0)
        val k = 2 * sqrt(a) * alpha
        b0 = a * ((a + 1) - (a - 1) * cw + k)
        b1 = 2 * a * ((a - 1) - (a + 1) * cw)
        b2 = a * ((a + 1) - (a - 1) * cw - k)
        a0 = (a + 1) + (a - 1) * cw + k
        a1 = -2 * ((a - 1) + (a + 1) * cw)
        a2 = (a + 1) + (a - 1) * cw - k
      }
      BANDS.lastIndex -> { // high shelf, S = 1
        val alpha = sw / 2 * sqrt(2.0)
        val k = 2 * sqrt(a) * alpha
        b0 = a * ((a + 1) + (a - 1) * cw + k)
        b1 = -2 * a * ((a - 1) + (a + 1) * cw)
        b2 = a * ((a + 1) + (a - 1) * cw - k)
        a0 = (a + 1) - (a - 1) * cw + k
        a1 = 2 * ((a - 1) - (a + 1) * cw)
        a2 = (a + 1) - (a - 1) * cw - k
      }
      else -> { // peaking
        val alpha = sw / (2 * Q)
        b0 = 1 + alpha * a
        b1 = -2 * cw
        b2 = 1 - alpha * a
        a0 = 1 + alpha / a
        a1 = -2 * cw
        a2 = 1 - alpha / a
      }
    }
    out[offset] = b0 / a0; out[offset + 1] = b1 / a0; out[offset + 2] = b2 / a0
    out[offset + 3] = a1 / a0; out[offset + 4] = a2 / a0
  }

  /**
   * Запас по уровню перед фильтрами: если какая-то полоса поднята, всё заранее чуть тише,
   * чтобы подъём не уходил в клиппинг. Как и на сайте, ровная кривая звучит без изменений.
   */
  fun headroomDb(gains: FloatArray): Float = -(gains.maxOrNull() ?: 0f).coerceAtLeast(0f) * 0.75f

  /**
   * Суммарная АЧХ (дБ) на частоте f. Для графика запас по уровню не учитываем:
   * человеку важна форма кривой относительно точек, а не общее снижение громкости.
   */
  fun responseDb(gains: FloatArray, f: Double, sampleRate: Int = 48_000, withHeadroom: Boolean = true): Double {
    val c = DoubleArray(5)
    val w = 2 * PI * f / sampleRate
    var total = if (withHeadroom) headroomDb(gains).toDouble() else 0.0
    for (b in gains.indices) {
      coefficients(b, gains[b], sampleRate, c)
      // |H(e^jw)| для биквада
      val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
      val nr = c[0] + c[1] * cw + c[2] * c2
      val ni = -(c[1] * sw + c[2] * s2)
      val dr = 1 + c[3] * cw + c[4] * c2
      val di = -(c[3] * sw + c[4] * s2)
      val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
      total += 20 * log10(mag.coerceAtLeast(1e-9))
    }
    return total
  }
}
