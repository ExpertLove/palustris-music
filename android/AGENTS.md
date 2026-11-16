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

**Emulator acceleration is NOT installed yet** (needs admin). Until the user runs
`%LOCALAPPDATA%\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat`
as administrator, the emulator cannot boot. Use a real phone over USB (`adb devices`) or
`android preview render` (renders Compose @Preview to PNG without a device).

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

Kotlin 2.4 · Jetpack Compose (BOM 2026.09) + **Material3 1.5.0-rc01** (Expressive APIs) · Navigation 3 ·
Media3 1.11 (ExoPlayer, MediaSessionService, OkHttp datasource, ui-compose) · Hilt (KSP) · Room · DataStore ·
Retrofit 3 + kotlinx.serialization · OkHttp 5 · Coil 3 · Palette · Timber · SplashScreen · ProfileInstaller.
minSdk 26, target/compile 37.

## Architecture (target)

```
ru.palustris.music
├─ core/        network (Retrofit, auth interceptor), database (Room), datastore, di modules
├─ data/        repositories: auth, catalog/search, library (likes, playlists, history), lyrics
├─ playback/    PlaybackService (MediaSessionService + ExoPlayer), queue, EQ (android.media.audiofx), cache
├─ ui/
│  ├─ theme/    MusicTheme: M3 Expressive, dynamic color from artwork (Palette), motion scheme
│  ├─ player/   mini player, full-screen player, lyrics, queue, equalizer
│  └─ <feature>/ home (wave), search, library, artist, album, playlist, settings, auth
└─ MainActivity (single activity, edge-to-edge, Navigation 3)
```

Unidirectional data flow: ViewModel exposes `StateFlow<UiState>`, UI collects with
`collectAsStateWithLifecycle()`. Playback state lives in the service. UI talks to it through `MediaController`.

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
