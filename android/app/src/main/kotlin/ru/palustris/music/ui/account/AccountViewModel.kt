package ru.palustris.music.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Me
import ru.palustris.music.data.AccountRepository
import ru.palustris.music.ui.common.plural

data class AccountUi(
  val loading: Boolean = false,
  val error: String? = null,
  val saving: Boolean = false,
  val verifySent: Boolean = false,
  /** Ошибка в диалоге смены пароля: показываем прямо в нём. */
  val passwordError: String? = null,
  val passwordBusy: Boolean = false,
  val deleting: Boolean = false,
)

@HiltViewModel
class AccountViewModel @Inject constructor(private val repo: AccountRepository) : ViewModel() {
  val me: StateFlow<Me?> = repo.me

  private val _ui = MutableStateFlow(AccountUi())
  val ui: StateFlow<AccountUi> = _ui.asStateFlow()

  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  /** Пароль сменился: диалог закрывается. */
  private val _passwordChanged = Channel<Unit>(Channel.CONFLATED)
  val passwordChanged = _passwordChanged.receiveAsFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      _ui.update { it.copy(loading = true, error = null) }
      val r = repo.refresh()
      _ui.update { it.copy(loading = false, error = r.exceptionOrNull()?.message) }
    }
  }

  fun save(name: String, genres: List<String>) {
    if (_ui.value.saving) return
    viewModelScope.launch {
      _ui.update { it.copy(saving = true) }
      repo.saveProfile(name, genres).fold({ _messages.send("Сохранено") }, { _messages.send(it.message ?: "Не сохранилось") })
      _ui.update { it.copy(saving = false) }
    }
  }

  fun resendVerification() {
    viewModelScope.launch {
      repo
        .resendVerification()
        .fold(
          { verified ->
            _ui.update { it.copy(verifySent = true) }
            _messages.send(if (verified) "Почта уже подтверждена" else "Письмо отправлено, проверьте почту")
          },
          { _messages.send(it.message ?: "Не отправилось") },
        )
    }
  }

  fun changePassword(old: String, new: String) {
    if (_ui.value.passwordBusy) return
    viewModelScope.launch {
      _ui.update { it.copy(passwordBusy = true, passwordError = null) }
      repo
        .changePassword(old, new)
        .fold(
          { n ->
            _ui.update { it.copy(passwordBusy = false) }
            _passwordChanged.send(Unit)
            _messages.send(
              if (n > 0) "Пароль изменён. Вышли на других устройствах: ${plural(n, "устройство", "устройства", "устройств")}"
              else "Пароль изменён"
            )
          },
          { e -> _ui.update { it.copy(passwordBusy = false, passwordError = e.message) } },
        )
    }
  }

  fun clearPasswordError() = _ui.update { it.copy(passwordError = null) }

  fun logoutOthers() {
    viewModelScope.launch {
      repo
        .logoutOthers()
        .fold(
          { n -> _messages.send(if (n > 0) "Вышли на других устройствах: $n" else "Других устройств нет") },
          { _messages.send(it.message ?: "Не получилось") },
        )
    }
  }

  fun deleteAccount(onDeleted: () -> Unit) {
    if (_ui.value.deleting) return
    viewModelScope.launch {
      _ui.update { it.copy(deleting = true) }
      repo
        .deleteAccount()
        .fold(
          {
            _ui.update { it.copy(deleting = false) }
            onDeleted()
          },
          { e ->
            _ui.update { it.copy(deleting = false) }
            _messages.send(e.message ?: "Не получилось удалить")
          },
        )
    }
  }
}
