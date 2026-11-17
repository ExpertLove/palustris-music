package ru.palustris.music.ui.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.ui.catalog.Page
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.PlayActions
import ru.palustris.music.ui.common.TrackRow

data class Region(val code: String, val title: String)

/** Страны чарта: те же, что на сайте (`REGIONS` в web/src/lib/data.ts). */
val REGIONS =
  listOf(
    Region("", "Весь мир"),
    Region("RU", "Россия"),
    Region("BY", "Беларусь"),
    Region("KZ", "Казахстан"),
    Region("UA", "Украина"),
    Region("UZ", "Узбекистан"),
    Region("GE", "Грузия"),
    Region("AM", "Армения"),
    Region("DE", "Германия"),
    Region("US", "США"),
    Region("GB", "Великобритания"),
    Region("FR", "Франция"),
    Region("ES", "Испания"),
    Region("IT", "Италия"),
    Region("PL", "Польша"),
    Region("TR", "Турция"),
    Region("AE", "ОАЭ"),
    Region("IL", "Израиль"),
    Region("IN", "Индия"),
    Region("KR", "Южная Корея"),
    Region("JP", "Япония"),
    Region("BR", "Бразилия"),
    Region("CA", "Канада"),
    Region("AU", "Австралия"),
  )

/** Выбранная страна или страна телефона, если её нет в списке — Россия. */
fun resolveRegion(saved: String?): String {
  if (saved != null && REGIONS.any { it.code == saved }) return saved
  val country = Locale.getDefault().country.uppercase()
  return if (REGIONS.any { it.code == country && country.isNotEmpty() }) country else "RU"
}

fun regionTitle(code: String): String = REGIONS.firstOrNull { it.code == code }?.title ?: "Весь мир"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChartViewModel @Inject constructor(private val api: MusicApi, private val settings: SettingsStore) : ViewModel() {
  val region: StateFlow<String> =
    settings.settings.filterNotNull().map { resolveRegion(it.region) }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.Eagerly, resolveRegion(settings.current.region))

  private val _page = MutableStateFlow(Page<List<Track>>())
  val page: StateFlow<Page<List<Track>>> = _page.asStateFlow()

  init {
    viewModelScope.launch {
      region.collect { r ->
        _page.value = Page()
        _page.value = apiCall { api.top(r.ifEmpty { null }) }.fold({ Page(it) }, { Page(error = it.message) })
      }
    }
  }

  fun setRegion(code: String) = viewModelScope.launch { settings.setRegion(code) }
}

@Composable
fun ChartScreen(contentPadding: PaddingValues, onBack: () -> Unit, vm: ChartViewModel = hiltViewModel()) {
  val region by vm.region.collectAsStateWithLifecycle()
  val page by vm.page.collectAsStateWithLifecycle()
  val actions = LocalTrackActions.current
  val chips = rememberLazyListState(initialFirstVisibleItemIndex = (REGIONS.indexOfFirst { it.code == region } - 1).coerceAtLeast(0))
  val tracks = page.data
  val source = "chart:$region"

  LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
    item {
      Column {
        BackBar(onBack)
        Text("Чарт недели", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
          "Самое популярное на YouTube Music · ${regionTitle(region)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        )
        LazyRow(state = chips, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(REGIONS, key = { it.code }) { r ->
            FilterChip(selected = r.code == region, onClick = { vm.setRegion(r.code) }, label = { Text(r.title) })
          }
        }
      }
    }
    when {
      tracks == null && page.error != null -> item { MessageState("Чарт не загрузился", page.error) }
      tracks == null -> item { LoadingState() }
      tracks.isEmpty() -> item { MessageState("Для этой страны чарта нет", "Выберите другую") }
      else -> {
        item { PlayActions(tracks, source = source) }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
          TrackRow(t, onClick = { actions.play(tracks, i, source) }, modifier = Modifier.padding(horizontal = 8.dp), leading = { ChartNumber(i) })
        }
      }
    }
  }
}

/** Место в чарте: первая тройка выделена цветом. */
@Composable
fun ChartNumber(i: Int) {
  Text(
    "${i + 1}",
    style = MaterialTheme.typography.titleMedium,
    fontWeight = if (i < 3) FontWeight.Bold else FontWeight.Normal,
    color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.width(30.dp),
  )
}
