package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.UserFacingException
import ru.palustris.music.core.net.apiCall

/**
 * «Мне нравится». Список хранится на телефоне, лайк ставится сразу и уходит на сервер через
 * [Outbox], поэтому лайкать можно и без сети. Ответ сервера при обновлении дополняется
 * ещё не отправленными лайками, так что они не мигают.
 */
@Singleton
class LibraryRepository
@Inject
constructor(
  private val api: MusicApi,
  private val outbox: Outbox,
  private val files: OfflineFiles,
  @AppScope private val scope: CoroutineScope,
) {
  private val _likes = MutableStateFlow<List<Track>?>(null)
  /** `null`, пока не загрузили ни с телефона, ни с сервера. */
  val likes: StateFlow<List<Track>?> = _likes.asStateFlow()

  private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
  val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

  private val lock = Mutex()

  init {
    scope.launch { loadCached() }
  }

  private suspend fun loadCached() =
    lock.withLock {
      if (_likes.value == null) files.read(FILE, ListSerializer(Track.serializer()))?.let { set(it) }
    }

  suspend fun refresh(): Result<Unit> {
    loadCached()
    outbox.flush()
    return apiCall { api.likes() }
      .map { server ->
        val pending = outbox.snapshot()
        lock.withLock {
          val merged = applyPending(server, pending)
          set(merged)
          files.write(FILE, ListSerializer(Track.serializer()), merged)
        }
      }
      .recoverCatching { e ->
        // Без сети, но копия есть: это не ошибка для экрана.
        if (_likes.value == null) throw e else throw UserFacingException(OFFLINE)
      }
  }

  /** Вызывается с главного потока: сердечко меняется сразу, сервер узнает через [Outbox]. */
  fun toggleLike(track: Track) {
    val liked = track.id in _likedIds.value
    val list = _likes.value.orEmpty()
    set(if (liked) list.filter { it.id != track.id } else listOf(track.copy(why = null)) + list.filter { it.id != track.id })
    outbox.add(if (liked) PendingOp.Unlike(track.id) else PendingOp.Like(track.copy(why = null)))
    scope.launch { lock.withLock { _likes.value?.let { files.write(FILE, ListSerializer(Track.serializer()), it) } } }
  }

  private fun set(list: List<Track>) {
    _likes.value = list
    _likedIds.value = list.mapTo(HashSet()) { it.id }
  }

  suspend fun clear() =
    lock.withLock {
      _likes.value = null
      _likedIds.value = emptySet()
    }

  companion object {
    private const val FILE = "likes.json"
    const val OFFLINE = "Нет связи. Показываем сохранённое на телефоне"

    /** Ответ сервера плюс неотправленные лайки, в том порядке, в каком их ставили. */
    fun applyPending(server: List<Track>, pending: List<PendingOp>): List<Track> {
      var list = server
      for (op in pending) {
        list =
          when (op) {
            is PendingOp.Like -> listOf(op.track) + list.filter { it.id != op.track.id }
            is PendingOp.Unlike -> list.filter { it.id != op.id }
            else -> list
          }
      }
      return list
    }
  }
}
