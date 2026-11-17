package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.NewPlaylist
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.SharedPlaylist
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.UserFacingException
import ru.palustris.music.core.net.apiCall

@Serializable
private data class PlaylistsSnapshot(val list: List<PlaylistRow> = emptyList(), val full: Map<Long, PlaylistFull> = emptyMap())

/**
 * Плейлисты: список и содержимое каждого лежат на телефоне, поэтому открываются и играют без
 * сети. Переименование, добавление, удаление трека и новый порядок применяются сразу и уходят
 * на сервер через [Outbox]. Создать, удалить и поделиться можно только онлайн: тут нужен ответ
 * сервера (id, ссылка).
 */
@Singleton
class PlaylistsRepository
@Inject
constructor(
  private val api: MusicApi,
  private val outbox: Outbox,
  private val files: OfflineFiles,
  @AppScope private val scope: CoroutineScope,
) {
  private val _list = MutableStateFlow<List<PlaylistRow>?>(null)
  val list: StateFlow<List<PlaylistRow>?> = _list.asStateFlow()

  private val full = MutableStateFlow<Map<Long, PlaylistFull>>(emptyMap())
  private val lock = Mutex()

  init {
    scope.launch { loadCached() }
  }

  private suspend fun loadCached() =
    lock.withLock {
      if (_list.value != null) return@withLock
      files.read(FILE, PlaylistsSnapshot.serializer())?.let {
        _list.value = it.list
        full.value = it.full
      }
    }

  /** Содержимое плейлиста, как только оно есть на телефоне. */
  fun observe(id: Long): Flow<PlaylistFull?> = full.map { it[id] }.distinctUntilChanged()

  /** Список и содержимое всех плейлистов с сервера, поверх — неотправленные правки. */
  suspend fun refresh(): Result<Unit> {
    loadCached()
    outbox.flush()
    return apiCall { api.playlists() }
      .mapCatching { rows ->
        val limiter = Semaphore(4)
        val contents =
          coroutineScope {
            rows.take(MAX_OFFLINE).map { r -> async { limiter.withPermit { apiCall { api.playlist(r.id) }.getOrNull() } } }.awaitAll()
          }
        val pending = outbox.snapshot()
        lock.withLock {
          val old = full.value
          val map = HashMap<Long, PlaylistFull>()
          rows.forEachIndexed { i, r -> (contents.getOrNull(i) ?: old[r.id])?.let { map[r.id] = applyPending(it, pending) } }
          full.value = map
          _list.value = rows.map { r -> map[r.id]?.let { rowFrom(r, it) } ?: r }
          persist()
        }
      }
      .recoverCatching { e -> if (_list.value == null) throw e else throw UserFacingException(LibraryRepository.OFFLINE) }
  }

  /** Открыли плейлист: подтягиваем свежую версию одного его. */
  suspend fun load(id: Long): Result<PlaylistFull> {
    loadCached()
    return apiCall { api.playlist(id) }
      .map { server ->
        val pending = outbox.snapshot()
        lock.withLock {
          val p = applyPending(server, pending)
          full.value = full.value + (id to p)
          _list.value = _list.value?.map { if (it.id == id) rowFrom(it, p) else it }
          persist()
          p
        }
      }
      .recoverCatching { e -> full.value[id] ?: throw e }
  }

  suspend fun create(name: String, items: List<Track> = emptyList()): Result<Long> {
    val clean = items.map { it.copy(why = null) }
    return apiCall { api.createPlaylist(NewPlaylist(name.trim(), clean)) }
      .map { it.id }
      .onSuccess { id ->
        lock.withLock {
          val p = PlaylistFull(id, name.trim(), null, clean.distinctBy { it.id })
          full.value = full.value + (id to p)
          _list.value = _list.value.orEmpty() + rowFrom(PlaylistRow(id, p.name), p)
          persist()
        }
      }
  }

  suspend fun delete(id: Long): Result<Unit> =
    apiCall { api.deletePlaylist(id) }
      .onSuccess {
        outbox.dropPlaylist(id)
        lock.withLock {
          full.value = full.value - id
          _list.value = _list.value?.filter { it.id != id }
          persist()
        }
      }

  fun rename(id: Long, name: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    edit(id) { it.copy(name = n) }
    outbox.add(PendingOp.PlaylistRename(id, n))
  }

  /** Как на сервере: повторное добавление переносит трек в конец. */
  fun add(id: Long, track: Track) {
    val t = track.copy(why = null)
    edit(id) { p -> p.copy(items = p.items.filter { it.id != t.id } + t) }
    // Содержимого на телефоне ещё нет: хотя бы счётчик в списке.
    if (full.value[id] == null) _list.value = _list.value?.map { if (it.id == id) it.copy(count = it.count + 1) else it }
    outbox.add(PendingOp.PlaylistAdd(id, t))
  }

  fun remove(id: Long, trackId: String) {
    edit(id) { p -> p.copy(items = p.items.filter { it.id != trackId }) }
    outbox.add(PendingOp.PlaylistRemove(id, trackId))
  }

  /** Новый порядок: на экране он уже применён, тут сохраняем и отправляем. */
  fun reorder(id: Long, ids: List<String>) {
    edit(id) { p -> p.copy(items = reorderBy(p.items, ids)) }
    outbox.add(PendingOp.PlaylistOrder(id, ids))
  }

  /** Ссылка `https://music.palustris.ru/s/{slug}`. Тот же плейлист всегда даёт ту же ссылку. */
  suspend fun share(id: Long): Result<String> =
    apiCall { api.sharePlaylist(id) }.map { it.share }.onSuccess { slug -> setShare(id, slug) }

  suspend fun unshare(id: Long): Result<Unit> = apiCall { api.unsharePlaylist(id) }.onSuccess { setShare(id, null) }

  suspend fun shared(slug: String): Result<SharedPlaylist> = apiCall { api.shared(slug) }

  private suspend fun setShare(id: Long, slug: String?) =
    lock.withLock {
      full.value[id]?.let { full.value = full.value + (id to it.copy(share = slug)) }
      _list.value = _list.value?.map { if (it.id == id) it.copy(share = slug) else it }
      persist()
    }

  /** Правка на главном потоке: экран меняется сразу, файл пишется в фоне. */
  private fun edit(id: Long, change: (PlaylistFull) -> PlaylistFull) {
    val p = full.value[id]?.let(change)
    if (p != null) full.value = full.value + (id to p)
    _list.value = _list.value?.map { r -> if (r.id == id) (p?.let { rowFrom(r, it) } ?: r) else r }
    scope.launch { lock.withLock { persist() } }
  }

  private suspend fun persist() {
    val l = _list.value ?: return
    files.write(FILE, PlaylistsSnapshot.serializer(), PlaylistsSnapshot(l, full.value))
  }

  suspend fun clear() =
    lock.withLock {
      _list.value = null
      full.value = emptyMap()
    }

  companion object {
    private const val FILE = "playlists.json"
    /** Больше плейлистов на телефон не тянем: каждая копия — отдельный запрос. */
    private const val MAX_OFFLINE = 100

    /** Строка списка по содержимому: название, число треков и до четырёх обложек, как считает сервер. */
    fun rowFrom(r: PlaylistRow, p: PlaylistFull) =
      r.copy(
        name = p.name,
        count = p.items.size,
        thumbs = p.items.mapNotNull { it.thumb?.takeIf(String::isNotBlank) }.distinct().take(4),
        share = p.share,
      )

    /** Порядок по списку id; треки, которых в нём нет, остаются в конце, как у сервера. */
    fun reorderBy(items: List<Track>, ids: List<String>): List<Track> {
      val pos = ids.withIndex().associate { (i, id) -> id to i }
      return items.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
    }

    fun applyPending(p: PlaylistFull, pending: List<PendingOp>): PlaylistFull {
      var r = p
      for (op in pending) {
        if (op.playlistId != p.id) continue
        r =
          when (op) {
            is PendingOp.PlaylistAdd -> r.copy(items = r.items.filter { it.id != op.track.id } + op.track)
            is PendingOp.PlaylistRemove -> r.copy(items = r.items.filter { it.id != op.trackId })
            is PendingOp.PlaylistOrder -> r.copy(items = reorderBy(r.items, op.ids))
            is PendingOp.PlaylistRename -> r.copy(name = op.name)
            else -> r
          }
      }
      return r
    }
  }
}
