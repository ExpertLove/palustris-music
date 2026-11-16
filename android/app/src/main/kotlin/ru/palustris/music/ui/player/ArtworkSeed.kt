package ru.palustris.music.ui.player

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.palustris.music.media.Streams
import ru.palustris.music.ui.theme.BrandAccent

private object ArtworkPalette {
  private val cache = LruCache<String, Int>(64)

  suspend fun seed(context: Context, url: String): Color? {
    cache.get(url)?.let { return Color(it) }
    return withContext(Dispatchers.Default) {
      val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
      val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
      val p = Palette.from(bitmap).maximumColorCount(16).generate()
      // Яркий оттенок узнаётся лучше, чем «средний» цвет обложки, который часто грязно-серый.
      val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch
      swatch?.rgb?.also { cache.put(url, it) }?.let { Color(it) }
    }
  }
}

/** Цвет обложки для темы. Пока новая обложка грузится, держим прежний цвет, чтобы тема не мигала. */
@Composable
fun rememberArtworkSeed(thumb: String?): Color {
  val context = LocalContext.current
  var seed by remember { mutableStateOf(BrandAccent) }
  LaunchedEffect(thumb) {
    val url = Streams.artwork(thumb, 120)
    seed = if (url == null) BrandAccent else ArtworkPalette.seed(context, url) ?: seed
  }
  return seed
}
