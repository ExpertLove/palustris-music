package ru.palustris.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.graphics.Brush
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
import ru.palustris.music.ui.account.AccountScreen
import ru.palustris.music.ui.auth.AuthScreen
import ru.palustris.music.ui.chart.ChartScreen
import ru.palustris.music.ui.moods.MoodScreen
import ru.palustris.music.ui.moods.MoodsScreen
import ru.palustris.music.ui.playlists.SharedPlaylistScreen
import ru.palustris.music.ui.common.LocalTrackActions
import ru.palustris.music.ui.common.LocalTrackMarks
import ru.palustris.music.ui.common.LocalNavigator
import ru.palustris.music.ui.common.Navigator
import ru.palustris.music.ui.catalog.ArtistScreen
import ru.palustris.music.ui.catalog.CollectionScreen
import ru.palustris.music.ui.equalizer.EqualizerScreen
import ru.palustris.music.ui.playlists.AddToPlaylistSheet
import ru.palustris.music.ui.playlists.PlaylistScreen
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

@Serializable data class ArtistKey(val id: String?, val name: String) : NavKey

@Serializable data class CollectionKey(val id: String) : NavKey

@Serializable data class PlaylistKey(val id: Long) : NavKey

@Serializable data object EqualizerKey : NavKey

@Serializable data object AccountKey : NavKey

@Serializable data class MoodKey(val id: String) : NavKey

@Serializable data object MoodsKey : NavKey

@Serializable data object ChartKey : NavKey

/** Чужой плейлист по ссылке `https://music.palustris.ru/s/{slug}`. */
@Serializable data class SharedKey(val slug: String) : NavKey

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
      when (val s = session) {
        Session.Loading -> Unit // держим системную заставку
        Session.LoggedOut -> {
          LaunchedEffect(Unit) { vm.dropSessionViewModels() }
          AuthScreen()
        }
        is Session.LoggedIn -> {
          val activity = LocalActivity.current as ComponentActivity
          val store = vm.viewModelStoreFor(s.token)
          val owner = remember(store, activity) { SessionViewModelStoreOwner(store, activity) }
          CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { MainScaffold(vm) }
        }
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
  val sleep by vm.sleep.collectAsStateWithLifecycle()
  val eqEnabled by vm.eqEnabled.collectAsStateWithLifecycle()
  val pickFor by vm.pickPlaylistFor.collectAsStateWithLifecycle()
  val playlists by vm.playlists.collectAsStateWithLifecycle()
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
  // Вкладка подсвечивается по корню текущей ветки: страница исполнителя из поиска — это всё ещё «Поиск».
  val currentTab =
    when (backStack.getOrNull(1)) {
      is SearchKey -> Tab.Search
      is LibraryKey -> Tab.Library
      null -> Tab.Home
      else -> Tab.Home
    }.takeIf { top !is SettingsKey && top !is EqualizerKey && top !is AccountKey }
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
  fun push(key: NavKey) {
    expanded = false
    if (backStack.lastOrNull() != key) backStack.add(key)
  }
  val navigator =
    remember(backStack) {
      object : Navigator {
        override fun openArtist(id: String?, name: String) = push(ArtistKey(id, name))

        override fun openCollection(id: String) = push(CollectionKey(id))

        override fun openPlaylist(id: Long) = push(PlaylistKey(id))

        override fun openEqualizer() = push(EqualizerKey)

        override fun openMood(id: String) = push(MoodKey(id))

        override fun openMoods() = push(MoodsKey)

        override fun openChart() = push(ChartKey)

        override fun openAccount() = push(AccountKey)

        override fun openSettings() = push(SettingsKey)

        override fun openLibrary(tab: LibraryTab) {
          expanded = false
          select(Tab.Library, tab)
        }

        override fun addToPlaylist(track: ru.palustris.music.core.model.Track) = vm.askPlaylistFor(track)
      }
    }
  // Ссылка на плейлист из другого приложения: открываем поверх текущего экрана.
  val link by vm.openLink.collectAsStateWithLifecycle()
  LaunchedEffect(link) {
    link?.let { slug ->
      push(SharedKey(slug))
      vm.consumeLink()
    }
  }
  val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

  CompositionLocalProvider(LocalTrackActions provides vm, LocalTrackMarks provides marks, LocalNavigator provides navigator) {
    Box(Modifier.fillMaxSize()) {
      NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        popTransitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        entryProvider =
          entryProvider {
            entry<HomeKey> { HomeScreen(player = playerState, contentPadding = contentPadding, onToggle = vm::togglePlay) }
            entry<SearchKey> { SearchScreen(contentPadding) }
            entry<LibraryKey> { key ->
              LibraryScreen(contentPadding, initialTab = key.tab, onOpenSettings = { backStack.add(SettingsKey) })
            }
            entry<SettingsKey> {
              SettingsScreen(
                contentPadding,
                onBack = back,
                onOpenAccount = { push(AccountKey) },
                onOpenEqualizer = { push(EqualizerKey) },
              )
            }
            entry<AccountKey> {
              AccountScreen(
                contentPadding,
                onBack = back,
                onLogout = vm::logout,
                onDeleted = vm::onAccountDeleted,
                onOpenLibrary = { select(Tab.Library, it) },
                onOpenSettings = { push(SettingsKey) },
                onMessage = vm::message,
              )
            }
            entry<MoodKey> { k -> MoodScreen(k.id, contentPadding, onBack = back, onMessage = vm::message) }
            entry<MoodsKey> { MoodsScreen(contentPadding, onBack = back, onOpen = { push(MoodKey(it.id)) }) }
            entry<ChartKey> { ChartScreen(contentPadding, onBack = back) }
            entry<SharedKey> { k -> SharedPlaylistScreen(k.slug, contentPadding, onBack = back, onMessage = vm::message) }
            entry<ArtistKey> { k -> ArtistScreen(k.id, k.name, contentPadding, onBack = back) }
            entry<CollectionKey> { k -> CollectionScreen(k.id, contentPadding, onBack = back, onMessage = vm::message) }
            entry<PlaylistKey> { k -> PlaylistScreen(k.id, contentPadding, onBack = back, onMessage = vm::message) }
            entry<EqualizerKey> { EqualizerScreen(contentPadding, onBack = back) }
          },
      )

      // Значки строки состояния читаются и поверх обложек, когда список прокручен.
      StatusBarScrim()

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
        FullPlayer(
          playerState,
          progress,
          controls = vm,
          onCollapse = { expanded = false },
          sleep = sleep,
          onSleep = vm::setSleep,
          eqEnabled = eqEnabled,
          onOpenEqualizer = { push(EqualizerKey) },
          onOpenArtist = {
            playerState.track?.let { t -> push(ArtistKey(t.artistId, t.artist.substringBefore(',').trim())) }
          },
        )
      }

      pickFor?.let { t ->
        AddToPlaylistSheet(
          t,
          playlists,
          onDismiss = { vm.askPlaylistFor(null) },
          onPick = { p -> vm.addToPlaylist(p, t) },
          onCreate = { name -> vm.createPlaylistWith(name, t) },
        )
      }
    }
  }
}

@Composable
private fun StatusBarScrim() {
  val bg = MaterialTheme.colorScheme.background
  Box(
    Modifier.fillMaxWidth()
      .windowInsetsTopHeight(WindowInsets.statusBars)
      .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.72f), bg.copy(alpha = 0.35f))))
  )
}

/** Хранилище ViewModel-ей сессии; фабрика Hilt и extras берутся у активити. */
private class SessionViewModelStoreOwner(override val viewModelStore: ViewModelStore, private val activity: ComponentActivity) :
  ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = activity.defaultViewModelProviderFactory

  override val defaultViewModelCreationExtras: CreationExtras
    get() = activity.defaultViewModelCreationExtras
}
