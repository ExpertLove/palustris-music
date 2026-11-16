package ru.palustris.music.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.palustris.music.core.AppScope

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore("session")

sealed interface Session {
  data object Loading : Session

  data object LoggedOut : Session

  data class LoggedIn(val email: String, val token: String) : Session
}

/**
 * Токен входа. Хранится в DataStore, а последнее значение держим в памяти: его синхронно
 * читает OkHttp-перехватчик, в том числе на потоках ExoPlayer и загрузчика.
 */
@Singleton
class SessionStore
@Inject
constructor(@ApplicationContext private val context: Context, @AppScope private val scope: CoroutineScope) {
  private val tokenKey = stringPreferencesKey("token")
  private val emailKey = stringPreferencesKey("email")
  private val loaded = CompletableDeferred<Unit>()

  private val _session = MutableStateFlow<Session>(Session.Loading)
  val session: StateFlow<Session> = _session.asStateFlow()

  @Volatile private var token: String? = null

  init {
    scope.launch {
      context.sessionStore.data.collect { p ->
        val t = p[tokenKey]
        token = t
        _session.value = if (t.isNullOrEmpty()) Session.LoggedOut else Session.LoggedIn(p[emailKey].orEmpty(), t)
        loaded.complete(Unit)
      }
    }
  }

  /**
   * Токен для запроса. Сервис воспроизведения может стартовать раньше, чем прочитан
   * DataStore (например, по кнопке на гарнитуре), поэтому один раз ждём загрузки.
   * Вызывается только с фоновых потоков OkHttp.
   */
  fun tokenBlocking(): String? {
    if (!loaded.isCompleted) runBlocking { withTimeoutOrNull(3_000) { loaded.await() } }
    return token
  }

  suspend fun save(email: String, token: String) {
    this.token = token
    context.sessionStore.edit {
      it[tokenKey] = token
      it[emailKey] = email
    }
  }

  suspend fun clear() {
    token = null
    context.sessionStore.edit { it.clear() }
  }

  /** Сервер ответил 401 на запрос с этим токеном: сессия истекла или её закрыли. */
  fun onUnauthorized(rejected: String) {
    if (rejected != token) return
    token = null
    scope.launch { clear() }
  }
}
