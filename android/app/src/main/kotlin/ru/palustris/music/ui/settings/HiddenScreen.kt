package ru.palustris.music.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Dislikes
import ru.palustris.music.data.TasteRepository
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.SectionTitle
import ru.palustris.music.ui.common.TrackRow

@HiltViewModel
class HiddenViewModel @Inject constructor(private val taste: TasteRepository) : ViewModel() {
  val state: StateFlow<Dislikes> = taste.state

  fun refresh() {
    viewModelScope.launch { taste.refresh() }
  }

  fun unblock(name: String) = taste.unblock(name)

  fun undislike(id: String) = taste.undislike(id)
}

/** «Скрытое»: что не попадает в рекомендации. Отсюда можно вернуть. */
@Composable
fun HiddenScreen(contentPadding: PaddingValues, onBack: () -> Unit, vm: HiddenViewModel = hiltViewModel()) {
  val s by vm.state.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { vm.refresh() }
  val actions = LocalTrackActions.current
  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    item { BackBar(onBack) }
    item {
      Text("Скрытое", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
      Text(
        "Этого не будет в «Моей волне», радио и миксах. Сами треки и исполнители не пропадают: их можно найти и включить",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
      )
    }
    if (s.artists.isEmpty() && s.tracks.isEmpty()) {
      item {
        MessageState(
          "Ничего не скрыто",
          "Нажмите «Не нравится» или «Не предлагать исполнителя» в меню трека",
          icon = Icons.Rounded.ThumbDown,
        )
      }
    }
    if (s.artists.isNotEmpty()) {
      item { SectionTitle("Исполнители") }
      items(s.artists, key = { "a:" + it.name }) { a ->
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.PersonOff, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
          TextButton(onClick = { vm.unblock(a.name) }) { Text("Вернуть") }
        }
      }
    }
    if (s.tracks.isNotEmpty()) {
      item { SectionTitle("Не нравится") }
      items(s.tracks, key = { "t:" + it.id }) { t ->
        TrackRow(
          t,
          // Скрыт только из рекомендаций: послушать можно.
          onClick = { actions.play(listOf(t), 0) },
          modifier = Modifier.padding(horizontal = 8.dp),
          trailing = { TextButton(onClick = { vm.undislike(t.id) }) { Text("Вернуть") } },
        )
      }
    }
  }
}
