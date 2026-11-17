package ru.palustris.music.core.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.palustris.music.core.model.ArtistPage
import ru.palustris.music.core.model.Collection
import ru.palustris.music.core.model.Creds
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.model.IdBody
import ru.palustris.music.core.model.LibraryArtist
import ru.palustris.music.core.model.Lyrics
import ru.palustris.music.core.model.NameBody
import ru.palustris.music.core.model.NewPlaylist
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.EmailBody
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.model.LoginResp
import ru.palustris.music.core.model.Loudness
import ru.palustris.music.core.model.Me
import ru.palustris.music.core.model.SearchAll
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.model.ChangePasswordBody
import ru.palustris.music.core.model.ChangePasswordResp
import ru.palustris.music.core.model.ProfileBody
import ru.palustris.music.core.model.RemovedResp
import ru.palustris.music.core.model.ResendResp
import ru.palustris.music.core.model.ShareResp
import ru.palustris.music.core.model.SharedPlaylist
import ru.palustris.music.core.model.ArtistNameBody
import ru.palustris.music.core.model.Dislikes
import ru.palustris.music.core.model.ListenBody
import ru.palustris.music.core.model.Mixes
import ru.palustris.music.core.model.Stats
import ru.palustris.music.core.model.Wrapped

/** Бэкенд music.palustris.ru, см. android/docs/API.md. */
interface MusicApi {
  @POST("api/login") suspend fun login(@Body creds: Creds): LoginResp

  @POST("api/register") suspend fun register(@Body creds: Creds): LoginResp

  @POST("api/logout") suspend fun logout()

  @POST("api/password/forgot") suspend fun forgotPassword(@Body body: EmailBody)

  @GET("api/me") suspend fun me(): Me

  @POST("api/profile") suspend fun setProfile(@Body body: ProfileBody)

  @POST("api/password/change") suspend fun changePassword(@Body body: ChangePasswordBody): ChangePasswordResp

  @POST("api/logout-others") suspend fun logoutOthers(): RemovedResp

  /** Письмо для подтверждения почты: не чаще раза в минуту. */
  @POST("api/resend") suspend fun resendVerification(): ResendResp

  /** Удаление аккаунта со всеми лайками, плейлистами и историей. */
  @DELETE("api/account") suspend fun deleteAccount()

  /**
   * «Моя волна». `mode`: favorite | discover | popular (пусто — всё вместе); `mood`: energetic | happy |
   * calm | sad; `activity`: wakeup | road | work | workout | sleep | party; `region` — страна для «Популярного».
   */
  @GET("api/wave")
  suspend fun wave(
    @Query("mode") mode: String? = null,
    @Query("mood") mood: String? = null,
    @Query("activity") activity: String? = null,
    @Query("region") region: String? = null,
  ): List<Track>

  /** Радио по треку: похожие песни (YouTube Music), без скрытого пользователем. */
  @GET("api/radio")
  suspend fun radio(@Query("id") id: String, @Query("title") title: String?, @Query("artist") artist: String?): List<Track>

  /** Радио исполнителя: по browseId или по имени. */
  @GET("api/radio")
  suspend fun artistRadio(@Query("artistId") artistId: String?, @Query("artistName") artistName: String?): List<Track>

  /** Миксы дня, забытые лайки, «на повторе». */
  @GET("api/mixes") suspend fun mixes(): Mixes

  @GET("api/dislikes") suspend fun dislikes(): Dislikes

  /** «Не нравится»: трек пропадает из рекомендаций, лайк снимается. */
  @POST("api/dislikes") suspend fun dislike(@Body track: Track)

  @DELETE("api/dislikes/{id}") suspend fun undislike(@Path("id") id: String)

  /** «Не предлагать исполнителя». */
  @POST("api/blocked-artists") suspend fun blockArtist(@Body body: ArtistNameBody)

  @HTTP(method = "DELETE", path = "api/blocked-artists", hasBody = true) suspend fun unblockArtist(@Body body: ArtistNameBody)

  /** Статистика: `period` = week | month | year | all, `tz` — сдвиг от UTC в минутах. */
  @GET("api/stats") suspend fun stats(@Query("period") period: String, @Query("tz") tz: Int): Stats

  @GET("api/wrapped") suspend fun wrapped(@Query("year") year: Int?, @Query("tz") tz: Int): Wrapped

  /** Персональные рекомендации «Для вас». */
  @GET("api/recs") suspend fun recs(): List<Track>

  /** Чарт недели. `region` — две латинские буквы, пусто — весь мир. */
  @GET("api/top") suspend fun top(@Query("region") region: String? = null): List<Track>

  @GET("api/search/all") suspend fun search(@Query("q") query: String): SearchAll

  /** Только треки. `kind = "songs"` — только песни (с длительностью, без клипов и часовых подборок). */
  @GET("api/search") suspend fun searchTracks(@Query("q") query: String, @Query("kind") kind: String? = null): List<Track>

  @GET("api/likes") suspend fun likes(): List<Track>

  @POST("api/likes") suspend fun like(@Body track: Track)

  @DELETE("api/likes/{id}") suspend fun unlike(@Path("id") id: String)

  @POST("api/history") suspend fun history(@Body body: ListenBody)

  @POST("api/skip") suspend fun skip(@Body track: Track)

  @POST("api/prefetch") suspend fun prefetch(@Body body: IdsBody)

  /** Громкость известных серверу треков; остальных в ответе нет, спросить позже. */
  @POST("api/loudness") suspend fun loudness(@Body body: IdsBody): Map<String, Loudness>

  @GET("api/lyrics")
  suspend fun lyrics(@Query("artist") artist: String, @Query("title") title: String, @Query("duration") duration: Long): Lyrics

  @GET("api/yt/artist") suspend fun artist(@Query("id") id: String?, @Query("name") name: String?): ArtistPage

  @GET("api/yt/collection/{id}") suspend fun collection(@Path("id") id: String): Collection

  @GET("api/history") suspend fun history(@Query("limit") limit: Int = 100): List<HistoryItem>

  /** Стереть историю и пропуски: «Моя волна» начнёт учиться заново. */
  @DELETE("api/history") suspend fun clearHistory()

  /** Исполнители из лайков и истории, самые частые сверху (до 60). */
  @GET("api/artists") suspend fun libraryArtists(): List<LibraryArtist>

  @GET("api/playlists") suspend fun playlists(): List<PlaylistRow>

  @POST("api/playlists") suspend fun createPlaylist(@Body body: NewPlaylist): IdBody

  @GET("api/playlists/{id}") suspend fun playlist(@Path("id") id: Long): PlaylistFull

  @PATCH("api/playlists/{id}") suspend fun renamePlaylist(@Path("id") id: Long, @Body body: NameBody)

  @DELETE("api/playlists/{id}") suspend fun deletePlaylist(@Path("id") id: Long)

  @PUT("api/playlists/{id}/order") suspend fun reorderPlaylist(@Path("id") id: Long, @Body body: IdsBody)

  @POST("api/playlists/{id}/items") suspend fun addToPlaylist(@Path("id") id: Long, @Body track: Track)

  @DELETE("api/playlists/{id}/items/{track}") suspend fun removeFromPlaylist(@Path("id") id: Long, @Path("track") trackId: String)

  /** Открыть доступ по ссылке: `https://music.palustris.ru/s/{share}`. Повторный вызов отдаёт ту же ссылку. */
  @POST("api/playlists/{id}/share") suspend fun sharePlaylist(@Path("id") id: Long): ShareResp

  @DELETE("api/playlists/{id}/share") suspend fun unsharePlaylist(@Path("id") id: Long)

  /** Чужой плейлист по ссылке, вход не нужен. */
  @GET("api/shared/{slug}") suspend fun shared(@Path("slug") slug: String): SharedPlaylist
}
