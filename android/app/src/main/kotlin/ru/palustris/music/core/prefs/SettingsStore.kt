package ru.palustris.music.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.palustris.music.core.AppScope

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

/** Качество скачанных треков. `param` уходит в `?q=` запроса потока. */
enum class DownloadQuality(val param: String?, val title: String, val note: String, val mbPerMinute: Float) {
  Compact("o64", "Экономно", "Opus 64 кбит/с. Почти без потерь на слух, места вдвое меньше", 0.5f),
  Balanced("o96", "Сбалансированно", "Opus 96 кбит/с", 0.72f),
  Original(null, "Как на сервере", "AAC 128 кбит/с, без перекодирования", 0.96f),
}

data class Settings(
  /** Лимит кэша прослушанных треков. Скачанные в него не входят и сами не удаляются. */
  val cacheLimitMb: Int = DEFAULT_CACHE_MB,
  val quality: DownloadQuality = DownloadQuality.Compact,
  val wifiOnly: Boolean = false,
) {
  companion object {
    const val DEFAULT_CACHE_MB = 256
    val CACHE_OPTIONS_MB = listOf(128, 256, 512, 1024, 2048)
  }
}

@Singleton
class SettingsStore
@Inject
constructor(@ApplicationContext private val context: Context, @AppScope scope: CoroutineScope) {
  private val cacheKey = intPreferencesKey("cache_limit_mb")
  private val qualityKey = stringPreferencesKey("download_quality")
  private val wifiKey = booleanPreferencesKey("wifi_only")

  /** `null`, пока DataStore не прочитан: кэш не должен чистить себя по лимиту по умолчанию. */
  val settings: StateFlow<Settings?> =
    context.settingsStore.data
      .map { p ->
        Settings(
          cacheLimitMb = p[cacheKey] ?: Settings.DEFAULT_CACHE_MB,
          quality = DownloadQuality.entries.firstOrNull { it.name == p[qualityKey] } ?: DownloadQuality.Compact,
          wifiOnly = p[wifiKey] ?: false,
        )
      }
      .stateIn(scope, SharingStarted.Eagerly, null)

  val current: Settings
    get() = settings.value ?: Settings()

  suspend fun setCacheLimit(mb: Int) = context.settingsStore.edit { it[cacheKey] = mb }

  suspend fun setQuality(q: DownloadQuality) = context.settingsStore.edit { it[qualityKey] = q.name }

  suspend fun setWifiOnly(on: Boolean) = context.settingsStore.edit { it[wifiKey] = on }
}
