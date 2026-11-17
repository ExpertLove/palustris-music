package ru.palustris.music.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.palustris.music.MainActivity
import ru.palustris.music.R
import ru.palustris.music.media.PlaybackService

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
  fun nowPlaying(): NowPlayingStore
}

/**
 * Виджет «Сейчас играет»: обложка, название, назад / играть / вперёд. Цвета берутся из обоев
 * (GlanceTheme, Material You). Нажатие на обложку или название открывает приложение.
 */
class MusicWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val store = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).nowPlaying()
    store.ensureInitialized()
    provideContent {
      val np by store.state.collectAsState()
      val art by store.art.collectAsState()
      GlanceTheme { WidgetContent(np, art) }
    }
  }

  companion object {
    val SMALL = DpSize(110.dp, 110.dp)
    val WIDE = DpSize(250.dp, 110.dp)
    val LARGE = DpSize(250.dp, 180.dp)
  }
}

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = MusicWidget()
}

@Composable
private fun WidgetContent(np: NowPlaying, art: Bitmap?) {
  val size = LocalSize.current
  val open = actionStartActivity<MainActivity>()
  Box(
    GlanceModifier.fillMaxSize()
      .appWidgetBackground()
      .cornerRadius(24.dp)
      .background(GlanceTheme.colors.widgetBackground)
      .padding(12.dp)
  ) {
    when {
      np.id == null -> Empty(open)
      size.width < MusicWidget.WIDE.width -> Small(np, art, open)
      else -> Wide(np, art, open, large = size.height >= MusicWidget.LARGE.height)
    }
  }
}

@Composable
private fun Empty(open: androidx.glance.action.Action) {
  Column(GlanceModifier.fillMaxSize().clickable(open), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
    Image(
      ImageProvider(R.drawable.ic_widget_play),
      contentDescription = null,
      colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
      modifier = GlanceModifier.size(32.dp),
    )
    Spacer(GlanceModifier.height(6.dp))
    Text(
      "Включить музыку",
      style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
      maxLines = 2,
    )
  }
}

/** Узкий: обложка сверху, под ней кнопки. */
@Composable
private fun Small(np: NowPlaying, art: Bitmap?, open: androidx.glance.action.Action) {
  Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(GlanceModifier.defaultWeight().fillMaxWidth().clickable(open), contentAlignment = Alignment.Center) {
      Artwork(art, np.title, GlanceModifier.fillMaxHeight().fillMaxWidth())
    }
    Spacer(GlanceModifier.height(8.dp))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
      PlayButton(np.playing, 40)
      Spacer(GlanceModifier.width(8.dp))
      ControlButton(R.drawable.ic_widget_next, "Следующий трек", actionRunCallback<NextAction>())
    }
  }
}

/** Широкий: обложка слева, справа название и кнопки. В высоком варианте всё крупнее. */
@Composable
private fun Wide(np: NowPlaying, art: Bitmap?, open: androidx.glance.action.Action, large: Boolean) {
  val artSize = if (large) 132.dp else 86.dp
  Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
    Artwork(art, np.title, GlanceModifier.size(artSize).clickable(open))
    Spacer(GlanceModifier.width(14.dp))
    Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
      Column(GlanceModifier.fillMaxWidth().clickable(open)) {
        Text(
          np.title,
          style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (large) 18.sp else 15.sp, fontWeight = FontWeight.Bold),
          maxLines = if (large) 2 else 1,
        )
        Text(
          np.artist,
          style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = if (large) 14.sp else 13.sp),
          maxLines = 1,
        )
      }
      Spacer(GlanceModifier.height(if (large) 14.dp else 6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        ControlButton(R.drawable.ic_widget_previous, "Предыдущий трек", actionRunCallback<PreviousAction>())
        Spacer(GlanceModifier.width(6.dp))
        PlayButton(np.playing, if (large) 52 else 44)
        Spacer(GlanceModifier.width(6.dp))
        ControlButton(R.drawable.ic_widget_next, "Следующий трек", actionRunCallback<NextAction>())
      }
    }
  }
}

@Composable
private fun Artwork(art: Bitmap?, title: String, modifier: GlanceModifier) {
  if (art != null) {
    Image(ImageProvider(art), contentDescription = title, contentScale = ContentScale.Crop, modifier = modifier.cornerRadius(16.dp))
  } else {
    Box(modifier.cornerRadius(16.dp).background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
      Image(
        ImageProvider(R.drawable.ic_stat_music),
        contentDescription = null,
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
        modifier = GlanceModifier.size(28.dp),
      )
    }
  }
}

@Composable
private fun PlayButton(playing: Boolean, sizeDp: Int) {
  Box(
    GlanceModifier.size(sizeDp.dp)
      .cornerRadius((sizeDp / 2).dp)
      .background(GlanceTheme.colors.primary)
      .clickable(actionRunCallback<PlayPauseAction>())
      .semantics { contentDescription = if (playing) "Пауза" else "Играть" },
    contentAlignment = Alignment.Center,
  ) {
    Image(
      ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
      contentDescription = null,
      colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
      modifier = GlanceModifier.size((sizeDp * 0.55f).dp),
    )
  }
}

@Composable
private fun ControlButton(icon: Int, label: String, action: androidx.glance.action.Action) {
  Box(
    GlanceModifier.size(40.dp).cornerRadius(20.dp).clickable(action).semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Image(ImageProvider(icon), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface), modifier = GlanceModifier.size(26.dp))
  }
}

/**
 * Кнопки виджета управляют плеером через MediaController, как интерфейс приложения.
 * MediaButtonReceiver тут не подходит: на Android 8+ он принимает только «играть».
 * Нажатие на виджет — исключение из запрета запуска foreground-сервиса из фона, поэтому
 * сервис может начать играть, даже если приложение было закрыто.
 */
private suspend fun withController(context: Context, block: suspend (MediaController) -> Unit) =
  withContext(Dispatchers.Main) {
    // Glance отдаёт контекст ресивера, а из него к сервису подключаться нельзя (ReceiverCallNotAllowedException).
    val app = context.applicationContext
    val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
    val future = MediaController.Builder(app, token).buildAsync()
    val controller = withTimeoutOrNull(5_000) { future.await() } ?: return@withContext MediaController.releaseFuture(future)
    try {
      block(controller)
    } finally {
      controller.release()
    }
  }

class PlayPauseAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
    withController(context) { c ->
      if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
        c.pause()
        return@withController
      }
      // Сервис только что запущен: очередь он читает с диска, ждём её, иначе играть нечего.
      if (c.mediaItemCount == 0) withTimeoutOrNull(3_000) { while (c.mediaItemCount == 0) delay(50) }
      if (c.playbackState == Player.STATE_IDLE) c.prepare()
      if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
      c.play()
      // Не отключаемся сразу: сервис должен успеть выйти на передний план, пока к нему подключены.
      delay(1_000)
    }
}

class NextAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
    withController(context) { it.seekToNext() }
}

class PreviousAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
    withController(context) { it.seekToPrevious() }
}
