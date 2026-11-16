package ru.palustris.music.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Creds
import ru.palustris.music.core.model.EmailBody
import ru.palustris.music.core.model.LoginResp
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.SessionStore

data class AuthUi(val busy: Boolean = false, val error: String? = null, val info: String? = null)

@HiltViewModel
class AuthViewModel @Inject constructor(private val api: MusicApi, private val session: SessionStore) : ViewModel() {
  private val _ui = MutableStateFlow(AuthUi())
  val ui: StateFlow<AuthUi> = _ui.asStateFlow()

  fun login(email: String, password: String) = run { api.login(Creds(email, password)) }

  fun register(email: String, password: String, name: String, consent: Boolean) = run {
    api.register(Creds(email, password, name = name.trim().ifEmpty { null }, consent = consent))
  }

  fun forgot(email: String) {
    if (!email.contains('@')) {
      _ui.value = AuthUi(error = "Впишите почту, и мы пришлём ссылку для нового пароля")
      return
    }
    viewModelScope.launch {
      _ui.value = AuthUi(busy = true)
      _ui.value =
        apiCall { api.forgotPassword(EmailBody(email)) }
          .fold({ AuthUi(info = "Если такой аккаунт есть, письмо уже в пути. Проверьте почту") }, { AuthUi(error = it.message) })
    }
  }

  fun clearError() = _ui.update { it.copy(error = null, info = null) }

  private fun run(call: suspend () -> LoginResp) {
    if (_ui.value.busy) return
    viewModelScope.launch {
      _ui.value = AuthUi(busy = true)
      apiCall(call)
        .onSuccess { session.save(it.email, it.token) }
        .onFailure { _ui.value = AuthUi(error = it.message) }
    }
  }
}
