package ru.palustris.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import retrofit2.HttpException
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.model.NameBody
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import timber.log.Timber

/** Изменение, которое ещё не дошло до сервера. */
@Serializable
sealed interface PendingOp {
  @Serializable @SerialName("like") data class Like(val track: Track) : PendingOp

  @Serializable @SerialName("unlike") data class Unlike(val id: String) : PendingOp

  /** Трек дослушан: из истории сервер строит «Мою волну». */
  @Serializable @SerialName("history") data class Listened(val track: Track) : PendingOp

  @Serializable @SerialName("pl.add") data class PlaylistAdd(val playlist: Long, val track: Track) : PendingOp

  @Serializable @SerialName("pl.remove") data class PlaylistRemove(val playlist: Long, val trackId: String) : PendingOp

  @Serializable @SerialName("pl.order") data class PlaylistOrder(val playlist: Long, val ids: List<String>) : PendingOp

  @Serializable @SerialName("pl.rename") data class PlaylistRename(val playlist: Long, val name: String) : PendingOp
}

val PendingOp.playlistId: Long?
  get() =
    when (this) {
      is PendingOp.PlaylistAdd -> playlist
      is PendingOp.PlaylistRemove -> playlist
      is PendingOp.PlaylistOrder -> playlist
      is PendingOp.PlaylistRename -> playlist
      else -> null
    }

/**
 * Очередь изменений для сервера. Лайки, правки плейлистов и прослушивания сначала применяются
 * на телефоне, а сюда попадают для отправки: так всё работает и без сети. Очередь лежит на диске
 * и отправляется по порядку, когда появляется связь (колбэк сети, повтор с паузой до 5 минут).
 *
 * Отправка: успех или отказ сервера 4xx — операция снимается (4xx значит «так уже нельзя»,
 * например плейлист удалили с сайта); нет сети, 5xx, 429, 401 — ждём и повторяем.
 */
@Singleton
class Outbox
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val api: MusicApi,
  private val files: OfflineFiles,
  @AppScope private val scope: CoroutineScope,
) {
  private val lock = Mutex()
  private val sending = Mutex()
  private var ops: MutableList<PendingOp>? = null
  /** Операция, которая сейчас летит на сервер: её нельзя схлопывать с новыми. */
  @Volatile private var inFlight: PendingOp? = null
  @Volatile private var retryJob: Job? = null
  @Volatile private var retryDelayMs = FIRST_RETRY_MS

  /** Порядок важен (лайк, потом снятие лайка), поэтому операции идут через один канал. */
  private val incoming = Channel<PendingOp>(Channel.UNLIMITED)

  private val _pending = MutableStateFlow(0)
  /** Сколько изменений ждёт сети: для подписи «синхронизируется». */
  val pending: StateFlow<Int> = _pending.asStateFlow()

  init {
    consume()
    scope.launch {
      lock.withLock { loaded() }
      flush()
    }
    context.getSystemService<ConnectivityManager>()?.let { cm ->
      runCatching {
        cm.registerDefaultNetworkCallback(
          object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
              retryDelayMs = FIRST_RETRY_MS
              scope.launch { flush() }
            }
          }
        )
      }
    }
  }

  private suspend fun loaded(): MutableList<PendingOp> =
    ops ?: (files.read(FILE, ListSerializer(PendingOp.serializer()))?.toMutableList() ?: mutableListOf()).also {
      ops = it
      _pending.value = it.size
    }

  /** Неотправленные изменения: репозитории накладывают их на ответ сервера. */
  suspend fun snapshot(): List<PendingOp> = lock.withLock { loaded().toList() }

  fun add(op: PendingOp) {
    incoming.trySend(op)
  }

  private fun consume() =
    scope.launch {
      for (first in incoming) {
        lock.withLock {
          val list = loaded()
          // Пачку изменений подряд записываем и отправляем одним заходом.
          var op: PendingOp? = first
          while (op != null) {
            compact(list, op, inFlight)
            op = incoming.tryReceive().getOrNull()
          }
          save(list)
        }
        scope.launch { flush() }
      }
    }

  /** Плейлист удалён: его неотправленные правки больше не нужны. */
  suspend fun dropPlaylist(id: Long) =
    lock.withLock {
      val list = loaded()
      if (list.removeAll { it.playlistId == id && it !== inFlight }) save(list)
    }

  private suspend fun save(list: List<PendingOp>) {
    _pending.value = list.size
    if (list.isEmpty()) files.delete(FILE) else files.write(FILE, ListSerializer(PendingOp.serializer()), list)
  }

  /** Отправить всё, что накопилось. `true` — очередь пуста. */
  suspend fun flush(): Boolean =
    sending.withLock {
      while (true) {
        val op = lock.withLock { loaded().firstOrNull().also { inFlight = it } } ?: break
        val result = send(op)
        lock.withLock {
          inFlight = null
          if (result != Sent.RETRY) {
            val list = loaded()
            // Пока операция летела, список могли поменять: снимаем именно её.
            val i = list.indexOfFirst { it === op }
            if (i >= 0) list.removeAt(i)
            save(list)
          }
        }
        if (result == Sent.RETRY) {
          scheduleRetry()
          return@withLock false
        }
        retryDelayMs = FIRST_RETRY_MS
      }
      true
    }

  /**
   * Один отложенный повтор за раз. Поле очищается до повторной отправки: иначе неудачный повтор
   * увидел бы себя «активным» и не запланировал следующий.
   */
  private fun scheduleRetry() {
    if (retryJob?.isActive == true) return
    val wait = retryDelayMs
    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    retryJob = scope.launch {
      delay(wait)
      retryJob = null
      flush()
    }
  }

  private enum class Sent { DONE, DROP, RETRY }

  private suspend fun send(op: PendingOp): Sent =
    try {
      when (op) {
        is PendingOp.Like -> api.like(op.track.copy(why = null))
        is PendingOp.Unlike -> api.unlike(op.id)
        is PendingOp.Listened -> api.history(op.track.copy(why = null))
        is PendingOp.PlaylistAdd -> api.addToPlaylist(op.playlist, op.track.copy(why = null))
        is PendingOp.PlaylistRemove -> api.removeFromPlaylist(op.playlist, op.trackId)
        is PendingOp.PlaylistOrder -> api.reorderPlaylist(op.playlist, IdsBody(op.ids))
        is PendingOp.PlaylistRename -> api.renamePlaylist(op.playlist, NameBody(op.name))
      }
      Sent.DONE
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpException) {
      val code = e.code()
      if (code == 401 || code == 408 || code == 429 || code >= 500) Sent.RETRY
      else {
        Timber.w("outbox: server refused %s with %d, dropped", op::class.simpleName, code)
        Sent.DROP
      }
    } catch (e: IOException) {
      Sent.RETRY
    } catch (e: Exception) {
      Timber.w(e, "outbox: %s dropped", op::class.simpleName)
      Sent.DROP
    }

  /** Выход из аккаунта: чужие изменения не должны уйти на сервер с новым токеном. */
  suspend fun clear() =
    lock.withLock {
      retryJob?.cancel()
      ops = mutableListOf()
      _pending.value = 0
      files.delete(FILE)
    }

  companion object {
    private const val FILE = "outbox.json"
    private const val FIRST_RETRY_MS = 15_000L
    private const val MAX_RETRY_MS = 5 * 60_000L

    /**
     * Схлопываем то, что отменяет друг друга, пока оно не ушло на сервер: лайк и снятие лайка,
     * добавление и удаление трека; из нескольких порядков и названий нужен только последний.
     * [inFlight] уже летит на сервер, его не трогаем.
     */
    fun compact(list: MutableList<PendingOp>, op: PendingOp, inFlight: PendingOp? = null) {
      fun removeLast(pred: (PendingOp) -> Boolean): Boolean {
        val i = list.indexOfLast { it !== inFlight && pred(it) }
        if (i < 0) return false
        list.removeAt(i)
        return true
      }
      when (op) {
        is PendingOp.Like -> if (removeLast { it is PendingOp.Unlike && it.id == op.track.id }) return
        is PendingOp.Unlike -> if (removeLast { it is PendingOp.Like && it.track.id == op.id }) return
        // Удаление уходит всегда: трек мог быть в плейлисте и до повторного добавления.
        is PendingOp.PlaylistRemove ->
          removeLast { it is PendingOp.PlaylistAdd && it.playlist == op.playlist && it.track.id == op.trackId }
        is PendingOp.PlaylistOrder -> removeLast { it is PendingOp.PlaylistOrder && it.playlist == op.playlist }
        is PendingOp.PlaylistRename -> removeLast { it is PendingOp.PlaylistRename && it.playlist == op.playlist }
        else -> Unit
      }
      list += op
    }
  }
}
