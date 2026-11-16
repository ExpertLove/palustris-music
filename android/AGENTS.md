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
./gradlew connectedDebugAndroidTest  # instrumented tests (device needed)
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
Retrofit 3 + kotlinx.serialization · OkHttp 5 · Coil 3 · Palette · Timber · SplashScreen · ProfileInstaller.
No Room yet: the download list lives in Media3's own DownloadIndex. Add Room when playlists or history go offline.
minSdk 26, target/compile 37.

## Architecture (as built)

```
ru.palustris.music
├─ core/   AppModule (OkHttp + auth interceptor, Retrofit, Json, @AppScope), net/ (MusicApi, apiCall → Russian error text),
│          model/ (Track…), prefs/ (SessionStore: token; SettingsStore: cache limit, download quality, Wi-Fi only)
├─ data/   LibraryRepository (likes, optimistic)
├─ media/  PlaybackService (MediaSessionService + ExoPlayer: background, notification, headset keys,
│          history/skip reporting, server prefetch, endless «Моя волна», skip on error),
│          PlayerConnection (MediaController → StateFlow<PlayerState> for UI),
│          AudioStorage (one SimpleCache + DownloadManager), PinnedLruEvictor, MusicDownloadService, Streams, MediaItems
└─ ui/     MusicRoot (theme from artwork, Nav3 tabs, mini/full player, snackbars), MainViewModel (TrackActions, PlayerControls),
           auth/ home/ search/ library/ settings/ player/ common/ theme/
```

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

### Known gaps / next steps

- Queue and position are not restored after process death (needs Media3 `onPlaybackResumption` plus a persisted queue).
- Equalizer.
- Lyrics.
- Artist and album pages.
- Playlists.
- Android Auto (MediaLibraryService).
- Glance widget.
- Baseline profile.
- Move Slider to the SliderState overload (deprecation warning).

## Product scope (parity with the website + native extras)

Login/registration/password reset · «Моя волна» · search (tracks/artists/albums) · artist/album pages ·
likes · playlists (create/rename/reorder/share) · history · synced lyrics · queue (play next, reorder) ·
shuffle/repeat · **equalizer with presets** (must have) · sleep timer · crossfade/normalization ·
background playback, notification, lock screen, headset buttons · offline cache · home-screen widget (Glance) ·
Android Auto (later).

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
