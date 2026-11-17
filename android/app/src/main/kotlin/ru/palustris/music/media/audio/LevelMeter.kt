package ru.palustris.music.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Уровни звука для анимации плеера (обложка в такт, спектр). Пишет [LevelProcessor] на аудиопотоке,
 * читает интерфейс на главном. Каждый снимок привязан к позиции трека: звук проходит цепочку
 * заранее (буфер AudioTrack — сотни миллисекунд), а интерфейс спрашивает уровень на текущей
 * позиции плеера. Так пульсация совпадает с тем, что слышно, а не опережает его.
 *
 * Один писатель и один читатель; рваное чтение кольца безопасно (это только картинка).
 */
@Singleton
class AudioLevels @Inject constructor() {
  /** Считать ли спектр: только пока виден полный плеер, иначе зря тратим батарею. */
  @Volatile var enabled = false

  private val ids = IntArray(SIZE)
  private val pos = LongArray(SIZE)
  private val bands = FloatArray(SIZE * BANDS)
  @Volatile private var written = 0L

  internal fun write(id: Int, positionMs: Long, values: FloatArray) {
    val i = (written % SIZE).toInt()
    ids[i] = id
    pos[i] = positionMs
    System.arraycopy(values, 0, bands, i * BANDS, BANDS)
    written++
  }

  /**
   * Полосы спектра (0..1) для трека [id] в позиции [positionMs] → [out]. `false` — данных нет
   * (пауза, трек ещё не дошёл до цепочки): тогда [out] не меняется.
   */
  fun read(id: Int, positionMs: Long, out: FloatArray): Boolean {
    val n = written
    var best = -1
    var bestDelta = Long.MAX_VALUE
    var k = 0L
    // Свежие снимки впереди позиции на буфер AudioTrack: ищем ближайший не позже неё.
    while (k < SIZE && k < n) {
      val i = ((n - 1 - k) % SIZE).toInt()
      if (ids[i] == id) {
        val d = positionMs - pos[i]
        if (d in 0 until bestDelta) {
          bestDelta = d
          best = i
        }
      }
      k++
    }
    if (best < 0 || bestDelta > 250) return false
    System.arraycopy(bands, best * BANDS, out, 0, BANDS)
    return true
  }

  companion object {
    const val BANDS = 16
    /** ~12 секунд снимков по 23 мс. */
    private const val SIZE = 512
  }
}

/**
 * Пропускает звук без изменений и считает спектр окнами по [WINDOW] кадров (Ханн, БПФ),
 * раскладывая его на [AudioLevels.BANDS] логарифмических полос от 40 Гц до 16 кГц.
 */
@OptIn(UnstableApi::class)
class LevelProcessor(private val levels: AudioLevels, private val resolver: StreamResolver = TimelineStreamResolver) : BaseAudioProcessor() {
  private var id = 0
  private var startMs = 0L
  private var framesIn = 0L
  private var rate = 0
  private var ch = 0
  private val window = FloatArray(WINDOW)
  private var filled = 0
  private val re = FloatArray(WINDOW)
  private val im = FloatArray(WINDOW)
  private val out = FloatArray(AudioLevels.BANDS)
  private val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1))).toFloat() }

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    rate = inputAudioFormat.sampleRate
    ch = inputAudioFormat.channelCount
    filled = 0
    val info = resolver.resolve(streamMetadata)
    // Промежуточный flush без метаданных (смена формата) — тот же трек, позицию не трогаем.
    if (info.mediaId == null) return
    id = info.mediaId.hashCode()
    startMs = info.startUs / 1000
    framesIn = 0
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytes = inputBuffer.remaining()
    if (bytes == 0) return
    val output = replaceOutputBuffer(bytes)
    if (!levels.enabled || ch <= 0 || rate <= 0) {
      output.put(inputBuffer)
      output.flip()
      framesIn += bytes / (2 * ch.coerceAtLeast(1))
      return
    }
    val src = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
    output.put(inputBuffer)
    output.flip()
    val frames = bytes / (2 * ch)
    for (f in 0 until frames) {
      var sum = 0
      for (c in 0 until ch) sum += src.getShort().toInt()
      window[filled++] = sum / (ch * 32768f)
      if (filled == WINDOW) {
        analyze(framesIn + f - WINDOW / 2)
        filled = 0
      }
    }
    framesIn += frames
  }

  private fun analyze(centerFrame: Long) {
    for (i in 0 until WINDOW) {
      re[i] = window[i] * hann[i]
      im[i] = 0f
    }
    fft(re, im)
    val binHz = rate.toFloat() / WINDOW
    for (b in 0 until AudioLevels.BANDS) {
      val lo = (EDGES[b] / binHz).toInt().coerceIn(1, WINDOW / 2 - 1)
      val hi = (EDGES[b + 1] / binHz).toInt().coerceIn(lo + 1, WINDOW / 2)
      var acc = 0f
      for (k in lo until hi) acc += re[k] * re[k] + im[k] * im[k]
      val mag = sqrt(acc / (hi - lo)) / (WINDOW / 4f)
      // У музыки энергия падает к высоким частотам примерно на 3 дБ на октаву: без поправки
      // правая часть спектра всегда стояла бы на нуле.
      val db = 20 * log10(mag + 1e-9f) + TILT[b]
      out[b] = ((db + 62f) / 52f).coerceIn(0f, 1f)
    }
    levels.write(id, startMs + centerFrame * 1000 / rate, out)
  }

  override fun onReset() {
    filled = 0
    framesIn = 0
  }

  companion object {
    const val WINDOW = 1024
    /** Границы полос, Гц: логарифмическая шкала от 40 Гц до 16 кГц. */
    private val EDGES = FloatArray(AudioLevels.BANDS + 1) { (40.0 * Math.pow(16000.0 / 40.0, it / AudioLevels.BANDS.toDouble())).toFloat() }
    /** Поправка полосы, дБ: +3 дБ на октаву от 250 Гц (розовый шум даёт ровный спектр). */
    private val TILT = FloatArray(AudioLevels.BANDS) { 3f * log2(sqrt(EDGES[it] * EDGES[it + 1]) / 250f) }

    /** БПФ Кули — Тьюки на месте, длина — степень двойки. */
    fun fft(re: FloatArray, im: FloatArray) {
      val n = re.size
      var j = 0
      for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
          j = j xor bit
          bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
          val tr = re[i]; re[i] = re[j]; re[j] = tr
          val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
      }
      var len = 2
      while (len <= n) {
        val ang = -2 * PI / len
        val wr = cos(ang).toFloat()
        val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
          var cr = 1f
          var ci = 0f
          for (k in 0 until len / 2) {
            val a = i + k
            val b = a + len / 2
            val xr = re[b] * cr - im[b] * ci
            val xi = re[b] * ci + im[b] * cr
            re[b] = re[a] - xr
            im[b] = im[a] - xi
            re[a] += xr
            im[a] += xi
            val nr = cr * wr - ci * wi
            ci = cr * wi + ci * wr
            cr = nr
          }
          i += len
        }
        len = len shl 1
      }
    }
  }
}
