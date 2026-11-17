package ru.palustris.music.ui.common

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import ru.palustris.music.core.model.Track
import ru.palustris.music.media.TrackDownload

/** Что можно сделать с треком из любого списка. Даётся через CompositionLocal, чтобы не тянуть через все экраны. */
@Stable
interface TrackActions {
  fun play(tracks: List<Track>, index: Int, source: String? = null)

  fun toggleLike(track: Track)

  fun download(tracks: List<Track>)

  fun removeDownload(track: Track)

  fun playNext(track: Track)

  fun enqueue(track: Track)
}

/** Состояние, которое нужно строке трека: лайк, загрузка, играет ли он сейчас. */
@Immutable
data class TrackMarks(
  val liked: Set<String> = emptySet(),
  val downloads: Map<String, TrackDownload> = emptyMap(),
  val currentId: String? = null,
  val isPlaying: Boolean = false,
)

val LocalTrackActions =
  staticCompositionLocalOf<TrackActions> { error("TrackActions не предоставлены") }

val LocalTrackMarks = androidx.compose.runtime.compositionLocalOf { TrackMarks() }

/** Переходы между разделами, доступные из любого экрана и из плеера. */
@Stable
interface Navigator {
  fun openArtist(id: String?, name: String)

  fun openCollection(id: String)

  fun openPlaylist(id: Long)

  fun openEqualizer()

  fun openMood(id: String)

  fun openMoods()

  /** Чарт недели целиком, с выбором страны. */
  fun openChart()

  fun openAccount()

  fun openSettings()

  fun openLibrary(tab: ru.palustris.music.ui.library.LibraryTab)

  /** Выбрать плейлист, куда добавить трек. */
  fun addToPlaylist(track: Track)
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator не предоставлен") }

/** Дополнительный пункт меню трека, зависящий от экрана (например, «Убрать из плейлиста»). */
@Immutable
data class ExtraAction(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)
