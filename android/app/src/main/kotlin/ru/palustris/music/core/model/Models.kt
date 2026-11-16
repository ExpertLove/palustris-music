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
