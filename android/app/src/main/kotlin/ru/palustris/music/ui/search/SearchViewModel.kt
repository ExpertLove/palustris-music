package ru.palustris.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.SearchAll
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

data class SearchUi(val query: String = "", val loading: Boolean = false, val result: SearchAll? = null, val error: String? = null)

@HiltViewModel
class SearchViewModel @Inject constructor(private val api: MusicApi) : ViewModel() {
  private val query = MutableStateFlow("")
  private val _ui = MutableStateFlow(SearchUi())
  val ui: StateFlow<SearchUi> = _ui.asStateFlow()

  init {
    @OptIn(FlowPreview::class)
    viewModelScope.launch {
      query
        .map { it.trim() }
        .debounce(350)
        .distinctUntilChanged()
        // Новый запрос отменяет предыдущий: ответы не приходят вперемешку.
        .collectLatest { q ->
          if (q.length < 2) {
            _ui.value = _ui.value.copy(loading = false, result = null, error = null)
            return@collectLatest
          }
          _ui.value = _ui.value.copy(loading = true, error = null)
          apiCall { api.search(q) }
            .onSuccess { _ui.value = _ui.value.copy(loading = false, result = it) }
            .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }
  }

  fun onQuery(q: String) {
    _ui.value = _ui.value.copy(query = q)
    query.value = q
  }
}
