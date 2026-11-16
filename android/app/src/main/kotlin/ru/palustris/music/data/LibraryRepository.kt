package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.palustris.music.core.AppScope
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

/** «Мне нравится». Лайк ставится сразу в интерфейсе и откатывается, если сервер отказал. */
@Singleton
class LibraryRepository
@Inject
constructor(private val api: MusicApi, @AppScope private val scope: CoroutineScope) {
  private val _likes = MutableStateFlow<List<Track>?>(null)
  /** `null`, пока не загрузили. */
  val likes: StateFlow<List<Track>?> = _likes.asStateFlow()

  private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
  val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

  suspend fun refresh(): Result<Unit> =
    apiCall { api.likes() }.map { list ->
      _likes.value = list
      _likedIds.value = list.mapTo(HashSet()) { it.id }
    }

  fun toggleLike(track: Track) {
    val liked = track.id in _likedIds.value
    apply(track, !liked)
    scope.launch {
      val r = apiCall { if (liked) api.unlike(track.id) else api.like(track.copy(why = null)) }
      if (r.isFailure) apply(track, liked)
    }
  }

  private fun apply(track: Track, liked: Boolean) {
    _likedIds.update { if (liked) it + track.id else it - track.id }
    _likes.update { list ->
      list?.let { l -> if (liked) listOf(track) + l.filter { it.id != track.id } else l.filter { it.id != track.id } }
    }
  }

  fun clear() {
    _likes.value = null
    _likedIds.value = emptySet()
  }
}
