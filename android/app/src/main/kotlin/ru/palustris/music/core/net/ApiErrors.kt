package ru.palustris.music.core.net

import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** Ошибка, которую можно показать человеку как есть. */
class UserFacingException(message: String) : Exception(message)

/**
 * Выполнить запрос и превратить сбой в понятный текст. Бэкенд пишет причины ошибок
 * по-русски простым текстом, поэтому их показываем как есть.
 */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: CancellationException) {
    throw e
  } catch (e: HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()?.trim().orEmpty()
    val text =
      when {
        body.isNotEmpty() && body.length < 200 && !body.startsWith("<") -> body
        e.code() == 401 -> "Нужно войти заново"
        e.code() == 429 -> "Слишком много попыток, подождите немного"
        e.code() >= 500 -> "Сервер сейчас не отвечает, попробуйте позже"
        else -> "Ошибка ${e.code()}"
      }
    Result.failure(UserFacingException(text.replaceFirstChar { it.uppercase() }))
  } catch (e: IOException) {
    Result.failure(UserFacingException("Нет связи с сервером. Проверьте интернет"))
  } catch (e: kotlinx.serialization.SerializationException) {
    Result.failure(UserFacingException("Сервер ответил что-то непонятное"))
  }
