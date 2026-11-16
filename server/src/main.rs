mod db;
mod mail;
mod recs;
mod yt;

use axum::{
    extract::{Path, Query, Request, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
    Json, Router,
};
use db::Track;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use tower::ServiceExt;
use tower_http::compression::CompressionLayer;
use tower_http::services::{ServeDir, ServeFile};

struct AppState {
    db: Mutex<Connection>,
    http: reqwest::Client,
    cache: yt::SearchCache,
    top: Mutex<std::collections::HashMap<String, (std::time::Instant, Vec<Track>)>>,
    cache_dir: std::path::PathBuf,
    ytdlp: String,
    dl: tokio::sync::Mutex<std::collections::HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
    mail: mail::Mailer,
    /// Примитивный антибрутфорс: ключ → моменты попыток.
    attempts: Mutex<std::collections::HashMap<String, Vec<std::time::Instant>>>,
    /// Запасной videoId для треков, которые не отдаются: id → рабочий id.
    alt: Mutex<std::collections::HashMap<String, String>>,
    /// Недавно не получившиеся треки: чтобы не долбить YouTube на каждый Range-запрос.
    failed: Mutex<std::collections::HashMap<String, std::time::Instant>>,
}

type St = State<Arc<AppState>>;
type ApiResult<T> = Result<Json<T>, (StatusCode, String)>;

fn e500(e: impl std::fmt::Display) -> (StatusCode, String) {
    (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
}

/// Ключ для лимитов: за Caddy реальный адрес приходит в X-Forwarded-For.
fn client_key(h: &HeaderMap) -> String {
    h.get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').next())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "local".into())
}

/// Не больше `max` попыток за `window` на ключ.
fn rate_limit(st: &AppState, key: &str, max: usize, window: std::time::Duration) -> Result<(), (StatusCode, String)> {
    let mut m = st.attempts.lock().map_err(e500)?;
    if m.len() > 10_000 {
        let now = std::time::Instant::now();
        m.retain(|_, v| v.iter().any(|t| now.duration_since(*t) < window));
    }
    let now = std::time::Instant::now();
    let v = m.entry(key.to_string()).or_default();
    v.retain(|t| now.duration_since(*t) < window);
    if v.len() >= max {
        return Err((StatusCode::TOO_MANY_REQUESTS, "слишком много попыток, подожди немного".into()));
    }
    v.push(now);
    Ok(())
}

/// Кладём токен подтверждения и шлём письмо. Ошибка отправки не ломает регистрацию.
async fn send_verify(st: &AppState, user: i64, email: &str) -> anyhow::Result<String> {
    let token = hex(&rand::random::<[u8; 24]>());
    let now = recs::now();
    {
        let db = st.db.lock().map_err(|_| anyhow::anyhow!("db"))?;
        db.execute("DELETE FROM email_tokens WHERE user_id=?1", [user])?;
        db.execute(
            "INSERT INTO email_tokens(token, user_id, created, expires) VALUES(?1, ?2, ?3, ?4)",
            params![token, user, now, now + 24 * 3600],
        )?;
    }
    if st.mail.enabled() {
        if let Err(e) = st.mail.send_verification(email, &token).await {
            eprintln!("verify mail to {email} failed: {e}");
        }
    }
    Ok(token)
}

fn uid(st: &AppState, h: &HeaderMap) -> Result<i64, (StatusCode, String)> {
    let token = h
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or((StatusCode::UNAUTHORIZED, "no token".into()))?;
    let db = st.db.lock().map_err(e500)?;
    db.query_row("SELECT user_id FROM sessions WHERE token=?1", [token], |r| r.get(0))
        .map_err(|_| (StatusCode::UNAUTHORIZED, "bad token".into()))
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

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

/// Жанры — только id из списка на фронте, иначе мусор в тегах.
const GENRES: &[&str] = &[
    "pop", "rock", "rap", "electronic", "indie", "jazz", "classical", "metal", "punk", "rnb",
    "reggae", "folk", "russian", "kpop", "soundtrack",
];

fn clean_genres(g: &[String]) -> Result<Vec<String>, (StatusCode, String)> {
    let mut out: Vec<String> = Vec::new();
    for x in g {
        let x = x.trim().to_lowercase();
        if !GENRES.contains(&x.as_str()) {
            return Err((StatusCode::BAD_REQUEST, format!("неизвестный жанр: {x}")));
        }
        if !out.contains(&x) {
            out.push(x);
        }
    }
    if out.len() > 8 {
        return Err((StatusCode::BAD_REQUEST, "не больше 8 жанров".into()));
    }
    Ok(out)
}

#[derive(Serialize)]
struct LoginResp {
    token: String,
    email: String,
}

fn check_creds(c: &Creds) -> Result<(String, String), (StatusCode, String)> {
    let email = c.email.trim().to_lowercase();
    if !email.contains('@') || email.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "bad email".into()));
    }
    if c.password.len() < 6 || c.password.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "password >= 6 chars".into()));
    }
    Ok((email, c.password.clone()))
}

fn hash_password(pw: &str) -> Result<String, String> {
    use argon2::password_hash::{rand_core::OsRng, SaltString};
    use argon2::{Argon2, PasswordHasher};
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(pw.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| e.to_string())
}

fn verify_password(pw: &str, hash: &str) -> bool {
    use argon2::password_hash::PasswordHash;
    use argon2::{Argon2, PasswordVerifier};
    PasswordHash::new(hash)
        .map(|p| Argon2::default().verify_password(pw.as_bytes(), &p).is_ok())
        .unwrap_or(false)
}

async fn register(State(st): St, h: HeaderMap, Json(c): Json<Creds>) -> ApiResult<serde_json::Value> {
    if !c.consent {
        return Err((StatusCode::BAD_REQUEST, "нужно согласие на обработку данных".into()));
    }
    rate_limit(&st, &format!("reg:{}", client_key(&h)), 5, std::time::Duration::from_secs(600))?;
    let (email, pw) = check_creds(&c)?;
    let genres = clean_genres(&c.genres)?;
    let name = c.name.clone().unwrap_or_default().trim().chars().take(40).collect::<String>();
    let hash = hash_password(&pw).map_err(e500)?;
    let token = hex(&rand::random::<[u8; 32]>());
    let now = recs::now();
    let id = {
        let db = st.db.lock().map_err(e500)?;
        db.execute(
            "INSERT INTO users(email, pass, created, name, consent_at) VALUES(?1, ?2, ?3, ?4, ?5)",
            params![email, hash, now, name, now],
        )
        .map_err(|e| {
            if e.to_string().contains("UNIQUE") {
                (StatusCode::CONFLICT, "email уже занят".into())
            } else {
                e500(e)
            }
        })?;
        let id = db.last_insert_rowid();
        for g in &genres {
            db.execute("INSERT INTO user_genres(user_id, genre) VALUES(?1, ?2)", params![id, g])
                .map_err(e500)?;
        }
        db.execute(
            "INSERT INTO sessions(token, user_id, created) VALUES(?1, ?2, ?3)",
            params![token, id, now],
        )
        .map_err(e500)?;
        id
    };
    let verify = send_verify(&st, id, &email).await;
    let letter_sent = st.mail.enabled();
    if let Err(e) = &verify {
        eprintln!("verify token for {email} failed: {e}");
    }
    Ok(Json(serde_json::json!({
        "token": token,
        "email": email,
        "verified": false,
        "letterSent": letter_sent,
        // Пока SMTP не настроен, отдаём ссылку, иначе подтвердить почту невозможно.
        "verifyUrl": if letter_sent { serde_json::Value::Null } else {
            verify.ok().map(|t| format!("{}/api/verify?token={}", st.mail.base_url.trim_end_matches('/'), t)).into()
        },
    })))
}

async fn login(State(st): St, h: HeaderMap, Json(c): Json<Creds>) -> ApiResult<LoginResp> {
    rate_limit(&st, &format!("login:{}:{}", client_key(&h), c.email.to_lowercase()), 10, std::time::Duration::from_secs(300))?;
    let (email, pw) = check_creds(&c)?;
    let (id, hash) = {
        let db = st.db.lock().map_err(e500)?;
        db.query_row("SELECT id, pass FROM users WHERE email=?1", [&email], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })
        .map_err(|_| (StatusCode::UNAUTHORIZED, "неверный email или пароль".into()))?
    };
    if !verify_password(&pw, &hash) {
        return Err((StatusCode::UNAUTHORIZED, "неверный email или пароль".into()));
    }
    let token = hex(&rand::random::<[u8; 32]>());
    let db = st.db.lock().map_err(e500)?;
    db.execute(
        "INSERT INTO sessions(token, user_id, created) VALUES(?1, ?2, ?3)",
        params![token, id, recs::now()],
    )
    .map_err(e500)?;
    Ok(Json(LoginResp { token, email }))
}

async fn me(State(st): St, h: HeaderMap) -> ApiResult<serde_json::Value> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let (email, name, created, verified, consent): (String, String, i64, i64, i64) = db
        .query_row("SELECT email, name, created, email_verified, consent_at FROM users WHERE id=?1", [u], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
        })
        .map_err(e500)?;
    let mut stmt = db.prepare("SELECT genre FROM user_genres WHERE user_id=?1 ORDER BY rowid").map_err(e500)?;
    let genres = stmt
        .query_map([u], |r| r.get::<_, String>(0))
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    let (likes, playlists): (i64, i64) = db
        .query_row(
            "SELECT (SELECT COUNT(*) FROM likes WHERE user_id=?1),
                    (SELECT COUNT(*) FROM playlists WHERE user_id=?1)",
            [u],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(e500)?;
    Ok(Json(serde_json::json!({
        "email": email,
        "name": name,
        "genres": genres,
        "created": created,
        "likes": likes,
        "playlists": playlists,
        "verified": verified != 0,
        "consentAt": consent
    })))
}

/// Подтверждение почты по ссылке из письма — отдаём человеческую HTML-страницу.
async fn verify(State(st): St, Query(q): Query<VerifyQ>) -> Response {
    let token = q.token.unwrap_or_default();
    let ok = !token.is_empty() && {
        let db = match st.db.lock() {
            Ok(d) => d,
            Err(_) => return (StatusCode::INTERNAL_SERVER_ERROR, "db").into_response(),
        };
        db.query_row(
            "SELECT user_id FROM email_tokens WHERE token=?1 AND expires>?2",
            params![token, recs::now()],
            |r| r.get::<_, i64>(0),
        )
        .ok()
        .map(|uid| {
            let _ = db.execute("UPDATE users SET email_verified=1 WHERE id=?1", [uid]);
            let _ = db.execute("DELETE FROM email_tokens WHERE user_id=?1", [uid]);
            true
        })
        .unwrap_or(false)
    };
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
        mail::verify_page(ok),
    )
        .into_response()
}

#[derive(Deserialize)]
struct VerifyQ {
    token: Option<String>,
}

/// Повторное письмо: не чаще раза в минуту и трёх раз в десять минут.
async fn resend(State(st): St, h: HeaderMap) -> ApiResult<serde_json::Value> {
    let u = uid(&st, &h)?;
    rate_limit(&st, &format!("resend:{u}"), 3, std::time::Duration::from_secs(600))?;
    let (email, verified, last): (String, i64, i64) = {
        let db = st.db.lock().map_err(e500)?;
        let row = db
            .query_row("SELECT email, email_verified FROM users WHERE id=?1", [u], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
            })
            .map_err(e500)?;
        let last: i64 = db
            .query_row("SELECT COALESCE(MAX(created), 0) FROM email_tokens WHERE user_id=?1", [u], |r| r.get(0))
            .unwrap_or(0);
        (row.0, row.1, last)
    };
    if verified != 0 {
        return Ok(Json(serde_json::json!({ "verified": true })));
    }
    if recs::now() - last < 60 {
        return Err((StatusCode::TOO_MANY_REQUESTS, "письмо уже отправлено, подожди минуту".into()));
    }
    let token = send_verify(&st, u, &email).await.map_err(e500)?;
    let sent = st.mail.enabled();
    Ok(Json(serde_json::json!({
        "verified": false,
        "letterSent": sent,
        "verifyUrl": if sent { serde_json::Value::Null } else {
            format!("{}/api/verify?token={}", st.mail.base_url.trim_end_matches('/'), token).into()
        },
    })))
}

/// Удаление аккаунта: всё связанное уходит каскадом.
async fn delete_account(State(st): St, h: HeaderMap) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("DELETE FROM users WHERE id=?1", [u]).map_err(e500)?;
    Ok(Json(()))
}

/// Bump в GENRES требует такой же правки на фронте (web/src/genres.ts).
#[derive(Deserialize)]
struct ProfileBody {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    genres: Vec<String>,
}

async fn set_profile(State(st): St, h: HeaderMap, Json(b): Json<ProfileBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let genres = clean_genres(&b.genres)?;
    let db = st.db.lock().map_err(e500)?;
    if let Some(n) = b.name {
        let n = n.trim().chars().take(40).collect::<String>();
        db.execute("UPDATE users SET name=?1 WHERE id=?2", params![n, u]).map_err(e500)?;
    }
    db.execute("DELETE FROM user_genres WHERE user_id=?1", [u]).map_err(e500)?;
    for g in &genres {
        db.execute("INSERT INTO user_genres(user_id, genre) VALUES(?1, ?2)", params![u, g])
            .map_err(e500)?;
    }
    Ok(Json(()))
}

#[derive(Serialize)]
struct ArtistRow {
    name: String,
    count: i64,
    thumb: Option<String>,
}

/// Исполнители из лайков и истории — для раздела «Исполнители».
async fn artists(State(st): St, h: HeaderMap) -> ApiResult<Vec<ArtistRow>> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let mut stmt = db
        .prepare(
            "SELECT artist, COUNT(*) AS c, MAX(thumb) AS thumb FROM (
                 SELECT artist, thumb, played_at AS ts FROM history WHERE user_id=?1
                 UNION ALL
                 SELECT artist, thumb, created AS ts FROM likes WHERE user_id=?1
             )
             WHERE artist <> '' GROUP BY artist ORDER BY c DESC, MAX(ts) DESC LIMIT 60",
        )
        .map_err(e500)?;
    let rows = stmt
        .query_map([u], |r| {
            Ok(ArtistRow { name: r.get(0)?, count: r.get(1)?, thumb: r.get(2)? })
        })
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(rows))
}

#[derive(Deserialize)]
struct ArtistQ {
    name: String,
}

/// Треки одного исполнителя: ищем по имени и оставляем только его.
async fn artist(State(st): St, h: HeaderMap, Query(q): Query<ArtistQ>) -> ApiResult<Vec<Track>> {
    uid(&st, &h)?;
    let name = q.name.trim();
    if name.is_empty() || name.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "bad artist".into()));
    }
    let needle = name.to_lowercase();
    let found = st.cache.search(&st.http, name).await;
    let mut out: Vec<Track> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for t in found {
        if !t.artist.to_lowercase().contains(&needle) {
            continue;
        }
        if seen.insert(t.id.clone()) {
            out.push(t);
        }
    }
    // Если строгий фильтр почти ничего не дал, добавляем остальное из поиска.
    if out.len() < 5 {
        for t in st.cache.search(&st.http, name).await {
            if seen.insert(t.id.clone()) {
                out.push(t);
            }
        }
    }
    out.truncate(60);
    Ok(Json(out))
}

#[derive(Deserialize)]
struct SearchQ {
    q: Option<String>,
    region: Option<String>,
}

async fn search(State(st): St, h: HeaderMap, Query(sq): Query<SearchQ>) -> ApiResult<Vec<Track>> {
    uid(&st, &h)?;
    let q = sq.q.unwrap_or_default();
    let q = q.trim();
    if q.is_empty() {
        // Пустой запрос = «популярное»: чарт региона.
        let region = clean_region(sq.region.as_deref());
        let mut v = yt::chart_top(&st.http, &region).await.unwrap_or_default();
        if v.is_empty() && !region.is_empty() {
            v = yt::chart_top(&st.http, "").await.unwrap_or_default();
        }
        return Ok(Json(v));
    }
    Ok(Json(st.cache.search(&st.http, q).await))
}

#[derive(Deserialize)]
struct StreamQ {
    a: Option<String>,
    t: Option<String>,
}

/// «Blinding Lights (Official Video)» → «blinding lights».
fn norm_name(s: &str) -> String {
    let mut out = String::new();
    let mut depth = 0i32;
    for c in s.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.extend(c.to_lowercase()),
            _ => {}
        }
    }
    out.chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// Другие ролики той же песни: другой загрузчик, клип, lyric-видео.
async fn alternatives(st: &Arc<AppState>, id: &str, artist: &str, title: &str) -> Vec<String> {
    let want = norm_name(title);
    if want.is_empty() {
        return Vec::new();
    }
    let found = st.cache.search(&st.http, &format!("{artist} {title}")).await;
    let mut out = Vec::new();
    for t in found {
        if t.id == id || out.contains(&t.id) {
            continue;
        }
        let got = norm_name(&t.title);
        // Название должно совпадать (или содержать искомое), а артист — быть тем же.
        let same_title = got == want || got.contains(&want) || want.contains(&got);
        let same_artist = !artist.is_empty()
            && (t.artist.to_lowercase().contains(&artist.to_lowercase())
                || artist.to_lowercase().contains(&t.artist.to_lowercase()));
        if same_title || same_artist {
            out.push(t.id);
        }
        if out.len() >= 2 {
            break;
        }
    }
    out
}

async fn stream(
    State(st): St,
    Path(id): Path<String>,
    Query(q): Query<StreamQ>,
    req: Request,
) -> Response {
    // Блокируем только скачивание этого же трека, а не весь сервер.
    let lock = {
        let mut m = st.dl.lock().await;
        m.entry(id.clone()).or_insert_with(|| Arc::new(tokio::sync::Mutex::new(()))).clone()
    };
    let _guard = lock.lock().await;

    // Недавняя неудача — отвечаем сразу, чтобы плеер переключился, а не ждал.
    if let Ok(m) = st.failed.lock() {
        if let Some(at) = m.get(&id) {
            if at.elapsed() < std::time::Duration::from_secs(600) {
                return (StatusCode::BAD_GATEWAY, "трек временно недоступен").into_response();
            }
        }
    }

    let serve = |path: std::path::PathBuf| async move {
        match ServeFile::new(path).oneshot(req).await {
            Ok(r) => r.into_response(),
            Err(e) => match e {},
        }
    };

    let mut last;
    match yt::ensure_audio(&st.cache_dir, &id, &st.ytdlp).await {
        Ok(path) => return serve(path).await,
        Err(e) => last = e.to_string(),
    }

    // 1. Уже найденная замена для этого трека.
    let known = st.alt.lock().ok().and_then(|m| m.get(&id).cloned());
    if let Some(alt) = known {
        if let Ok(path) = yt::ensure_audio(&st.cache_dir, &alt, &st.ytdlp).await {
            return serve(path).await;
        }
    }

    // 2. Ищем другой ролик той же песни.
    let artist = q.a.unwrap_or_default();
    let title = q.t.unwrap_or_default();
    for alt in alternatives(&st, &id, &artist, &title).await {
        match yt::ensure_audio(&st.cache_dir, &alt, &st.ytdlp).await {
            Ok(path) => {
                eprintln!("stream {id}: заменён на {alt}");
                if let Ok(mut m) = st.alt.lock() {
                    m.insert(id.clone(), alt);
                }
                return serve(path).await;
            }
            Err(e) => last = e.to_string(),
        }
    }

    if let Ok(mut m) = st.failed.lock() {
        if m.len() > 5000 {
            let now = std::time::Instant::now();
            m.retain(|_, at| now.duration_since(*at) < std::time::Duration::from_secs(600));
        }
        m.insert(id.clone(), std::time::Instant::now());
    }
    eprintln!("stream {id}: {last}");
    (StatusCode::BAD_GATEWAY, format!("не удалось получить аудио: {last}")).into_response()
}

async fn likes(State(st): St, h: HeaderMap) -> ApiResult<Vec<Track>> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let mut stmt = db
        .prepare(&format!(
            "SELECT {} FROM likes WHERE user_id=?1 ORDER BY created DESC",
            db::TRACK_COLS
        ))
        .map_err(e500)?;
    let rows = stmt
        .query_map([u], db::row_track)
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(rows))
}

async fn like(State(st): St, h: HeaderMap, Json(t): Json<Track>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute(
        "INSERT OR REPLACE INTO likes(user_id, track_id, title, artist, album, duration, thumb, created)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![u, t.id, t.title, t.artist, t.album, t.duration, t.thumb, recs::now()],
    )
    .map_err(e500)?;
    Ok(Json(()))
}

async fn unlike(State(st): St, h: HeaderMap, Path(id): Path<String>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("DELETE FROM likes WHERE user_id=?1 AND track_id=?2", params![u, id])
        .map_err(e500)?;
    Ok(Json(()))
}

async fn history(State(st): St, h: HeaderMap, Json(t): Json<Track>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute(
        "INSERT INTO history(user_id, track_id, title, artist, album, duration, thumb, played_at)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![u, t.id, t.title, t.artist, t.album, t.duration, t.thumb, recs::now()],
    )
    .map_err(e500)?;
    Ok(Json(()))
}

async fn recs_handler(State(st): St, h: HeaderMap) -> ApiResult<Vec<Track>> {
    let u = uid(&st, &h)?;
    Ok(Json(recs::recommend(&st.db, &st.cache, &st.http, u).await))
}

async fn wave_handler(State(st): St, h: HeaderMap) -> ApiResult<Vec<recs::WaveTrack>> {
    let u = uid(&st, &h)?;
    Ok(Json(recs::wave(&st.db, &st.cache, &st.http, u).await))
}

#[derive(Deserialize)]
struct TopQ {
    region: Option<String>,
}

/// Регион — только две латинские буквы, иначе показываем мир.
fn clean_region(r: Option<&str>) -> String {
    match r.map(|s| s.trim().to_uppercase()) {
        Some(s) if s.len() == 2 && s.chars().all(|c| c.is_ascii_alphabetic()) => s,
        _ => String::new(),
    }
}

async fn top(State(st): St, h: HeaderMap, Query(q): Query<TopQ>) -> ApiResult<Vec<Track>> {
    uid(&st, &h)?;
    let region = clean_region(q.region.as_deref());
    let key = if region.is_empty() { "001".to_string() } else { region.clone() };

    let mut stale = None;
    if let Ok(m) = st.top.lock() {
        if let Some((at, v)) = m.get(&key) {
            if at.elapsed() < std::time::Duration::from_secs(6 * 3600) {
                return Ok(Json(v.clone()));
            }
            stale = Some(v.clone());
        }
    }

    let mut v = yt::chart_top(&st.http, &region).await.unwrap_or_default();
    // Для редких стран чарта может не быть — тогда мировой.
    if v.is_empty() && !region.is_empty() {
        v = yt::chart_top(&st.http, "").await.unwrap_or_default();
    }
    if v.is_empty() {
        return Ok(Json(stale.unwrap_or_default()));
    }
    if let Ok(mut m) = st.top.lock() {
        m.insert(key, (std::time::Instant::now(), v.clone()));
    }
    Ok(Json(v))
}

#[derive(Serialize)]
struct PlaylistRow {
    id: i64,
    name: String,
    count: i64,
}

async fn playlists(State(st): St, h: HeaderMap) -> ApiResult<Vec<PlaylistRow>> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let mut stmt = db
        .prepare(
            "SELECT p.id, p.name, COUNT(i.track_id)
             FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id=p.id
             WHERE p.user_id=?1 GROUP BY p.id ORDER BY p.created",
        )
        .map_err(e500)?;
    let rows = stmt
        .query_map([u], |r| {
            Ok(PlaylistRow { id: r.get(0)?, name: r.get(1)?, count: r.get(2)? })
        })
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(rows))
}

#[derive(Deserialize)]
struct NewPlaylist {
    name: String,
}

async fn create_playlist(State(st): St, h: HeaderMap, Json(b): Json<NewPlaylist>) -> ApiResult<serde_json::Value> {
    let u = uid(&st, &h)?;
    let name = b.name.trim();
    if name.is_empty() || name.len() > 100 {
        return Err((StatusCode::BAD_REQUEST, "bad name".into()));
    }
    let db = st.db.lock().map_err(e500)?;
    db.execute(
        "INSERT INTO playlists(user_id, name, created) VALUES(?1, ?2, ?3)",
        params![u, name, recs::now()],
    )
    .map_err(e500)?;
    Ok(Json(serde_json::json!({ "id": db.last_insert_rowid() })))
}

fn owns_playlist(db: &Connection, user: i64, id: i64) -> bool {
    db.query_row("SELECT 1 FROM playlists WHERE id=?1 AND user_id=?2", params![id, user], |_| Ok(()))
        .is_ok()
}

#[derive(Serialize)]
struct PlaylistFull {
    id: i64,
    name: String,
    items: Vec<Track>,
}

async fn playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<PlaylistFull> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    let name: String = db
        .query_row("SELECT name FROM playlists WHERE id=?1", [id], |r| r.get(0))
        .map_err(e500)?;
    let mut stmt = db
        .prepare(&format!(
            "SELECT {} FROM playlist_items WHERE playlist_id=?1 ORDER BY pos",
            db::TRACK_COLS
        ))
        .map_err(e500)?;
    let items = stmt
        .query_map([id], db::row_track)
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(PlaylistFull { id, name, items }))
}

async fn delete_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    // Треки удаляются каскадом (foreign_keys=ON).
    db.execute("DELETE FROM playlists WHERE id=?1", [id]).map_err(e500)?;
    Ok(Json(()))
}

async fn add_to_playlist(
    State(st): St,
    h: HeaderMap,
    Path(id): Path<i64>,
    Json(t): Json<Track>,
) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    let pos: i64 = db
        .query_row("SELECT COALESCE(MAX(pos), 0) + 1 FROM playlist_items WHERE playlist_id=?1", [id], |r| r.get(0))
        .map_err(e500)?;
    db.execute(
        "INSERT OR REPLACE INTO playlist_items(playlist_id, track_id, title, artist, album, duration, thumb, pos)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![id, t.id, t.title, t.artist, t.album, t.duration, t.thumb, pos],
    )
    .map_err(e500)?;
    Ok(Json(()))
}

async fn remove_from_playlist(
    State(st): St,
    h: HeaderMap,
    Path((id, track)): Path<(i64, String)>,
) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    db.execute(
        "DELETE FROM playlist_items WHERE playlist_id=?1 AND track_id=?2",
        params![id, track],
    )
    .map_err(e500)?;
    Ok(Json(()))
}

async fn health() -> &'static str {
    "ok"
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let data = std::env::var("MUSIC_DATA").unwrap_or_else(|_| "data".into());
    let cache_dir = std::path::PathBuf::from(std::env::var("MUSIC_CACHE").unwrap_or_else(|_| format!("{data}/cache")));
    let web = std::env::var("MUSIC_WEB").unwrap_or_else(|_| "web/dist".into());
    let addr = std::env::var("MUSIC_ADDR").unwrap_or_else(|_| "127.0.0.1:8030".into());
    let ytdlp = std::env::var("MUSIC_YTDLP").unwrap_or_else(|_| "yt-dlp".into());

    std::fs::create_dir_all(&data)?;
    std::fs::create_dir_all(&cache_dir)?;
    let conn = db::open(&std::path::Path::new(&data).join("music.db"))?;

    let state = Arc::new(AppState {
        db: Mutex::new(conn),
        http: reqwest::Client::builder()
            .user_agent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36")
            .timeout(std::time::Duration::from_secs(20))
            .build()?,
        cache: yt::SearchCache::default(),
        top: Mutex::new(std::collections::HashMap::new()),
        cache_dir,
        ytdlp,
        dl: tokio::sync::Mutex::new(std::collections::HashMap::new()),
        mail: mail::Mailer::from_env(),
        attempts: Mutex::new(std::collections::HashMap::new()),
        alt: Mutex::new(std::collections::HashMap::new()),
        failed: Mutex::new(std::collections::HashMap::new()),
    });

    let spa = ServeDir::new(&web).not_found_service(ServeFile::new(std::path::Path::new(&web).join("index.html")));

    let app = Router::new()
        .route("/api/health", get(health))
        .route("/api/register", post(register))
        .route("/api/login", post(login))
        .route("/api/verify", get(verify))
        .route("/api/resend", post(resend))
        .route("/api/account", delete(delete_account))
        .route("/api/me", get(me))
        .route("/api/profile", post(set_profile))
        .route("/api/search", get(search))
        .route("/api/artists", get(artists))
        .route("/api/artist", get(artist))
        .route("/api/recs", get(recs_handler))
        .route("/api/wave", get(wave_handler))
        .route("/api/top", get(top))
        .route("/api/stream/{id}", get(stream))
        .route("/api/likes", get(likes).post(like))
        .route("/api/likes/{id}", delete(unlike))
        .route("/api/history", post(history))
        .route("/api/playlists", get(playlists).post(create_playlist))
        .route("/api/playlists/{id}", get(playlist).delete(delete_playlist))
        .route("/api/playlists/{id}/items", post(add_to_playlist))
        .route("/api/playlists/{id}/items/{track}", delete(remove_from_playlist))
        .fallback_service(spa)
        .layer(CompressionLayer::new())
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    println!("listening on {addr}, web={web}");
    axum::serve(listener, app).await?;
    Ok(())
}
