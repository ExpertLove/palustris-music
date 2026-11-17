package ru.palustris.music.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.palustris.music.BuildConfig
import timber.log.Timber

/**
 * Обложки для Android Auto. Машина не грузит картинки по http, ей нужен `content://`:
 * провайдер скачивает обложку один раз в `cacheDir/auto_art` и отдаёт файл.
 *
 * Провайдер открыт наружу (так требует Android Auto), поэтому берёт только картинки с хостов
 * обложек YouTube и Google и не больше 2 МБ: через него нельзя заставить приложение сходить
 * по чужому адресу.
 */
class ArtworkProvider : ContentProvider() {
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun okHttp(): OkHttpClient
  }

  override fun onCreate() = true

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw FileNotFoundException("только чтение")
    val src = uri.getQueryParameter("u") ?: throw FileNotFoundException("нет адреса")
    val url = Uri.parse(src)
    if (url.scheme != "https" || !allowedHost(url.host)) throw FileNotFoundException("чужой адрес")
    val ctx = context ?: throw FileNotFoundException()
    val dir = File(ctx.cacheDir, "auto_art").apply { mkdirs() }
    val file = File(dir, sha1(src) + ".img")
    if (!file.exists()) {
      // Через Binder доходит только FileNotFoundException: остальное превращаем в него.
      try {
        download(src, file)
      } catch (e: FileNotFoundException) {
        throw e
      } catch (e: Exception) {
        Timber.w(e, "artwork download failed")
        throw FileNotFoundException("обложка не скачалась: ${e.javaClass.simpleName}")
      }
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  private fun download(src: String, file: File) {
    val client = EntryPointAccessors.fromApplication(context!!.applicationContext, Deps::class.java).okHttp()
    client.newCall(Request.Builder().url(src).build()).execute().use { res ->
      val body = res.body
      if (!res.isSuccessful || body.contentLength() > MAX_BYTES) throw FileNotFoundException("обложка не скачалась")
      val bytes = body.byteStream().use { it.readNBytesCompat(MAX_BYTES + 1) }
      if (bytes.size > MAX_BYTES) throw FileNotFoundException("слишком большая обложка")
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeBytes(bytes)
      tmp.renameTo(file)
    }
    trim(file.parentFile!!)
  }

  /** Держим не больше 300 обложек: старые удаляем. */
  private fun trim(dir: File) {
    val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
    files.drop(MAX_FILES).forEach { it.delete() }
  }

  override fun getType(uri: Uri) = "image/jpeg"

  override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

  override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

  companion object {
    private const val AUTHORITY = BuildConfig.APPLICATION_ID + ".artwork"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_FILES = 300

    /** `content://…artwork/img?u=<адрес обложки 300 px>`. */
    fun uriFor(thumb: String): Uri? {
      val src = Streams.artwork(thumb, 300) ?: return null
      val host = Uri.parse(src).host
      if (!allowedHost(host)) return null
      return Uri.Builder().scheme("content").authority(AUTHORITY).path("img").appendQueryParameter("u", src).build()
    }

    fun allowedHost(host: String?): Boolean {
      if (host == null) return false
      return host == "i.ytimg.com" || host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com") ||
        host == "lh3.googleusercontent.com"
    }

    private fun sha1(s: String): String =
      MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(16 * 1024)
      while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
      }
      return out.toByteArray()
    }
  }
}
