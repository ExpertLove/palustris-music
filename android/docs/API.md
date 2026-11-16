# Music backend API (generated from server/src/main.rs)

Base URL: `https://music.palustris.ru`. Auth: `Authorization: Bearer <token>` (token from `/api/login` / `/api/register` JSON body); cookie `music_s` is the web fallback. `/api/stream/{id}` also accepts Bearer, supports `Range` (206).

**`/api/stream/{id}?q=o64|o96`** — compact Opus copy (Ogg) for downloads: ffmpeg transcodes the cached original once, then it is served as a file with `Content-Length` and Range. ~2× smaller than the AAC 128 original. First request for a track can take ~5–10 s. Query `a`/`t` (artist/title) let the server find a replacement if the video is gone.

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
| `/api/wave` | GET `wave_handler` | `h: HeaderMap` | `ApiResult<Vec<recs::WaveTrack>>` |  |
| `/api/top` | GET `top` | `h: HeaderMap, Query(q): Query<TopQ>` | `ApiResult<Vec<Track>>` |  |
| `/api/stream/{id}` | GET `stream` | `Path(id): Path<String>, Query(q): Query<StreamQ>, req: Request` | `Response` |  |
| `/api/prefetch` | POST `prefetch` | `h: HeaderMap, Json(b): Json<PrefetchBody>` | `ApiResult<()>` | Заранее качаем следующие треки очереди, чтобы они стартовали мгновенно. |
| `/api/likes` | GET `likes` | `h: HeaderMap` | `ApiResult<Vec<Track>>` |  |
| `/api/likes` | POST `like` | `h: HeaderMap, Json(t): Json<Track>` | `ApiResult<()>` |  |
| `/api/likes/{id}` | DELETE `unlike` | `h: HeaderMap, Path(id): Path<String>` | `ApiResult<()>` |  |
| `/api/history` | GET `history_list` | `h: HeaderMap, Query(q): Query<LimitQ>` | `ApiResult<Vec<HistItem>>` | Недавно прослушанные треки, без повторов. |
| `/api/history` | POST `history` | `h: HeaderMap, Json(t): Json<Track>` | `ApiResult<()>` |  |
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
#[derive(Serialize)]
pub struct WaveTrack {
    #[serde(flatten)]
    track: Track,
    why: String,
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

