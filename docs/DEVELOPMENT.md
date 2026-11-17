# Разработка

## Что поставить

| Для чего | Что | Версии, на которых всё собирается |
|---|---|---|
| бэкенд | Rust (rustup), C-компилятор для `rusqlite` (bundled SQLite) | rustc 1.93 |
| сайт | Node.js + npm | Node 24, TypeScript 7, Vite 8, React 19 |
| Android | JDK 21, Android SDK (platform 37), Gradle wrapper в репо | см. `android/AGENTS.md` |
| выкладка | `ssh`, `tar`, bash (на Windows — Git Bash) | — |
| проверка UI | Chrome + `puppeteer-core` (по желанию) | — |

Основная машина разработки — Windows 10 + Git Bash. В Linux и macOS всё работает так же.

## Сайт

```bash
cd web
npm ci
npm run dev                  # http://localhost:5173, /api проксируется на прод
API_UPSTREAM=http://127.0.0.1:8030 npm run dev   # …или на локальный бэкенд
npx tsc -p tsconfig.app.json --noEmit             # типы
npm run build                # tsc -b + vite build → web/dist
npm run lint                 # oxlint
```

В dev-режиме сайт работает с **боевыми данными**: вход настоящим аккаунтом, лайки и плейлисты пишутся в прод.
Для экспериментов заведите тестовый аккаунт и удалите его после (`Профиль → Удалить аккаунт` или `DELETE /api/account`).

## Бэкенд

```bash
cd server
cargo check
cargo test                   # юнит-тесты (разбор cookies, классификация ошибок yt-dlp и др.)
```

Запуск локально (нужен `yt-dlp` в PATH, иначе не будет звука; остальное работает):

```bash
MUSIC_DATA=./data MUSIC_WEB=../web/dist MUSIC_ADMINS=you@example.com cargo run
# API на http://127.0.0.1:8030; сайт — npm run dev с API_UPSTREAM=http://127.0.0.1:8030
```

Локальная база создаётся пустой в `server/data/` (в `.gitignore`). Боевую базу на машину разработчика не копируйте:
в ней почты и хеши паролей пользователей.

## Android

Всё в [`android/AGENTS.md`](../android/AGENTS.md): окружение, эмулятор, подпись, команды Gradle.

## Как вносить изменения

1. `git pull`. Посмотрите `git status`: в репозитории могут параллельно работать другие люди или агенты.
2. Небольшие коммиты, сообщения **по-русски**, в повелительном или назывном виде: «Админка: журнал загрузок».
   Коммитьте только свои файлы: `git commit -- путь1 путь2`.
3. Перед выкладкой:
   - `cargo test`, `npm run build` и для Android `./gradlew assembleDebug testDebugUnitTest` проходят (то же проверяет CI на GitHub, отладочный APK лежит в артефактах);
   - сайт сначала на `/next/`, проверка руками или скриншотами на 1440 и 390 px;
   - для бэкенда — проверка на живом сервере сразу после `deploy-server.sh` (`curl`, админка);
   - тестовые аккаунты и данные удалены.
4. Поменялись поведение, переменные, эндпоинты или сервер — обновите `README.md`, `docs/*`, `android/docs/API.md`.
5. `git push` в `master`. Force-push запрещён.

## Соглашения в коде

**Rust**
- Обработчики возвращают `ApiResult<T>` = `Result<Json<T>, (StatusCode, String)>`. Текст ошибки для пользователя — по-русски.
- `std::sync::Mutex` на базе: блокировку держать коротко, не держать через `.await`.
- Схема только расширяется в `db.rs::open`: `CREATE TABLE IF NOT EXISTS`, `ALTER TABLE … ADD COLUMN` с `let _ =`.
- Внешние процессы (yt-dlp, ffmpeg, journalctl) — через `tokio::process` с таймаутом и `kill_on_drop(true)`.
- Комментарии объясняют *зачем*, а не *что*.

**TypeScript / React**
- Состояние — zustand (`store/`, `player/store.ts`), без Redux и React Query.
- HTTP — только через `lib/api.ts` (`api`, `adminApi`).
- Стили — обычный CSS с токенами из `styles/base.css`; переиспользуйте `.btn`, `.chip`, `.seg`, `.acc-card`, `.badge`, `Dialog`.
  UI-кит не подключать.
- Тяжёлые редкие страницы грузятся лениво (`lazy(() => import(...))`), как `Account` и `Admin`.
- Тексты интерфейса — короткие, на «ты», без канцелярита; числа со словами через `plural()`.

## Проверка интерфейса скриншотами

Пример сценария (Node, `puppeteer-core`):

```js
const puppeteer = require('puppeteer-core')
const b = await puppeteer.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', args: ['--mute-audio'] })
const p = await b.newPage()
await p.setViewport({ width: 1440, height: 900 })
await p.goto('https://music.palustris.ru/next/', { waitUntil: 'networkidle2' })
await p.evaluate((t) => localStorage.setItem('music.token', t), process.env.TOKEN)  // токен из POST /api/login
await p.goto('https://music.palustris.ru/next/admin', { waitUntil: 'networkidle2' })
await p.screenshot({ path: 'admin.png' })
```

Мобильная проверка: `setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true })`.

## Ловушки окружения (Windows)

- В Git Bash heredoc с обратными кавычками и `${…}` портится. Сложные скрипты пишите в файл.
- Кириллица в аргументах `curl -d` на Windows ломается — используйте файл (`-d @body.json`) или латиницу.
- `.gitattributes` приводит всё к LF; не коммитьте CRLF.
- Порядок строк в Cargo.lock и package-lock.json не трогайте руками.
