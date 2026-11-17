# Архитектура

```
 браузер / Android ──HTTPS──► Caddy (music.palustris.ru)
                               ├─ /assets, /icons, index.html  → /opt/music/web       (статика, CSP, вечный кэш хешей)
                               ├─ /next/*                      → /opt/music/web-next  (проверочная копия сайта)
                               └─ /api/*                       → 127.0.0.1:8030  music.service (Rust, axum)
                                                                   ├─ SQLite  /var/lib/music/music.db
                                                                   ├─ кэш аудио /var/lib/music/cache
                                                                   ├─ yt-dlp (/opt/yt-dlp) ──► YouTube
                                                                   │     └─ плагин PO-токенов ──► bgutil-provider :4416 (Docker)
                                                                   ├─ InnerTube YouTube Music (поиск, страницы, чарты)
                                                                   ├─ LRCLIB (тексты)
                                                                   └─ SMTP pmail (письма)
```

Один процесс на Rust делает всё: API, сессии, загрузку аудио, рекомендации. Без очередей, без внешней БД.
Сервер слабый (1 ядро, `MemoryMax=600M`), поэтому всё тяжёлое ограничено семафорами.

## Бэкенд (`server/src`)

| Файл | Ответственность |
|---|---|
| `main.rs` | `AppState`, маршруты, сессии и пароли (argon2), профиль, лайки, история, плейлисты, `/api/stream`, обслуживание раз в 30 мин |
| `audio.rs` | загрузка трека через yt-dlp, отдача **во время** загрузки, предзагрузка, LRU-очистка кэша |
| `accounts.rs` | аккаунты YouTube (cookies), ротация и паузы, пометка IP, журнал попыток `dl_events` |
| `admin.rs` | `/api/admin/*`: обзор, аккаунты, журнал, логи (journalctl/docker/tail), пользователи |
| `yt.rs` | неофициальный API YouTube Music (InnerTube `WEB_REMIX`): поиск, исполнители, альбомы, плейлисты, чарты; кэш 12 ч |
| `recs.rs` | «Моя волна» и «Для тебя» |
| `lyrics.rs` | тексты из lrclib.net с кэшем в таблице `lyrics` |
| `compact.rs` | Opus-копии (`?q=o64|o96`) для скачивания в Android, ffmpeg по одной задаче с низким приоритетом |
| `mail.rs` | письма через lettre (STARTTLS), HTML-страница подтверждения почты |
| `db.rs` | схема и миграции SQLite, модель `Track` |

### Аутентификация

- `POST /api/login|register` → `{token}` + cookie `music_s` (HttpOnly, `Path=/api`, 60 дней).
- Токен принимается из `Authorization: Bearer` (Android, fetch) или из cookie (нужно для `<audio src>`, он не шлёт заголовков).
- Сессия скользящая: живёт 60 дней с последнего обращения (`sessions.seen`). Выход удаляет строку из `sessions`.
- Почту подтверждать не обязательно — от неё зависят только письма. Поэтому **админов задают только по зарегистрированным адресам**.
- Антибрутфорс: `rate_limit()` в памяти по IP из `X-Forwarded-For`.
- Админ — почта из `MUSIC_ADMINS`. `/api/me` отдаёт `admin: true|false`, каждый `/api/admin/*` проверяет заново (иначе 403).

### Как играет трек — `/api/stream/{id}?a=<artist>&t=<title>`

1. Есть файл в кэше `{id}.m4a|webm|opus|mp3` → отдаём с Range (`ServeFile`), обновляем mtime для LRU.
2. Трек недавно не скачался (10 мин) → сразу 502, чтобы плеер перешёл к следующему.
3. Иначе `Audio::job(id)`: одна загрузка на трек, остальные запросы ждут её же.
   yt-dlp печатает `before_dl:%(filesize)s %(ext)s` — как только размер известен, клиент получает ответ с полным
   `Content-Length`, а байты дочитываются из растущего `{id}.{ext}.part` (`serve_progressive`). Старт ≈ 2 с.
4. Порядок попыток (`audio.rs::run`):
   - **без аккаунта**: `visionos` (нет 5-секундной «рекламной» паузы) → `music` → `web` → `tv_simply` и `mweb` с PO-токеном;
   - на «Sign in to confirm you're not a bot» IP помечается на 15 мин и загрузка переходит к **аккаунтам**:
     до трёх на трек, по давности использования; аккаунт с той же ошибкой отдыхает 30 мин, с 429 — 60 мин,
     с «cookies are no longer valid» становится `dead` до замены cookies;
   - пока IP помечен и аккаунтов нет — только `visionos` и `mweb`, чтобы не продлевать блок;
   - если клиент уже получает байты попытки, переключаться нельзя — ошибка уходит клиенту.
5. Не вышло по этому id → ищем 2 других ролика той же песни (`alternatives`: тот же исполнитель или название)
   и запоминаем замену в `AppState.alt`.
6. Каждая попытка пишется в `dl_events` (способ, аккаунт, мс, тип ошибки) — это вкладка «Загрузки» в админке.

Слоты: 3 одновременные загрузки для воспроизведения, 1 для предзагрузки (`POST /api/prefetch` — следующие в очереди).
Громкость (`loudness.rs`): после каждой загрузки `nice ffmpeg -af ebur128=peak=true` считает LUFS и true peak
(~1 с CPU на трек, строго по одному), результат в таблице `loudness`. `POST /api/loudness` отдаёт известные значения,
а уже скачанные, но не посчитанные треки ставит в очередь. Пользуется приложение для выравнивания громкости.
Очистка: раз в 30 мин, если кэш больше `MUSIC_CACHE_MAX_GB`, удаляются самые давно игравшие файлы до 90% лимита;
брошенные `.part` старше суток — тоже.

### База (`/var/lib/music/music.db`, WAL)

| Таблица | Что хранит |
|---|---|
| `users` | почта, argon2-хеш, имя, `email_verified`, `consent_at` |
| `sessions` | токен → пользователь, `created`, `seen` |
| `email_tokens`, `password_tokens` | ссылки подтверждения почты и сброса пароля |
| `likes`, `history`, `skips` | лайки, прослушивания (≥ 30 с), пропуски (храним 90 дней) |
| `playlists`, `playlist_items` | плейлисты (`share` — слаг публичной ссылки), треки с `pos` |
| `user_genres` | жанры из онбординга |
| `lyrics` | кэш LRCLIB |
| `yt_accounts` | аккаунты YouTube: статус, пауза, счётчики (cookies — файлами в `ytaccounts/`) |
| `dl_events` | журнал попыток загрузки, 14 дней |
| `loudness` | громкость трека по EBU R128: `lufs`, `peak` (dBTP) — для выравнивания в приложении |

Треки отдельной таблицы не имеют: метаданные (`title, artist, album, duration, thumb`) копируются в строки лайков,
истории и плейлистов. Миграции — только добавляющие, в `db.rs::open`.

### Рекомендации (`recs.rs`)

- **Вкус** = лайки (вес 3) + история с затуханием (полураспад ~21 день) − пропуски.
- **Моя волна** собирает кандидатов поиском YouTube Music, по убыванию веса:
  1) исполнитель последнего дослушанного трека (пропуски подряд не считаются);
  2) исполнители вкуса (недавние слегка понижены, чтобы волна не зацикливалась);
  3) жанры из профиля — источник нового;
  4) добор чартом и запросами «новые песни» / «лучшие песни» (не больше 8) — для холодного старта.
  Дальше: без сборников, часовых миксов и каналов-агрегаторов, дубли схлопнуты («Song (From "X")» = «Song»),
  совсем недавнее не повторяется, не больше 2 треков исполнителя, соседние — от разных исполнителей.
  У каждого трека короткое «почему» («Твой жанр: рок»).
- **Для тебя**: треки исполнителей вкуса и жанров профиля по тем же правилам чистки.

## Фронтенд (`web/src`)

| Папка | Что |
|---|---|
| `App.tsx` | роутинг (wouter): гость → лендинг/вход, пользователь → оболочка с плеером; `/admin`, `/account` — ленивые чанки |
| `player/engine.ts` | две «деки» `<audio>` для кроссфейда; Web Audio: гейн деки → предусилитель → 10 полос EQ → [компрессор для ровной громкости] → мастер → анализатор |
| `player/store.ts` | zustand: очередь, перемешивание, повтор, волна, таймер сна, история/пропуски, Media Session, сохранение в `localStorage['music.player.v2']` |
| `player/effects.ts` | цвет из обложки, горячие клавиши |
| `store/` | `auth` (токен в `localStorage['music.token']`), `library` (лайки и плейлисты), `ui` (меню, диалоги, тосты) |
| `lib/api.ts` | весь HTTP: `api` и `adminApi`; человеческие тексты ошибок |
| `lib/color.ts` | акцент и «аура» из обложки |
| `pages/` | Home, Search, Library, Collections (альбом/исполнитель/плейлист/настроения/топ/шаринг), Account, Admin, Auth, Landing, Legal |
| `components/` | Player (док, мини-плеер, полноэкранный), TrackList, Cards, Overlays (меню, диалоги, тосты), Equalizer, LyricsQueue, Seek, Shell |
| `styles/` | `base.css` — токены и кнопки; `layout.css`, `components.css`, `pages.css`, `admin.css` |
| `public/sw.js` | сервис-воркер: оболочка офлайн; API и аудио не кэшируются |

Сборка: Vite, `VITE_BASE=/next/` для проверочной копии. CSP в Caddy разрешает картинки только с googleusercontent,
ggpht и i.ytimg, скрипты и шрифты — только свои: новые внешние ресурсы требуют правки `ops/music.caddy`.

## Android (`android/`)

Нативный клиент к тому же API: Compose (Material 3 Expressive), Media3 (фоновое воспроизведение, экран блокировки),
Hilt, Room, Retrofit, Coil 3. Скачанные треки — компактные Opus-копии с сервера (`?q=o64|o96`).
Всё остальное — в [`android/AGENTS.md`](../android/AGENTS.md), API — в [`android/docs/API.md`](../android/docs/API.md).

## API админки

Все требуют сессию админа, иначе 401/403.

| Метод и путь | Что |
|---|---|
| `GET /api/admin/overview` | статус: пометка IP, аккаунты, загрузки за час/сутки, кэш, диск, yt-dlp, пользователи |
| `GET /api/admin/accounts` | аккаунты YouTube со статусом и счётчиками |
| `POST /api/admin/accounts` `{label, cookies}` | добавить; `cookies` — текст Netscape cookies.txt или JSON-массив |
| `PUT /api/admin/accounts/{id}/cookies` `{cookies}` | заменить cookies, сбросить статус |
| `PATCH /api/admin/accounts/{id}` `{label?, enabled?, reset?}` | переименовать, вкл/выкл, снять паузу |
| `DELETE /api/admin/accounts/{id}` | удалить вместе с файлом cookies |
| `POST /api/admin/accounts/{id}/test` | видит ли YouTube вход + `yt-dlp --simulate` на `MUSIC_TEST_VIDEO` |
| `GET /api/admin/events?limit&fail=1&video` | журнал попыток загрузки |
| `GET /api/admin/logs?src&lines&q` | `src`: `music`, `caddy`, `system`, `bgutil`, `ytdlp`, `backup` |
| `GET /api/admin/users` | пользователи и активность |
