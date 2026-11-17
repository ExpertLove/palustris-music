# Android app «Музыка» (ru.palustris.music)

Native Android client for https://music.palustris.ru: the same Rust backend as the website, no WebView.
Goal: a flawless, fast, beautiful music player that follows Material 3 Expressive.

## Environment (Windows, set up 2026-11-17)

| Tool | Location / version |
|---|---|
| JDK | Temurin 21, `C:\Users\test2\scoop\apps\temurin21-jdk\current` (user `JAVA_HOME`) |
| Android SDK | `C:\Users\test2\AppData\Local\Android\Sdk` (user `ANDROID_HOME`): platform 37.0, build-tools 37.0.0, platform-tools, emulator, cmdline-tools |
| `android` CLI | `C:\Users\test2\AppData\AndroidCLI\android.exe` (Google, v1.1): sdk, emulator, run, layout, screen capture, preview render, docs search |
| AVD | `Pixel_9_API_37` (Android 17, Google Play x86_64) |
| Gradle | wrapper 9.9.1 (AGP 9.5.1, built-in Kotlin 2.4.21) |

The system `java` on PATH is JDK 8. Bash sessions started before the setup keep the old
environment, so export explicitly:

```bash
export JAVA_HOME="$USERPROFILE/scoop/apps/temurin21-jdk/current"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export PATH="$PATH:$USERPROFILE/AppData/AndroidCLI:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
```

Emulator acceleration (AEHD 2.2) is installed. `android emulator start Pixel_9_API_37` boots in about 3 minutes.

**Test account** for the emulator: `C:\Users\test2\.android-keys\test-account.txt` (`android-test@palustris.ru`).
Delete it via `DELETE /api/account` when it is no longer needed. Log in with `adb shell input text` after
tapping the fields. `android layout --flat -o file.json` gives the center of each element for `adb shell input tap`.

## Commands

```bash
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk (id ru.palustris.music.debug)
./gradlew assembleRelease            # signed, R8-minified, app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests (device needed); NOTE: uninstalls the app afterwards
# Keep the logged-in app: build, install both APKs and run the class by hand
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class ru.palustris.music.LibraryBrowseTest ru.palustris.music.debug.test/androidx.test.runner.AndroidJUnitRunner
android emulator start Pixel_9_API_37
android run app/build/outputs/apk/debug/app-debug.apk
android layout --pretty              # UI tree as JSON; primary way to inspect the running app
android screen capture -o shot.png   # then LOOK at the png
android preview list "gradle://?:app"   # the explicit gradle reference is required: on Windows `build/` dirs
android preview render "gradle://?:app" --fqn=<fqn from list> -o <dir>   # look like Bazel BUILD files
android docs search "media3 session" # official Android knowledge base
```

Verify every UI change visually (screenshot or preview render). Test release builds for
performance: debug Compose is several times slower.

## Signing

`android/keystore.properties` (git-ignored) points at `C:\Users\test2\.android-keys\palustris-music-release.jks`.
A copy of the properties also sits next to the keystore. Certificate SHA-256:
`B1:77:ED:36:0F:45:FF:1B:82:C9:24:D4:43:A6:35:0A:DE:F6:D6:4A:8E:69:B9:DF:A6:7C:29:94:D1:E0:75:74`.
Never regenerate the key: installed apps can only be updated with the same key.

## Backend

- API reference: `docs/API.md` (generated from `../server/src/main.rs`; regenerate when the server changes).
- Auth: `POST /api/login` → `{token, email}`. Send `Authorization: Bearer <token>` on every call,
  **including `/api/stream/{id}`** (ExoPlayer via OkHttpDataSource with an auth interceptor).
- `/api/stream/{id}` supports `Range` / 206 and streams while the server is still downloading.
  First play of an uncached track can take seconds, so show buffering state and use `/api/prefetch` for the next track.
- Production server: `ssh -i ~/.ssh/palustris_admin root@music.palustris.ru`, code in `/opt/music`.
  Server changes go through the Rust project in `../server`.

## Stack (versions in gradle/libs.versions.toml)

Kotlin 2.4 · Jetpack Compose (BOM 2026.09) + **Material3 1.5.0-rc01** (Expressive APIs) + material-icons-extended · Navigation 3 ·
Media3 1.11 (ExoPlayer, MediaSessionService, OkHttp datasource, DownloadManager) · Hilt (KSP) · DataStore ·
Retrofit 3 + kotlinx.serialization · OkHttp 5 · Coil 3 · Palette · Timber · SplashScreen · ProfileInstaller ·
Glance 1.2 (widget) + WorkManager 2.12 (pinned explicitly, see R8 below) · Calvin-LL Reorderable 3.1 (Apache-2.0, drag in lists).
No Room: downloads live in Media3's DownloadIndex; likes, playlists, profile, history and home are small JSON files
(`data/OfflineFiles`, AtomicFile) because they are read whole and never queried. Revisit if that stops being true.
minSdk 26, target/compile 37.

**R8 (AGP 9 strict full mode):** `-keep class X` no longer keeps the no-arg constructor. Libraries with old consumer
rules break only in release. Glance 1.2.0 drags in WorkManager 2.7.1 (Room 2.2.5) whose rule does not keep
`WorkDatabase_Impl.<init>()`: release crashed in `InitializationProvider` until WorkManager 2.12 was added explicitly.
**Always run the release build on the emulator before shipping.** Glance `ActionCallback`s are kept in `proguard-rules.pro`.

## Architecture (as built)

```
ru.palustris.music
├─ core/   AppModule (OkHttp + auth interceptor, Retrofit, Json, @AppScope), net/ (MusicApi, apiCall → Russian error text),
│          model/ (Track, Me, Playlist…), prefs/ (SessionStore: token; SettingsStore: cache limit, quality, Wi-Fi only,
│          normalize, crossfade, chart region)
├─ data/   OfflineFiles (JSON in filesDir/offline, AtomicFile), Outbox (queue of edits for the server),
│          AccountRepository (profile, password, devices, delete), LibraryRepository (likes, offline),
│          PlaylistsRepository (list + content offline, edits via Outbox, share links, shared playlists)
├─ media/  PlaybackService (MediaLibraryService + ExoPlayer: background, notification, headset keys,
│          history via Outbox / skips, server prefetch, endless «Моя волна», skip on error,
│          queue persistence + onPlaybackResumption, EQ in the audio sink, Android Auto catalog),
│          LibraryTree (catalog for Android Auto), ArtworkProvider (content:// artwork for the car),
│          PlayerConnection (MediaController → StateFlow<PlayerState> for UI), QueueStore (filesDir/queue.json),
│          SleepTimer, AudioStorage (one SimpleCache + DownloadManager), PinnedLruEvictor, MusicDownloadService, Streams, MediaItems
│   audio/ LoudnessProcessor → (eq) → CrossfadeProcessor, StreamInfo, LoudnessStore (+ LoudnessMath)
│   eq/    EqMath (bands/formulas = website), EqualizerProcessor (BaseAudioProcessor, PCM16), EqualizerStore (DataStore)
├─ widget/ MusicWidget (Glance, 3 sizes), MusicWidgetReceiver, NowPlayingStore (state + one decoded artwork),
│          Play/Next/Previous ActionCallbacks (MediaController)
└─ ui/     MusicRoot (theme from artwork, Nav3, mini/full player, snackbars, Navigator, add-to-playlist sheet, status bar scrim),
           MainViewModel (TrackActions, PlayerControls, sign out / account deleted, incoming links), home/ (new home),
           account/ (profile screen, genres, Avatar), moods/ (20 moods, cards, mood and all-moods screens), chart/ (regions),
           auth/ search/ (moods grid when idle) library/ settings/ player/ catalog/ playlists/ (drag, share, shared)
           equalizer/ common/ (Components, Collections, Groups) theme/
```

Navigation keys: Home, Search, Library(tab), Settings, Artist(id?, name), Collection(id), Playlist(id), Equalizer,
Account, Mood(id), Moods, Chart, Shared(slug). The nav bar is hidden on Settings, Equalizer and Account.
Detail screens use `hiltViewModel(key = ...)` in the activity store (no per-entry ViewModel decorator, so tab state
survives switching). `LocalNavigator` opens pages from anywhere, including the full player (it collapses first).

Unidirectional data flow: ViewModel exposes `StateFlow`, UI collects with `collectAsStateWithLifecycle()`.
The UI talks to the player only through `PlayerConnection`. It connects in MainActivity.onStart and releases
in onStop; playback continues in the service. Track rows get actions and marks through `LocalTrackActions` and
`LocalTrackMarks`.

### Audio storage (how space is saved)

- **One cache for everything** (`noBackupFilesDir/audio`): Android does not clear it on its own, it is excluded
  from backup, and it is removed with the app. Listened tracks and downloads share it, so nothing is stored twice.
- **PinnedLruEvictor**: above the user limit (128 MB to 2 GB, default 256 MB), listened tracks are evicted
  least recently played first. Downloads are *pinned*: they are never evicted and do not count toward the limit.
  Nothing is evicted until the pin list (from the DownloadIndex) and the limit are loaded, so downloads cannot be
  wiped while SimpleCache loads from disk. Unit tests: `src/test/.../PinnedLruEvictorTest.kt`.
- **Download quality** (default «Экономно»): `?q=o64` makes the server transcode to Opus 64 kbit/s, about half the size
  of the AAC 128 original. Cache keys: `id` for the original stream, `id@o64`/`id@o96` for compact copies.
  When a compact download finishes, the original is dropped from the listening cache.
- **Playback resolves downloads**: the player always gets the plain stream URL. When the track is downloaded,
  a `ResolvingDataSource` swaps in the downloaded copy's key and URI, so downloaded tracks play offline.
- Artwork has its own Coil disk cache capped at 64 MB (`cacheDir/artwork`).

Verified on the emulator:
- background playback after swiping the task away (FGS type mediaPlayback);
- notification and lock-screen controls, and media keys;
- download with a progress notification;
- offline playback of a downloaded track to its end;
- downloads and cache survive process death;
- «Очистить кэш» keeps downloads;
- release build (R8): login, playback and download.

Also verified (0.2.0):
- queue and position restored after force-stop;
- headset Play with the app stopped resumes from the saved position (MediaButtonReceiver + onPlaybackResumption);
- EQ preset and dragging while playing (debug and release);
- synced lyrics follow playback;
- artist page → YouTube playlist → «Сохранить» creates a server playlist;
- track menu → «В плейлист» → new playlist;
- history tab;
- sleep timer «когда закончится трек» pauses exactly at the end.

Also verified (0.3.0), with PCM dumps of the sink output:
- crossfade AAC → AAC: 6 s tail held, mixed into the next track; no click at the junction, no level dip;
  the new track's position starts at the start of the blend (lyrics stay in sync);
- crossfade AAC 44.1 kHz → downloaded Opus 48 kHz (format change, AudioTrack recreated): tail resampled and mixed;
- sleep timer «когда закончится трек» with crossfade on: no blend, the track plays to its end and pauses;
- release build (R8): crossfade and playback.

Also verified (0.4.0), debug and release (R8):
- new home, account (profile save, wrong current password shown in the dialog), moods (35 tracks from 3 searches),
  chart region switch, search idle moods, seek slider (SliderState);
- airplane mode: rename + like queued, app restarted offline (home, likes, playlists from the phone), network back →
  outbox flushed by itself, server has both edits;
- drag in a playlist → order on the server; share link → share sheet, link opens the shared screen, unshare;
- App Links verified for the release package; widget: pin from settings, play/pause, next, small size, after
  force-stop + app start; release: like through the outbox, widget actions, artwork provider;
- `LibraryBrowseTest` (car catalog): root, 20 moods, playlist paging, tap 2nd track → whole playlist from index 1,
  search, «Моя волна».

Unit tests (38): PinnedLruEvictor (6), EqualizerProcessor (4: bit-exact flat, band gain, graph equals processor, no
wrap on overload), LRC parser, CrossfadeProcessor (8: blend shape, plan revoked → tail played out, seek and skip
cancel, double flush at the boundary, sample-rate change, last track not cut), Loudness (4: gain math, per-track
gain and ramp), OfflineSync (9: outbox compaction, in-flight op, JSON round trip, pending likes and playlist edits over
the server answer, reorder, row covers), UiLogic (6: initials, mood interleave, moods by hour, queries, region,
car item ids). Instrumented: LibraryBrowseTest.

### Audio chain: loudness and crossfade (how it works)

`DefaultAudioSink` runs `LoudnessProcessor → EqualizerProcessor → CrossfadeProcessor` (+ `TeeAudioProcessor` in
debug when `files/pcm_dump` exists: `adb shell run-as ru.palustris.music.debug touch files/pcm_dump`, restart the
app, WAVs appear in `files/pcm/`). Everything rides on Media3 1.11 `AudioProcessor.flush(StreamMetadata)`: before
each new stream (next item, seek) processors get the timeline and period uid, so they know the track exactly.

- **Loudness.** The server measures EBU R128 (LUFS, true peak) after each download; `LoudnessStore` asks
  `POST /api/loudness` for the current and next 3 tracks (on transitions and every 4 s, with backoff), caches to
  `filesDir/loudness.json` (downloads are requested at download time, so they work offline). Gain: target −14 LUFS;
  boost ≤ +6 dB and only while the true peak stays ≤ −1 dBTP; unknown → −5 dB (typical master ≈ −9 LUFS).
  The gain switches exactly at the track boundary; a value that arrives mid-track ramps in over 1 s.
- **Crossfade, one player.** About `fade + 1 s` before the end the processor starts holding the last `fade` ms in a
  ring buffer (older frames go out as usual). On end of stream it keeps the tail; at the next stream's flush it mixes
  the tail into the new start with equal-power curves (old cos, new sin), resampling if the rate changed. Media3
  anchors the next item's start to the frames written, so the item transition and the position are right.
  No second ExoPlayer, no MediaSession swap, no audio-focus juggling.
- **Who decides.** `PlaybackService.planCrossfade` runs every 250 ms on the main thread and publishes a volatile
  `CrossfadePlan(fromMediaId, fadeMs, allowed)`. Not allowed: track < max(20 s, 3 × fade), no next item or the same
  id, repeat one, «stop at end of track» (`pauseAtEndOfMediaItems`), next item not buffered ≥ 2 s. If the plan is
  revoked before the end, the held tail is played out unchanged — nothing is lost.
- **Sink quirks the processor relies on (seen on the emulator, covered by tests).** At a gapless boundary the sink
  drains, flushes with the new stream, then drains and flushes the same stream again to sync time: a flush of the
  same period with no frames in between is a no-op. On a format change it first flushes with the *old* period
  (or none) while the old AudioTrack plays out: that flush is intermediate and keeps the tail. A held tail older
  than 3 s is dropped. Any other flush during capture or mixing (seek, manual skip) cancels the blend.
- `EqualizerProcessor` applies the format in `onFlush`, not `onConfigure`: configure for the next track arrives while
  the current one is still playing.

### Offline: likes, playlists, profile, home (0.4.0)

- **Copies on the phone** (`filesDir/offline/*.json` via `OfflineFiles`): `me.json`, `likes.json`, `playlists.json`
  (list + every playlist's tracks, up to 100 playlists), `history.json`, `home.json`, `mood-{id}.json`. Repositories read
  the copy first, then the server. Sign out and account deletion wipe the folder.
- **Outbox** (`outbox.json`): like/unlike, playlist add/remove/order/rename and listens (history) are applied locally at
  once and queued. Sent in order when the default network becomes available, on every add, and on retry with backoff
  15 s → 5 min. 2xx/4xx drop the op (4xx = «no longer valid», e.g. playlist deleted on the site); IO, 5xx, 429, 401 retry.
  Compaction: like+unlike cancel, only the last order and name per playlist are kept, an add is dropped by a later
  remove (the remove is still sent). The op in flight is never compacted.
- **Merging:** a refresh first flushes the outbox, then lays the still-pending ops over the server answer
  (`applyPending`), so nothing blinks. Create, delete and share need the network (they need the server's answer).
- Home reads its copy before the network: offline requests fail instantly and would otherwise win the race.
- Settings → account card shows how many edits wait for the network.

### Playlists: drag, share, links

- Drag by the handle (`sh.calvin.reorderable`); the order lives in the screen while dragging and goes to
  `PlaylistsRepository.reorder` on release. «Выше/Ниже» stay in the track menu for TalkBack.
- Share: `POST /api/playlists/{id}/share` → `https://music.palustris.ru/s/{slug}` in the system share sheet; «Закрыть
  доступ по ссылке» unshares. **App Links:** the manifest filter `https://music.palustris.ru/s/*` (autoVerify) and
  `web/public/.well-known/assetlinks.json` (release key SHA-256). `pm get-app-links ru.palustris.music` → verified.
  The debug build (other id and key) is not verified: open links with `am start -a VIEW -d <url> ru.palustris.music.debug`.
- `SharedKey(slug)` screen: listen, download, «Сохранить к себе» (creates your copy).

### Widget (Glance)

- `MusicWidget`: SizeMode.Responsive 110×110 (artwork + play/next), 250×110 and 250×180 (artwork, title, prev/play/next).
  GlanceTheme = wallpaper colors. Tap on artwork/title opens the app; empty state «Включить музыку» opens the app.
- `NowPlayingStore` is fed by PlaybackService (`onEvents`) and decodes the artwork once per track (Coil, 300 px,
  RGB_565, `allowHardware(false)`), shared by all sizes; widget updates are debounced 300 ms. After a reboot the store
  starts from `queue.json`.
- Buttons are `ActionCallback`s that build a `MediaController` from **applicationContext** (the receiver context cannot
  bind: `ReceiverCallNotAllowedException`). Play waits up to 3 s for the restored queue and keeps the controller 1 s so
  the service reaches the foreground. Settings has «Виджет на главный экран» (`requestPinAppWidget`).
- Force-stop makes Android grey out the widget until the app is opened again (system behaviour).

### Android Auto

- `PlaybackService` is a `MediaLibraryService`; `automotive_app_desc.xml` + `com.google.android.gms.car.application`.
- `LibraryTree`: root → «Моя волна» (playable), Мне нравится, Плейлисты → pl:{id}, Скачанные, Недавно играло,
  Настроение → mood:{id}. Track items are `item|{list}|{trackId}`; `onSetMediaItems`/`onAddMediaItems` expand a tapped
  item to the whole list from that track (the queue gets plain track ids). Voice «play X» → search; empty query → wave.
  Paging is honoured. Grid style for playlists and moods.
- Library commands only for the app itself, trusted controllers (`isTrusted`) and Android Auto / Assistant / Wear /
  SystemUI packages; other apps get playback control only.
- `ArtworkProvider` (exported, `${applicationId}.artwork`) serves artwork as `content://` (the car does not load http):
  only https from i.ytimg.com / *.googleusercontent.com / *.ggpht.com, ≤ 2 MB, 300 files in `cacheDir/auto_art`.
  Any failure becomes `FileNotFoundException` (the only error Binder passes back for `openFile`).
- Verified with `LibraryBrowseTest` (MediaBrowser like the car). Not tried on a real head unit or the DHU.

### Known gaps / next steps

- Android Auto on a real car or the Desktop Head Unit (only the MediaBrowser test so far).
- Widget picker preview (Glance generated previews, API 35+); today the picker shows the app icon.
- Offline creation and deletion of playlists (need temporary ids).
- Crossfade: optional «no blend inside an album» rule; crossfade for repeat-one.
- Baseline profile.
- Delete the test account `android-test@palustris.ru` when it is no longer needed for emulator tests.

## Product scope (parity with the website + native extras)

Login/registration/password reset · account settings · «Моя волна» · home with recommendations · moods · charts by
country · search (tracks/artists/albums) · artist/album pages · likes · playlists (create/rename/drag/share/open links) ·
history · synced lyrics · queue (play next, reorder) · shuffle/repeat · **equalizer with presets** (must have) ·
sleep timer · crossfade (0–12 s) · loudness normalization · background playback, notification, lock screen, headset
buttons · offline cache and offline library · home-screen widget (Glance) · Android Auto.

## Design direction

The website direction is «Обложка задаёт цвет» (the artwork sets the colors). Carry it over natively:
- Material 3 Expressive: shape morphing, spring motion (`MotionScheme.expressive()`), expressive typography.
- The player and accents re-tint from the current artwork (Palette → `ColorScheme`), with animated transitions.
  Respect dynamic color and dark mode.
- Mini player docked above the navigation bar, expanding to a full-screen player with a shared-element transition.
  Swipe gestures, predictive back.
- Edge-to-edge everywhere, adaptive layouts for tablets and foldables.
- Russian UI text: short and human, no bureaucratic phrasing.

## Skills (in `Desktop/SSH/.agents/skills`, loaded when pi runs from `Desktop/SSH`)

- **android-official/**: `android-cli` (tooling), `navigation-3`, `navigation-event` (predictive back),
  `edge-to-edge`, `adaptive`, `jetpack-compose-performance`, `media3-cast-integration`,
  `glance-*` (widget), `anr-audit-and-fix`, `r8-analyzer`, `android-profiler`, `testing-setup`, security audits, `agp-9-upgrade`
- **kotlin-footguns/**: `media-playback-footguns` (read before touching playback/EQ/crossfade),
  `compose-visuals-footguns` (artwork palettes, gradients, glass), `compose-screens-footguns`,
  `state-and-background-footguns`, `data-layer-footguns`, `remote-api-footguns`, `footgun-scan` (run on diffs)
- **compose-expert/**: Compose + Material 3 (incl. Expressive) with androidx source references
- **compose-perf/**: stability, lazy lists, deferred state reads, baseline profiles, R8, release-mode testing
- **android-arch/**: architecture, ViewModel, data layer, coroutines, Retrofit, Coil, accessibility, testing, Gradle
- **design/**: `ui-ux-pro-max` (has a Jetpack Compose stack database), `mobile-app-ui-design`
- Global: `frontend-design`, `imagegen` (icons, artwork placeholders)

## Rules

- Do not copy code from `Desktop/SSH/_refs` (GPL/proprietary). Read for ideas only, except `media3/` (Apache-2.0).
- Keep dependency versions in the version catalog. Prefer stable versions. The exception is material3 RC, kept for Expressive.
- No hardcoded secrets in the repo. `keystore.properties` and `local.properties` stay ignored.
