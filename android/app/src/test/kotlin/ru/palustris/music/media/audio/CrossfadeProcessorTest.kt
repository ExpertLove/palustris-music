package ru.palustris.music.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Процессор гоняем так же, как это делает DefaultAudioSink: configure → flush(метаданные трека) →
 * буферы → queueEndOfStream на границе → configure/flush следующего трека.
 */
class CrossfadeProcessorTest {
  private val rate = 8_000
  private val ch = 2

  private class Track(val uid: String, val mediaId: String, val seconds: Int, val value: Short, val rate: Int = 8_000)

  private val tracks = mutableMapOf<String, Track>()
  private val resolver = StreamResolver { meta ->
    val t = meta.periodUid?.let { tracks[it] } ?: return@StreamResolver StreamInfo.UNKNOWN
    StreamInfo(t.uid, t.mediaId, t.seconds * 1_000_000L, meta.positionOffsetUs)
  }

  private var plan = CrossfadePlan("a", 3000, true)
  private val p = CrossfadeProcessor({ plan }, resolver)
  private val out = ArrayList<Short>()

  private fun meta(uid: String?, startUs: Long = 0) =
    AudioProcessor.StreamMetadata.Builder().setPeriodUid(uid).setPositionOffsetUs(startUs).build()

  private fun start(t: Track, startUs: Long = 0) {
    tracks[t.uid] = t
    p.configure(AudioProcessor.AudioFormat(t.rate, ch, C.ENCODING_PCM_16BIT))
    p.flush(meta(t.uid, startUs))
  }

  private fun feed(t: Track, fromSec: Int = 0, toSec: Int = t.seconds, chunkFrames: Int = 1024) {
    var left = (toSec - fromSec) * t.rate
    while (left > 0) {
      val n = minOf(chunkFrames, left)
      val buf = ByteBuffer.allocateDirect(n * ch * 2).order(ByteOrder.nativeOrder())
      repeat(n * ch) { buf.putShort(t.value) }
      buf.flip()
      p.queueInput(buf)
      assertEquals("вход съеден целиком", 0, buf.remaining())
      drain()
      left -= n
    }
  }

  private fun end() {
    p.queueEndOfStream()
    drain()
    assertTrue(p.isEnded)
  }

  private fun drain() {
    val o = p.output.order(ByteOrder.nativeOrder())
    while (o.remaining() >= 2) out += o.getShort()
  }

  private fun frame(i: Int) = out[i * ch].toInt()

  @Test
  fun tailIsMixedIntoNextTrackWithEqualPower() {
    val a = Track("A", "a", 30, 1000)
    val b = Track("B", "b", 30, 2000)
    start(a)
    feed(a)
    end()
    plan = CrossfadePlan("b", 3000, false)
    start(b)
    feed(b)

    val fade = 3 * rate
    assertEquals("переход съедает длину перехода", (30 + 30) * rate - fade, out.size / ch)
    val mixStart = 27 * rate
    assertEquals(1000, frame(mixStart - 1))
    assertEquals(1000.0, frame(mixStart).toDouble(), 1.0)
    for (k in listOf(fade / 4, fade / 2, 3 * fade / 4)) {
      val angle = PI / 2 * k / fade
      assertEquals(2000 * sin(angle) + 1000 * cos(angle), frame(mixStart + k).toDouble(), 1.5)
    }
    assertEquals(2000, frame(mixStart + fade))
    assertEquals(2000, frame(out.size / ch - 1))
  }

  @Test
  fun repeatedFlushAtBoundaryKeepsTransition() {
    // Так делает DefaultAudioSink на бесшовной границе: flush при перенастройке, затем ещё
    // один drain + flush того же потока, выравнивая время (видно в логах эмулятора).
    val a = Track("A", "a", 30, 1000)
    val b = Track("B", "b", 30, 2000)
    start(a)
    feed(a)
    end()
    start(b)
    p.queueEndOfStream()
    drain()
    p.flush(meta("B"))
    feed(b)
    assertEquals((30 + 30) * rate - 3 * rate, out.size / ch)
    assertEquals((2000 + 1000) * sqrt(0.5), frame(27 * rate + 3 * rate / 2).toDouble(), 2.0)
  }

  @Test
  fun equalPowerKeepsLoudnessOfUncorrelatedTracks() {
    // Для некоррелированных сигналов сумма мощностей sin²+cos² = 1: громкость не проседает посередине.
    for (k in 0..10) {
      val angle = PI / 2 * k / 10
      assertEquals(1.0, sqrt(sin(angle) * sin(angle) + cos(angle) * cos(angle)), 1e-9)
    }
  }

  @Test
  fun tailIsPlayedOutWhenCrossfadeBecomesNotAllowed() {
    val a = Track("A", "a", 30, 1000)
    val b = Track("B", "b", 30, 2000)
    start(a)
    feed(a, 0, 29)
    plan = CrossfadePlan("a", 3000, false) // например, включили «остановить в конце трека»
    feed(a, 29, 30)
    end()
    start(b)
    feed(b)
    assertEquals(60 * rate, out.size / ch)
    assertEquals(1000, frame(30 * rate - 1))
    assertEquals(2000, frame(30 * rate))
  }

  @Test
  fun seekDuringCaptureCancelsTransition() {
    val a = Track("A", "a", 30, 1000)
    val b = Track("B", "b", 30, 2000)
    start(a)
    feed(a, 0, 28) // захват уже идёт
    p.flush(meta(null)) // перемотка: сначала flush без метаданных…
    p.flush(meta("A", 10_000_000)) // …потом с новой позицией
    plan = CrossfadePlan("a", 3000, false)
    feed(a, 10, 30)
    end()
    start(b)
    feed(b, 0, 5)
    // Придержанное до перемотки выброшено (как и весь буфер плеера при перемотке), остальное без смешивания.
    assertEquals(2000, frame(out.size / ch - 5 * rate))
    assertEquals(1000, frame(out.size / ch - 5 * rate - 1))
  }

  @Test
  fun manualSkipDuringMixStopsMixing() {
    val a = Track("A", "a", 30, 1000)
    val b = Track("B", "b", 30, 2000)
    val c = Track("C", "c", 30, 3000)
    start(a)
    feed(a)
    end()
    start(b)
    feed(b, 0, 1) // смешивание идёт
    start(c) // пользователь нажал «следующий»
    feed(c, 0, 1)
    assertEquals(3000, frame(out.size / ch - 1))
    assertEquals(3000, frame(out.size / ch - rate))
  }

  @Test
  fun differentSampleRatesAreResampled() {
    val a = Track("A", "a", 30, 1000, rate = 44_100)
    val b = Track("B", "b", 30, 2000, rate = 48_000)
    start(a)
    feed(a)
    end()
    // Между треками разного формата синк доигрывает старый AudioTrack и делает промежуточные flush:
    // без метаданных и с метаданными прежнего трека (видно в логах эмулятора).
    p.flush(meta(null))
    p.flush(meta("A"))
    plan = CrossfadePlan("b", 3000, false)
    // Второй flush нового трека уточняет стартовую позицию: в первом она бывает неверной.
    start(b, startUs = 30_000_000)
    p.flush(meta("B", 23_000))
    feed(b)
    val total = out.size / ch
    assertEquals(27 * 44_100 + 30 * 48_000, total)
    // Смешивание длится 3 с уже на частоте нового трека.
    val mixStart = 27 * 44_100
    val mid = mixStart + 3 * 48_000 / 2
    assertEquals((2000 + 1000) * sqrt(0.5), frame(mid).toDouble(), 2.0)
    assertEquals(2000, frame(mixStart + 3 * 48_000))
  }

  @Test
  fun lastTrackIsNotCut() {
    plan = CrossfadePlan("a", 3000, true)
    val a = Track("A", "a", 30, 1000)
    start(a)
    feed(a, 0, 29)
    plan = CrossfadePlan("a", 3000, false) // следующего трека нет
    feed(a, 29, 30)
    end()
    assertEquals(30 * rate, out.size / ch)
    assertTrue(out.all { abs(it - 1000) == 0 })
  }
}
