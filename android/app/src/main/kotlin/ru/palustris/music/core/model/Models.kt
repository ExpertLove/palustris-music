package ru.palustris.music.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Трек в том виде, в каком его отдаёт и принимает бэкенд. `duration` в секундах. */
@Immutable
@Serializable
data class Track(
  val id: String,
  val title: String,
  val artist: String,
  val album: String? = null,
  val duration: Long = 0,
  val thumb: String? = null,
  @SerialName("artistId") val artistId: String? = null,
  @SerialName("albumId") val albumId: String? = null,
  /** Только у «Моей волны»: почему трек попал в подборку. */
  val why: String? = null,
)

@Immutable
@Serializable
data class ArtistCard(val id: String, val name: String, val thumb: String? = null, val sub: String = "")

@Immutable
@Serializable
data class AlbumCard(
  val id: String,
  val title: String,
  val artist: String = "",
  val sub: String = "",
  val thumb: String? = null,
  val kind: String = "album",
)

@Serializable
data class SearchAll(
  val top: ArtistCard? = null,
  val tracks: List<Track> = emptyList(),
  val artists: List<ArtistCard> = emptyList(),
  val albums: List<AlbumCard> = emptyList(),
  val playlists: List<AlbumCard> = emptyList(),
)

@Serializable
data class Creds(
  val email: String,
  val password: String,
  val name: String? = null,
  val consent: Boolean = false,
  val genres: List<String> = emptyList(),
)

@Serializable data class LoginResp(val token: String, val email: String)

@Serializable
data class Me(
  val email: String,
  val name: String = "",
  val verified: Boolean = false,
  val likes: Int = 0,
  val playlists: Int = 0,
  val plays: Int = 0,
)

@Serializable data class EmailBody(val email: String)

@Serializable data class IdsBody(val ids: List<String>)

/** Громкость трека по EBU R128: интегральная (LUFS) и пиковая (dBTP). */
@Serializable data class Loudness(val lufs: Float, val peak: Float)

@Serializable
data class Lyrics(
  val synced: String? = null,
  val plain: String? = null,
  val instrumental: Boolean = false,
  val found: Boolean = false,
)

@Serializable
data class ArtistPage(
  val id: String,
  val name: String,
  val thumb: String? = null,
  val listeners: String = "",
  val description: String = "",
  val tracks: List<Track> = emptyList(),
  @SerialName("allTracks") val allTracks: String? = null,
  val albums: List<AlbumCard> = emptyList(),
  val singles: List<AlbumCard> = emptyList(),
  val playlists: List<AlbumCard> = emptyList(),
  val similar: List<ArtistCard> = emptyList(),
)

/** Альбом, сингл или чужой плейлист из YouTube Music. */
@Serializable
data class Collection(
  val id: String,
  val kind: String = "album",
  val title: String,
  val artist: String = "",
  @SerialName("artistId") val artistId: String? = null,
  val sub: String = "",
  val thumb: String? = null,
  val description: String = "",
  val tracks: List<Track> = emptyList(),
)

@Immutable
@Serializable
data class PlaylistRow(
  val id: Long,
  val name: String,
  val count: Int = 0,
  val thumbs: List<String> = emptyList(),
  val share: String? = null,
)

@Serializable
data class PlaylistFull(val id: Long, val name: String, val share: String? = null, val items: List<Track> = emptyList())

@Serializable data class NewPlaylist(val name: String, val items: List<Track> = emptyList())

@Serializable data class NameBody(val name: String)

@Serializable data class IdBody(val id: Long)

@Serializable
data class HistoryItem(
  val id: String,
  val title: String,
  val artist: String,
  val album: String? = null,
  val duration: Long = 0,
  val thumb: String? = null,
  @SerialName("playedAt") val playedAt: Long = 0,
  val plays: Int = 1,
) {
  fun toTrack() = Track(id, title, artist, album, duration, thumb)
}
