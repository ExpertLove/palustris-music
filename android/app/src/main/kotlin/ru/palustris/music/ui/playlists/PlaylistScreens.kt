package ru.palustris.music.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.Track
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.ui.catalog.Page
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.CollectionHeader
import ru.palustris.music.ui.common.ExtraAction
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlayActions
import ru.palustris.music.ui.common.PlaylistCover
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.pluralTracks

@HiltViewModel
class PlaylistViewModel @Inject constructor(private val repo: PlaylistsRepository) : ViewModel() {
  private val _page = MutableStateFlow(Page<PlaylistFull>())
  val page: StateFlow<Page<PlaylistFull>> = _page.asStateFlow()
  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()
  private var id = -1L

  fun open(id: Long) {
    if (this.id == id) return
    this.id = id
    reload()
    viewModelScope.launch { repo.version.drop(1).collect { reload() } }
  }

  private fun reload() {
    viewModelScope.launch {
      repo.get(id).onSuccess { _page.value = Page(it) }.onFailure { if (_page.value.data == null) _page.value = Page(error = it.message) }
    }
  }

  fun rename(name: String) = viewModelScope.launch { repo.rename(id, name).onFailure { _message.value = it.message } }

  fun delete(onDone: () -> Unit) =
    viewModelScope.launch { repo.delete(id).onSuccess { onDone() }.onFailure { _message.value = it.message } }

  fun remove(t: Track) {
    _page.value.data?.let { p -> _page.value = Page(p.copy(items = p.items.filter { it.id != t.id })) }
    viewModelScope.launch { repo.remove(id, t.id) }
  }

  /** Сдвинуть трек на одну позицию. Меняем сразу на экране, сервер догоняет. */
  fun move(index: Int, by: Int) {
    val p = _page.value.data ?: return
    val to = index + by
    if (to !in p.items.indices) return
    val items = p.items.toMutableList().apply { add(to, removeAt(index)) }
    _page.value = Page(p.copy(items = items))
    viewModelScope.launch { repo.reorder(id, items.map { it.id }) }
  }

  fun consumeMessage() {
    _message.value = null
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(id: Long, contentPadding: PaddingValues, onBack: () -> Unit, onMessage: (String) -> Unit) {
  val vm: PlaylistViewModel = hiltViewModel(key = "playlist:$id")
  LaunchedEffect(id) { vm.open(id) }
  val page by vm.page.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  LaunchedEffect(message) {
    message?.let {
      onMessage(it)
      vm.consumeMessage()
    }
  }
  val actions = LocalTrackActions.current
  var menu by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf(false) }
  val p = page.data

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    when {
      p == null && page.error != null -> {
        item { BackBar(onBack) }
        item { MessageState("Плейлист не открылся", page.error) }
      }
      p == null -> {
        item { BackBar(onBack) }
        item { LoadingState() }
      }
      else -> {
        item {
          CollectionHeader(
            p.name,
            pluralTracks(p.items.size),
            onBack,
            cover = { m -> PlaylistCover(p.items.mapNotNull { it.thumb }.distinct().take(4), m) },
          ) {
            Box {
              IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с плейлистом") }
              DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                  menu = false
                  renaming = true
                })
                DropdownMenuItem(text = { Text("Удалить плейлист") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                  menu = false
                  deleting = true
                })
              }
            }
          }
        }
        item { PlayActions(p.items) }
        if (p.items.isEmpty()) {
          item {
            MessageState(
              "Пока пусто",
              "Добавляйте треки через меню трека → «В плейлист»",
              icon = Icons.AutoMirrored.Rounded.QueueMusic,
            )
          }
        }
        itemsIndexed(p.items, key = { _, t -> "pl:" + t.id }) { i, t ->
          TrackRow(
            t,
            onClick = { actions.play(p.items, i) },
            modifier = Modifier.padding(horizontal = 8.dp).animateItem(),
            extra =
              listOfNotNull(
                ExtraAction("Выше", Icons.Rounded.ArrowUpward) { vm.move(i, -1) }.takeIf { i > 0 },
                ExtraAction("Ниже", Icons.Rounded.ArrowDownward) { vm.move(i, 1) }.takeIf { i < p.items.lastIndex },
                ExtraAction("Убрать из плейлиста", Icons.Rounded.PlaylistRemove) { vm.remove(t) },
              ),
          )
        }
      }
    }
  }

  if (renaming && p != null) {
    NameDialog("Новое название", initial = p.name, confirm = "Сохранить", onDismiss = { renaming = false }) {
      vm.rename(it)
      renaming = false
    }
  }
  if (deleting && p != null) {
    AlertDialog(
      onDismissRequest = { deleting = false },
      title = { Text("Удалить «${p.name}»?") },
      text = { Text("Сами треки никуда не денутся: скачанные останутся на телефоне, лайки на месте.") },
      confirmButton = {
        TextButton(onClick = {
          deleting = false
          vm.delete(onBack)
        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } },
    )
  }
}

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var name by rememberSaveable { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= 100) name = it },
        singleLine = true,
        placeholder = { Text("Например, «В дорогу»") },
        shape = RoundedCornerShape(16.dp),
      )
    },
    confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

/** Список плейлистов для вкладки «Моя музыка». */
@Composable
fun PlaylistListItem(p: PlaylistRow, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PlaylistCover(p.thumbs, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(pluralTracks(p.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** «В плейлист»: выбрать существующий или создать новый прямо здесь. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
  track: Track,
  playlists: List<PlaylistRow>?,
  onDismiss: () -> Unit,
  onPick: (PlaylistRow) -> Unit,
  onCreate: (String) -> Unit,
) {
  var creating by remember { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.navigationBarsPadding()) {
      Text("В плейлист", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
      Text(
        "${track.title} · ${track.artist}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
      )
      LazyColumn(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        item {
          Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(18.dp)).clickable { creating = true }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("Новый плейлист", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp))
          }
        }
        if (playlists == null) item { LoadingState() }
        else items(playlists, key = { it.id }) { p -> PlaylistListItem(p) { onPick(p) } }
      }
    }
  }
  if (creating) {
    NameDialog("Новый плейлист", confirm = "Создать", onDismiss = { creating = false }) {
      creating = false
      onCreate(it)
    }
  }
}
