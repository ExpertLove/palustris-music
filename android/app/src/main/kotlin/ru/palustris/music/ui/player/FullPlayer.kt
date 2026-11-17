package ru.palustris.music.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.palustris.music.core.prefs.Settings
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.State
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import ru.palustris.music.media.SleepState
import ru.palustris.music.ui.common.LocalNavigator
import coil3.compose.AsyncImage
import ru.palustris.music.media.PlayerState
import ru.palustris.music.media.Progress
import ru.palustris.music.media.Streams
import ru.palustris.music.media.Substitute
import ru.palustris.music.media.TrackDownload
import ru.palustris.music.ui.common.Artwork
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.LocalTrackMarks
import ru.palustris.music.ui.common.PlayingBars
import ru.palustris.music.ui.common.formatTime
import ru.palustris.music.ui.common.TrackMenu
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import ru.palustris.music.media.QueueSource
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt

/** Команды плеера, которые нужны экрану. */
interface PlayerControls {
  fun togglePlay()

  fun next()

  fun previous()

  /** Предыдущий трек очереди (а не начало текущего): для жеста по обложке. */
  fun previousTrack()

  fun seekTo(ms: Long)

  fun skipTo(index: Int)

  fun toggleShuffle()

  fun cycleRepeat()

  /** `false` — очередь успела измениться, ничего не переставлено. */
  fun moveInQueue(from: Int, to: Int, id: String): Boolean

  fun removeFromQueue(index: Int, id: String)

  fun clearUpNext()

  fun setSpeed(percent: Int)
}

/** «Версия с SoundCloud» под исполнителем; по нажатию — что это значит. */
@Composable
private fun SubstituteNote(sub: Substitute, trackId: String) {
  var info by remember(trackId) { mutableStateOf(false) }
  Text(
    "Версия с ${sub.sourceName}",
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.tertiary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier =
      Modifier.padding(top = 2.dp)
        .clip(RoundedCornerShape(6.dp))
        .clickable(onClickLabel = "Что это значит") { info = true },
  )
  if (info) {
    val what = listOf(sub.artist, sub.title).filter { it.isNotBlank() }.joinToString(" — ")
    AlertDialog(
      onDismissRequest = { info = false },
      confirmButton = { TextButton(onClick = { info = false }) { Text("Понятно") } },
      title = { Text("Запасной источник") },
      text = {
        Text(
          "YouTube сейчас не отдаёт этот трек, поэтому играет такая же запись с ${sub.sourceName}" +
            (if (what.isNotEmpty()) ": «$what»." else ".") +
            " Скачать трек получится, когда YouTube снова его отдаст."
        )
      },
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
  state: PlayerState,
  progress: State<Progress>,
  controls: PlayerControls,
  onCollapse: () -> Unit,
  sleep: SleepState,
  onSleep: (Int) -> Unit,
  eqEnabled: Boolean,
  onOpenEqualizer: () -> Unit,
  onOpenArtist: () -> Unit,
  speedPercent: Int = 100,
  sheet: PlayerSheetState? = null,
  beat: Beat? = null,
  /** YouTube не отдал трек: играет такая же запись из другого каталога. */
  substitute: Substitute? = null,
) {
  val track = state.track ?: return
  val marks = LocalTrackMarks.current
  val actions = LocalTrackActions.current
  val nav = LocalNavigator.current
  var showQueue by remember { mutableStateOf(false) }
  var showSleep by remember { mutableStateOf(false) }
  var showSpeed by remember { mutableStateOf(false) }
  var showLyrics by rememberSaveable { mutableStateOf(false) }
  val lyricsVm: LyricsViewModel = hiltViewModel()
  val lyrics by lyricsVm.state.collectAsStateWithLifecycle()
  LaunchedEffect(track.id, showLyrics) { if (showLyrics) lyricsVm.load(track) }
  val liked = track.id in marks.liked
  val dl = marks.downloads[track.id]
  val scheme = MaterialTheme.colorScheme

  Box(
    Modifier.fillMaxSize()
      .background(scheme.surface)
      // Плеер лежит поверх экранов: касания не должны проходить к спискам под ним.
      .pointerInput(Unit) { detectTapGestures { } }
      // Лист ездит за пальцем: вниз — свернуть. Списки внутри (текст песни) прокручиваются сами.
      .then(
        if (sheet != null)
          Modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { dy -> sheet.dragBy(-dy) },
            onDragStopped = { v -> sheet.settle(-v) },
          )
        else Modifier.pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag > 18) onCollapse() } }
      )
  ) {
    // Фон: живые пятна в цветах обложки, сверху — сама обложка, сильно размытая.
    LiveBackground(beat, Modifier.fillMaxSize())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      AsyncImage(
        model = Streams.artwork(track.thumb, 120),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = 0.3f },
      )
    }

    Column(
      Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть", Modifier.size(32.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text("Сейчас играет", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
          if (state.queue.size > 1) {
            Text(
              "${state.index + 1} из ${state.queue.size}",
              style = MaterialTheme.typography.labelSmall,
              color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
          }
        }
        // Скорость не обычная: показываем, чтобы не гадать, почему музыка звучит быстрее.
        if (speedPercent != 100) {
          TextButton(onClick = { showSpeed = true }) {
            Text(
              Settings.speedLabel(speedPercent),
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.semantics { contentDescription = "Скорость ${Settings.speedLabel(speedPercent)}. Изменить" },
            )
          }
        }
        IconButton(onClick = { nav.addToPlaylist(track) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "В плейлист") }
        Box {
          var menu by remember { mutableStateOf(false) }
          IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
          TrackMenu(
            track,
            expanded = menu,
            onDismiss = { menu = false },
            liked = liked,
            disliked = track.id in marks.disliked,
            dl = dl,
            actions = actions,
            inPlayer = true,
          )
        }
      }

      if (showLyrics) {
        Spacer(Modifier.height(8.dp))
        LyricsPanel(lyrics, progress, controls::seekTo, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(12.dp))
      } else {
      Spacer(Modifier.weight(0.6f))

      // Обложка чуть «выдыхает» на паузе и пульсирует на ударах баса.
      val scale by animateFloatAsState(
        if (state.playWhenReady) 1f else 0.88f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale",
      )
      val prev = state.queue.getOrNull(state.index - 1)
      val next = state.upNext.firstOrNull()?.let { state.queue.getOrNull(it) }
      SwipeArtwork(
        current = track,
        prev = prev,
        next = next,
        onNext = controls::next,
        onPrev = controls::previousTrack,
        modifier =
          Modifier.widthIn(max = 420.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { c -> if (sheet != null && sheet.progress.value == 1f && sheet.back == 0f) sheet.fullArt = c.boundsInWindow() }
            // Пока обложка перелетает из мини-плеера, настоящая спрятана.
            .graphicsLayer { alpha = if (sheet?.morphing == true) 0f else 1f },
      ) { t, m ->
        Artwork(
          t.thumb,
          m.fillMaxSize()
            .graphicsLayer {
              val pulse = 1f + 0.035f * (beat?.bass ?: 0f)
              scaleX = scale * pulse
              scaleY = scale * pulse
            }
            .shadow(28.dp, RoundedCornerShape(32.dp)),
          size = 720,
          shape = RoundedCornerShape(32.dp),
        )
      }
      if (beat != null && state.isPlaying) {
        Spectrum(beat, Modifier.padding(top = 18.dp).widthIn(max = 280.dp).fillMaxWidth().height(22.dp))
      } else {
        Spacer(Modifier.height(40.dp))
      }

      Spacer(Modifier.weight(0.5f))
      }

      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            track.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            modifier = Modifier.basicMarquee(initialDelayMillis = 2000),
          )
          Text(
            track.artist,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Открыть исполнителя", onClick = onOpenArtist),
          )
          // Почему этот трек в волне или радио: «Похоже на «Tusa»».
          val why = track.why
          if (why != null && QueueSource.isRecommended(state.source)) {
            Text(
              why,
              style = MaterialTheme.typography.labelMedium,
              color = scheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
          if (substitute != null) SubstituteNote(substitute, track.id)
        }
        if (QueueSource.isRecommended(state.source)) {
          val disliked = track.id in marks.disliked
          IconButton(onClick = { actions.dislike(track) }) {
            Icon(
              if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
              if (disliked) "Убрать из «Не нравится»" else "Не нравится",
              tint = scheme.onSurfaceVariant,
            )
          }
        }
        DownloadButton(dl, onDownload = { actions.download(listOf(track)) }, onRemove = { actions.removeDownload(track) })
        IconButton(onClick = { actions.toggleLike(track) }) {
          Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Убрать из «Нравится»" else "Нравится",
            tint = if (liked) scheme.primary else scheme.onSurface,
          )
        }
      }

      Spacer(Modifier.height(12.dp))
      SeekBar(progress, state.durationMs, controls::seekTo)

      Spacer(Modifier.height(8.dp))
      Controls(state, controls)

      Spacer(Modifier.height(if (showLyrics) 8.dp else 20.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        PlayerAction(Icons.Rounded.Lyrics, "Текст", showLyrics) { showLyrics = !showLyrics }
        PlayerAction(Icons.Rounded.Bedtime, sleepLabel(sleep), sleep != SleepState.Off) { showSleep = true }
        PlayerAction(Icons.Rounded.GraphicEq, "Эквалайзер", eqEnabled, onOpenEqualizer)
        PlayerAction(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", false) { showQueue = true }
      }
      if (!showLyrics) Spacer(Modifier.weight(0.4f))
      if (state.error != null) {
        Text(state.error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
      }
      Spacer(Modifier.height(16.dp))
    }
  }

  if (showQueue) {
    // Высокая сразу: в полураскрытой перетаскивать неудобно.
    ModalBottomSheet(onDismissRequest = { showQueue = false }, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
      QueueSheet(state, controls)
    }
  }
  if (showSpeed) {
    ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
      SpeedSheet(speedPercent) {
        controls.setSpeed(it)
        showSpeed = false
      }
    }
  }
  if (showSleep) {
    ModalBottomSheet(onDismissRequest = { showSleep = false }) {
      SleepSheet(sleep) {
        onSleep(it)
        showSleep = false
      }
    }
  }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
    if (active) {
      FilledTonalIconButton(onClick = onClick) { Icon(icon, label) }
    } else {
      IconButton(onClick = onClick) { Icon(icon, label, tint = scheme.onSurfaceVariant) }
    }
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = if (active) scheme.primary else scheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

/** Сколько осталось: «24 мин», «0:45», «Конец трека». */
@Composable
private fun sleepLabel(sleep: SleepState): String =
  when (sleep) {
    SleepState.Off -> "Таймер"
    SleepState.EndOfTrack -> "Конец трека"
    is SleepState.At -> {
      val left by produceState(sleep.endsAt - SystemClock.elapsedRealtime(), sleep) {
        while (true) {
          value = sleep.endsAt - SystemClock.elapsedRealtime()
          delay(1000)
        }
      }
      val min = (left / 60_000).toInt()
      if (min >= 1) "$min мин" else formatTime(left.coerceAtLeast(0))
    }
  }

@Composable
private fun SleepSheet(sleep: SleepState, onPick: (Int) -> Unit) {
  Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
    Text("Таймер сна", style = MaterialTheme.typography.titleLarge)
    Text(
      when (sleep) {
        SleepState.Off -> "Музыка плавно затихнет и встанет на паузу"
        SleepState.EndOfTrack -> "Остановится, когда закончится этот трек"
        is SleepState.At -> "Остановится через ${sleepLabel(sleep)}"
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      listOf(15, 30, 45, 60, 90).forEach { m -> FilterChip(selected = false, onClick = { onPick(m) }, label = { Text("$m мин") }) }
      FilterChip(selected = sleep == SleepState.EndOfTrack, onClick = { onPick(-1) }, label = { Text("Когда закончится трек") })
    }
    if (sleep != SleepState.Off) {
      TextButton(onClick = { onPick(0) }, modifier = Modifier.padding(top = 8.dp)) { Text("Выключить таймер") }
    }
  }
}

@Composable
private fun SpeedSheet(percent: Int, onPick: (Int) -> Unit) {
  Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
    Text("Скорость", style = MaterialTheme.typography.titleLarge)
    Text(
      "Тон голоса не меняется. Выбор запоминается",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    SpeedChips(percent, onPick)
  }
}

/** Варианты скорости, как на сайте. Нужны и в плеере, и в настройках. */
@Composable
fun SpeedChips(percent: Int, onPick: (Int) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Settings.SPEED_OPTIONS_PERCENT.forEach { p ->
      FilterChip(selected = percent == p, onClick = { onPick(p) }, label = { Text(Settings.speedLabel(p)) })
    }
  }
}

@Composable
private fun DownloadButton(dl: TrackDownload?, onDownload: () -> Unit, onRemove: () -> Unit) {
  IconButton(onClick = { if (dl == null || dl.state == TrackDownload.State.Failed) onDownload() else onRemove() }) {
    when (dl?.state) {
      null, TrackDownload.State.Failed -> Icon(Icons.Rounded.FileDownload, "Скачать на телефон")
      TrackDownload.State.Done -> Icon(Icons.Rounded.DownloadDone, "Скачан. Нажмите, чтобы удалить", tint = MaterialTheme.colorScheme.primary)
      else -> {
        val p = dl.progress
        if (p == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        else CircularProgressIndicator(progress = { p }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
      }
    }
  }
}

@Composable
private fun SeekBar(progress: State<Progress>, durationHint: Long, onSeek: (Long) -> Unit) {
  val p = progress.value
  val duration = if (p.durationMs > 0) p.durationMs else durationHint
  var dragging by remember { mutableStateOf(false) }
  val slider = remember { SliderState(0f) }
  val fraction = if (duration > 0) (p.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
  // Пока палец не держит ползунок, он идёт за воспроизведением.
  LaunchedEffect(fraction, dragging) { if (!dragging) slider.value = fraction }
  val shown = if (dragging) slider.value else fraction

  Column(Modifier.fillMaxWidth()) {
    Slider(
      state = slider,
      onValueChange = {
        dragging = true
        slider.value = it
      },
      onValueChangeFinished = {
        onSeek((slider.value * duration).toLong())
        dragging = false
      },
      enabled = duration > 0,
      colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
      Text(
        formatTime((shown * duration).toLong()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(state: PlayerState, controls: PlayerControls) {
  val scheme = MaterialTheme.colorScheme
  // Кнопка «играть» — круг на паузе и мягкий квадрат во время игры, с пружинкой.
  val corner by animateDpAsState(
    if (state.playWhenReady) 30.dp else 46.dp,
    spring(dampingRatio = 0.45f, stiffness = 500f),
    label = "playShape",
  )
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = controls::toggleShuffle) {
      Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) scheme.primary else scheme.onSurfaceVariant)
    }
    IconButton(onClick = controls::previous, modifier = Modifier.size(60.dp)) {
      Icon(Icons.Rounded.SkipPrevious, "Предыдущий", Modifier.size(38.dp))
    }
    FilledIconButton(
      onClick = controls::togglePlay,
      modifier = Modifier.size(92.dp),
      shape = RoundedCornerShape(corner),
      colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
    ) {
      if (state.isBuffering) LoadingIndicator(Modifier.size(72.dp), color = scheme.onPrimary)
      Icon(
        if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        if (state.playWhenReady) "Пауза" else "Играть",
        Modifier.size(if (state.isBuffering) 26.dp else 44.dp),
      )
    }
    IconButton(onClick = controls::next, enabled = state.hasNext, modifier = Modifier.size(60.dp)) {
      Icon(Icons.Rounded.SkipNext, "Следующий", Modifier.size(38.dp))
    }
    IconButton(onClick = controls::cycleRepeat) {
      Icon(
        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
        when (state.repeatMode) {
          Player.REPEAT_MODE_ONE -> "Повтор трека"
          Player.REPEAT_MODE_ALL -> "Повтор очереди"
          else -> "Повтор выключен"
        },
        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) scheme.primary else scheme.onSurfaceVariant,
      )
    }
  }
}
