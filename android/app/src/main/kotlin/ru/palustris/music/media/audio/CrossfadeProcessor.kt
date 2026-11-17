package ru.palustris.music.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import timber.log.Timber

/**
 * Что разрешено кроссфейду. Публикует сервис воспроизведения с главного потока (там видны очередь,
 * повтор, таймер сна и буфер следующего трека), процессор читает на аудиопотоке.
 */
class CrossfadePlan(
  /** Из какого трека можно сделать переход: план относится только к нему. */
  val fromMediaId: String?,
  val fadeMs: Int,
  val allowed: Boolean,
) {
  companion object {
    val OFF = CrossfadePlan(null, 0, false)
  }
}

/**
 * Плавный переход между треками внутри одного плеера, с точностью до сэмпла.
 *
 * ExoPlayer при бесшовном воспроизведении подаёт треки в звуковую цепочку друг за другом.
 * Ближе к концу трека процессор начинает придерживать последние [CrossfadePlan.fadeMs]
 * миллисекунд звука в кольцевом буфере (всё, что старше, уходит дальше как обычно).
 * Когда трек кончился и пришёл следующий, придержанный хвост смешивается с началом нового
 * по равномощным кривым: старый cos, новый sin, так что общая громкость не проседает.
 *
 * Почему так, а не двумя плеерами: не нужно менять плеер у MediaSession, делить аудиофокус и
 * синхронизировать очередь. Позиция и смена трека тоже остаются верными: Media3 привязывает начало
 * следующего трека к выведенным кадрам, поэтому переключение трека в интерфейсе совпадает с началом
 * перехода.
 *
 * Если переход в последний момент стал нельзя (последний трек, «остановить в конце трека»,
 * следующий не загрузился), хвост просто выводится как есть, ничего не теряется. Перемотка и
 * ручное переключение сбрасывают переход.
 */
@OptIn(UnstableApi::class)
class CrossfadeProcessor(
  private val plan: () -> CrossfadePlan,
  private val resolver: StreamResolver = TimelineStreamResolver,
) : BaseAudioProcessor() {
  // Текущий поток.
  private var info = StreamInfo.UNKNOWN
  private var rate = 0
  private var ch = 0
  /** Кадров текущего потока, принятых после flush. */
  private var framesIn = 0L

  // Кольцевой буфер последних кадров трека (interleaved PCM16).
  private var ring = ShortArray(0)
  private var ringCap = 0
  private var ringStart = 0
  private var ringCount = 0
  private var capturing = false

  // Хвост закончившегося трека, который ждёт следующий.
  private var tail: ShortArray? = null
  private var tailFrames = 0
  private var tailRate = 0
  private var tailCh = 0
  private var tailUid: Any? = null
  private var tailAt = 0L

  // Смешивание хвоста с началом нового трека.
  private var mixing = false
  private var mixPos = 0
  private var mixFrames = 0

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    val next = resolver.resolve(streamMetadata)
    // Тот же трек с того же места: синк слил цепочку и сбросил её, чтобы сменить скорость.
    // Это не перемотка и не конец трека: захват и смешивание идут дальше, а хвост, который
    // «конец потока» успел отложить, возвращается в кольцо. Иначе при смене скорости в последние
    // секунды трека пропало бы до 12 с звука.
    if (continuesCurrent(next)) {
      if (tail != null && tailUid == info.periodUid && !mixing) restoreCapture()
      Timber.tag(TAG).d("flush %s continues at %dms", next.mediaId, next.startUs / 1000)
      return
    }
    rate = inputAudioFormat.sampleRate
    ch = inputAudioFormat.channelCount
    // Повторный flush того же потока без данных между ними: на границе треков синк сначала
    // перенастраивает цепочку, потом ещё раз сбрасывает её, выравнивая время. Это не перемотка,
    // ничего не сбрасываем, иначе переход пропал бы, не начавшись. Стартовую позицию берём новую:
    // после смены формата первый flush приходит с неверной, второй её уточняет.
    if (next.periodUid != null && next.periodUid == info.periodUid && framesIn == 0L && !capturing) {
      info = next
      return
    }
    // Flush посреди захвата или смешивания — это перемотка или ручное переключение: переход отменяется.
    capturing = false
    ringCount = 0
    ringStart = 0
    if (mixing) dropTail()
    if (tail != null && System.nanoTime() - tailAt > TAIL_TTL_NS) dropTail()
    val waiting = tail
    // Промежуточный flush между треками хвост не трогает. Такой бывает, когда у следующего
    // трека другой формат (44,1 → 48 кГц): синк доигрывает старый AudioTrack и сбрасывается с
    // метаданными прежнего трека или совсем без них.
    val intermediate = waiting != null && (next.periodUid == null || next.periodUid == tailUid)
    if (waiting != null && !intermediate && rate > 0 && ch > 0) {
      mixing = true
      mixPos = 0
      mixFrames = (tailFrames.toLong() * rate / tailRate).toInt().coerceAtLeast(1)
    }
    if (!intermediate) {
      info = next
      framesIn = 0
    }
    Timber.tag(TAG).d("flush %s start=%dms %dHz mix=%s", next.mediaId, next.startUs / 1000, rate, mixing)
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytes = inputBuffer.remaining()
    if (bytes == 0) return
    val output = replaceOutputBuffer(bytes)
    if (ch <= 0 || (!mixing && !capturing && captureStartFrame() == Long.MAX_VALUE)) {
      output.put(inputBuffer)
      output.flip()
      framesIn += bytes / (2 * ch.coerceAtLeast(1))
      return
    }
    inputBuffer.order(ByteOrder.nativeOrder())
    output.order(ByteOrder.nativeOrder())
    val frameBytes = 2 * ch
    while (inputBuffer.remaining() >= frameBytes) {
      when {
        mixing -> mixFrame(inputBuffer, output)
        capturing -> captureFrame(inputBuffer, output)
        else -> {
          val start = captureStartFrame()
          if (framesIn >= start && startCapture()) {
            captureFrame(inputBuffer, output)
          } else {
            // До точки захвата копируем пачкой. Если точка пройдена, а переход пока нельзя,
            // выводим весь буфер и проверяем снова на следующем.
            val left = (inputBuffer.remaining() / frameBytes).toLong()
            val n = if (framesIn >= start) left else minOf(left, start - framesIn)
            copyFrames(inputBuffer, output, n.toInt())
            framesIn += n
            continue
          }
        }
      }
      framesIn++
    }
    while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
    output.flip()
  }

  override fun onQueueEndOfStream() {
    if (!capturing) return
    capturing = false
    val p = plan()
    val keep =
      p.allowed && p.fadeMs > 0 && p.fromMediaId != null && p.fromMediaId == info.mediaId &&
        ringCount.toLong() * 1000 >= rate.toLong() * MIN_TAIL_MS
    Timber.tag(TAG).d("end of %s: tail %dms, %s", info.mediaId, ringCount * 1000L / rate.coerceAtLeast(1), if (keep) "crossfade" else "play out")
    if (keep) {
      // Хвост ждёт следующий трек; выводить нечего.
      val t = ShortArray(ringCount * ch)
      for (i in 0 until ringCount) {
        val src = ((ringStart + i) % ringCap) * ch
        System.arraycopy(ring, src, t, i * ch, ch)
      }
      tail = t
      tailFrames = ringCount
      tailRate = rate
      tailCh = ch
      tailUid = info.periodUid
      tailAt = System.nanoTime()
    } else {
      emitRing()
    }
    ringCount = 0
    ringStart = 0
  }

  override fun onReset() {
    info = StreamInfo.UNKNOWN
    rate = 0
    ch = 0
    framesIn = 0
    ring = ShortArray(0)
    ringCap = 0
    ringCount = 0
    ringStart = 0
    capturing = false
    mixing = false
    dropTail()
  }

  /** Flush того же трека ровно с того кадра, на котором остановился вход. */
  private fun continuesCurrent(next: StreamInfo): Boolean {
    if (next.periodUid == null || next.periodUid != info.periodUid || framesIn == 0L || rate <= 0) return false
    val expectedUs = info.startUs + framesIn * 1_000_000L / rate
    return abs(next.startUs - expectedUs) <= CONTINUE_TOLERANCE_US
  }

  /** Хвост текущего трека снова в кольцо: трек не кончился, захват продолжается. */
  private fun restoreCapture() {
    val t = tail ?: return
    if (tailCh != ch || tailFrames > ringCap || ring.size < tailFrames * ch) return
    System.arraycopy(t, 0, ring, 0, tailFrames * ch)
    ringStart = 0
    ringCount = tailFrames
    capturing = true
    dropTail()
  }

  /** С какого кадра потока начинать придерживать хвост, или MAX_VALUE, если не нужно. */
  private fun captureStartFrame(): Long {
    if (mixing || rate <= 0 || info.periodUid == null || info.durationUs == C.TIME_UNSET) return Long.MAX_VALUE
    val p = plan()
    if (p.fadeMs <= 0 || p.fromMediaId != info.mediaId) return Long.MAX_VALUE
    val leftUs = info.durationUs - info.startUs - (p.fadeMs + LEAD_MS) * 1000L
    return (leftUs.coerceAtLeast(0) * rate / 1_000_000L)
  }

  private fun startCapture(): Boolean {
    val p = plan()
    if (!p.allowed || p.fadeMs <= 0 || p.fromMediaId == null || p.fromMediaId != info.mediaId) return false
    val cap = (p.fadeMs.toLong() * rate / 1000).toInt().coerceAtLeast(1)
    if (ring.size < cap * ch) ring = ShortArray(cap * ch)
    ringCap = cap
    ringStart = 0
    ringCount = 0
    capturing = true
    return true
  }

  /** Кадр в кольцо; если оно полное, самый старый кадр уходит на выход. */
  private fun captureFrame(input: ByteBuffer, output: ByteBuffer) {
    val slot: Int
    if (ringCount == ringCap) {
      val base = ringStart * ch
      for (c in 0 until ch) output.putShort(ring[base + c])
      slot = ringStart
      ringStart = (ringStart + 1) % ringCap
    } else {
      slot = (ringStart + ringCount) % ringCap
      ringCount++
    }
    val base = slot * ch
    for (c in 0 until ch) ring[base + c] = input.getShort()
  }

  private fun mixFrame(input: ByteBuffer, output: ByteBuffer) {
    val t = tail ?: return copyFrames(input, output, 1).also { mixing = false }
    val angle = (PI / 2) * mixPos / mixFrames
    val gIn = sin(angle)
    val gOut = cos(angle)
    // Позиция в хвосте с учётом разной частоты дискретизации.
    val pos = mixPos.toDouble() * tailRate / rate
    val i0 = pos.toInt().coerceAtMost(tailFrames - 1)
    val i1 = (i0 + 1).coerceAtMost(tailFrames - 1)
    val frac = pos - i0
    for (c in 0 until ch) {
      val old = tailSample(t, i0, c) * (1 - frac) + tailSample(t, i1, c) * frac
      val v = input.getShort() * gIn + old * gOut
      output.putShort(v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }
    if (++mixPos >= mixFrames) dropTail()
  }

  private fun tailSample(t: ShortArray, frame: Int, c: Int): Double {
    val base = frame * tailCh
    return when {
      tailCh == ch -> t[base + c].toDouble()
      tailCh == 1 -> t[base].toDouble()
      ch == 1 -> (0 until tailCh).sumOf { t[base + it].toDouble() } / tailCh
      else -> t[base + c % tailCh].toDouble()
    }
  }

  private fun copyFrames(input: ByteBuffer, output: ByteBuffer, frames: Int) {
    val n = frames * 2 * ch
    if (n <= 0) return
    val limit = input.limit()
    input.limit(input.position() + n)
    output.put(input)
    input.limit(limit)
  }

  /** Придержанное выводим как есть (переход не состоялся). */
  private fun emitRing() {
    if (ringCount == 0) return
    val pending = if (hasPendingOutput()) getOutput() else null
    val pendingBytes = pending?.let { ByteArray(it.remaining()).also { b -> it.get(b) } }
    val out = replaceOutputBuffer((pendingBytes?.size ?: 0) + ringCount * ch * 2)
    out.order(ByteOrder.nativeOrder())
    if (pendingBytes != null) out.put(pendingBytes)
    for (i in 0 until ringCount) {
      val base = ((ringStart + i) % ringCap) * ch
      for (c in 0 until ch) out.putShort(ring[base + c])
    }
    out.flip()
  }

  private fun dropTail() {
    tail = null
    tailFrames = 0
    tailUid = null
    mixing = false
    mixPos = 0
    mixFrames = 0
  }

  companion object {
    /** Начинаем придерживать чуть раньше: длина из контейнера бывает на доли секунды больше реальной. */
    const val LEAD_MS = 1000
    /** Короче секунды переход не делаем: хвост просто выводится. */
    const val MIN_TAIL_MS = 1000
    private const val TAG = "Crossfade"
    /**
     * Насколько позиция из flush может расходиться со счётом кадров, чтобы считаться продолжением:
     * синк срезает задержку кодека (до ~50 мс) до нас. Перемотка сюда не попадает: её первый flush
     * идёт с нулевой позицией.
     */
    private const val CONTINUE_TOLERANCE_US = 150_000L
    /**
     * Хвост старше трёх секунд не смешиваем: значит, следующий трек не пришёл сразу (ошибка,
     * пауза ровно на границе), и конец старого трека в начале нового уже неуместен.
     */
    private const val TAIL_TTL_NS = 3_000_000_000L
  }
}
