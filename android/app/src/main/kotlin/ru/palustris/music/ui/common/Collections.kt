package ru.palustris.music.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.palustris.music.core.model.AlbumCard
import ru.palustris.music.core.model.ArtistCard
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.TrackDownload

@Composable
fun BackBar(onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
  Row(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
    Spacer(Modifier.weight(1f))
    actions()
  }
}

/** Шапка альбома, плейлиста или исполнителя: обложка, название, подпись, фон в цвет темы. */
@Composable
fun CollectionHeader(
  title: String,
  subtitle: String?,
  onBack: () -> Unit,
  cover: @Composable (Modifier) -> Unit,
  coverShape: Shape = RoundedCornerShape(28.dp),
  actions: @Composable () -> Unit = {},
) {
  val scheme = MaterialTheme.colorScheme
  Box(
    Modifier.fillMaxWidth()
      .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.7f), scheme.surface)))
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
      BackBar(onBack, actions = actions)
      cover(Modifier.size(220.dp).clip(coverShape))
      Spacer(Modifier.height(18.dp))
      Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp),
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )
      }
    }
  }
}

/** «Слушать», «Перемешать», «Скачать всё». Кнопка скачивания показывает, скачано ли уже всё. */
@Composable
fun PlayActions(tracks: List<Track>, modifier: Modifier = Modifier, source: String? = null, trailing: @Composable () -> Unit = {}) {
  val actions = LocalTrackActions.current
  val marks = LocalTrackMarks.current
  val missing = tracks.filter { marks.downloads[it.id]?.state.let { s -> s == null || s == TrackDownload.State.Failed } }
  Row(
    modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Button(onClick = { actions.play(tracks, 0, source) }, enabled = tracks.isNotEmpty()) {
      Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(6.dp))
      Text("Слушать")
    }
    FilledTonalIconButton(onClick = { actions.play(tracks.shuffled(), 0, source) }, enabled = tracks.size > 1) {
      Icon(Icons.Rounded.Shuffle, "Перемешать")
    }
    FilledTonalIconButton(onClick = { actions.download(missing) }, enabled = missing.isNotEmpty()) {
      Icon(
        if (missing.isEmpty() && tracks.isNotEmpty()) Icons.Rounded.DownloadDone else Icons.Rounded.FileDownload,
        if (missing.isEmpty()) "Всё скачано" else "Скачать все",
      )
    }
    Spacer(Modifier.weight(1f))
    trailing()
  }
}

/** Квадратная карточка альбома или плейлиста для горизонтальных рядов. */
@Composable
fun AlbumTile(album: AlbumCard, onClick: () -> Unit, width: Dp = 148.dp) {
  Column(Modifier.width(width).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick, role = Role.Button).padding(4.dp)) {
    Artwork(album.thumb, Modifier.fillMaxWidth().aspectRatio(1f), size = 300, shape = RoundedCornerShape(18.dp))
    Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    Text(
      album.sub.ifBlank { album.artist },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun AlbumRow(title: String, albums: List<AlbumCard>, onOpen: (AlbumCard) -> Unit) {
  if (albums.isEmpty()) return
  SectionTitle(title)
  LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    items(albums, key = { it.id }) { a -> AlbumTile(a, onClick = { onOpen(a) }) }
  }
}

@Composable
fun ArtistBubble(artist: ArtistCard, onClick: () -> Unit) {
  Column(
    Modifier.width(100.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick, role = Role.Button).padding(4.dp),
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

@Composable
fun ArtistRow(title: String, artists: List<ArtistCard>, onOpen: (ArtistCard) -> Unit) {
  if (artists.isEmpty()) return
  SectionTitle(title)
  LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    items(artists, key = { it.id }) { a -> ArtistBubble(a) { onOpen(a) } }
  }
}

/** Обложка плейлиста: мозаика из четырёх обложек треков или одна, если треков мало. */
@Composable
fun PlaylistCover(thumbs: List<String>, modifier: Modifier = Modifier) {
  Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
    when {
      thumbs.size >= 4 ->
        Column(Modifier.fillMaxSize()) {
          for (r in 0..1) Row(Modifier.weight(1f)) {
            for (c in 0..1) Artwork(thumbs[r * 2 + c], Modifier.weight(1f).fillMaxSize(), size = 160, shape = RoundedCornerShape(0.dp))
          }
        }
      thumbs.isNotEmpty() -> Artwork(thumbs.first(), Modifier.fillMaxSize(), size = 300, shape = RoundedCornerShape(0.dp))
      else -> Artwork(null, Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
    }
  }
}

@Composable
fun FilledTonalTextButton(text: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
  FilledTonalButton(onClick = onClick) {
    if (icon != null) {
      Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(6.dp))
    }
    Text(text)
  }
}
