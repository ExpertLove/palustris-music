package ru.palustris.music.media.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerProcessorTest {
  private val rate = 48_000

  private fun sine(freq: Double, seconds: Double = 0.5, amp: Double = 0.25): ShortArray =
    ShortArray((rate * seconds).toInt() * 2) { i -> (sin(2 * PI * freq * (i / 2) / rate) * amp * 32767).toInt().toShort() }

  private fun run(p: EqualizerProcessor, input: ShortArray): ShortArray {
    p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
    p.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
    input.forEach { buf.putShort(it) }
    buf.flip()
    p.queueInput(buf)
    assertEquals("весь вход должен быть съеден", 0, buf.remaining())
    val out = p.output.order(ByteOrder.nativeOrder())
    return ShortArray(out.remaining() / 2) { out.getShort() }
  }

  /** RMS второй половины (без переходного процесса), дБ. */
  private fun rmsDb(s: ShortArray): Double {
    val tail = s.copyOfRange(s.size / 2, s.size)
    val rms = sqrt(tail.sumOf { (it / 32768.0) * (it / 32768.0) } / tail.size)
    return 20 * log10(rms)
  }

  @Test
  fun flatOrDisabledIsBitExactPassThrough() {
    val input = sine(440.0)
    assertTrue(input.contentEquals(run(EqualizerProcessor { EqSnapshot.OFF }, input)))
    val flatOn = EqSnapshot(true, FloatArray(10))
    assertTrue(input.contentEquals(run(EqualizerProcessor { flatOn }, input)))
  }

  @Test
  fun boostingABandRaisesThatFrequencyOnly() {
    val gains = FloatArray(10).also { it[5] = 6f } // +6 дБ на 1 кГц
    val eq = EqSnapshot(true, gains)
    val headroom = EqMath.headroomDb(gains) // -4.5 дБ
    val at1k = rmsDb(run(EqualizerProcessor { eq }, sine(1000.0))) - rmsDb(sine(1000.0))
    val at62 = rmsDb(run(EqualizerProcessor { eq }, sine(62.0))) - rmsDb(sine(62.0))
    assertEquals(6.0 + headroom, at1k, 0.3)
    assertEquals(headroom.toDouble(), at62, 0.3)
  }

  @Test
  fun graphMatchesProcessor() {
    val gains = EQ_PRESETS.first { it.id == "rock" }.gains
    val eq = EqSnapshot(true, gains)
    for (f in listOf(125.0, 1000.0, 8000.0)) {
      val measured = rmsDb(run(EqualizerProcessor { eq }, sine(f))) - rmsDb(sine(f))
      assertEquals("$f Гц", EqMath.responseDb(gains, f, rate), measured, 0.3)
    }
  }

  @Test
  fun hardBoostDoesNotWrapAround() {
    val eq = EqSnapshot(true, FloatArray(10) { 12f })
    val input = sine(100.0, amp = 0.99)
    val out = run(EqualizerProcessor { eq }, input)
    // Перегруз должен упираться в предел, а не переворачивать знак (это слышно как треск):
    // на пиках входа знак выхода совпадает.
    val peaks = input.indices.filter { it > input.size / 2 && abs(input[it].toInt()) > 30000 }
    assertTrue(peaks.isNotEmpty())
    assertTrue(peaks.all { (input[it] > 0) == (out[it] > 0) })
  }
}
