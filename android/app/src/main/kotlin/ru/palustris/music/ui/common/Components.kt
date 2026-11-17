package ru.palustris.music.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.Streams
import ru.palustris.music.media.TrackDownload

/** Обложка с заглушкой в цветах темы. Кадры клипов 4:3 обрезаются до квадрата. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, size: Int = 226, shape: Shape = RoundedCornerShape(14.dp)) {
  Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
    SubcomposeAsyncImage(
      model = Streams.artwork(url, size),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
      error = { ArtworkPlaceholder() },
      loading = { ArtworkPlaceholder() },
    )
  }
}

@Composable
private fun ArtworkPlaceholder() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Icon(
      Icons.Rounded.MusicNote,
      null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
      modifier = Modifier.fillMaxSize(0.4f),
    )
  }
}

/** Три прыгающих столбика поверх обложки играющего трека. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
  val t = rememberInfiniteTransition(label = "bars")
  val heights =
    listOf(420, 560, 360).mapIndexed { i, dur ->
      t.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(dur, delayMillis = i * 90), RepeatMode.Reverse),
        label = "bar$i",
      )
    }
  Row(modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.Bottom) {
    heights.forEach { h ->
      Box(
        Modifier.width(3.5.dp)
          .fillMaxSize()
          .graphicsLayer {
            val f = if (playing) h.value else 0.35f
            scaleY = f
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
          }
          .background(color, RoundedCornerShape(2.dp))
      )
    }
  }
}

/** Значок состояния загрузки: кольцо прогресса, галочка или ошибка. */
@Composable
fun DownloadBadge(dl: TrackDownload?, modifier: Modifier = Modifier, size: Dp = 18.dp) {
  AnimatedContent(
    targetState = dl?.state,
    transitionSpec = { fadeIn() togetherWith fadeOut() },
    modifier = modifier,
    label = "dl",
  ) { st ->
    when (st) {
      null -> Spacer(Modifier.size(0.dp))
      TrackDownload.State.Done ->
        Icon(Icons.Rounded.DownloadDone, "Скачан", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size))
      TrackDownload.State.Failed ->
        Icon(Icons.Rounded.ErrorOutline, "Не скачался", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(size))
      else -> {
        val p = dl?.progress
        if (p == null || st == TrackDownload.State.Queued)
          CircularProgressIndicator(Modifier.size(size - 2.dp), strokeWidth = 2.dp)
        else CircularProgressIndicator(progress = { p }, modifier = Modifier.size(size - 2.dp), strokeWidth = 2.dp)
      }
    }
  }
}

/**
 * Строка трека. Нажатие — играть список с этого места, долгое нажатие или «⋮» — меню.
 */
@Composable
fun TrackRow(
  track: Track,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  leading: (@Composable () -> Unit)? = null,
  extra: List<ExtraAction> = emptyList(),
  /** Перед меню: например, ручка для перетаскивания в плейлисте. */
  trailing: (@Composable () -> Unit)? = null,
) {
  val marks = LocalTrackMarks.current
  val actions = LocalTrackActions.current
  val isCurrent = marks.currentId == track.id
  val dl = marks.downloads[track.id]
  var menu by remember { mutableStateOf(false) }
  val haptics = LocalHapticFeedback.current

  Row(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
      .combinedClickable(
        onClick = onClick,
        onLongClick = {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          menu = true
        },
      )
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leading?.invoke()
    Box(Modifier.size(52.dp)) {
      Artwork(track.thumb, Modifier.fillMaxSize(), size = 120)
      if (isCurrent) {
        Box(
          Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)),
          contentAlignment = Alignment.Center,
        ) {
          PlayingBars(marks.isPlaying)
        }
      }
    }
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(
        track.title,
        style = MaterialTheme.typography.titleMedium,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (dl != null) {
          DownloadBadge(dl, size = 14.dp)
          Spacer(Modifier.width(5.dp))
        }
        Text(
          subtitle ?: track.artist,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (track.duration > 0) {
      Text(
        formatTime(track.duration * 1000),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
    trailing?.invoke()
    Box {
      IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
      TrackMenu(
        track,
        expanded = menu,
        onDismiss = { menu = false },
        liked = track.id in marks.liked,
        disliked = track.id in marks.disliked,
        dl = dl,
        actions = actions,
        extra = extra,
      )
    }
  }
}

/** Меню трека: очередь, плейлист, лайк, радио, загрузка, «не нравится». Есть и в полном плеере. */
@Composable
fun TrackMenu(
  track: Track,
  expanded: Boolean,
  onDismiss: () -> Unit,
  liked: Boolean,
  disliked: Boolean,
  dl: TrackDownload?,
  actions: TrackActions,
  extra: List<ExtraAction> = emptyList(),
  /** В полном плеере очередь и «Исполнитель» есть и так. */
  inPlayer: Boolean = false,
) {
  val nav = LocalNavigator.current
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
    fun item(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) =
      @Composable {
        DropdownMenuItem(
          text = { Text(text) },
          leadingIcon = { Icon(icon, null) },
          onClick = {
            action()
            onDismiss()
          },
        )
      }
    if (!inPlayer) {
      item("Играть следующим", Icons.Rounded.QueuePlayNext) { actions.playNext(track) }()
      item("Добавить в очередь", Icons.AutoMirrored.Rounded.QueueMusic) { actions.enqueue(track) }()
    }
    item("Радио по треку", Icons.Rounded.Radio) { actions.startRadio(track) }()
    item("В плейлист", Icons.AutoMirrored.Rounded.PlaylistAdd) { nav.addToPlaylist(track) }()
    if (liked) item("Убрать из «Нравится»", Icons.Rounded.Favorite) { actions.toggleLike(track) }()
    else item("Нравится", Icons.Rounded.FavoriteBorder) { actions.toggleLike(track) }()
    when (dl?.state) {
      null -> item("Скачать на телефон", Icons.Rounded.FileDownload) { actions.download(listOf(track)) }()
      TrackDownload.State.Failed -> item("Скачать ещё раз", Icons.Rounded.FileDownload) { actions.download(listOf(track)) }()
      TrackDownload.State.Done -> item("Удалить с телефона", Icons.Rounded.Delete) { actions.removeDownload(track) }()
      else -> item("Отменить загрузку", Icons.Rounded.Delete) { actions.removeDownload(track) }()
    }
    if (!inPlayer) item("Исполнитель", Icons.Rounded.Person) { nav.openArtist(track.artistId, track.artist.substringBefore(',').trim()) }()
    track.albumId?.let { album -> item("Альбом", Icons.Rounded.Album) { nav.openCollection(album) }() }
    extra.forEach { e -> item(e.title, e.icon, e.onClick)() }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    if (disliked) item("Убрать из «Не нравится»", Icons.Rounded.ThumbDown) { actions.dislike(track) }()
    else item("Не нравится", Icons.Rounded.ThumbDownOffAlt) { actions.dislike(track) }()
    item("Не предлагать исполнителя", Icons.Rounded.PersonOff) { actions.hideArtist(track) }()
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

@Composable
fun MessageState(
  title: String,
  text: String? = null,
  modifier: Modifier = Modifier,
  icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.CloudOff,
  action: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Column(
    modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(34.dp))
    }
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (text != null) {
      Spacer(Modifier.height(6.dp))
      Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    if (action != null && onAction != null) {
      Spacer(Modifier.height(18.dp))
      FilledTonalButton(onClick = onAction) { Text(action) }
    }
  }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
  Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    trailing?.invoke()
  }
}
