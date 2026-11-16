package ru.palustris.music.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.TrackDownload
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.LocalTrackMarks
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.TrackRow
import ru.palustris.music.ui.common.formatBytes
import ru.palustris.music.ui.common.pluralTracks

enum class LibraryTab { Likes, Downloads }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  contentPadding: PaddingValues,
  initialTab: LibraryTab,
  onOpenSettings: () -> Unit,
  vm: LibraryViewModel = hiltViewModel(),
) {
  val likes by vm.likes.collectAsStateWithLifecycle()
  val downloads by vm.downloads.collectAsStateWithLifecycle()
  val refreshing by vm.refreshing.collectAsStateWithLifecycle()
  val error by vm.error.collectAsStateWithLifecycle()
  val downloadsBytes by vm.downloadsBytes.collectAsStateWithLifecycle()
  var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.ordinal) }
  val actions = LocalTrackActions.current

  LaunchedEffect(Unit) { if (likes == null) vm.refresh() }

  Column(Modifier.fillMaxSize()) {
    Text(
      "Моя музыка",
      style = MaterialTheme.typography.headlineLarge,
      modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
    )
    PrimaryTabRow(selectedTabIndex = tab) {
      Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Нравится" + (likes?.size?.let { " · $it" } ?: "")) })
      Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Скачанные · ${downloads.size}") })
    }
    if (tab == 0) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
        val list = likes
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
          when {
            list == null && error != null ->
              item { MessageState("Не получилось загрузить", error, action = "Повторить", onAction = vm::refresh) }
            list == null -> item { LoadingState() }
            list.isEmpty() ->
              item {
                MessageState(
                  "Здесь будут любимые треки",
                  "Нажмите ♡ в плеере или в меню трека",
                  icon = Icons.Rounded.FavoriteBorder,
                )
              }
            else -> {
              item { LikesHeader(list) }
              itemsIndexed(list, key = { _, t -> "like:" + t.id }) { i, t ->
                TrackRow(t, onClick = { actions.play(list, i) }, modifier = Modifier.padding(horizontal = 8.dp))
              }
            }
          }
        }
      }
    } else {
      LazyColumn(contentPadding = contentPadding, modifier = Modifier.weight(1f)) {
        if (downloads.isEmpty()) {
          item {
            MessageState(
              "Пока ничего не скачано",
              "Скачанные треки играют без интернета. Скачать можно из меню трека или кнопкой в плеере",
              icon = Icons.Rounded.DownloadForOffline,
            )
          }
        } else {
          val done = downloads.filter { it.state == TrackDownload.State.Done }.map { it.track }
          item {
            Row(
              Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(onClick = { actions.play(done, 0) }, enabled = done.isNotEmpty()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(6.dp))
                Text("Слушать")
              }
              FilledTonalButton(onClick = { actions.play(done.shuffled(), 0) }, enabled = done.size > 1) {
                Icon(Icons.Rounded.Shuffle, null, Modifier.size(ButtonDefaults.IconSize))
              }
              Spacer(Modifier.weight(1f))
              AssistChip(
                onClick = onOpenSettings,
                label = { Text(downloadsBytes?.let(::formatBytes) ?: "…") },
                leadingIcon = { Icon(Icons.Rounded.Storage, null, Modifier.size(18.dp)) },
              )
            }
          }
          itemsIndexed(downloads, key = { _, d -> "dl:" + d.track.id }) { _, d ->
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
      }
    }
  }
}

@Composable
private fun LikesHeader(list: List<Track>) {
  val actions = LocalTrackActions.current
  val marks = LocalTrackMarks.current
  val missing = list.filter { it.id !in marks.downloads }
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Button(onClick = { actions.play(list, 0) }) {
      Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(6.dp))
      Text("Слушать")
    }
    FilledTonalButton(onClick = { actions.play(list.shuffled(), 0) }, enabled = list.size > 1) {
      Icon(Icons.Rounded.Shuffle, null, Modifier.size(ButtonDefaults.IconSize))
    }
    Spacer(Modifier.weight(1f))
    if (missing.isNotEmpty()) {
      FilledTonalButton(onClick = { actions.download(missing) }) {
        Icon(Icons.Rounded.FileDownload, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(6.dp))
        Text(if (missing.size == list.size) "Скачать все" else "Ещё ${pluralTracks(missing.size)}")
      }
    }
  }
}
