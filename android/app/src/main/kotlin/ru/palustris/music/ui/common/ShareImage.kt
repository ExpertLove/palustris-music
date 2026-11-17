package ru.palustris.music.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.palustris.music.BuildConfig

/**
 * Снимок части экрана для «Поделиться»: модификатор записывает содержимое в [GraphicsLayer], а
 * [share] сохраняет его в PNG и открывает системное окно отправки (сторис, мессенджеры).
 * Картинки внутри должны грузиться без аппаратных битмапов (`allowHardware(false)`).
 */
class ShareCapture(private val layer: GraphicsLayer) {
  val modifier: Modifier =
    Modifier.drawWithContent {
      layer.record { this@drawWithContent.drawContent() }
      drawLayer(layer)
    }

  suspend fun share(context: Context, name: String, text: String) {
    val bitmap = layer.toImageBitmap().asAndroidBitmap()
    val file =
      withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, "$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
      }
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
    val send =
      Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

@Composable
fun rememberShareCapture(): ShareCapture {
  val layer = rememberGraphicsLayer()
  return remember(layer) { ShareCapture(layer) }
}
