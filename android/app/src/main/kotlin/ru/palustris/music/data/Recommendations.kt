package ru.palustris.music.data

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.core.prefs.WaveSettings

/**
 * Волна и радио с учётом настроек и скрытого. Одно место для главной, плеера, Android Auto и виджета:
 * сервер уже убирает скрытое, а здесь — то, что скрыли только что и ещё не отправили.
 */
@Singleton
class Recommendations
@Inject
constructor(private val api: MusicApi, private val settings: SettingsStore, private val taste: TasteRepository) {
  suspend fun wave(w: WaveSettings = settings.current.wave): Result<List<Track>> =
    apiCall {
        api.wave(
          mode = w.mode.ifEmpty { null },
          mood = w.mood.ifEmpty { null },
          activity = w.activity.ifEmpty { null },
          region = if (w.mode == "popular") (settings.current.region ?: Locale.getDefault().country).ifEmpty { null } else null,
        )
      }
      .map { it.filterNot(taste::isHidden) }

  /** Похожие на трек; сам трек в ответ не входит. */
  suspend fun radio(seed: Track): Result<List<Track>> =
    apiCall { api.radio(seed.id, seed.title, seed.artist) }.map { list -> list.filterNot { it.id == seed.id || taste.isHidden(it) } }

  suspend fun artistRadio(artistId: String?, name: String): Result<List<Track>> =
    apiCall { api.artistRadio(artistId?.takeIf { it.startsWith("UC") }, name) }.map { it.filterNot(taste::isHidden) }
}
