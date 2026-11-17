package ru.palustris.music.media.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спектр для анимации плеера: БПФ и кольцо снимков, привязанных к позиции трека. */
class LevelMeterTest {
  @Test
  fun fftFindsTheSineBin() {
    val n = 1024
    val re = FloatArray(n) { sin(2 * PI * 64 * it / n).toFloat() }
    val im = FloatArray(n)
    LevelProcessor.fft(re, im)
    val mags = (0 until n / 2).map { hypot(re[it], im[it]) }
    assertEquals(64, mags.indices.maxBy { mags[it] })
    // Синус без окна ровно на бине: амплитуда N/2, соседи — ноль.
    assertEquals(n / 2f, mags[64], 0.5f)
    assertTrue(mags[10] < 0.01f)
  }

  @Test
  fun levelsAreReadAtThePlayerPosition() {
    val levels = AudioLevels()
    val a = FloatArray(AudioLevels.BANDS) { 0.1f }
    val b = FloatArray(AudioLevels.BANDS) { 0.9f }
    // Цепочка обогнала динамик: снимки на 1000 и 1300 мс уже есть, а играет 1100 мс.
    levels.write(7, 1000, a)
    levels.write(7, 1300, b)
    val out = FloatArray(AudioLevels.BANDS)
    assertTrue(levels.read(7, 1100, out))
    assertEquals(0.1f, out[0], 0f)
    assertTrue(levels.read(7, 1320, out))
    assertEquals(0.9f, out[0], 0f)
  }

  @Test
  fun otherTrackOrStaleSnapshotGivesNothing() {
    val levels = AudioLevels()
    levels.write(7, 1000, FloatArray(AudioLevels.BANDS) { 0.5f })
    val out = FloatArray(AudioLevels.BANDS) { -1f }
    assertFalse(levels.read(8, 1000, out))
    // Пауза или перемотка далеко вперёд: старый снимок не показываем.
    assertFalse(levels.read(7, 5000, out))
    assertTrue(out.all { abs(it + 1f) < 1e-6f })
  }
}
