package ru.palustris.music.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.palustris.music.core.model.NameBody
import ru.palustris.music.core.model.NewPlaylist
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall

/** Плейлисты на сервере. Список держим в памяти и обновляем после каждого изменения. */
@Singleton
class PlaylistsRepository @Inject constructor(private val api: MusicApi) {
  private val _list = MutableStateFlow<List<PlaylistRow>?>(null)
  val list: StateFlow<List<PlaylistRow>?> = _list.asStateFlow()

  /** Увеличивается при каждом изменении: открытый плейлист по нему перезагружается. */
  private val _version = MutableStateFlow(0)
  val version: StateFlow<Int> = _version.asStateFlow()

  suspend fun refresh(): Result<Unit> = apiCall { api.playlists() }.map { _list.value = it }

  suspend fun get(id: Long): Result<PlaylistFull> = apiCall { api.playlist(id) }

  suspend fun create(name: String, items: List<Track> = emptyList()): Result<Long> =
    apiCall { api.createPlaylist(NewPlaylist(name.trim(), items.map { it.copy(why = null) })) }.map { it.id }.also { changed() }

  suspend fun rename(id: Long, name: String) = apiCall { api.renamePlaylist(id, NameBody(name.trim())) }.also { changed() }

  suspend fun delete(id: Long) = apiCall { api.deletePlaylist(id) }.also { changed() }

  suspend fun add(id: Long, track: Track) = apiCall { api.addToPlaylist(id, track.copy(why = null)) }.also { changed() }

  suspend fun remove(id: Long, trackId: String) = apiCall { api.removeFromPlaylist(id, trackId) }.also { changed() }

  suspend fun reorder(id: Long, ids: List<String>) = apiCall { api.reorderPlaylist(id, IdsBody(ids)) }.also { changed() }

  private suspend fun changed() {
    _version.value++
    refresh()
  }

  fun clear() {
    _list.value = null
  }
}
