package ru.palustris.music.core

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.palustris.music.BuildConfig
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.prefs.SessionStore

/** Скоуп на всё время жизни процесса: для работы, которая не должна обрываться с экраном. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
  val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
  }

  @Provides @Singleton fun json(): Json = json

  @Provides
  @Singleton
  @AppScope
  fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Provides
  @Singleton
  fun okHttp(session: SessionStore): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      // Первая загрузка трека и перекодирование в Opus на сервере занимают секунды.
      .readTimeout(90, TimeUnit.SECONDS)
      .addInterceptor(AuthInterceptor(session))
      .build()

  @Provides
  @Singleton
  fun retrofit(client: OkHttpClient, json: Json): Retrofit =
    Retrofit.Builder()
      .baseUrl(BuildConfig.API_BASE_URL)
      .client(client)
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
      .build()

  @Provides @Singleton fun api(retrofit: Retrofit): MusicApi = retrofit.create(MusicApi::class.java)
}

/** Токен уходит только на наш сервер: обложки с googleusercontent его не получают. */
private class AuthInterceptor(private val session: SessionStore) : Interceptor {
  private val host: String = java.net.URI(BuildConfig.API_BASE_URL).host

  override fun intercept(chain: Interceptor.Chain): Response {
    val req = chain.request()
    if (req.url.host != host) return chain.proceed(req)
    val token = session.tokenBlocking() ?: return chain.proceed(req)
    val res = chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    if (res.code == 401 && !req.url.encodedPath.startsWith("/api/login")) session.onUnauthorized(token)
    return res
  }
}
