package ru.palustris.music

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
