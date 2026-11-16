package ru.palustris.music.core.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.palustris.music.core.model.Creds
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
}
