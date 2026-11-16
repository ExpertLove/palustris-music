package ru.palustris.music.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.palustris.music.core.prefs.DownloadQuality
import ru.palustris.music.core.prefs.Session
import ru.palustris.music.core.prefs.SessionStore
import ru.palustris.music.core.prefs.Settings
import ru.palustris.music.core.prefs.SettingsStore
import ru.palustris.music.media.AudioStorage
import ru.palustris.music.media.StorageUsage

@HiltViewModel
class SettingsViewModel
@Inject
constructor(private val store: SettingsStore, private val storage: AudioStorage, session: SessionStore) : ViewModel() {
  val settings: StateFlow<Settings> =
    store.settings.map { it ?: Settings() }.stateIn(viewModelScope, SharingStarted.Eagerly, store.current)

  val email: StateFlow<String> =
    session.session.map { (it as? Session.LoggedIn)?.email.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

  private val _usage = MutableStateFlow<StorageUsage?>(null)
  val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

  init {
    viewModelScope.launch { storage.downloads.collect { refreshUsage() } }
  }

  private fun refreshUsage() {
    viewModelScope.launch { _usage.value = storage.usage() }
  }

  fun setCacheLimit(mb: Int) =
    viewModelScope.launch {
      store.setCacheLimit(mb)
      delay(300) // кэш ужимается в фоне
      refreshUsage()
    }

  fun setQuality(q: DownloadQuality) = viewModelScope.launch { store.setQuality(q) }

  fun setWifiOnly(on: Boolean) = viewModelScope.launch { store.setWifiOnly(on) }

  fun clearCache() =
    viewModelScope.launch {
      storage.clearListeningCache()
      refreshUsage()
    }

  fun removeAllDownloads() = storage.removeAll()
}
