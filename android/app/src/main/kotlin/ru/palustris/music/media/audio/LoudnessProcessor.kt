package ru.palustris.music.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Выравнивание громкости: у каждого трека свой множитель (см. [LoudnessStore.gain]).
 *
 * Множитель меняется ровно на границе треков, потому что процессор узнаёт о новом треке
 * из `flush(StreamMetadata)`. Если громкость трека пришла с сервера, когда он уже играет,
 * громкость плавно доезжает до нужной за секунду, без скачка.
 *
 * Стоит первым в цепочке: эквалайзер и кроссфейд получают уже выровненный звук.
 */
@OptIn(UnstableApi::class)
class LoudnessProcessor(
  private val gainFor: (mediaId: String?) -> Float,
  private val resolver: StreamResolver = TimelineStreamResolver,
) : BaseAudioProcessor() {
  private var mediaId: String? = null
  private var channels = 0
  private var sampleRate = 0
  private var current = 1f
  private var target = 1f
  /** На сколько меняется множитель за кадр, пока идёт плавный переход. */
  private var step = 0f

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    channels = inputAudioFormat.channelCount.coerceAtLeast(1)
    sampleRate = inputAudioFormat.sampleRate
    val info = resolver.resolve(streamMetadata)
    // Промежуточный flush без метаданных: трек тот же, громкость не трогаем.
    if (info.periodUid == null) return
    mediaId = info.mediaId
    target = gainFor(mediaId)
    current = target
    step = 0f
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining == 0) return
    val wanted = gainFor(mediaId)
    if (wanted != target) {
      target = wanted
      step = abs(target - current) / (sampleRate.coerceAtLeast(1) * RAMP_SECONDS)
    }
    val output = replaceOutputBuffer(remaining)
    if (current == 1f && target == 1f) {
      output.put(inputBuffer)
      output.flip()
      return
    }
    inputBuffer.order(ByteOrder.nativeOrder())
    output.order(ByteOrder.nativeOrder())
    var g = current
    var c = 0
    while (inputBuffer.remaining() >= 2) {
      val v = (inputBuffer.getShort() * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      output.putShort(v.toShort())
      if (++c == channels) {
        c = 0
        if (g != target) g = if (g < target) (g + step).coerceAtMost(target) else (g - step).coerceAtLeast(target)
      }
    }
    while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
    current = g
    output.flip()
  }

  override fun onReset() {
    mediaId = null
    current = 1f
    target = 1f
    step = 0f
    channels = 0
    sampleRate = 0
  }

  private companion object {
    const val RAMP_SECONDS = 1f
  }
}
