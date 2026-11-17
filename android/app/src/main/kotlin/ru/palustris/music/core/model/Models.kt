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

/** Профиль из `/api/me`. `created` — unix-время регистрации, `sessions` — на скольких устройствах выполнен вход. */
@Immutable
@Serializable
data class Me(
  val email: String,
  val name: String = "",
  val verified: Boolean = false,
  val admin: Boolean = false,
  val genres: List<String> = emptyList(),
  val created: Long = 0,
  val likes: Int = 0,
  val playlists: Int = 0,
  val plays: Int = 0,
  val sessions: Int = 1,
)

/** Сервер заменяет имя и жанры целиком: отправлять нужно оба поля. */
@Serializable data class ProfileBody(val name: String, val genres: List<String>)

@Serializable data class ChangePasswordBody(val old: String, val new: String)

@Serializable data class ChangePasswordResp(val ok: Boolean = true, val loggedOut: Int = 0)

@Serializable data class RemovedResp(val removed: Int = 0)

@Serializable data class ResendResp(val verified: Boolean = false, val letterSent: Boolean = false)

@Serializable data class ShareResp(val share: String)

/** Плейлист по публичной ссылке `/s/{slug}`. */
@Serializable
data class SharedPlaylist(val name: String, val owner: String = "", val share: String = "", val items: List<Track> = emptyList())

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

/** Исполнитель из «Моей музыки»: собран из лайков и истории. `count` — прослушивания и лайки вместе. */
@Immutable
@Serializable
data class LibraryArtist(val name: String, val count: Int = 0, val thumb: String? = null)

/** Прослушивание для `/api/history`: когда трек играл на самом деле (unix, с) и сколько секунд. */
@Serializable
data class ListenBody(
  val id: String,
  val title: String,
  val artist: String,
  val album: String? = null,
  val duration: Long = 0,
  val thumb: String? = null,
  val at: Long = 0,
  val secs: Long = 0,
)

/** Микс дня: группа похожих любимых исполнителей + новое рядом с ними. */
@Immutable
@Serializable
data class Mix(
  val id: String,
  val title: String,
  val sub: String = "",
  val thumbs: List<String> = emptyList(),
  val tracks: List<Track> = emptyList(),
)

/** `/api/mixes`: миксы дня, забытые лайки и «на повторе». */
@Immutable
@Serializable
data class Mixes(
  val mixes: List<Mix> = emptyList(),
  val forgotten: List<Track> = emptyList(),
  val repeat: List<Track> = emptyList(),
)

@Immutable @Serializable data class BlockedArtist(val name: String, val at: Long = 0)

/** Что скрыто из рекомендаций: треки с «не нравится» и исполнители. */
@Immutable
@Serializable
data class Dislikes(val tracks: List<Track> = emptyList(), val artists: List<BlockedArtist> = emptyList())

@Serializable data class ArtistNameBody(val name: String)

@Immutable
@Serializable
data class ArtistStat(
  val name: String,
  val plays: Int = 0,
  val minutes: Int = 0,
  val thumb: String? = null,
  /** Фото исполнителя (есть только у первых мест). */
  val image: String? = null,
  @SerialName("artistId") val artistId: String? = null,
)

/** Трек в статистике: поля трека плюс сколько раз и сколько минут. */
@Immutable
@Serializable
data class TrackStat(
  val id: String,
  val title: String,
  val artist: String,
  val album: String? = null,
  val duration: Long = 0,
  val thumb: String? = null,
  val plays: Int = 0,
  val minutes: Int = 0,
) {
  fun toTrack() = Track(id, title, artist, album, duration, thumb)
}

@Immutable @Serializable data class StatBucket(val start: Long, val minutes: Int = 0)

/** `/api/stats`: прослушивания за неделю, месяц, год или всё время. */
@Immutable
@Serializable
data class Stats(
  val period: String = "week",
  val from: Long = 0,
  val minutes: Int = 0,
  val plays: Int = 0,
  val tracks: Int = 0,
  val artists: Int = 0,
  val prevMinutes: Int? = null,
  val topArtists: List<ArtistStat> = emptyList(),
  val topTracks: List<TrackStat> = emptyList(),
  val hours: List<Int> = emptyList(),
  val weekdays: List<Int> = emptyList(),
  val buckets: List<StatBucket> = emptyList(),
  val bucketKind: String = "day",
  val streak: Int = 0,
  val bestStreak: Int = 0,
  val newArtists: Int = 0,
  val since: Long = 0,
)

@Immutable @Serializable data class DayStat(val start: Long, val minutes: Int = 0, val plays: Int = 0)

/** `/api/wrapped`: итоги года. */
@Immutable
@Serializable
data class Wrapped(
  val year: Int,
  val minutes: Int = 0,
  val plays: Int = 0,
  val tracks: Int = 0,
  val artists: Int = 0,
  val days: Int = 0,
  val topArtists: List<ArtistStat> = emptyList(),
  val topTracks: List<TrackStat> = emptyList(),
  val months: List<Int> = emptyList(),
  val hours: List<Int> = emptyList(),
  val busiestDay: DayStat? = null,
  val bestStreak: Int = 0,
  val newArtists: Int = 0,
  val firstTrack: Track? = null,
  val firstAt: Long = 0,
  val likes: Int = 0,
  val percentile: Int? = null,
)
