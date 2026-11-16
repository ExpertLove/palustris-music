package ru.palustris.music.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.palustris.music.core.model.Track

@Serializable
data class SavedItem(val track: Track, val source: String? = null)

@Serializable
data class SavedQueue(val items: List<SavedItem>, val index: Int, val positionMs: Long)

/**
 * Очередь и позиция на диске: после перезапуска телефона или выгрузки приложения
 * плеер открывается на том же треке и с того же места.
 */
@Singleton
class QueueStore @Inject constructor(@ApplicationContext context: Context, private val json: Json) {
  private val file = File(context.filesDir, "queue.json")

  /** Снимок берём на главном потоке (плеер требует), а пишем на фоне. */
  fun snapshot(player: Player): SavedQueue? {
    if (player.mediaItemCount == 0) return null
    // Очень длинная волна не нужна целиком: держим окно вокруг текущего трека.
    val from = (player.currentMediaItemIndex - 50).coerceAtLeast(0)
    val to = (player.currentMediaItemIndex + 450).coerceAtMost(player.mediaItemCount)
    val items = (from until to).map { player.getMediaItemAt(it).let { m -> SavedItem(m.toTrack(), m.queueSource) } }
    return SavedQueue(items, player.currentMediaItemIndex - from, player.currentPosition.coerceAtLeast(0))
  }

  suspend fun save(q: SavedQueue?) =
    withContext(Dispatchers.IO) {
      runCatching {
        if (q == null) file.delete()
        else {
          val tmp = File(file.parentFile, "queue.json.tmp")
          tmp.writeText(json.encodeToString(SavedQueue.serializer(), q))
          tmp.renameTo(file)
        }
      }
    }

  suspend fun load(): SavedQueue? =
    withContext(Dispatchers.IO) {
      runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }
        .getOrNull()
        ?.takeIf { it.items.isNotEmpty() && it.index in it.items.indices }
    }

  fun SavedQueue.toMediaItems(): List<MediaItem> = items.map { it.track.toMediaItem(it.source) }
}
