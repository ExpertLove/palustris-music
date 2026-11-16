package ru.palustris.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import ru.palustris.music.core.prefs.Session
import ru.palustris.music.ui.auth.AuthScreen
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.LocalTrackMarks
import ru.palustris.music.ui.home.HomeScreen
import ru.palustris.music.ui.library.LibraryScreen
import ru.palustris.music.ui.library.LibraryTab
import ru.palustris.music.ui.player.FullPlayer
import ru.palustris.music.ui.player.MiniPlayer
import ru.palustris.music.ui.player.rememberArtworkSeed
import ru.palustris.music.ui.search.SearchScreen
import ru.palustris.music.ui.settings.SettingsScreen
import ru.palustris.music.ui.theme.MusicTheme

@Serializable data object HomeKey : NavKey

@Serializable data object SearchKey : NavKey

@Serializable data class LibraryKey(val tab: LibraryTab = LibraryTab.Likes) : NavKey

@Serializable data object SettingsKey : NavKey

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
  Home("Главная", Icons.Outlined.Home, Icons.Rounded.Home),
  Search("Поиск", Icons.Rounded.Search, Icons.Rounded.Search),
  Library("Моя музыка", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
}

@Composable
fun MusicRoot(vm: MainViewModel) {
  val session by vm.session.collectAsStateWithLifecycle()
  val playerState by vm.playerState.collectAsStateWithLifecycle()
  val seed = rememberArtworkSeed(playerState.track?.thumb)

  MusicTheme(seed) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      when (session) {
        Session.Loading -> Unit // держим системную заставку
        Session.LoggedOut -> AuthScreen()
        is Session.LoggedIn -> MainScaffold(vm)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainScaffold(vm: MainViewModel) {
  val playerState by vm.playerState.collectAsStateWithLifecycle()
  val marks by vm.marks.collectAsStateWithLifecycle()
  val progress = vm.player.progress.collectAsState(initial = ru.palustris.music.media.Progress(0, 0, 0))
  val backStack = rememberNavBackStack(HomeKey)
  var expanded by rememberSaveable { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }
  val density = LocalDensity.current
  var bottomHeight by remember { mutableStateOf(0.dp) }
  val context = LocalContext.current

  val notificationsPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* без уведомления загрузка всё равно идёт */ }

  LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
  LaunchedEffect(Unit) {
    vm.askNotifications.collect {
      if (
        Build.VERSION.SDK_INT >= 33 &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
      ) {
        notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }
  // Очередь закончилась и плеер пуст: полному плееру нечего показывать.
  LaunchedEffect(playerState.track == null) { if (playerState.track == null) expanded = false }
  BackHandler(enabled = expanded) { expanded = false }

  val top = backStack.lastOrNull()
  val currentTab =
    when (top) {
      is SearchKey -> Tab.Search
      is LibraryKey -> Tab.Library
      is SettingsKey -> null
      else -> Tab.Home
    }
  fun select(tab: Tab, libraryTab: LibraryTab = LibraryTab.Likes) {
    // «Главная» — корень; другие вкладки лежат поверх неё, и «назад» ведёт на главную.
    while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    when (tab) {
      Tab.Home -> Unit
      Tab.Search -> backStack.add(SearchKey)
      Tab.Library -> backStack.add(LibraryKey(libraryTab))
    }
  }
  val contentPadding = PaddingValues(bottom = bottomHeight + 12.dp)

  CompositionLocalProvider(LocalTrackActions provides vm, LocalTrackMarks provides marks) {
    Box(Modifier.fillMaxSize()) {
      NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        popTransitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        entryProvider =
          entryProvider {
            entry<HomeKey> {
              HomeScreen(
                player = playerState,
                contentPadding = contentPadding,
                onOpenSettings = { backStack.add(SettingsKey) },
                onOpenDownloads = { select(Tab.Library, LibraryTab.Downloads) },
                onToggle = vm::togglePlay,
              )
            }
            entry<SearchKey> { SearchScreen(contentPadding) }
            entry<LibraryKey> { key ->
              LibraryScreen(contentPadding, initialTab = key.tab, onOpenSettings = { backStack.add(SettingsKey) })
            }
            entry<SettingsKey> {
              SettingsScreen(
                contentPadding,
                onBack = { backStack.removeAt(backStack.lastIndex) },
                onLogout = vm::logout,
              )
            }
          },
      )

      Column(
        Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }
      ) {
        SnackbarHost(snackbar, Modifier.padding(horizontal = 8.dp))
        MiniPlayer(
          state = playerState,
          progress = progress,
          onToggle = vm::togglePlay,
          onNext = vm::next,
          onExpand = { expanded = true },
          modifier = Modifier.padding(bottom = 8.dp),
        )
        if (currentTab != null) {
          ShortNavigationBar {
            Tab.entries.forEach { tab ->
              val selected = tab == currentTab
              ShortNavigationBarItem(
                selected = selected,
                onClick = { if (!selected) select(tab) },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                label = { Text(tab.label) },
              )
            }
          }
        } else {
          androidx.compose.foundation.layout.Spacer(Modifier.navigationBarsPadding())
        }
      }

      AnimatedVisibility(
        visible = expanded && playerState.track != null,
        enter = slideInVertically(tween(380)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300)),
      ) {
        FullPlayer(playerState, progress, controls = vm, onCollapse = { expanded = false })
      }
    }
  }
}
