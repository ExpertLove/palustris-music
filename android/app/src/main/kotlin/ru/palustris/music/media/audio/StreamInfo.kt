package ru.palustris.music.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * Какой трек сейчас идёт через аудиопроцессор.
 *
 * Media3 перед каждым новым потоком (следующий трек очереди, перемотка) вызывает у процессоров
 * `flush(StreamMetadata)` с таймлайном плеера и uid периода. По ним находим трек, его длину и
 * позицию, с которой начался поток. Так процессор точно, до сэмпла, знает границы треков,
 * даже при бесшовном переходе.
 */
data class StreamInfo(
  /** `null` — поток неизвестен (промежуточный flush без метаданных). */
  val periodUid: Any?,
  val mediaId: String?,
  /** Длина трека, мкс, или [C.TIME_UNSET]. */
  val durationUs: Long,
  /** С какой позиции трека начался поток после flush, мкс. */
  val startUs: Long,
) {
  companion object {
    val UNKNOWN = StreamInfo(null, null, C.TIME_UNSET, 0)
  }
}

@OptIn(UnstableApi::class)
fun interface StreamResolver {
  fun resolve(meta: AudioProcessor.StreamMetadata): StreamInfo
}

@OptIn(UnstableApi::class)
object TimelineStreamResolver : StreamResolver {
  override fun resolve(meta: AudioProcessor.StreamMetadata): StreamInfo {
    val uid = meta.periodUid ?: return StreamInfo.UNKNOWN
    val timeline = meta.timeline
    val index = timeline.getIndexOfPeriod(uid)
    if (index == C.INDEX_UNSET) return StreamInfo.UNKNOWN
    val period = timeline.getPeriod(index, Timeline.Period())
    val window = timeline.getWindow(period.windowIndex, Timeline.Window())
    val duration = if (window.durationUs != C.TIME_UNSET) window.durationUs else period.durationUs
    return StreamInfo(uid, window.mediaItem.mediaId, duration, meta.positionOffsetUs.coerceAtLeast(0))
  }
}
