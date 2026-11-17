package ru.palustris.music.data

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Копии данных аккаунта на телефоне: профиль, лайки, плейлисты, главная, очередь изменений.
 * Маленькие JSON-файлы в `filesDir/offline`, запись атомарная (AtomicFile), так что оборванная
 * запись не портит прошлую копию. Room тут не нужен: данных немного, читаются они целиком,
 * а запросов по ним нет.
 */
@Singleton
class OfflineFiles @Inject constructor(@ApplicationContext context: Context, private val json: Json) {
  private val dir = File(context.filesDir, "offline")
  private val lock = Mutex()

  suspend fun <T> read(name: String, serializer: KSerializer<T>): T? =
    withContext(Dispatchers.IO) {
      val f = AtomicFile(File(dir, name))
      if (!f.baseFile.exists()) return@withContext null
      runCatching { json.decodeFromString(serializer, f.readFully().decodeToString()) }
        .onFailure { Timber.w(it, "offline file %s is unreadable", name) }
        .getOrNull()
    }

  suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) =
    withContext(Dispatchers.IO) {
      lock.withLock {
        dir.mkdirs()
        val f = AtomicFile(File(dir, name))
        val out = f.startWrite()
        try {
          out.write(json.encodeToString(serializer, value).encodeToByteArray())
          f.finishWrite(out)
        } catch (e: Exception) {
          f.failWrite(out)
          Timber.w(e, "offline file %s not written", name)
        }
      }
    }

  suspend fun delete(name: String) = withContext(Dispatchers.IO) { lock.withLock { AtomicFile(File(dir, name)).delete() } }

  /** Выход из аккаунта: чужие лайки и плейлисты на телефоне не остаются. */
  suspend fun clearAll() = withContext(Dispatchers.IO) { lock.withLock { dir.deleteRecursively() } }
}
