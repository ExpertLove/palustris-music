package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.ChangePasswordBody
import ru.palustris.music.core.model.Me
import ru.palustris.music.core.model.ProfileBody
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

/**
 * Профиль: имя, жанры, подтверждена ли почта, сколько устройств. Последняя копия лежит на
 * телефоне, чтобы главная здоровалась по имени и без интернета.
 */
@Singleton
class AccountRepository
@Inject
constructor(private val api: MusicApi, private val files: OfflineFiles, @AppScope private val scope: CoroutineScope) {
  private val _me = MutableStateFlow<Me?>(null)
  val me: StateFlow<Me?> = _me.asStateFlow()

  init {
    scope.launch { if (_me.value == null) _me.value = files.read(FILE, Me.serializer()) }
  }

  suspend fun refresh(): Result<Me> =
    apiCall { api.me() }.onSuccess {
      _me.value = it
      files.write(FILE, Me.serializer(), it)
    }

  suspend fun saveProfile(name: String, genres: List<String>): Result<Unit> =
    apiCall { api.setProfile(ProfileBody(name.trim(), genres)) }.onSuccess { refresh() }

  /** Остальные устройства после смены пароля выходят сами; возвращает, сколько их было. */
  suspend fun changePassword(old: String, new: String): Result<Int> =
    apiCall { api.changePassword(ChangePasswordBody(old, new)) }.map { it.loggedOut }.onSuccess { refresh() }

  suspend fun logoutOthers(): Result<Int> = apiCall { api.logoutOthers() }.map { it.removed }.onSuccess { refresh() }

  /** `true`, если почта уже подтверждена и письмо не нужно. */
  suspend fun resendVerification(): Result<Boolean> =
    apiCall { api.resendVerification() }.map { it.verified }.onSuccess { if (it) refresh() }

  suspend fun deleteAccount(): Result<Unit> = apiCall { api.deleteAccount() }

  fun clear() {
    _me.value = null
  }

  private companion object {
    const val FILE = "me.json"
  }
}
