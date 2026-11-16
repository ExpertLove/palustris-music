package ru.palustris.music.media

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.AppScope

sealed interface SleepState {
  data object Off : SleepState

  /** Когда остановить, по SystemClock.elapsedRealtime(). */
  data class At(val endsAt: Long) : SleepState

  data object EndOfTrack : SleepState
}

/**
 * Таймер сна. Живёт в процессе, а не в экране, поэтому срабатывает и при выключенном экране.
 * Плеер подключает сервис воспроизведения. В конце громкость плавно уходит в ноль за 10 секунд,
 * затем пауза, и громкость плеера возвращается обратно (системную громкость не трогаем).
 */
@Singleton
class SleepTimer @Inject constructor(@AppScope private val scope: CoroutineScope) {
  private val _state = MutableStateFlow<SleepState>(SleepState.Off)
  val state: StateFlow<SleepState> = _state.asStateFlow()

  @Volatile var player: ExoPlayer? = null
  private var job: Job? = null

  fun start(minutes: Int) {
    cancel()
    val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
    _state.value = SleepState.At(endsAt)
    job =
      scope.launch(Dispatchers.Main) {
        delay((endsAt - SystemClock.elapsedRealtime() - FADE_MS).coerceAtLeast(0))
        val p = player
        if (p != null && p.isPlaying) {
          val steps = 40
          repeat(steps) { i ->
            p.volume = 1f - (i + 1).toFloat() / steps
            delay(FADE_MS / steps)
          }
          p.pause()
          p.volume = 1f
        }
        _state.value = SleepState.Off
      }
  }

  fun stopAtEndOfTrack() {
    cancel()
    player?.pauseAtEndOfMediaItems = true
    _state.value = SleepState.EndOfTrack
  }

  /** Сервис сообщает, что трек закончился и плеер встал на паузу. */
  fun onTrackEndedPause() {
    if (_state.value == SleepState.EndOfTrack) cancel()
  }

  fun cancel() {
    job?.cancel()
    job = null
    player?.let {
      it.pauseAtEndOfMediaItems = false
      it.volume = 1f
    }
    _state.value = SleepState.Off
  }

  private companion object {
    const val FADE_MS = 10_000L
  }
}
