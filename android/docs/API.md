# Music backend API (generated from server/src/main.rs)

Base URL: `https://music.palustris.ru`. Auth: `Authorization: Bearer <token>` (token from `/api/login` / `/api/register` JSON body); cookie `music_s` is the web fallback. `/api/stream/{id}` also accepts Bearer, supports `Range` (206).

**`/api/stream/{id}?q=o64|o96`** — compact Opus copy (Ogg) for downloads: ffmpeg transcodes the cached original once, then it is served as a file with `Content-Length` and Range. ~2× smaller than the AAC 128 original. First request for a track can take ~5–10 s. Query `a`/`t` (artist/title) let the server find a replacement if the video is gone.

**`POST /api/loudness`** `{"ids": [...]}` (до 100) → `{"<id>": {"lufs": -8.8, "peak": 1.2}, …}` — громкость по EBU R128:
интегральная (LUFS) и пиковая с учётом межсэмпловых выбросов (dBTP). Сервер считает её ffmpeg-ом после загрузки трека
(около секунды на трек). Треков, которых ещё нет, в ответе нет: если файл уже в кэше, он встаёт в очередь на анализ,
спросите снова через несколько секунд. Целевую громкость выбирает клиент (приложение: −14 LUFS).


### Рекомендации (с 0.6.0)

Источник похожего — радио YouTube Music (`next` с `RDAMVM{id}`, кэш сутки): оно строится по совместным
прослушиваниям, а не по названию. Зёрна радио — последние дослушанные треки, свежие лайки и самые «свои» треки
любимых исполнителей. Скрытое пользователем (`/api/dislikes`, `/api/blocked-artists`) не попадает никуда.

- **`GET /api/wave?mode=&mood=&activity=&region=`** — «Моя волна». `mode`: пусто (всё вместе) | `favorite` |
  `discover` (только незнакомые исполнители) | `popular` (чарт `region` под вкус). `mood`: `energetic` | `happy` |
  `calm` | `sad`. `activity`: `wakeup` | `road` | `work` | `workout` | `sleep` | `party`. Ответ — 30 треков с полем
  `why` («Похоже на «Tusa»», «Новое для тебя, похоже на Шакиру»). Без параметров — как раньше (сайт).
- **`GET /api/radio?id=&title=&artist=`** — радио по треку (до 40 похожих, сам трек не входит).
  **`GET /api/radio?artistId=UC…`** или **`?artistName=`** — радио исполнителя. Поле `why`: «Радио «…»».
- **`GET /api/mixes`** → `{mixes: [{id, title, sub, thumbs, tracks}], forgotten: [Track], repeat: [Track]}`.
  Миксы дня (до 4: группы похожих любимых исполнителей + новое рядом с ними) пересчитываются раз в сутки
  (полночь по Москве) и сразу после скрытия чего-либо. `forgotten` — лайкнутые больше 3 недель назад и не
  игравшие месяц, `repeat` — что играло 2+ раза за 30 дней.
- **`POST /api/dislikes`** `Track` — «Не нравится»: трек исчезает из рекомендаций, исполнитель становится реже,
  лайк снимается. **`DELETE /api/dislikes/{id}`** — вернуть. **`GET /api/dislikes`** → `{tracks, artists}`.
- **`POST /api/blocked-artists`** / **`DELETE /api/blocked-artists`** `{"name": "Eagles"}` — не предлагать
  исполнителя (и как соавтора). Имена сравниваются по ключу: транслит + согласные, «Шакира» = «Shakira».
- **`GET /api/search?q=…&kind=songs`** — только песни (фильтр «Композиции»): с длительностью, без клипов и часовых
  подборок. Обычный поиск YouTube Music больше не отдаёт длительность (`duration: 0`).

### Прослушивания и статистика

- **`POST /api/history`** принимает трек и необязательные `at` (unix-время, когда трек играл: приложение копит
  прослушивания без сети) и `secs` (сколько секунд он на самом деле звучал). `at` старше 30 дней или из будущего
  заменяется временем сервера. Сайт с 17.11.2026 тоже присылает `at` и `secs` (когда трек перестал играть, со вкладкой — через
  `keepalive`); без `secs` в статистике считается длина трека (или 3 минуты).
- **`GET /api/stats?period=week|month|year|all&tz=180`** — минуты, прослушивания, топ-10 исполнителей (у первых
  трёх — фото `image` и `artistId`) и треков, минуты по часам суток (24) и дням недели (7, с понедельника),
  `buckets` по дням (неделя, месяц) или месяцам (год, всё время), серии дней подряд, новые исполнители,
  `prevMinutes` — такой же период до этого. `tz` — сдвиг местного времени от UTC в минутах.
- **`GET /api/wrapped?year=&tz=`** — итоги года: то же за год плюс месяцы, самый музыкальный день, первый трек года,
  лайки за год и `percentile` (обогнали X % слушателей; только если слушателей с часом музыки за год ≥ 9).

| Route | Methods → handler | Params | Returns | Doc |
|---|---|---|---|---|
| `/api/health` | GET `health` | `` | `&'static str` |  |
| `/api/register` | POST `register` | `h: HeaderMap, Json(c): Json<Creds>` | `Result<Response, ApiError>` |  |
| `/api/login` | POST `login` | `h: HeaderMap, Json(c): Json<Creds>` | `Result<Response, ApiError>` |  |
| `/api/logout` | POST `logout` | `h: HeaderMap` | `Result<Response, ApiError>` |  |
| `/api/logout-others` | POST `logout_others` | `h: HeaderMap` | `ApiResult<serde_json::Value>` | Выйти на всех устройствах, кроме текущего. |
| `/api/password/forgot` | POST `forgot` | `h: HeaderMap, Json(b): Json<ForgotBody>` | `ApiResult<serde_json::Value>` | Письмо со ссылкой сброса. Ответ одинаковый, есть такой email или нет. |
| `/api/password/reset` | POST `reset_password` | `h: HeaderMap, Json(b): Json<ResetBody>` | `Result<Response, ApiError>` |  |
| `/api/password/change` | POST `change_password` | `h: HeaderMap, Json(b): Json<ChangeBody>` | `ApiResult<serde_json::Value>` |  |
| `/api/verify` | GET `verify` | `Query(q): Query<VerifyQ>` | `Response` | Подтверждение почты по ссылке из письма — отдаём человеческую HTML-страницу. |
| `/api/resend` | POST `resend` | `h: HeaderMap` | `ApiResult<serde_json::Value>` | Повторное письмо: не чаще раза в минуту и трёх раз в десять минут. |
| `/api/account` | DELETE `delete_account` | `h: HeaderMap` | `Result<Response, ApiError>` | Удаление аккаунта: всё связанное уходит каскадом. |
| `/api/me` | GET `me` | `h: HeaderMap` | `Result<Response, ApiError>` |  |
| `/api/profile` | POST `set_profile` | `h: HeaderMap, Json(b): Json<ProfileBody>` | `ApiResult<()>` |  |
| `/api/search` | GET `search` | `h: HeaderMap, Query(sq): Query<SearchQ>` | `ApiResult<Vec<Track>>` |  |
| `/api/search/all` | GET `search_all` | `h: HeaderMap, Query(sq): Query<SearchQ>` | `ApiResult<yt::SearchAll>` | Поиск по всем типам: треки, исполнители, альбомы, плейлисты. |
| `/api/artists` | GET `artists` | `h: HeaderMap` | `ApiResult<Vec<ArtistRow>>` | Исполнители из лайков и истории — для раздела «Исполнители». |
| `/api/artist` | GET `artist` | `h: HeaderMap, Query(q): Query<ArtistQ>` | `ApiResult<Vec<Track>>` | Треки одного исполнителя по имени (старый API). |
| `/api/yt/artist` | GET `yt_artist` | `h: HeaderMap, Query(q): Query<YtArtistQ>` | `ApiResult<yt::ArtistPage>` | Страница исполнителя YouTube Music: по browseId или по имени. |
| `/api/yt/collection/{id}` | GET `yt_collection` | `h: HeaderMap, Path(id): Path<String>` | `ApiResult<yt::Collection>` |  |
| `/api/lyrics` | GET `lyrics_handler` | `h: HeaderMap, Query(q): Query<LyricsQ>` | `ApiResult<lyrics::Lyrics>` |  |
| `/api/recs` | GET `recs_handler` | `h: HeaderMap` | `ApiResult<Vec<Track>>` |  |
| `/api/wave` | GET `wave_handler` | `h: HeaderMap, Query(q): Query<WaveQ>` | `ApiResult<Vec<recs::WaveTrack>>` | «Моя волна». Без параметров — как раньше; `mode`, `mood`, `activity` — настройки волны. |
| `/api/radio` | GET `radio_handler` | `h: HeaderMap, Query(q): Query<RadioQ>` | `ApiResult<Vec<recs::WaveTrack>>` | Радио: похожее на трек (`id`) или на исполнителя (`artistId` / `artistName`). |
| `/api/mixes` | GET `mixes_handler` | `h: HeaderMap` | `ApiResult<recs::Mixes>` | Миксы дня, забытые лайки и «на повторе» для главной. |
| `/api/dislikes` | GET `dislikes_list` | `h: HeaderMap` | `ApiResult<Dislikes>` | Что пользователь скрыл: треки с «не нравится» и исполнители. |
| `/api/dislikes` | POST `dislike` | `h: HeaderMap, Json(t): Json<Track>` | `ApiResult<()>` | «Не нравится»: трек больше не попадёт ни в волну, ни в радио, исполнитель — реже. Лайк снимается. |
| `/api/dislikes/{id}` | DELETE `undislike` | `h: HeaderMap, Path(id): Path<String>` | `ApiResult<()>` |  |
| `/api/blocked-artists` | POST `block_artist` | `h: HeaderMap, Json(b): Json<ArtistNameBody>` | `ApiResult<()>` | «Не предлагать исполнителя»: ни в волне, ни в радио, ни в миксах. |
| `/api/blocked-artists` | DELETE `unblock_artist` | `h: HeaderMap, Json(b): Json<ArtistNameBody>` | `ApiResult<()>` |  |
| `/api/stats` | GET `stats_handler` | `h: HeaderMap, Query(q): Query<StatsQ>` | `ApiResult<stats::Stats>` |  |
| `/api/wrapped` | GET `wrapped_handler` | `h: HeaderMap, Query(q): Query<StatsQ>` | `ApiResult<stats::Wrapped>` | «Итоги года». |
| `/api/top` | GET `top` | `h: HeaderMap, Query(q): Query<TopQ>` | `ApiResult<Vec<Track>>` |  |
| `/api/stream/{id}` | GET `stream` | `Path(id): Path<String>, Query(q): Query<StreamQ>, req: Request` | `Response` |  |
| `/api/prefetch` | POST `prefetch` | `h: HeaderMap, Json(b): Json<PrefetchBody>` | `ApiResult<()>` | Заранее качаем следующие треки очереди, чтобы они стартовали мгновенно. |
| `/api/loudness` | POST `loudness_handler` | `h: HeaderMap, Json(b): Json<PrefetchBody>` | `ApiResult<std::collections::HashMap<String, loudness::Loudness>>` | Громкость треков (LUFS и true peak) для выравнивания. Неизвестные в ответ не попадают; если трек уже есть в кэше, он встаёт в очередь на анализ, и клиент спросит снова позже. |
| `/api/likes` | GET `likes` | `h: HeaderMap` | `ApiResult<Vec<Track>>` |  |
| `/api/likes` | POST `like` | `h: HeaderMap, Json(t): Json<Track>` | `ApiResult<()>` |  |
| `/api/likes/{id}` | DELETE `unlike` | `h: HeaderMap, Path(id): Path<String>` | `ApiResult<()>` |  |
| `/api/history` | GET `history_list` | `h: HeaderMap, Query(q): Query<LimitQ>` | `ApiResult<Vec<HistItem>>` | Недавно прослушанные треки, без повторов. |
| `/api/history` | POST `history` | `h: HeaderMap, Json(b): Json<HistBody>` | `ApiResult<()>` |  |
| `/api/history` | DELETE `history_clear` | `h: HeaderMap` | `ApiResult<()>` |  |
| `/api/skip` | POST `skip` | `h: HeaderMap, Json(t): Json<Track>` | `ApiResult<()>` | Пропуск в первые секунды — сигнал «не то» для рекомендаций. |
| `/api/playlists` | GET `playlists` | `h: HeaderMap` | `ApiResult<Vec<PlaylistRow>>` |  |
| `/api/playlists` | POST `create_playlist` | `h: HeaderMap, Json(b): Json<NewPlaylist>` | `ApiResult<serde_json::Value>` |  |
| `/api/playlists/{id}` | GET `playlist` | `h: HeaderMap, Path(id): Path<i64>` | `ApiResult<PlaylistFull>` |  |
| `/api/playlists/{id}` | DELETE `delete_playlist` | `h: HeaderMap, Path(id): Path<i64>` | `ApiResult<()>` |  |
| `/api/playlists/{id}` | PATCH `rename_playlist` | `h: HeaderMap, Path(id): Path<i64>, Json(b): Json<RenameBody>` | `ApiResult<()>` |  |
| `/api/playlists/{id}/order` | PUT `reorder_playlist` | `h: HeaderMap, Path(id): Path<i64>, Json(b): Json<OrderBody>` | `ApiResult<()>` |  |
| `/api/playlists/{id}/share` | POST `share_playlist` | `h: HeaderMap, Path(id): Path<i64>` | `ApiResult<serde_json::Value>` |  |
| `/api/playlists/{id}/share` | DELETE `unshare_playlist` | `h: HeaderMap, Path(id): Path<i64>` | `ApiResult<()>` |  |
| `/api/playlists/{id}/items` | POST `add_to_playlist` | `h: HeaderMap, Path(id): Path<i64>, Json(t): Json<Track>` | `ApiResult<()>` |  |
| `/api/playlists/{id}/items/{track}` | DELETE `remove_from_playlist` | `` | `` |  |
| `/api/shared/{slug}` | GET `shared_playlist` | `Path(slug): Path<String>` | `ApiResult<serde_json::Value>` | Публичный плейлист по ссылке — без входа. |

## Request/response structs

```rust
#[derive(Deserialize)]
struct Creds {
    email: String,
    password: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    genres: Vec<String>,
    #[serde(default)]
    consent: bool,
}

#[derive(Serialize)]
struct LoginResp {
    token: String,
    email: String,
}

#[derive(Deserialize)]
struct VerifyQ {
    token: Option<String>,
}

#[derive(Deserialize)]
struct ForgotBody {
    email: String,
}

#[derive(Deserialize)]
struct ResetBody {
    token: String,
    password: String,
}

#[derive(Deserialize)]
struct ChangeBody {
    old: String,
    new: String,
}

#[derive(Deserialize)]
struct ProfileBody {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    genres: Vec<String>,
}

#[derive(Serialize)]
struct ArtistRow {
    name: String,
    count: i64,
    thumb: Option<String>,
}

#[derive(Deserialize)]
struct ArtistQ {
    name: String,
}

#[derive(Deserialize)]
struct YtArtistQ {
    id: Option<String>,
    name: Option<String>,
}

#[derive(Deserialize)]
struct SearchQ {
    q: Option<String>,
    region: Option<String>,
    /// `songs` — только песни (с длительностью), без клипов и подборок.
    kind: Option<String>,
}

#[derive(Deserialize)]
struct LyricsQ {
    artist: String,
    title: String,
    #[serde(default)]
    duration: i64,
}

#[derive(Deserialize)]
struct StreamQ {
    a: Option<String>,
    t: Option<String>,
    q: Option<String>,
}

#[derive(Deserialize)]
struct PrefetchBody {
    ids: Vec<String>,
}

/// Прослушивание. Приложение присылает, когда трек на самом деле играл (`at`, оно копит
/// прослушивания без сети) и сколько секунд (`secs`); сайт — только трек.
#[derive(Deserialize)]
struct HistBody {
    #[serde(flatten)]
    track: Track,
    #[serde(default)]
    at: Option<i64>,
    #[serde(default)]
    secs: Option<i64>,
}

#[derive(Serialize)]
struct HistItem {
    #[serde(flatten)]
    track: Track,
    #[serde(rename = "playedAt")]
    played_at: i64,
    plays: i64,
}

#[derive(Deserialize)]
struct LimitQ {
    limit: Option<i64>,
}

#[derive(Deserialize)]
struct WaveQ {
    mode: Option<String>,
    mood: Option<String>,
    activity: Option<String>,
    region: Option<String>,
}

#[derive(Deserialize)]
struct RadioQ {
    /// videoId трека-зерна.
    id: Option<String>,
    title: Option<String>,
    artist: Option<String>,
    /// Радио исполнителя: browseId (UC…) или имя.
    #[serde(rename = "artistId")]
    artist_id: Option<String>,
    #[serde(rename = "artistName")]
    artist_name: Option<String>,
}

#[derive(Serialize)]
struct BlockedArtist {
    name: String,
    at: i64,
}

#[derive(Serialize)]
struct Dislikes {
    tracks: Vec<Track>,
    artists: Vec<BlockedArtist>,
}

#[derive(Deserialize)]
struct ArtistNameBody {
    name: String,
}

#[derive(Deserialize)]
struct StatsQ {
    period: Option<String>,
    /// Сдвиг местного времени от UTC в минутах (Москва = 180).
    tz: Option<i64>,
    year: Option<i32>,
}

#[derive(Deserialize)]
struct TopQ {
    region: Option<String>,
}

#[derive(Serialize)]
struct PlaylistRow {
    id: i64,
    name: String,
    count: i64,
    thumbs: Vec<String>,
    share: Option<String>,
}

#[derive(Deserialize)]
struct NewPlaylist {
    name: String,
    /// Сразу наполнить: «сохранить альбом / чужой плейлист к себе».
    #[serde(default)]
    items: Vec<Track>,
}

#[derive(Serialize)]
struct PlaylistFull {
    id: i64,
    name: String,
    share: Option<String>,
    items: Vec<Track>,
}

#[derive(Deserialize)]
struct RenameBody {
    name: String,
}

#[derive(Deserialize)]
struct OrderBody {
    ids: Vec<String>,
}
```

## Structs from other modules

### accounts.rs
```rust
#[derive(Serialize)]
pub struct AccountInfo {
    pub id: i64,
    pub label: String,
    pub enabled: bool,
    /// ok | new | cooldown | dead | off
    pub status: String,
    pub note: Option<String>,
    pub created: i64,
    pub updated: i64,
    #[serde(rename = "lastUsed")]
    pub last_used: i64,
    #[serde(rename = "lastOk")]
    pub last_ok: i64,
    #[serde(rename = "lastErr")]
    pub last_err: i64,
    #[serde(rename = "cooldownUntil")]
    pub cooldown_until: i64,
    #[serde(rename = "okCount")]
    pub ok_count: i64,
    #[serde(rename = "errCount")]
    pub err_count: i64,
    /// Удачных загрузок за последний час — чтобы видеть нагрузку на аккаунт.
    #[serde(rename = "hourOk")]
    pub hour_ok: i64,
    /// Сколько cookies в файле и есть ли среди них cookies входа.
    pub cookies: usize,
    #[serde(rename = "loggedIn")]
    pub logged_in: bool,
}

#[derive(Serialize)]
pub struct Event {
    pub id: i64,
    pub at: i64,
    pub video: String,
    pub title: Option<String>,
    pub account: Option<i64>,
    #[serde(rename = "accountLabel")]
    pub account_label: Option<String>,
    pub client: String,
    pub ok: bool,
    pub kind: Option<String>,
    pub err: Option<String>,
    pub ms: i64,
}
```

### db.rs
```rust
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default)]
    pub duration: i64,
    #[serde(default)]
    pub thumb: Option<String>,
    /// browseId исполнителя в YouTube Music — только из свежих ответов YTM, в базе не хранится.
    #[serde(default, rename = "artistId", skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
    /// browseId альбома (MPREb_…), если известен.
    #[serde(default, rename = "albumId", skip_serializing_if = "Option::is_none")]
    pub album_id: Option<String>,
}
```

### lyrics.rs
```rust
#[derive(Serialize, Default, Clone)]
pub struct Lyrics {
    pub synced: Option<String>,
    pub plain: Option<String>,
    pub instrumental: bool,
    pub found: bool,
}
```

### recs.rs
```rust
/// Трек в выдаче + короткое объяснение, почему он здесь.
#[derive(Serialize, Clone, Debug)]
pub struct WaveTrack {
    #[serde(flatten)]
    pub track: Track,
    pub why: String,
}

#[derive(Serialize, Clone, Debug)]
pub struct Mix {
    pub id: String,
    pub title: String,
    pub sub: String,
    pub thumbs: Vec<String>,
    pub tracks: Vec<Track>,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct Mixes {
    pub mixes: Vec<Mix>,
    /// Лайкнутые давно и давно не игравшие.
    pub forgotten: Vec<Track>,
    /// Что крутят последние недели.
    pub repeat: Vec<Track>,
}
```

### stats.rs
```rust
#[derive(Serialize, Clone, Debug)]
pub struct ArtistStat {
    pub name: String,
    pub plays: i64,
    pub minutes: i64,
    pub thumb: Option<String>,
    /// Фото исполнителя из YouTube Music (только у первых мест).
    pub image: Option<String>,
    #[serde(rename = "artistId", skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
pub struct TrackStat {
    #[serde(flatten)]
    pub track: Track,
    pub plays: i64,
    pub minutes: i64,
}

#[derive(Serialize, Clone, Debug)]
pub struct Bucket {
    /// Начало промежутка (unix, UTC): подпись делает клиент.
    pub start: i64,
    pub minutes: i64,
}

#[derive(Serialize, Debug)]
pub struct Stats {
    pub period: String,
    pub from: i64,
    pub minutes: i64,
    pub plays: i64,
    pub tracks: i64,
    pub artists: i64,
    /// Минуты за такой же промежуток перед этим (для «+12 % к прошлой неделе»).
    #[serde(rename = "prevMinutes")]
    pub prev_minutes: Option<i64>,
    #[serde(rename = "topArtists")]
    pub top_artists: Vec<ArtistStat>,
    #[serde(rename = "topTracks")]
    pub top_tracks: Vec<TrackStat>,
    /// Минуты по часам суток (местное время), 24 значения.
    pub hours: Vec<i64>,
    /// Минуты по дням недели, с понедельника.
    pub weekdays: Vec<i64>,
    /// Неделя и месяц — по дням, год и всё время — по месяцам.
    pub buckets: Vec<Bucket>,
    #[serde(rename = "bucketKind")]
    pub bucket_kind: String,
    /// Сколько дней подряд с музыкой, включая сегодня (или вчера, если сегодня ещё не слушали).
    pub streak: i64,
    #[serde(rename = "bestStreak")]
    pub best_streak: i64,
    /// Исполнители, которых впервые послушали в этот промежуток.
    #[serde(rename = "newArtists")]
    pub new_artists: i64,
    /// Первое прослушивание вообще.
    pub since: i64,
}

#[derive(Serialize, Debug)]
pub struct DayStat {
    pub start: i64,
    pub minutes: i64,
    pub plays: i64,
}

#[derive(Serialize, Debug)]
pub struct Wrapped {
    pub year: i32,
    pub minutes: i64,
    pub plays: i64,
    pub tracks: i64,
    pub artists: i64,
    /// В сколько дней года была музыка.
    pub days: i64,
    #[serde(rename = "topArtists")]
    pub top_artists: Vec<ArtistStat>,
    #[serde(rename = "topTracks")]
    pub top_tracks: Vec<TrackStat>,
    /// Минуты по месяцам, 12 значений.
    pub months: Vec<i64>,
    pub hours: Vec<i64>,
    #[serde(rename = "busiestDay")]
    pub busiest_day: Option<DayStat>,
    #[serde(rename = "bestStreak")]
    pub best_streak: i64,
    #[serde(rename = "newArtists")]
    pub new_artists: i64,
    #[serde(rename = "firstTrack")]
    pub first_track: Option<Track>,
    #[serde(rename = "firstAt")]
    pub first_at: i64,
    pub likes: i64,
    /// Какую долю слушателей пользователь обогнал по минутам, в процентах. Нет, если слушателей мало.
    pub percentile: Option<i64>,
}
```

### yt.rs
```rust
#[derive(Serialize, Clone, Debug)]
pub struct ArtistCard {
    pub id: String,
    pub name: String,
    pub thumb: Option<String>,
    pub sub: String,
}

#[derive(Serialize, Clone, Debug)]
pub struct AlbumCard {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub sub: String,
    pub thumb: Option<String>,
    /// album | single | playlist
    pub kind: String,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct SearchAll {
    pub top: Option<ArtistCard>,
    pub tracks: Vec<Track>,
    pub artists: Vec<ArtistCard>,
    pub albums: Vec<AlbumCard>,
    pub playlists: Vec<AlbumCard>,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct ArtistPage {
    pub id: String,
    pub name: String,
    pub thumb: Option<String>,
    pub listeners: String,
    pub description: String,
    pub tracks: Vec<Track>,
    /// browseId плейлиста со всеми треками исполнителя.
    #[serde(rename = "allTracks")]
    pub all_tracks: Option<String>,
    pub albums: Vec<AlbumCard>,
    pub singles: Vec<AlbumCard>,
    pub playlists: Vec<AlbumCard>,
    pub similar: Vec<ArtistCard>,
    /// Радио исполнителя в YouTube Music: (videoId, playlistId RDEM…).
    #[serde(skip)]
    pub radio: Option<(String, String)>,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct Collection {
    pub id: String,
    pub kind: String,
    pub title: String,
    pub artist: String,
    #[serde(rename = "artistId")]
    pub artist_id: Option<String>,
    pub sub: String,
    pub thumb: Option<String>,
    pub description: String,
    pub tracks: Vec<Track>,
}
```
