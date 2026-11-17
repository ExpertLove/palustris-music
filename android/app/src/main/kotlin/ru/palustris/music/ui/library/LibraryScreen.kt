package ru.palustris.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import ru.palustris.music.core.model.LibraryArtist
import ru.palustris.music.ui.common.Artwork
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import ru.palustris.music.media.TrackDownload
import ru.palustris.music.ui.common.FilledTonalTextButton
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalNavigator
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlayActions
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.formatBytes
import ru.palustris.music.ui.common.plural
import ru.palustris.music.ui.playlists.NameDialog
import ru.palustris.music.ui.playlists.PlaylistListItem

enum class LibraryTab(val title: String) {
  Likes("Нравится"),
  Playlists("Плейлисты"),
  Artists("Исполнители"),
  Downloads("Скачанные"),
  History("История"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  contentPadding: PaddingValues,
  initialTab: LibraryTab,
  onOpenSettings: () -> Unit,
  onMessage: (String) -> Unit,
  vm: LibraryViewModel = hiltViewModel(),
) {
  val likes by vm.likes.collectAsStateWithLifecycle()
  val downloads by vm.downloads.collectAsStateWithLifecycle()
  val playlists by vm.playlists.collectAsStateWithLifecycle()
  val history by vm.history.collectAsStateWithLifecycle()
  val artists by vm.artists.collectAsStateWithLifecycle()
  val clearingHistory by vm.clearingHistory.collectAsStateWithLifecycle()
  var confirmClear by remember { mutableStateOf(false) }
  val refreshing by vm.refreshing.collectAsStateWithLifecycle()
  val error by vm.error.collectAsStateWithLifecycle()
  val downloadsBytes by vm.downloadsBytes.collectAsStateWithLifecycle()
  var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.ordinal) }
  var creating by remember { mutableStateOf(false) }
  val actions = LocalTrackActions.current
  val nav = LocalNavigator.current

  LaunchedEffect(Unit) { if (likes == null || playlists == null) vm.refresh() }
  LaunchedEffect(vm) { vm.messages.collect(onMessage) }
  LaunchedEffect(tab) {
    when (LibraryTab.entries[tab]) {
      LibraryTab.History -> vm.refreshHistory()
      LibraryTab.Artists -> vm.refreshArtists()
      else -> {}
    }
  }

  Column(Modifier.fillMaxSize()) {
    Text(
      "Моя музыка",
      style = MaterialTheme.typography.headlineLarge,
      modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
    )
    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
      LibraryTab.entries.forEach { t ->
        val count =
          when (t) {
            LibraryTab.Likes -> likes?.size
            LibraryTab.Playlists -> playlists?.size
            LibraryTab.Artists -> null
            LibraryTab.Downloads -> downloads.size
            LibraryTab.History -> null
          }
        Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.title + (count?.let { " · $it" } ?: "")) })
      }
    }
    PullToRefreshBox(
      isRefreshing = refreshing,
      onRefresh = {
        when (LibraryTab.entries[tab]) {
          LibraryTab.History -> vm.refreshHistory()
          LibraryTab.Artists -> vm.refreshArtists()
          else -> vm.refresh()
        }
      },
      modifier = Modifier.weight(1f),
    ) {
      LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        when (LibraryTab.entries[tab]) {
          LibraryTab.Likes -> {
            val list = likes
            when {
              list == null && error != null ->
                item { MessageState("Не получилось загрузить", error, action = "Повторить", onAction = vm::refresh) }
              list == null -> item { LoadingState() }
              list.isEmpty() ->
                item { MessageState("Здесь будут любимые треки", "Нажмите ♡ в плеере или в меню трека", icon = Icons.Rounded.FavoriteBorder) }
              else -> {
                item { PlayActions(list) }
                itemsIndexed(list, key = { _, t -> "like:" + t.id }) { i, t ->
                  TrackRow(t, onClick = { actions.play(list, i) }, modifier = Modifier.padding(horizontal = 8.dp))
                }
              }
            }
          }
          LibraryTab.Playlists -> {
            item {
              Row(Modifier.fillMaxWidth().padding(16.dp)) {
                FilledTonalTextButton("Новый плейлист", { creating = true }, Icons.Rounded.Add)
              }
            }
            val list = playlists
            when {
              list == null -> item { LoadingState() }
              list.isEmpty() ->
                item {
                  MessageState(
                    "Плейлистов пока нет",
                    "Соберите свой или сохраните альбом кнопкой «+» на его странице",
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                  )
                }
              else -> items(list, key = { "pl:" + it.id }) { p -> PlaylistListItem(p) { nav.openPlaylist(p.id) } }
            }
          }
          LibraryTab.Artists -> {
            val list = artists
            when {
              list == null -> item { LoadingState() }
              list.isEmpty() ->
                item {
                  MessageState(
                    "Пока пусто",
                    "Здесь соберутся исполнители, которых вы слушаете и лайкаете",
                    icon = Icons.Rounded.Person,
                  )
                }
              else ->
                items(list.chunked(3), key = { "ar:" + it.first().name }) { row ->
                  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    row.forEach { a ->
                      LibraryArtistCell(a, Modifier.weight(1f)) { nav.openArtist(null, a.name.substringBefore(',').trim()) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                  }
                }
            }
          }
          LibraryTab.Downloads -> downloadsTab(downloads, downloadsBytes, onOpenSettings)
          LibraryTab.History -> {
            val list = history
            when {
              list == null -> item { LoadingState() }
              list.isEmpty() ->
                item { MessageState("История пуста", "Здесь появятся треки, которые вы дослушали", icon = Icons.Rounded.History) }
              else -> {
                val tracks = list.map { it.toTrack() }
                item(key = "h-actions") {
                  PlayActions(tracks) {
                    AssistChip(
                      onClick = { confirmClear = true },
                      enabled = !clearingHistory,
                      label = { Text(if (clearingHistory) "Очищаем…" else "Очистить") },
                      leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)) },
                    )
                  }
                }
                itemsIndexed(list, key = { _, h -> "h:" + h.id }) { i, h ->
                  TrackRow(
                    tracks[i],
                    onClick = { actions.play(tracks, i) },
                    subtitle = "${h.artist} · ${whenPlayed(h.playedAt)}" + if (h.plays > 1) " · " + plural(h.plays, "раз", "раза", "раз") else "",
                    modifier = Modifier.padding(horizontal = 8.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("Очистить историю?") },
      text = { Text("«Моя волна» и рекомендации начнут подбирать музыку заново. Лайки, плейлисты и скачанное останутся.") },
      confirmButton = {
        TextButton(onClick = {
          confirmClear = false
          vm.clearHistory()
        }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
    )
  }

  if (creating) {
    NameDialog("Новый плейлист", confirm = "Создать", onDismiss = { creating = false }) {
      creating = false
      vm.createPlaylist(it) { id -> nav.openPlaylist(id) }
    }
  }
}

private fun LazyListScope.downloadsTab(downloads: List<TrackDownload>, bytes: Long?, onOpenSettings: () -> Unit) {
  if (downloads.isEmpty()) {
    item {
      MessageState(
        "Пока ничего не скачано",
        "Скачанные треки играют без интернета. Скачать можно из меню трека или кнопкой в плеере",
        icon = Icons.Rounded.DownloadForOffline,
      )
    }
    return
  }
  val done = downloads.filter { it.state == TrackDownload.State.Done }.map { it.track }
  item {
    PlayActions(done) {
      AssistChip(
        onClick = onOpenSettings,
        label = { Text(bytes?.let(::formatBytes) ?: "…") },
        leadingIcon = { Icon(Icons.Rounded.Storage, null, Modifier.size(18.dp)) },
      )
    }
  }
  items(downloads, key = { "dl:" + it.track.id }) { d ->
    val actions = LocalTrackActions.current
    val sub =
      when (d.state) {
        TrackDownload.State.Queued -> "В очереди на скачивание"
        TrackDownload.State.Downloading -> d.progress?.let { "Скачивается… ${(it * 100).toInt()}%" } ?: "Скачивается…"
        TrackDownload.State.Failed -> "Не скачался. Меню → «Скачать ещё раз»"
        TrackDownload.State.Done -> "${d.track.artist} · ${formatBytes(d.bytes)}"
      }
    TrackRow(
      d.track,
      onClick = { if (done.isNotEmpty()) actions.play(done, done.indexOfFirst { it.id == d.track.id }.coerceAtLeast(0)) },
      subtitle = sub,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
  }
}

/** Исполнитель в сетке: круглая обложка, имя и сколько раз его слушали и лайкали. */
@Composable
private fun LibraryArtistCell(artist: LibraryArtist, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Column(
    modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick, role = Role.Button).padding(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Artwork(artist.thumb, Modifier.fillMaxWidth().aspectRatio(1f), size = 226, shape = CircleShape)
    Text(
      artist.name,
      style = MaterialTheme.typography.labelLarge,
      // Две строки всегда: подписи в ряду стоят на одной высоте.
      minLines = 2,
      maxLines = 2,
      textAlign = TextAlign.Center,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text(
      plural(artist.count, "прослушивание", "прослушивания", "прослушиваний"),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

/** «сегодня, 14:05», «вчера», «12 ноя». */
private fun whenPlayed(unixSec: Long): String {
  if (unixSec <= 0) return ""
  val then = Calendar.getInstance().apply { timeInMillis = unixSec * 1000 }
  val now = Calendar.getInstance()
  val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
  val days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
  val ru = Locale.forLanguageTag("ru")
  return when {
    sameYear && days == 0 -> "сегодня, " + SimpleDateFormat("HH:mm", ru).format(Date(unixSec * 1000))
    sameYear && days == 1 -> "вчера"
    sameYear -> SimpleDateFormat("d MMM", ru).format(Date(unixSec * 1000))
    else -> SimpleDateFormat("d MMM yyyy", ru).format(Date(unixSec * 1000))
  }
}
