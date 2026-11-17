package ru.palustris.music.media.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Снимок настроек для аудиопотока. Меняется целиком, поэтому поток читает его без блокировок. */
class EqSnapshot(val enabled: Boolean, val gains: FloatArray) {
  val isFlat: Boolean = !enabled || gains.all { it == 0f }

  companion object {
    val OFF = EqSnapshot(false, FloatArray(EqMath.BANDS.size))
  }
}

/**
 * 10-полосный эквалайзер в цепочке ExoPlayer. Свой, а не системный android.media.audiofx.Equalizer:
 * у системного число и частоты полос зависят от телефона, а этот звучит одинаково везде
 * и совпадает с эквалайзером на сайте.
 *
 * Правила из media-playback-footguns: isActive не переопределяем (выключение — ветка внутри
 * queueInput), весь входной буфер съедаем, параметры читаем на каждом буфере,
 * в onFlush чистим только историю фильтров.
 *
 * Формат применяем в onFlush, а не в onConfigure: configure для следующего трека приходит,
 * пока текущий ещё доигрывает, а новый формат вступает в силу только на flush.
 */
@OptIn(UnstableApi::class)
class EqualizerProcessor(private val settings: () -> EqSnapshot) : BaseAudioProcessor() {
  private val bands = EqMath.BANDS.size
  private var sampleRate = 0
  private var channels = 0
  private var applied: EqSnapshot? = null
  private val coef = DoubleArray(bands * 5)
  private var preamp = 1.0
  /** История фильтров: [полоса][канал][x1, x2, y1, y2]. Только для аудиопотока. */
  private var state = DoubleArray(0)

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining == 0) return
    val snap = settings()
    val output = replaceOutputBuffer(remaining)
    if (snap.isFlat) {
      if (applied?.isFlat == false) state.fill(0.0) // включат снова — без «хвоста» старой кривой
      applied = snap
      output.put(inputBuffer)
      output.flip()
      return
    }
    if (snap !== applied) {
      if (applied?.isFlat != false) state.fill(0.0)
      for (b in 0 until bands) EqMath.coefficients(b, snap.gains[b], sampleRate, coef, b * 5)
      preamp = 10.0.pow(EqMath.headroomDb(snap.gains) / 20.0)
      applied = snap
    }

    inputBuffer.order(ByteOrder.nativeOrder())
    output.order(ByteOrder.nativeOrder())
    val ch = channels
    var c = 0
    while (inputBuffer.remaining() >= 2) {
      var x = inputBuffer.getShort() / 32768.0 * preamp
      var b = 0
      while (b < bands) {
        val k = b * 5
        val s = (b * ch + c) * 4
        val y = coef[k] * x + coef[k + 1] * state[s] + coef[k + 2] * state[s + 1] - coef[k + 3] * state[s + 2] - coef[k + 4] * state[s + 3]
        state[s + 1] = state[s]; state[s] = x
        state[s + 3] = state[s + 2]; state[s + 2] = y
        x = y
        b++
      }
      val v = (x * 32768.0).coerceIn(-32768.0, 32767.0)
      output.putShort(v.toInt().toShort())
      c = if (c + 1 == ch) 0 else c + 1
    }
    while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
    output.flip()
  }

  override fun onFlush() {
    if (inputAudioFormat.sampleRate != sampleRate || inputAudioFormat.channelCount != channels) {
      sampleRate = inputAudioFormat.sampleRate
      channels = inputAudioFormat.channelCount
      state = DoubleArray(bands * channels.coerceAtLeast(0) * 4)
      applied = null // коэффициенты зависят от частоты
    }
    state.fill(0.0)
  }

  override fun onReset() {
    state = DoubleArray(0)
    applied = null
    sampleRate = 0
    channels = 0
  }
}
