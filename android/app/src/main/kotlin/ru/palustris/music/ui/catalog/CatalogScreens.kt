package ru.palustris.music.ui.catalog

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.ArtistPage
import ru.palustris.music.core.model.Collection
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.ui.common.AlbumRow
import ru.palustris.music.ui.common.ArtistRow
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.CollectionHeader
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalNavigator
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlayActions
import ru.palustris.music.ui.common.SectionTitle
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.pluralTracks

/** Загрузка одной страницы: ждём, данные или ошибка. */
data class Page<T>(val data: T? = null, val error: String? = null)

@HiltViewModel
class CatalogViewModel @Inject constructor(private val api: MusicApi, private val playlists: PlaylistsRepository) : ViewModel() {
  private val _artist = MutableStateFlow(Page<ArtistPage>())
  val artist: StateFlow<Page<ArtistPage>> = _artist.asStateFlow()
  private val _collection = MutableStateFlow(Page<Collection>())
  val collection: StateFlow<Page<Collection>> = _collection.asStateFlow()
  private val _saved = MutableStateFlow<String?>(null)
  val saved: StateFlow<String?> = _saved.asStateFlow()
  private var loadedKey: String? = null

  fun loadArtist(id: String?, name: String) {
    val key = "a:$id:$name"
    if (loadedKey == key && _artist.value.error == null) return
    loadedKey = key
    viewModelScope.launch {
      _artist.value = Page()
      _artist.value = apiCall { api.artist(id?.takeIf { it.isNotBlank() }, name) }.fold({ Page(it) }, { Page(error = it.message) })
    }
  }

  fun loadCollection(id: String) {
    val key = "c:$id"
    if (loadedKey == key && _collection.value.error == null) return
    loadedKey = key
    viewModelScope.launch {
      _collection.value = Page()
      _collection.value = apiCall { api.collection(id) }.fold({ Page(it) }, { Page(error = it.message) })
    }
  }

  /** «Сохранить к себе»: альбом становится вашим плейлистом. */
  fun saveAsPlaylist(c: Collection) {
    viewModelScope.launch {
      _saved.value =
        playlists.create(c.title, c.tracks).fold({ "Сохранено в плейлисты" }, { it.message ?: "Не получилось сохранить" })
    }
  }

  fun consumeSaved() {
    _saved.value = null
  }
}

@Composable
fun ArtistScreen(id: String?, name: String, contentPadding: PaddingValues, onBack: () -> Unit) {
  val vm: CatalogViewModel = hiltViewModel(key = "artist:$id:$name")
  LaunchedEffect(id, name) { vm.loadArtist(id, name) }
  val page by vm.artist.collectAsStateWithLifecycle()
  val nav = LocalNavigator.current
  val actions = LocalTrackActions.current
  val a = page.data
  var expanded by rememberSaveable { mutableStateOf(false) }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    when {
      a == null && page.error != null -> {
        item { BackBar(onBack) }
        item { MessageState("Не получилось открыть исполнителя", page.error) }
      }
      a == null -> {
        item { BackBar(onBack) }
        item { LoadingState() }
      }
      else -> {
        item {
          CollectionHeader(
            a.name,
            a.listeners.takeIf { it.isNotBlank() },
            onBack,
            cover = { m -> Artwork(a.thumb, m, size = 544, shape = CircleShape) },
            coverShape = CircleShape,
          )
        }
        item { PlayActions(a.tracks) }
        if (a.description.isNotBlank()) {
          item {
            Text(
              a.description,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = if (expanded) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.animateContentSize().clickable { expanded = !expanded }.padding(horizontal = 20.dp, vertical = 4.dp),
            )
          }
        }
        if (a.tracks.isNotEmpty()) {
          item { SectionTitle("Популярные треки") }
          itemsIndexed(a.tracks, key = { _, t -> "t:" + t.id }) { i, t ->
            TrackRow(t, onClick = { actions.play(a.tracks, i) }, modifier = Modifier.padding(horizontal = 8.dp))
          }
        }
        item { AlbumRow("Альбомы", a.albums) { nav.openCollection(it.id) } }
        item { AlbumRow("Синглы и EP", a.singles) { nav.openCollection(it.id) } }
        item { AlbumRow("Плейлисты", a.playlists) { nav.openCollection(it.id) } }
        item { ArtistRow("Похожие исполнители", a.similar) { nav.openArtist(it.id, it.name) } }
      }
    }
  }
}

@Composable
fun CollectionScreen(id: String, contentPadding: PaddingValues, onBack: () -> Unit, onMessage: (String) -> Unit) {
  val vm: CatalogViewModel = hiltViewModel(key = "collection:$id")
  LaunchedEffect(id) { vm.loadCollection(id) }
  val page by vm.collection.collectAsStateWithLifecycle()
  val saved by vm.saved.collectAsStateWithLifecycle()
  LaunchedEffect(saved) {
    saved?.let {
      onMessage(it)
      vm.consumeSaved()
    }
  }
  val nav = LocalNavigator.current
  val actions = LocalTrackActions.current
  val c = page.data

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    when {
      c == null && page.error != null -> {
        item { BackBar(onBack) }
        item { MessageState("Не получилось открыть", page.error) }
      }
      c == null -> {
        item { BackBar(onBack) }
        item { LoadingState() }
      }
      else -> {
        item {
          CollectionHeader(
            c.title,
            listOf(c.artist, c.sub).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { pluralTracks(c.tracks.size) },
            onBack,
            cover = { m -> Artwork(c.thumb, m, size = 544) },
          ) {
            IconButton(onClick = { vm.saveAsPlaylist(c) }) { Icon(Icons.Rounded.LibraryAdd, "Сохранить в мои плейлисты") }
          }
        }
        item {
          PlayActions(c.tracks) {
            if (c.artist.isNotBlank()) {
              ru.palustris.music.ui.common.FilledTonalTextButton("Исполнитель", { nav.openArtist(c.artistId, c.artist) })
            }
          }
        }
        itemsIndexed(c.tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
          TrackRow(t, onClick = { actions.play(c.tracks, i) }, modifier = Modifier.padding(horizontal = 8.dp))
        }
      }
    }
  }
}
