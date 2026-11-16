package ru.palustris.music.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

data class HomeUi(
  val loading: Boolean = true,
  val wave: List<Track> = emptyList(),
  val top: List<Track> = emptyList(),
  val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val api: MusicApi) : ViewModel() {
  private val _ui = MutableStateFlow(HomeUi())
  val ui: StateFlow<HomeUi> = _ui.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      _ui.value = _ui.value.copy(loading = true, error = null)
      val wave = async { apiCall { api.wave() } }
      val top = async { apiCall { api.top() } }
      val w = wave.await()
      val t = top.await()
      _ui.value =
        HomeUi(
          loading = false,
          wave = w.getOrDefault(_ui.value.wave),
          top = t.getOrDefault(_ui.value.top),
          error = (w.exceptionOrNull() ?: t.exceptionOrNull())?.message,
        )
    }
  }

  /** Свежая волна при каждом запуске: сервер учитывает то, что слушали только что. */
  suspend fun freshWave(): Result<List<Track>> =
    apiCall { api.wave() }.onSuccess { _ui.value = _ui.value.copy(wave = it) }
}
