package ru.palustris.music.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.palustris.music.core.model.ArtistCard
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.SectionTitle
import ru.palustris.music.ui.common.TrackRow

@Composable
fun SearchScreen(contentPadding: PaddingValues, vm: SearchViewModel = hiltViewModel()) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val actions = LocalTrackActions.current
  val focus = LocalFocusManager.current
  val result = ui.result

  LazyColumn(contentPadding = contentPadding) {
    item {
      Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Поиск", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        TextField(
          value = ui.query,
          onValueChange = vm::onQuery,
          placeholder = { Text("Песня или исполнитель") },
          leadingIcon = { Icon(Icons.Rounded.Search, null) },
          trailingIcon = {
            if (ui.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Rounded.Close, "Очистить") }
          },
          singleLine = true,
          shape = CircleShape,
          colors =
            TextFieldDefaults.colors(
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    when {
      ui.loading && result == null -> item { LoadingState() }
      ui.error != null -> item { MessageState("Поиск не удался", ui.error) }
      result == null ->
        item {
          MessageState(
            "Что послушаем?",
            "Ищите по названию песни или имени исполнителя",
            icon = Icons.Rounded.Search,
          )
        }
      result.tracks.isEmpty() && result.artists.isEmpty() ->
        item { MessageState("Ничего не нашлось", "Попробуйте написать иначе", icon = Icons.Rounded.SearchOff) }
      else -> {
        val artists = listOfNotNull(result.top) + result.artists.filter { it.id != result.top?.id }
        if (artists.isNotEmpty()) {
          item { SectionTitle("Исполнители") }
          item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              items(artists, key = { it.id }) { a -> ArtistBubble(a) { vm.onQuery(a.name) } }
            }
          }
        }
        if (result.tracks.isNotEmpty()) {
          item {
            SectionTitle("Треки")
          }
          itemsIndexed(result.tracks, key = { _, t -> "t:" + t.id }) { i, t ->
            TrackRow(t, onClick = { actions.play(result.tracks, i) }, modifier = Modifier.padding(horizontal = 8.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun ArtistBubble(artist: ArtistCard, onClick: () -> Unit) {
  Column(
    Modifier.width(96.dp).clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Artwork(artist.thumb, Modifier.size(88.dp), size = 226, shape = CircleShape)
    Text(
      artist.name,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 2,
      textAlign = TextAlign.Center,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}
