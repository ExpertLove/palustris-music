package ru.palustris.music.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Mixes
import ru.palustris.music.data.MixesRepository
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.data.TasteRepository
import ru.palustris.music.media.QueueSource
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.CollectionHeader
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlayActions
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.formatLength
import ru.palustris.music.ui.common.pluralTracks

@HiltViewModel
class MixViewModel
@Inject
constructor(private val repo: MixesRepository, private val playlists: PlaylistsRepository, taste: TasteRepository) : ViewModel() {
  val mixes: StateFlow<Mixes?> =
    combine(repo.mixes, taste.state) { m, _ -> m?.copy(mixes = m.mixes.map { it.copy(tracks = it.tracks.filterNot(taste::isHidden)) }) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.mixes.value)

  fun ensureLoaded() {
    if (repo.mixes.value == null) viewModelScope.launch { repo.refresh() }
  }

  suspend fun save(name: String, mixId: String): Result<Unit> {
    val tracks = mixes.value?.mixes?.firstOrNull { it.id == mixId }?.tracks.orEmpty()
    return playlists.create(name, tracks).map { }
  }
}

@Composable
fun MixScreen(id: String, contentPadding: PaddingValues, onBack: () -> Unit, onMessage: (String) -> Unit, vm: MixViewModel = hiltViewModel()) {
  val all by vm.mixes.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { vm.ensureLoaded() }
  val actions = LocalTrackActions.current
  val index = all?.mixes?.indexOfFirst { it.id == id } ?: -1
  val mix = all?.mixes?.getOrNull(index)
  val scope = androidx.compose.runtime.rememberCoroutineScope()

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    when {
      mix == null && all == null -> {
        item { BackBar(onBack) }
        item { LoadingState() }
      }
      mix == null -> {
        item { BackBar(onBack) }
        item { MessageState("Микс обновился", "Миксы дня собираются заново каждую ночь. Загляните на главную") }
      }
      else -> {
        item {
          CollectionHeader(
            mix.title,
            listOf(mix.sub, pluralTracks(mix.tracks.size), formatLength(mix.tracks.sumOf { it.duration })).joinToString(" · "),
            onBack,
            cover = { m -> MixCover(mix, index, m) },
            coverShape = RoundedCornerShape(28.dp),
          ) {
            IconButton(onClick = {
              scope.launch {
                vm.save("${mix.title} · ${mix.sub.substringBefore(" и ")}", mix.id)
                  .fold({ onMessage("Сохранено в плейлисты") }, { onMessage(it.message ?: "Не получилось сохранить") })
              }
            }) { Icon(Icons.Rounded.LibraryAdd, "Сохранить в мои плейлисты") }
          }
        }
        item { PlayActions(mix.tracks, source = QueueSource.MIX + ":" + mix.id) }
        itemsIndexed(mix.tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
          TrackRow(t, onClick = { actions.play(mix.tracks, i, QueueSource.MIX + ":" + mix.id) }, modifier = Modifier.padding(horizontal = 8.dp))
        }
      }
    }
  }
}
