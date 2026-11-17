package ru.palustris.music.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import ru.palustris.music.BuildConfig
import ru.palustris.music.core.model.HistoryItem
import ru.palustris.music.core.model.Track
import ru.palustris.music.core.net.MusicApi
import ru.palustris.music.core.net.apiCall
import ru.palustris.music.data.LibraryRepository
import ru.palustris.music.data.OfflineFiles
import ru.palustris.music.data.PlaylistsRepository
import ru.palustris.music.ui.moods.MOODS
import ru.palustris.music.ui.moods.MoodViewModel
import ru.palustris.music.ui.moods.moodById

/**
 * Дерево библиотеки для Android Auto и других «браузеров» (MediaLibraryService):
 *
 * ```
 * root
 * ├─ wave            «Моя волна» (сразу играет)
 * ├─ likes           «Мне нравится» → треки
 * ├─ playlists       плейлисты → pl:{id} → треки
 * ├─ downloads       скачанные → треки
 * ├─ recent          недавно играло → треки
 * └─ moods           настроение → mood:{id} → треки
 * ```
 *
 * Трек в списке имеет id `item|{список}|{id трека}`: по нажатию играет весь список с этого трека.
 * В очередь плеера уходят обычные элементы с id трека, как из приложения.
 */
@Singleton
class LibraryTree
@Inject
constructor(
  private val api: MusicApi,
  private val library: LibraryRepository,
  private val playlists: PlaylistsRepository,
  private val storage: AudioStorage,
  private val files: OfflineFiles,
) {
  /** Последние показанные списки: по ним разворачиваем нажатие на трек. */
  private val lists = ConcurrentHashMap<String, List<Track>>()

  fun root(): MediaItem =
    folder(ROOT, "Музыка", extras = contentStyle(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM))

  suspend fun item(id: String): MediaItem? =
    when {
      id == ROOT -> root()
      id == WAVE -> waveItem()
      id in FOLDERS -> topFolders().firstOrNull { it.mediaId == id }
      id.startsWith(PLAYLIST) -> playlistFolders().firstOrNull { it.mediaId == id }
      id.startsWith(MOOD) -> moodFolders().firstOrNull { it.mediaId == id }
      id.startsWith(ITEM) -> parseItem(id)?.let { (parent, trackId) -> tracks(parent).firstOrNull { it.id == trackId }?.let { trackItem(parent, it) } }
      else -> null
    }

  suspend fun children(parent: String): List<MediaItem> =
    when (parent) {
      ROOT -> listOf(waveItem()) + topFolders()
      PLAYLISTS -> playlistFolders()
      MOODS_ID -> moodFolders()
      else -> tracks(parent).map { trackItem(parent, it) }
    }

  /** Треки списка: с телефона, если есть, иначе с сервера. */
  suspend fun tracks(parent: String): List<Track> {
    val list: List<Track> =
      when {
        parent == WAVE -> apiCall { api.wave() }.getOrNull().orEmpty()
        parent == LIKES -> library.likes.value ?: library.refresh().let { library.likes.value }.orEmpty()
        parent == DOWNLOADS ->
          storage.downloads.value.values.filter { it.state == TrackDownload.State.Done }.sortedByDescending { it.updatedAt }.map { it.track }
        parent == RECENT ->
          apiCall { api.history(50) }.getOrNull()?.map(HistoryItem::toTrack)
            ?: files.read("history.json", ListSerializer(HistoryItem.serializer()))?.map(HistoryItem::toTrack).orEmpty()
        parent.startsWith(PLAYLIST) -> {
          val id = parent.removePrefix(PLAYLIST).toLongOrNull() ?: return emptyList()
          (playlists.observe(id).first() ?: playlists.load(id).getOrNull())?.items.orEmpty()
        }
        parent.startsWith(MOOD) -> moodTracks(parent.removePrefix(MOOD))
        parent == SEARCH -> lists[SEARCH].orEmpty()
        else -> emptyList()
      }
    lists[parent] = list
    return list
  }

  /** Поиск для голосовых команд и строки поиска в машине. */
  suspend fun search(query: String): List<Track> {
    val found = apiCall { api.searchTracks(query) }.getOrNull().orEmpty()
    lists[SEARCH] = found
    return found
  }

  fun searchItems(): List<MediaItem> = lists[SEARCH].orEmpty().map { trackItem(SEARCH, it) }

  /**
   * Что поставить в очередь по запросу браузера. Нажали трек в списке — весь список с него;
   * «Моя волна» — свежая волна; иначе `null`: это обычные треки от приложения.
   */
  suspend fun resolve(requested: List<MediaItem>): Resolved? {
    val first = requested.firstOrNull() ?: return null
    val query = first.requestMetadata.searchQuery
    return when {
      // «Окей, Google, включи … в Музыке». Пустой запрос — «включи музыку»: играем волну.
      first.mediaId.isEmpty() && query != null ->
        if (query.isBlank()) wave() else search(query).takeIf { it.isNotEmpty() }?.let { Resolved(it, 0, null) }
      first.mediaId == WAVE -> wave()
      requested.size == 1 && first.mediaId.startsWith(ITEM) -> {
        val (parent, trackId) = parseItem(first.mediaId) ?: return null
        val list = lists[parent] ?: tracks(parent)
        val i = list.indexOfFirst { it.id == trackId }
        if (i < 0) null else Resolved(list, i, if (parent == WAVE) QueueSource.WAVE else null)
      }
      else -> null
    }
  }

  private suspend fun wave(): Resolved? =
    tracks(WAVE).takeIf { it.isNotEmpty() }?.let { Resolved(it, 0, QueueSource.WAVE) }

  data class Resolved(val tracks: List<Track>, val index: Int, val source: String?)

  private fun waveItem() =
    MediaItem.Builder()
      .setMediaId(WAVE)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Моя волна")
          .setSubtitle("Бесконечный поток под ваш вкус")
          .setArtworkUri(resourceUri("ic_auto_wave"))
          .setIsBrowsable(false)
          .setIsPlayable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
          .build()
      )
      .build()

  private fun topFolders() =
    listOf(
      folder(LIKES, "Мне нравится", icon = "ic_auto_likes"),
      folder(PLAYLISTS, "Плейлисты", icon = "ic_auto_playlists", extras = contentStyle(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)),
      folder(DOWNLOADS, "Скачанные", icon = "ic_auto_downloads"),
      folder(RECENT, "Недавно играло", icon = "ic_auto_recent"),
      folder(MOODS_ID, "Настроение", icon = "ic_auto_moods", extras = contentStyle(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)),
    )

  private suspend fun playlistFolders(): List<MediaItem> {
    val rows = playlists.list.value ?: playlists.refresh().let { playlists.list.value }.orEmpty()
    return rows.map { p ->
      folder(PLAYLIST + p.id, p.name, subtitle = ru.palustris.music.ui.common.pluralTracks(p.count), art = p.thumbs.firstOrNull())
    }
  }

  private fun moodFolders() = MOODS.map { m -> folder(MOOD + m.id, m.title, subtitle = m.sub, icon = "ic_auto_moods") }

  private suspend fun moodTracks(id: String): List<Track> {
    val m = moodById(id) ?: return emptyList()
    val cached = files.read("mood-${m.id}.json", ListSerializer(Track.serializer()))
    if (cached != null) return cached
    val lists = coroutineScope { m.queries.map { q -> async { apiCall { api.searchTracks(q) }.getOrNull() } }.awaitAll() }.filterNotNull()
    return MoodViewModel.interleave(lists).also { if (it.isNotEmpty()) files.write("mood-${m.id}.json", ListSerializer(Track.serializer()), it) }
  }

  private fun folder(
    id: String,
    title: String,
    subtitle: String? = null,
    icon: String? = null,
    art: String? = null,
    extras: Bundle? = null,
  ) =
    MediaItem.Builder()
      .setMediaId(id)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(title)
          .setSubtitle(subtitle)
          .setArtworkUri(art?.let(ArtworkProvider::uriFor) ?: icon?.let(::resourceUri))
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setExtras(extras)
          .build()
      )
      .build()

  private fun trackItem(parent: String, t: Track) =
    MediaItem.Builder()
      .setMediaId("$ITEM|$parent|${t.id}")
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(t.title)
          .setArtist(t.artist)
          .setAlbumTitle(t.album)
          .setArtworkUri(t.thumb?.let(ArtworkProvider::uriFor))
          .setDurationMs(if (t.duration > 0) t.duration * 1000 else null)
          .setIsBrowsable(false)
          .setIsPlayable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
          .build()
      )
      .build()

  private fun contentStyle(browsable: Int) =
    Bundle().apply {
      putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
      putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

  private fun resourceUri(name: String): Uri = Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/$name")

  companion object {
    const val ROOT = "root"
    const val WAVE = "wave"
    const val LIKES = "likes"
    const val PLAYLISTS = "playlists"
    const val DOWNLOADS = "downloads"
    const val RECENT = "recent"
    const val MOODS_ID = "moods"
    const val SEARCH = "search"
    const val PLAYLIST = "pl:"
    const val MOOD = "mood:"
    const val ITEM = "item"
    private val FOLDERS = setOf(LIKES, PLAYLISTS, DOWNLOADS, RECENT, MOODS_ID)

    /** `item|pl:5|abc` → (`pl:5`, `abc`). */
    fun parseItem(id: String): Pair<String, String>? {
      val parts = id.split('|')
      return if (parts.size == 3 && parts[0] == ITEM && parts[1].isNotEmpty() && parts[2].isNotEmpty()) parts[1] to parts[2] else null
    }
  }
}
