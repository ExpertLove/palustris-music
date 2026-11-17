package ru.palustris.music.media.audio

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.model.Loudness
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.prefs.SettingsStore
import timber.log.Timber

/** Сколько убавить или прибавить трек, чтобы все звучали на одной громкости. */
object LoudnessMath {
  /** Как у Spotify и YouTube. */
  const val TARGET_LUFS = -14f
  /** Тихие треки поднимаем не больше чем на 6 дБ и так, чтобы пик остался ниже −1 dBTP: без клиппинга. */
  const val MAX_BOOST_DB = 6f
  const val PEAK_CEILING_DB = -1f
  /**
   * Громкость ещё не посчитана. Большинство современных треков звучат около −9 LUFS, так что
   * «−5 дБ» обычно близко к правде, и когда точное значение придёт, громкость почти не сдвинется.
   */
  const val UNKNOWN_DB = -5f

  fun gainDb(l: Loudness?): Float {
    // Значение вне разумных пределов — испорченная запись, а не «очень громкий трек»: не корректируем по ней.
    if (l == null || !l.lufs.isFinite() || l.lufs !in -60f..0f || !l.peak.isFinite() || l.peak !in -60f..6f) {
      return UNKNOWN_DB
    }
    var g = TARGET_LUFS - l.lufs
    if (g > 0) g = min(min(g, MAX_BOOST_DB), PEAK_CEILING_DB - l.peak).coerceAtLeast(0f)
    return g.coerceIn(-20f, MAX_BOOST_DB)
  }

  fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
}

/**
 * Громкость треков для выравнивания. Сервер считает её по EBU R128 после загрузки трека
 * (`POST /api/loudness`), здесь значения кэшируются в памяти и в файле, чтобы скачанные треки
 * выравнивались и без интернета.
 *
 * [gain] вызывается на аудиопотоке на каждом буфере: только чтение из ConcurrentHashMap.
 */
@Singleton
class LoudnessStore
@Inject
constructor(
  @ApplicationContext context: Context,
  private val api: MusicApi,
  private val json: Json,
  settings: SettingsStore,
  @AppScope private val scope: CoroutineScope,
) {
  @Serializable private class Entry(val lufs: Float, val peak: Float, val at: Long)

  private val file = File(context.filesDir, "loudness.json")
  private val entries = ConcurrentHashMap<String, Entry>()
  /** Множитель по id, чтобы аудиопоток не считал степени. */
  private val gains = ConcurrentHashMap<String, Float>()
  private val unknownGain = LoudnessMath.dbToLinear(LoudnessMath.UNKNOWN_DB)

  @Volatile var enabled = true
    private set

  // Повторы для треков, которых у сервера ещё нет: через 4, 8, 16 с… до 10 минут.
  private val nextTry = ConcurrentHashMap<String, Long>()
  private val tries = ConcurrentHashMap<String, Int>()
  private var saveJob: Job? = null

  init {
    scope.launch(Dispatchers.IO) { load() }
    scope.launch { settings.settings.filterNotNull().collect { enabled = it.normalize } }
  }

  /** Линейный множитель для трека. 1 — выравнивание выключено. */
  fun gain(mediaId: String?): Float {
    if (!enabled || mediaId == null) return 1f
    return gains[mediaId] ?: unknownGain
  }

  fun isKnown(mediaId: String): Boolean = entries.containsKey(mediaId)

  /** Спросить у сервера громкость треков, которых ещё нет. Можно звать часто: лишнего не спросит. */
  fun request(ids: Collection<String>) {
    val now = SystemClock.elapsedRealtime()
    val want = ids.asSequence().distinct().filter { !entries.containsKey(it) && (nextTry[it] ?: 0L) <= now }.take(100).toList()
    if (want.isEmpty()) return
    for (id in want) {
      val n = (tries[id] ?: 0) + 1
      tries[id] = n
      nextTry[id] = now + (4_000L shl (n - 1).coerceAtMost(8)).coerceAtMost(600_000L)
    }
    scope.launch(Dispatchers.IO) {
      runCatching { api.loudness(IdsBody(want)) }
        .onSuccess { got ->
          if (got.isEmpty()) return@onSuccess
          val at = System.currentTimeMillis() / 1000
          got.forEach { (id, l) -> put(id, Entry(l.lufs, l.peak, at)) }
          scheduleSave()
          Timber.d("loudness: %d of %d", got.size, want.size)
        }
        .onFailure { Timber.d(it, "loudness request failed") }
    }
  }

  private fun put(id: String, e: Entry) {
    entries[id] = e
    gains[id] = LoudnessMath.dbToLinear(LoudnessMath.gainDb(Loudness(e.lufs, e.peak)))
    tries.remove(id)
    nextTry.remove(id)
  }

  private fun load() {
    runCatching {
      if (!file.exists()) return
      val map = json.decodeFromString<Map<String, Entry>>(file.readText())
      map.forEach { (id, e) -> if (!entries.containsKey(id)) put(id, e) }
    }.onFailure { Timber.w(it, "loudness cache unreadable") }
  }

  private fun scheduleSave() {
    if (saveJob?.isActive == true) return
    saveJob =
      scope.launch(Dispatchers.IO) {
        delay(3_000)
        runCatching {
          // Держим не больше MAX_ENTRIES самых свежих.
          val all = entries.entries.sortedByDescending { it.value.at }.take(MAX_ENTRIES).associate { it.key to it.value }
          val tmp = File(file.parentFile, file.name + ".tmp")
          tmp.writeText(json.encodeToString(all))
          tmp.renameTo(file)
        }.onFailure { Timber.w(it, "loudness cache not saved") }
      }
  }

  private companion object {
    const val MAX_ENTRIES = 20_000
  }
}
