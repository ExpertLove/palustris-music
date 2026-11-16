package ru.palustris.music.core.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.palustris.music.core.model.ArtistPage
import ru.palustris.music.core.model.Collection
import ru.palustris.music.core.model.Creds
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.model.IdBody
import ru.palustris.music.core.model.Lyrics
import ru.palustris.music.core.model.NameBody
import ru.palustris.music.core.model.NewPlaylist
import ru.palustris.music.core.model.PlaylistFull
import ru.palustris.music.core.model.PlaylistRow
import ru.palustris.music.core.model.EmailBody
import ru.palustris.music.core.model.IdsBody
import ru.palustris.music.core.model.LoginResp
import ru.palustris.music.core.model.Me
import ru.palustris.music.core.model.SearchAll
import ru.palustris.music.core.model.Track

/** Бэкенд music.palustris.ru, см. android/docs/API.md. */
interface MusicApi {
  @POST("api/login") suspend fun login(@Body creds: Creds): LoginResp

  @POST("api/register") suspend fun register(@Body creds: Creds): LoginResp

  @POST("api/logout") suspend fun logout()

  @POST("api/password/forgot") suspend fun forgotPassword(@Body body: EmailBody)

  @GET("api/me") suspend fun me(): Me

  @GET("api/wave") suspend fun wave(): List<Track>

  @GET("api/top") suspend fun top(): List<Track>

  @GET("api/search/all") suspend fun search(@Query("q") query: String): SearchAll

  @GET("api/likes") suspend fun likes(): List<Track>

  @POST("api/likes") suspend fun like(@Body track: Track)

  @DELETE("api/likes/{id}") suspend fun unlike(@Path("id") id: String)

  @POST("api/history") suspend fun history(@Body track: Track)

  @POST("api/skip") suspend fun skip(@Body track: Track)

  @POST("api/prefetch") suspend fun prefetch(@Body body: IdsBody)

  @GET("api/lyrics")
  suspend fun lyrics(@Query("artist") artist: String, @Query("title") title: String, @Query("duration") duration: Long): Lyrics

  @GET("api/yt/artist") suspend fun artist(@Query("id") id: String?, @Query("name") name: String?): ArtistPage

  @GET("api/yt/collection/{id}") suspend fun collection(@Path("id") id: String): Collection

  @GET("api/history") suspend fun history(@Query("limit") limit: Int = 100): List<HistoryItem>

  @GET("api/playlists") suspend fun playlists(): List<PlaylistRow>

  @POST("api/playlists") suspend fun createPlaylist(@Body body: NewPlaylist): IdBody

  @GET("api/playlists/{id}") suspend fun playlist(@Path("id") id: Long): PlaylistFull

  @PATCH("api/playlists/{id}") suspend fun renamePlaylist(@Path("id") id: Long, @Body body: NameBody)

  @DELETE("api/playlists/{id}") suspend fun deletePlaylist(@Path("id") id: Long)

  @PUT("api/playlists/{id}/order") suspend fun reorderPlaylist(@Path("id") id: Long, @Body body: IdsBody)

  @POST("api/playlists/{id}/items") suspend fun addToPlaylist(@Path("id") id: Long, @Body track: Track)

  @DELETE("api/playlists/{id}/items/{track}") suspend fun removeFromPlaylist(@Path("id") id: Long, @Path("track") trackId: String)
}
