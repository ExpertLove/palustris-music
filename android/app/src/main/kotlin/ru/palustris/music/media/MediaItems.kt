package ru.palustris.music.media

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import ru.palustris.music.core.AppModule
import ru.palustris.music.core.model.Track

/** Откуда пришла очередь: «Моя волна» сама подгружает продолжение. */
object QueueSource {
  const val WAVE = "wave"
}

private const val EXTRA_TRACK = "track"
private const val EXTRA_SOURCE = "src"

fun Track.toMediaItem(source: String? = null): MediaItem {
  val extras =
    Bundle().apply {
      putString(EXTRA_TRACK, AppModule.json.encodeToString(Track.serializer(), this@toMediaItem))
      if (source != null) putString(EXTRA_SOURCE, source)
    }
  return MediaItem.Builder()
    .setMediaId(id)
    .setUri(Streams.uri(this))
    .setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(Streams.artwork(thumb)?.toUri())
        .setDurationMs(if (duration > 0) duration * 1000 else null)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(extras)
        .build()
    )
    .build()
}

/** Трек обратно из элемента очереди. Если extras потерялись, собираем из метаданных. */
fun MediaItem.toTrack(): Track {
  val raw = mediaMetadata.extras?.getString(EXTRA_TRACK)
  raw?.let { runCatching { return AppModule.json.decodeFromString(Track.serializer(), it) } }
  return Track(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString(),
    duration = (mediaMetadata.durationMs ?: 0) / 1000,
    thumb = mediaMetadata.artworkUri?.toString(),
  )
}

val MediaItem.queueSource: String?
  get() = mediaMetadata.extras?.getString(EXTRA_SOURCE)

/** Элементы, пришедшие от контроллера, могут быть без URI: восстанавливаем по треку. */
fun MediaItem.withPlayableUri(): MediaItem =
  if (localConfiguration != null) this else buildUpon().setUri(Streams.uri(toTrack())).build()
