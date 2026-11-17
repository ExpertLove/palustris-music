package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.Mix
import ru.palustris.music.core.model.Mixes
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

/**
 * Миксы дня, «Забытое любимое» и «На повторе». Сервер пересчитывает их раз в сутки; копия на
 * телефоне (`mixes.json`) нужна, чтобы микс открывался и без сети.
 */
@Singleton
class MixesRepository
@Inject
constructor(private val api: MusicApi, private val files: OfflineFiles, @AppScope scope: CoroutineScope) {
  private val _mixes = MutableStateFlow<Mixes?>(null)
  val mixes: StateFlow<Mixes?> = _mixes.asStateFlow()

  init {
    scope.launch { if (_mixes.value == null) files.read(FILE, Mixes.serializer())?.let { _mixes.value = it } }
  }

  suspend fun refresh(): Result<Mixes> =
    apiCall { api.mixes() }.onSuccess {
      _mixes.value = it
      files.write(FILE, Mixes.serializer(), it)
    }

  fun mix(id: String): Mix? = _mixes.value?.mixes?.firstOrNull { it.id == id }

  fun clear() {
    _mixes.value = null
  }

  private companion object {
    const val FILE = "mixes.json"
  }
}
