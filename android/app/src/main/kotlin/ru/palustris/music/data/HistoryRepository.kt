package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

/**
 * История прослушиваний. Копия лежит на телефоне, чтобы вкладка «История» без сети не крутилась
 * бесконечно. Сами прослушивания уходят на сервер через [Outbox] из сервиса плеера.
 */
@Singleton
class HistoryRepository
@Inject
constructor(private val api: MusicApi, private val outbox: Outbox, private val files: OfflineFiles) {
  private val _items = MutableStateFlow<List<HistoryItem>?>(null)
  /** `null`, пока не загрузили ни с телефона, ни с сервера. */
  val items: StateFlow<List<HistoryItem>?> = _items.asStateFlow()

  private val _cleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  /** История очищена: главная убирает «Недавно играло». */
  val cleared: SharedFlow<Unit> = _cleared.asSharedFlow()

  /** Растёт при очистке: ответ, запрошенный до неё, старую историю не вернёт. */
  @Volatile private var generation = 0

  suspend fun refresh() {
    if (_items.value == null) _items.value = files.read(FILE, SERIALIZER)
    val gen = generation
    apiCall { api.history(LIMIT) }
      .onSuccess {
        if (gen != generation) return@onSuccess
        _items.value = it
        files.write(FILE, SERIALIZER, it)
      }
      .onFailure { if (_items.value == null) _items.value = emptyList() }
  }

  /**
   * Стереть историю на сервере. Нужна сеть: очистка «в очереди» сработала бы позже, чем человек
   * ждёт, и на сайте история ещё висела бы. Вместе с историей сервер забывает пропуски.
   */
  suspend fun clearAll(): Result<Unit> =
    apiCall { api.clearHistory() }
      .onSuccess {
        generation++
        outbox.dropListens()
        _items.value = emptyList()
        files.delete(FILE)
        _cleared.tryEmit(Unit)
      }

  /** Выход из аккаунта: файлы стирает [OfflineFiles.clearAll], здесь только память. */
  fun reset() {
    _items.value = null
  }

  companion object {
    const val FILE = "history.json"
    private const val LIMIT = 150
    private val SERIALIZER = ListSerializer(HistoryItem.serializer())
  }
}
