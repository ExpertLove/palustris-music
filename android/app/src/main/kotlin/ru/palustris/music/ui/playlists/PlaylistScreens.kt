package ru.palustris.music.ui.playlists

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.palustris.music.BuildConfig
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.SharedPlaylist
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Публичная ссылка на плейлист, та же, что даёт сайт. */
fun shareUrl(slug: String) = BuildConfig.API_BASE_URL + "s/" + slug

/** Системное окно «Поделиться» со ссылкой на плейлист. */
fun sharePlaylistLink(context: Context, name: String, slug: String) {
  val send =
    Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_SUBJECT, name)
      .putExtra(Intent.EXTRA_TITLE, "Плейлист «$name»")
      .putExtra(Intent.EXTRA_TEXT, "Плейлист «$name»: ${shareUrl(slug)}")
  context.startActivity(Intent.createChooser(send, "Поделиться плейлистом"))
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(private val repo: PlaylistsRepository) : ViewModel() {
  private val _page = MutableStateFlow(Page<PlaylistFull>())
  val page: StateFlow<Page<PlaylistFull>> = _page.asStateFlow()
  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()
  /** Ссылка готова: открыть окно «Поделиться». */
  private val _shareLink = Channel<String>(Channel.CONFLATED)
  val shareLink = _shareLink.receiveAsFlow()
  private var id = -1L

  fun open(id: Long) {
    if (this.id == id) return
    this.id = id
    // Копия с телефона показывается сразу, свежая с сервера подменяет её, когда придёт.
    viewModelScope.launch { repo.observe(id).collect { p -> if (p != null) _page.value = Page(p) } }
    viewModelScope.launch {
      repo.load(id).onFailure { e -> if (_page.value.data == null) _page.value = Page(error = e.message) }
    }
  }

  fun rename(name: String) = repo.rename(id, name)

  fun delete(onDone: () -> Unit) =
    viewModelScope.launch { repo.delete(id).onSuccess { onDone() }.onFailure { _messages.send(it.message ?: "Не получилось") } }

  fun remove(t: Track) = repo.remove(id, t.id)

  /** Сдвинуть трек на одну позицию: для меню и TalkBack, где перетаскивать неудобно. */
  fun move(index: Int, by: Int) {
    val items = _page.value.data?.items ?: return
    val to = index + by
    if (to !in items.indices) return
    reorder(items.toMutableList().apply { add(to, removeAt(index)) })
  }

  fun reorder(items: List<Track>) = repo.reorder(id, items.map { it.id })

  fun share() {
    val p = _page.value.data ?: return
    p.share?.let {
      _shareLink.trySend(it)
      return
    }
    viewModelScope.launch {
      repo.share(id).fold({ _shareLink.send(it) }, { _messages.send(it.message ?: "Не получилось") })
    }
  }

  fun unshare() =
    viewModelScope.launch {
      repo.unshare(id).fold({ _messages.send("Ссылка больше не работает") }, { _messages.send(it.message ?: "Не получилось") })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(id: Long, contentPadding: PaddingValues, onBack: () -> Unit, onMessage: (String) -> Unit) {
  val vm: PlaylistViewModel = hiltViewModel(key = "playlist:$id")
  LaunchedEffect(id) { vm.open(id) }
  val page by vm.page.collectAsStateWithLifecycle()
  val context = LocalContext.current
  LaunchedEffect(vm) { vm.messages.collect(onMessage) }
  val actions = LocalTrackActions.current
  var menu by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf(false) }
  val p = page.data
  LaunchedEffect(vm) { vm.shareLink.collect { slug -> sharePlaylistLink(context, vm.page.value.data?.name.orEmpty(), slug) } }

  // Пока палец тянет трек, порядок живёт здесь; на сервер уходит, когда трек отпустили.
  var dragged by remember { mutableStateOf<List<Track>?>(null) }
  var dragging by remember { mutableStateOf(false) }
  LaunchedEffect(p?.items) { if (!dragging) dragged = null }
  val items = dragged ?: p?.items.orEmpty()
  val haptics = LocalHapticFeedback.current
  val listState = rememberLazyListState()
  val reorder =
    rememberReorderableLazyListState(listState) { from, to ->
      val fromId = (from.key as? String)?.removePrefix(KEY) ?: return@rememberReorderableLazyListState
      val toId = (to.key as? String)?.takeIf { it.startsWith(KEY) }?.removePrefix(KEY) ?: return@rememberReorderableLazyListState
      val list = (dragged ?: p?.items ?: return@rememberReorderableLazyListState).toMutableList()
      val fi = list.indexOfFirst { it.id == fromId }
      val ti = list.indexOfFirst { it.id == toId }
      if (fi < 0 || ti < 0) return@rememberReorderableLazyListState
      list.add(ti, list.removeAt(fi))
      dragged = list
      haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

  LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
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
        item(key = "header") {
          CollectionHeader(
            p.name,
            pluralTracks(p.items.size),
            onBack,
            cover = { m -> PlaylistCover(p.items.mapNotNull { it.thumb }.distinct().take(4), m) },
          ) {
            IconButton(onClick = vm::share) { Icon(Icons.Rounded.Share, "Поделиться ссылкой") }
            Box {
              IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с плейлистом") }
              DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                  menu = false
                  renaming = true
                })
                DropdownMenuItem(text = { Text("Поделиться ссылкой") }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, onClick = {
                  menu = false
                  vm.share()
                })
                if (p.share != null) {
                  DropdownMenuItem(text = { Text("Закрыть доступ по ссылке") }, leadingIcon = { Icon(Icons.Rounded.LinkOff, null) }, onClick = {
                    menu = false
                    vm.unshare()
                  })
                }
                DropdownMenuItem(text = { Text("Удалить плейлист") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                  menu = false
                  deleting = true
                })
              }
            }
          }
        }
        item(key = "actions") {
          PlayActions(items, source = "playlist:$id") {
            if (p.share != null) {
              AssistChip(
                onClick = vm::share,
                label = { Text("По ссылке") },
                leadingIcon = { Icon(Icons.Rounded.Link, null, Modifier.size(AssistChipDefaults.IconSize)) },
              )
            }
          }
        }
        if (items.isEmpty()) {
          item(key = "empty") {
            MessageState(
              "Пока пусто",
              "Добавляйте треки через меню трека → «В плейлист»",
              icon = Icons.AutoMirrored.Rounded.QueueMusic,
            )
          }
        }
        itemsIndexed(items, key = { _, t -> KEY + t.id }) { i, t ->
          ReorderableItem(reorder, key = KEY + t.id) { isDragging ->
            val lift by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "lift")
            Surface(
              shadowElevation = lift,
              shape = RoundedCornerShape(18.dp),
              color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
              modifier = Modifier.padding(horizontal = 8.dp),
            ) {
              TrackRow(
                t,
                onClick = { actions.play(items, i, "playlist:$id") },
                extra =
                  listOfNotNull(
                    ExtraAction("Выше", Icons.Rounded.ArrowUpward) { vm.move(i, -1) }.takeIf { i > 0 },
                    ExtraAction("Ниже", Icons.Rounded.ArrowDownward) { vm.move(i, 1) }.takeIf { i < items.lastIndex },
                    ExtraAction("Убрать из плейлиста", Icons.Rounded.PlaylistRemove) { vm.remove(t) },
                  ),
                trailing = {
                  IconButton(
                    onClick = {},
                    modifier =
                      Modifier.draggableHandle(
                          onDragStarted = {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                          },
                          onDragStopped = {
                            dragging = false
                            dragged?.let { vm.reorder(it) }
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                          },
                        )
                        .semantics { contentDescription = "Перетащить, чтобы поменять порядок" },
                  ) {
                    Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                },
              )
            }
          }
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

private const val KEY = "pl:"

@HiltViewModel
class SharedPlaylistViewModel @Inject constructor(private val repo: PlaylistsRepository) : ViewModel() {
  private val _page = MutableStateFlow(Page<SharedPlaylist>())
  val page: StateFlow<Page<SharedPlaylist>> = _page.asStateFlow()
  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()
  private var slug: String? = null

  fun open(slug: String) {
    if (this.slug == slug && _page.value.error == null) return
    this.slug = slug
    viewModelScope.launch {
      _page.value = Page()
      _page.value = repo.shared(slug).fold({ Page(it) }, { Page(error = it.message) })
    }
  }

  fun save(onSaved: (Long) -> Unit) {
    val p = _page.value.data ?: return
    viewModelScope.launch {
      repo.create(p.name, p.items).fold({ onSaved(it); _messages.send("Сохранено в ваши плейлисты") }, { _messages.send(it.message ?: "Не сохранилось") })
    }
  }
}

/** Чужой плейлист по ссылке: послушать, скачать или сохранить к себе. */
@Composable
fun SharedPlaylistScreen(slug: String, contentPadding: PaddingValues, onBack: () -> Unit, onMessage: (String) -> Unit) {
  val vm: SharedPlaylistViewModel = hiltViewModel(key = "shared:$slug")
  LaunchedEffect(slug) { vm.open(slug) }
  LaunchedEffect(vm) { vm.messages.collect(onMessage) }
  val page by vm.page.collectAsStateWithLifecycle()
  val actions = LocalTrackActions.current
  val nav = ru.palustris.music.ui.common.LocalNavigator.current
  val p = page.data

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    when {
      p == null && page.error != null -> {
        item { BackBar(onBack) }
        item { MessageState("Плейлист не открылся", "Возможно, автор закрыл доступ по ссылке. ${page.error}") }
      }
      p == null -> {
        item { BackBar(onBack) }
        item { LoadingState() }
      }
      else -> {
        item {
          CollectionHeader(
            p.name,
            listOfNotNull(p.owner.takeIf { it.isNotBlank() }?.let { "от $it" }, pluralTracks(p.items.size)).joinToString(" · "),
            onBack,
            cover = { m -> PlaylistCover(p.items.mapNotNull { it.thumb }.distinct().take(4), m) },
          ) {
            IconButton(onClick = { vm.save { id -> nav.openPlaylist(id) } }) { Icon(Icons.Rounded.LibraryAdd, "Сохранить к себе") }
          }
        }
        item { PlayActions(p.items, source = "shared:$slug") }
        itemsIndexed(p.items, key = { i, t -> "$i:${t.id}" }) { i, t ->
          TrackRow(t, onClick = { actions.play(p.items, i, "shared:$slug") }, modifier = Modifier.padding(horizontal = 8.dp))
        }
      }
    }
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
    Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick, role = Role.Button).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PlaylistCover(p.thumbs, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        pluralTracks(p.count) + if (p.share != null) " · по ссылке" else "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
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
