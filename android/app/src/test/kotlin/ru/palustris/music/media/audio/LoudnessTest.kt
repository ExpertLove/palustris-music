package ru.palustris.music.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.palustris.music.core.model.Loudness

class LoudnessTest {
  @Test
  fun loudTracksAreTurnedDown() {
    assertEquals(-5.2f, LoudnessMath.gainDb(Loudness(-8.8f, 1.2f)), 0.01f)
  }

  @Test
  fun quietTracksAreBoostedOnlyBelowPeakCeiling() {
    // −20 LUFS хочет +6 дБ, но пик −3 dBTP разрешает только +2.
    assertEquals(2f, LoudnessMath.gainDb(Loudness(-20f, -3f)), 0.01f)
    // Пик позволяет, но больше +6 дБ не поднимаем.
    assertEquals(6f, LoudnessMath.gainDb(Loudness(-30f, -20f)), 0.01f)
    // Пик уже у потолка: не поднимаем совсем.
    assertEquals(0f, LoudnessMath.gainDb(Loudness(-16f, 0f)), 0.01f)
  }

  @Test
  fun brokenValuesAreIgnoredNotClamped() {
    assertEquals(LoudnessMath.UNKNOWN_DB, LoudnessMath.gainDb(Loudness(-70f, -50f)))
    assertEquals(LoudnessMath.UNKNOWN_DB, LoudnessMath.gainDb(Loudness(Float.NaN, 0f)))
    assertEquals(LoudnessMath.UNKNOWN_DB, LoudnessMath.gainDb(null))
  }

  @Test
  fun processorAppliesGainPerTrackAndRampsLateValues() {
    val rate = 8_000
    val gains = mutableMapOf("a" to 0.5f, "b" to 1f)
    val p = LoudnessProcessor({ id -> gains[id] ?: 1f }) { meta ->
      StreamInfo(meta.periodUid, meta.periodUid as String?, 30_000_000, 0)
    }
    fun play(id: String, frames: Int): ShortArray {
      p.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
      p.flush(AudioProcessor.StreamMetadata.Builder().setPeriodUid(id).build())
      return feed(p, frames)
    }
    assertEquals(5000, play("a", 100).last().toInt())
    // Новый трек сразу со своей громкостью, без «наследства» прошлого.
    assertEquals(10000, play("b", 100).first().toInt())
    // Громкость пришла посреди трека: доезжает за секунду, без скачка.
    gains["b"] = 0.5f
    val ramp = feed(p, rate)
    assertEquals(10000.0, ramp.first().toDouble(), 10.0)
    assertEquals(7500.0, ramp[rate / 2].toDouble(), 20.0)
    assertEquals(5000, feed(p, 10).last().toInt())
  }

  private fun feed(p: LoudnessProcessor, frames: Int): ShortArray {
    val buf = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
    repeat(frames) { buf.putShort(10000) }
    buf.flip()
    p.queueInput(buf)
    val o = p.output.order(ByteOrder.nativeOrder())
    return ShortArray(o.remaining() / 2) { o.getShort() }
  }
}
