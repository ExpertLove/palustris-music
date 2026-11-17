package ru.palustris.music

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ru.palustris.music.core.prefs.Session
import ru.palustris.music.media.PlayerConnection
import ru.palustris.music.ui.MainViewModel
import ru.palustris.music.ui.MusicRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  @Inject lateinit var player: PlayerConnection
  private val vm: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    // Заставка держится, пока не прочитан токен: без мигания экрана входа у вошедших.
    installSplashScreen().setKeepOnScreenCondition { vm.session.value is Session.Loading }
    super.onCreate(savedInstanceState)
    // Интерфейс всегда тёмный, поэтому значки системных панелей всегда светлые.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    setContent { MusicRoot(vm) }
    // После поворота экрана ссылка уже обработана.
    if (savedInstanceState == null) handleLink(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleLink(intent)
  }

  /** `https://music.palustris.ru/s/{slug}` — плейлист, которым поделились. Остальное игнорируем. */
  private fun handleLink(intent: Intent?) {
    if (intent?.action != Intent.ACTION_VIEW) return
    val uri = intent.data ?: return
    val segments = uri.pathSegments
    if (uri.scheme != "https" || uri.host != "music.palustris.ru" || segments.size != 2 || segments[0] != "s") return
    val slug = segments[1]
    // Как на сервере: только буквы и цифры, не длиннее 20.
    if (slug.length in 1..20 && slug.all { it.isLetterOrDigit() && it.code < 128 }) vm.openLink(slug)
  }

  override fun onStart() {
    super.onStart()
    player.connect()
  }

  override fun onStop() {
    // Отключается только интерфейс: музыка продолжает играть в сервисе.
    player.disconnect()
    super.onStop()
  }
}
