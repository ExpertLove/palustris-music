package ru.palustris.music.media.eq

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.palustris.music.core.AppScope

private val Context.eqStore: DataStore<Preferences> by preferencesDataStore("equalizer")

data class EqPreset(val id: String, val title: String, val gains: FloatArray)

data class EqUi(val enabled: Boolean = false, val presetId: String = "flat", val gains: List<Float> = List(10) { 0f })

/** Те же пресеты, что на сайте. */
val EQ_PRESETS =
  listOf(
    EqPreset("flat", "Ровно", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("bass", "Бас", floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("bass-max", "Много баса", floatArrayOf(9f, 8f, 6f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("rock", "Рок", floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    EqPreset("pop", "Поп", floatArrayOf(-1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1f, -1f)),
    EqPreset("electronic", "Электроника", floatArrayOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f)),
    EqPreset("hiphop", "Хип-хоп", floatArrayOf(5f, 4f, 1f, 3f, -1f, -1f, 1f, -1f, 2f, 3f)),
    EqPreset("vocal", "Голос", floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    EqPreset("jazz", "Джаз", floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    EqPreset("classical", "Классика", floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    EqPreset("acoustic", "Акустика", floatArrayOf(4f, 3f, 2f, 1f, 2f, 2f, 3f, 3f, 3f, 2f)),
    EqPreset("treble", "Высокие", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 6f, 7f)),
    EqPreset("night", "Тихо ночью", floatArrayOf(5f, 4f, 2f, 0f, -1f, 0f, -1f, -2f, 3f, 1f)),
  )

/**
 * Настройки эквалайзера. Аудиопоток читает [snapshot] на каждом буфере, поэтому изменения
 * слышны сразу, посреди трека.
 */
@Singleton
class EqualizerStore
@Inject
constructor(@ApplicationContext private val context: Context, @AppScope private val scope: CoroutineScope) {
  private val enabledKey = booleanPreferencesKey("enabled")
  private val presetKey = stringPreferencesKey("preset")
  private val gainsKey = stringPreferencesKey("gains")

  @Volatile var snapshot: EqSnapshot = EqSnapshot.OFF
    private set

  private val _ui = MutableStateFlow(EqUi())
  val ui: StateFlow<EqUi> = _ui.asStateFlow()

  init {
    scope.launch {
      val p = context.eqStore.data.first()
      val gains =
        p[gainsKey]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == EqMath.BANDS.size }
          ?: List(EqMath.BANDS.size) { 0f }
      apply(EqUi(p[enabledKey] ?: false, p[presetKey] ?: "flat", gains), persist = false)
    }
  }

  fun setEnabled(on: Boolean) = apply(_ui.value.copy(enabled = on))

  fun selectPreset(p: EqPreset) = apply(_ui.value.copy(enabled = true, presetId = p.id, gains = p.gains.toList()))

  fun setGain(band: Int, db: Float) {
    val g = _ui.value.gains.toMutableList()
    g[band] = (Math.round(db.coerceIn(-EqMath.MAX_DB, EqMath.MAX_DB) * 2) / 2f)
    apply(_ui.value.copy(enabled = true, presetId = "custom", gains = g), persist = false)
  }

  /** Перетаскивание закончилось: сохраняем один раз, а не на каждый кадр. */
  fun commit() = apply(_ui.value)

  private fun apply(ui: EqUi, persist: Boolean = true) {
    _ui.value = ui
    snapshot = EqSnapshot(ui.enabled, ui.gains.toFloatArray())
    if (persist) {
      scope.launch {
        context.eqStore.edit {
          it[enabledKey] = ui.enabled
          it[presetKey] = ui.presetId
          it[gainsKey] = ui.gains.joinToString(",")
        }
      }
    }
  }
}
