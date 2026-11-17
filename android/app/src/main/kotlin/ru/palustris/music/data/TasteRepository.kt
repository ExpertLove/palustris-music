package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.BlockedArtist
import ru.palustris.music.core.model.Dislikes
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

/**
 * Что пользователь не хочет слышать: треки с «не нравится» и скрытые исполнители. Применяется сразу
 * на телефоне (подборки по настроению, очередь), а на сервер уходит через [Outbox], где из этого
 * строятся рекомендации. Копия лежит в `taste.json`.
 */
@Singleton
class TasteRepository
@Inject
constructor(
  private val api: MusicApi,
  private val outbox: Outbox,
  private val files: OfflineFiles,
  private val library: LibraryRepository,
  @AppScope private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow(Dislikes())
  val state: StateFlow<Dislikes> = _state.asStateFlow()

  private val _dislikedIds = MutableStateFlow<Set<String>>(emptySet())
  val dislikedIds: StateFlow<Set<String>> = _dislikedIds.asStateFlow()

  private val lock = Mutex()
  @Volatile private var blockedKeys: Set<String> = emptySet()

  init {
    scope.launch { lock.withLock { files.read(FILE, Dislikes.serializer())?.let(::set) } }
  }

  suspend fun refresh(): Result<Unit> =
    apiCall { api.dislikes() }.map { server ->
      val pending = outbox.snapshot()
      lock.withLock {
        val merged = applyPending(server, pending)
        set(merged)
        files.write(FILE, Dislikes.serializer(), merged)
      }
    }

  /** «Не нравится». Лайк снимается: и на сервере, и тут. */
  fun dislike(track: Track) {
    val t = track.copy(why = null)
    if (t.id in library.likedIds.value) library.toggleLike(t)
    update { it.copy(tracks = listOf(t) + it.tracks.filter { x -> x.id != t.id }) }
    outbox.add(PendingOp.Dislike(t))
  }

  fun undislike(id: String) {
    update { it.copy(tracks = it.tracks.filter { x -> x.id != id }) }
    outbox.add(PendingOp.Undislike(id))
  }

  /** «Не предлагать исполнителя». Из «Кино, Виктор Цой» скрывается первый — «Кино». */
  fun block(artist: String) {
    val name = artist.substringBefore(',').trim().ifEmpty { return }
    update { s -> s.copy(artists = listOf(BlockedArtist(name, System.currentTimeMillis() / 1000)) + s.artists.filter { artistKey(it.name) != artistKey(name) }) }
    outbox.add(PendingOp.BlockArtist(name))
  }

  fun unblock(name: String) {
    update { s -> s.copy(artists = s.artists.filter { it.name != name }) }
    outbox.add(PendingOp.UnblockArtist(name))
  }

  /** Скрыт ли трек: «не нравится» или исполнитель в списке скрытых (в том числе как соавтор). */
  fun isHidden(track: Track): Boolean =
    track.id in _dislikedIds.value || (blockedKeys.isNotEmpty() && artistKeys(track.artist).any { it in blockedKeys })

  fun isBlocked(artist: String): Boolean = blockedKeys.isNotEmpty() && artistKeys(artist).any { it in blockedKeys }

  suspend fun clear() =
    lock.withLock {
      set(Dislikes())
    }

  private fun update(f: (Dislikes) -> Dislikes) {
    _state.update(f)
    set(_state.value)
    scope.launch { lock.withLock { files.write(FILE, Dislikes.serializer(), _state.value) } }
  }

  private fun set(d: Dislikes) {
    _state.value = d
    _dislikedIds.value = d.tracks.mapTo(HashSet()) { it.id }
    blockedKeys = d.artists.mapTo(HashSet()) { artistKey(it.name) }
  }

  companion object {
    private const val FILE = "taste.json"

    /** Ответ сервера плюс ещё не отправленные изменения. */
    fun applyPending(server: Dislikes, pending: List<PendingOp>): Dislikes {
      var d = server
      for (op in pending) {
        d =
          when (op) {
            is PendingOp.Dislike -> d.copy(tracks = listOf(op.track) + d.tracks.filter { it.id != op.track.id })
            is PendingOp.Undislike -> d.copy(tracks = d.tracks.filter { it.id != op.id })
            is PendingOp.BlockArtist ->
              d.copy(artists = listOf(BlockedArtist(op.name)) + d.artists.filter { artistKey(it.name) != artistKey(op.name) })
            is PendingOp.UnblockArtist -> d.copy(artists = d.artists.filter { artistKey(it.name) != artistKey(op.name) })
            else -> d
          }
      }
      return d
    }
  }
}

/**
 * Ключ исполнителя для сравнения, как на сервере (`recs::artist_key`): YouTube Music пишет одного
 * и того же то «Шакира», то «Shakira». Транслит, затем только согласные.
 */
fun artistKey(name: String): String {
  val t = translit(name)
  val skeleton =
    t.filter { it.isLetterOrDigit() && it !in "aeiouy" }
      .map {
        when (it) {
          'w' -> 'v'
          'c', 'q' -> 'k'
          else -> it
        }
      }
      .joinToString("")
  return if (skeleton.length >= 3) skeleton else t.replace(" ", "")
}

/** Все исполнители строки «A, B & C и D feat. E». */
fun artistKeys(artist: String): List<String> =
  artist
    .split(',')
    .flatMap { it.split(" & ", " и ", " feat. ", " ft. ") }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map(::artistKey)
    .distinct()

private val CYR =
  mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'э' to "e", 'ж' to "zh", 'з' to "z",
    'и' to "i", 'й' to "i", 'і' to "i", 'ї' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
    'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
    'щ' to "sch", 'ъ' to "", 'ь' to "", 'ы' to "y", 'ю' to "yu", 'я' to "ya", 'є' to "e",
  )

fun translit(s: String): String {
  val sb = StringBuilder()
  for (c in s.lowercase()) {
    val r = CYR[c]
    when {
      r != null -> sb.append(r)
      c.isLetterOrDigit() -> sb.append(c)
      else -> sb.append(' ')
    }
  }
  return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
