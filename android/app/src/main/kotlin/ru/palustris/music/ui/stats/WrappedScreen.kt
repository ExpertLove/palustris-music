package ru.palustris.music.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.ArtistStat
import ru.palustris.music.core.model.TrackStat
import ru.palustris.music.core.model.Wrapped
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.OfflineFiles
import ru.palustris.music.media.Streams
import ru.palustris.music.ui.catalog.Page
import ru.palustris.music.ui.common.plural
import ru.palustris.music.ui.common.rememberShareCapture
import ru.palustris.music.ui.home.tzMinutes

@HiltViewModel
class WrappedViewModel @Inject constructor(private val api: MusicApi, private val files: OfflineFiles) : ViewModel() {
  private val _page = MutableStateFlow(Page<Wrapped>())
  val page: StateFlow<Page<Wrapped>> = _page.asStateFlow()

  init {
    viewModelScope.launch {
      files.read(FILE, Wrapped.serializer())?.let { _page.value = Page(it) }
      apiCall { api.wrapped(null, tzMinutes()) }
        .onSuccess {
          _page.value = Page(it)
          files.write(FILE, Wrapped.serializer(), it)
        }
        .onFailure { e -> if (_page.value.data == null) _page.value = Page(error = e.message) }
    }
  }

  private companion object {
    const val FILE = "wrapped.json"
  }
}

/** Одна карточка итогов: своё содержимое и свои цвета фона. */
private sealed interface Story {
  val colors: List<Color>

  data object Intro : Story {
    override val colors = listOf(Color(0xFFFF4D8D), Color(0xFF7B47E8), Color(0xFF16123A))
  }

  data object Minutes : Story {
    override val colors = listOf(Color(0xFF1DB9A0), Color(0xFF1C5BD8), Color(0xFF0B1630))
  }

  data class TopArtist(val a: ArtistStat) : Story {
    override val colors = listOf(Color(0xFFFF8A3D), Color(0xFFD8336F), Color(0xFF2A0F24))
  }

  data class Artists(val list: List<ArtistStat>) : Story {
    override val colors = listOf(Color(0xFF7B47E8), Color(0xFF2AA2E0), Color(0xFF0E1433))
  }

  data class TopTrack(val t: TrackStat) : Story {
    override val colors = listOf(Color(0xFFF0C33C), Color(0xFFE0612F), Color(0xFF2B1A0E))
  }

  data class Tracks(val list: List<TrackStat>) : Story {
    override val colors = listOf(Color(0xFF2C9A52), Color(0xFF139184), Color(0xFF0B2219))
  }

  data object Months : Story {
    override val colors = listOf(Color(0xFF5150D8), Color(0xFFB8285F), Color(0xFF15122E))
  }

  data class Time(val p: Persona) : Story {
    override val colors = listOf(Color(0xFF3B3A9E), Color(0xFF8C3FD6), Color(0xFF0C0B22))
  }

  data object Facts : Story {
    override val colors = listOf(Color(0xFFE8562B), Color(0xFF7B47E8), Color(0xFF1A0F2E))
  }

  data object Summary : Story {
    override val colors = listOf(Color(0xFFFF4D8D), Color(0xFF7B47E8), Color(0xFF1C1B4B))
  }
}

private fun stories(w: Wrapped): List<Story> = buildList {
  add(Story.Intro)
  add(Story.Minutes)
  w.topArtists.firstOrNull()?.let { add(Story.TopArtist(it)) }
  if (w.topArtists.size >= 3) add(Story.Artists(w.topArtists))
  w.topTracks.firstOrNull()?.let { add(Story.TopTrack(it)) }
  if (w.topTracks.size >= 3) add(Story.Tracks(w.topTracks))
  if (w.months.count { it > 0 } >= 2) add(Story.Months)
  persona(w.hours)?.let { add(Story.Time(it)) }
  add(Story.Facts)
  add(Story.Summary)
}

private const val STORY_MS = 7_000

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WrappedScreen(onBack: () -> Unit, vm: WrappedViewModel = hiltViewModel()) {
  val page by vm.page.collectAsStateWithLifecycle()
  val w = page.data
  Box(Modifier.fillMaxSize().background(Color(0xFF0B0A1A))) {
    when {
      w == null && page.error == null -> LoadingIndicator(Modifier.align(Alignment.Center), color = Color.White)
      w == null || w.plays < 5 ->
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Text("Итоги пока не готовы", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
          Text(
            page.error ?: "Слушайте музыку в приложении — к концу года здесь будет ваша история",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      else -> Stories(w, onBack)
    }
    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 14.dp, end = 4.dp)) {
      Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White)
    }
  }
}

@Composable
private fun Stories(w: Wrapped, onClose: () -> Unit) {
  val list = remember(w) { stories(w) }
  var index by rememberSaveable { mutableIntStateOf(0) }
  var paused by remember { mutableStateOf(false) }
  val progress = remember { Animatable(0f) }
  val capture = rememberShareCapture()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  fun go(to: Int) {
    if (to >= list.size) onClose() else index = to.coerceAtLeast(0)
  }

  // Окно «Поделиться» и сворачивание приложения тоже ставят на паузу: вернувшись, человек
  // должен увидеть ту же карточку.
  val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  val hold = paused || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)

  LaunchedEffect(index) { progress.snapTo(0f) }
  LaunchedEffect(index, hold) {
    if (hold) return@LaunchedEffect
    val left = ((1f - progress.value) * STORY_MS).toInt()
    progress.animateTo(1f, tween(left, easing = LinearEasing))
    // Последняя карточка не закрывается сама: на ней кнопка «Поделиться».
    if (index < list.lastIndex) go(index + 1)
  }

  val story = list[index]
  Box(
    Modifier.fillMaxSize().pointerInput(list.size) {
      detectTapGestures(
        onPress = {
          paused = true
          tryAwaitRelease()
          paused = false
        },
        onTap = { o -> if (o.x < size.width / 3f) go(index - 1) else go(index + 1) },
      )
    }
  ) {
    // Карточка и есть картинка для «Поделиться».
    Box(capture.modifier.fillMaxSize()) {
      StoryBackground(story.colors)
      AnimatedContent(
        index,
        transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(200)) },
        label = "story",
      ) { i ->
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 96.dp)) {
          StoryContent(list[i], w)
        }
      }
      Text(
        "Музыка · music.palustris.ru",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 28.dp, bottom = 36.dp),
      )
    }
    // Полоски прогресса.
    Row(
      Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      list.indices.forEach { i ->
        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
          val f = when {
            i < index -> 1f
            i == index -> progress.value
            else -> 0f
          }
          Box(Modifier.fillMaxWidth(f).height(3.dp).background(Color.White))
        }
      }
    }
    Button(
      onClick = {
        scope.launch {
          capture.share(
            context,
            "itogi-${w.year}",
            "Мои итоги ${w.year} в Музыке: ${plural(w.minutes, "минута", "минуты", "минут")}" +
              (w.topArtists.firstOrNull()?.let { ", исполнитель года — ${it.name}" } ?: "") + ". music.palustris.ru",
          )
        }
      },
      colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
      modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 24.dp),
    ) {
      Icon(Icons.Rounded.IosShare, null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(6.dp))
      Text("Поделиться")
    }
  }
}

/** Фон карточки: три мягких цветных пятна медленно плавают по кругу. */
@Composable
private fun StoryBackground(colors: List<Color>) {
  val t = rememberInfiniteTransition(label = "bg")
  val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
  Canvas(Modifier.fillMaxSize()) {
    drawRect(colors.last())
    val r = size.maxDimension * 0.75f
    listOf(0f, 2.1f, 4.2f).forEachIndexed { i, shift ->
      val c = colors[i % (colors.size - 1)]
      val center = Offset(size.width * (0.5f + 0.35f * cos(phase + shift)), size.height * (0.4f + 0.3f * sin(phase * 0.8f + shift)))
      drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.85f), c.copy(alpha = 0f)), center, r), r, center)
    }
  }
}

@Composable
private fun BoxScope.StoryContent(s: Story, w: Wrapped) {
  val white = Color.White
  when (s) {
    Story.Intro ->
      Column(Modifier.align(Alignment.CenterStart)) {
        Text("${w.year}", fontSize = 120.sp, fontWeight = FontWeight.Black, color = white, lineHeight = 120.sp)
        Text("Ваш год\nв музыке", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = white)
        Text(
          "Нажимайте справа, чтобы листать, и удерживайте, чтобы остановить",
          style = MaterialTheme.typography.bodyMedium,
          color = white.copy(alpha = 0.75f),
          modifier = Modifier.padding(top = 20.dp),
        )
      }
    Story.Minutes -> {
      val n = remember { Animatable(0f) }
      LaunchedEffect(Unit) { n.animateTo(w.minutes.toFloat(), tween(1600, easing = FastOutSlowInEasing)) }
      Column(Modifier.align(Alignment.CenterStart)) {
        Text("Вы слушали музыку", style = MaterialTheme.typography.headlineSmall, color = white)
        Text(groupDigits(n.value.roundToInt()), fontSize = 80.sp, fontWeight = FontWeight.Black, color = white, lineHeight = 84.sp)
        Text(plural(w.minutes, "минуту", "минуты", "минут").substringAfter(' '), style = MaterialTheme.typography.headlineSmall, color = white)
        val hours = w.minutes / 60
        if (hours >= 2) {
          Text(
            "Это ${plural(hours, "час", "часа", "часов")}" + (if (hours >= 48) ", или ${plural(hours / 24, "сутки", "суток", "суток")} без перерыва" else ""),
            style = MaterialTheme.typography.titleMedium,
            color = white.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 20.dp),
          )
        }
        w.percentile?.takeIf { it >= 30 }?.let {
          Text("Больше, чем у $it % слушателей", style = MaterialTheme.typography.titleMedium, color = white.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        }
      }
    }
    is Story.TopArtist ->
      Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Исполнитель года", style = MaterialTheme.typography.titleLarge, color = white.copy(alpha = 0.85f))
        Spacer(Modifier.height(20.dp))
        ShareImage(s.a.image ?: s.a.thumb, Modifier.size(240.dp).clip(CircleShape))
        Spacer(Modifier.height(24.dp))
        Text(s.a.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = white, textAlign = TextAlign.Center)
        Text(
          "${minutesLong(s.a.minutes)} · ${plural(s.a.plays, "прослушивание", "прослушивания", "прослушиваний")}",
          style = MaterialTheme.typography.titleMedium,
          color = white.copy(alpha = 0.85f),
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    is Story.Artists ->
      Column(Modifier.align(Alignment.CenterStart)) {
        Text("Ваши исполнители", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = white)
        Spacer(Modifier.height(20.dp))
        s.list.forEachIndexed { i, a ->
          Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = white, modifier = Modifier.width(32.dp))
            ShareImage(a.image ?: a.thumb, Modifier.size(56.dp).clip(CircleShape))
            Column(Modifier.padding(start = 14.dp)) {
              Text(a.name, style = MaterialTheme.typography.titleLarge, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(minutesLong(a.minutes), style = MaterialTheme.typography.bodyMedium, color = white.copy(alpha = 0.75f))
            }
          }
        }
      }
    is Story.TopTrack ->
      Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Песня года", style = MaterialTheme.typography.titleLarge, color = white.copy(alpha = 0.85f))
        Spacer(Modifier.height(20.dp))
        ShareImage(s.t.thumb, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)))
        Spacer(Modifier.height(24.dp))
        Text(s.t.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = white, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(s.t.artist, style = MaterialTheme.typography.titleMedium, color = white.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        Text(
          "Звучала ${plural(s.t.plays, "раз", "раза", "раз")}",
          style = MaterialTheme.typography.titleMedium,
          color = white.copy(alpha = 0.85f),
          modifier = Modifier.padding(top = 10.dp),
        )
      }
    is Story.Tracks ->
      Column(Modifier.align(Alignment.CenterStart)) {
        Text("Ваши треки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = white)
        Spacer(Modifier.height(20.dp))
        s.list.forEachIndexed { i, t ->
          Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = white, modifier = Modifier.width(32.dp))
            ShareImage(t.thumb, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.padding(start = 14.dp)) {
              Text(t.title, style = MaterialTheme.typography.titleMedium, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text("${t.artist} · ${plural(t.plays, "раз", "раза", "раз")}", style = MaterialTheme.typography.bodyMedium, color = white.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    Story.Months -> {
      val best = w.months.indices.maxBy { w.months[it] }
      Column(Modifier.align(Alignment.CenterStart)) {
        Text("Самый музыкальный месяц", style = MaterialTheme.typography.titleLarge, color = white.copy(alpha = 0.85f))
        Text(MONTHS_FULL[best].replaceFirstChar { it.uppercase() }, fontSize = 56.sp, fontWeight = FontWeight.Black, color = white)
        Text(minutesLong(w.months[best]), style = MaterialTheme.typography.titleMedium, color = white.copy(alpha = 0.85f))
        Spacer(Modifier.height(28.dp))
        BarChart(w.months, MONTHS_SHORT.map { it.take(1).uppercase() }, height = 160.dp, color = white, dim = white.copy(alpha = 0.35f), description = "Минуты по месяцам")
      }
    }
    is Story.Time ->
      Column(Modifier.align(Alignment.CenterStart)) {
        Text("Вы —", style = MaterialTheme.typography.headlineSmall, color = white.copy(alpha = 0.85f))
        Text(s.p.title.lowercase(), fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Black, color = white)
        Text(s.p.text, style = MaterialTheme.typography.titleMedium, color = white.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        BarChart(w.hours, (0 until 24).map { if (it % 6 == 0) "$it" else "" }, height = 140.dp, color = white, dim = white.copy(alpha = 0.35f), description = "Минуты по часам суток")
      }
    Story.Facts ->
      Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("А ещё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = white)
        Fact(groupDigits(w.tracks), plural(w.tracks, "разный трек", "разных трека", "разных треков").substringAfter(' '))
        if (w.newArtists > 0) Fact(groupDigits(w.newArtists), plural(w.newArtists, "новый исполнитель", "новых исполнителя", "новых исполнителей").substringAfter(' '))
        if (w.bestStreak > 1) Fact("${w.bestStreak}", plural(w.bestStreak, "день", "дня", "дней").substringAfter(' ') + " подряд с музыкой")
        w.busiestDay?.let { d -> Fact(minutesLong(d.minutes), "в самый музыкальный день — ${dayTitle(d.start)}") }
        if (w.likes > 0) Fact(groupDigits(w.likes), plural(w.likes, "трек в «Мне нравится»", "трека в «Мне нравится»", "треков в «Мне нравится»").substringAfter(' '))
      }
    Story.Summary -> SummaryCard(w)
  }
}

@Composable
private fun Fact(value: String, text: String) {
  Column {
    Text(value, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black, color = Color.White)
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
  }
}

@Composable
private fun BoxScope.SummaryCard(w: Wrapped) {
  val white = Color.White
  Column(
    Modifier.align(Alignment.Center)
      .fillMaxWidth()
      .clip(RoundedCornerShape(32.dp))
      .background(Color.Black.copy(alpha = 0.35f))
      .padding(24.dp)
      .semantics { contentDescription = "Итоги ${w.year}" }
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ShareImage(w.topArtists.firstOrNull()?.let { it.image ?: it.thumb }, Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)))
      Column(Modifier.padding(start = 16.dp)) {
        Text("Итоги ${w.year}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = white)
        Text(groupDigits(w.minutes) + " " + plural(w.minutes, "минута", "минуты", "минут").substringAfter(' '), style = MaterialTheme.typography.titleMedium, color = white.copy(alpha = 0.85f))
      }
    }
    Spacer(Modifier.height(20.dp))
    Row {
      SummaryColumn("Исполнители", w.topArtists.map { it.name }, Modifier.weight(1f))
      Spacer(Modifier.width(12.dp))
      SummaryColumn("Треки", w.topTracks.map { it.title }, Modifier.weight(1f))
    }
  }
}

@Composable
private fun SummaryColumn(title: String, items: List<String>, modifier: Modifier) {
  Column(modifier) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
    items.take(5).forEachIndexed { i, s ->
      Text("${i + 1}. $s", style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

/** Картинка без аппаратного битмапа: иначе её не получится сохранить в «Поделиться». */
@Composable
private fun ShareImage(url: String?, modifier: Modifier) {
  val context = LocalContext.current
  val req = remember(url) { ImageRequest.Builder(context).data(Streams.artwork(url, 600)).allowHardware(false).build() }
  Box(modifier.background(Color.White.copy(alpha = 0.12f))) {
    AsyncImage(req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
  }
}
