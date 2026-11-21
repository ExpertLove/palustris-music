mod accounts;
mod admin;
mod audio;
mod compact;
mod db;
mod loudness;
mod lyrics;
mod mail;
mod recs;
mod sources;
mod stats;
mod yandex;
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
use tower_http::compression::predicate::{NotForContentType, Predicate};
use tower_http::compression::{CompressionLayer, DefaultPredicate};
use tower_http::services::{ServeDir, ServeFile};

struct AppState {
    db: Mutex<Connection>,
    http: reqwest::Client,
    cache: yt::SearchCache,
    top: Mutex<std::collections::HashMap<String, (std::time::Instant, Vec<Track>)>>,
    audio: Arc<audio::Audio>,
    mail: mail::Mailer,
    /// Примитивный антибрутфорс: ключ → моменты попыток.
    attempts: Mutex<std::collections::HashMap<String, Vec<std::time::Instant>>>,
    /// Запасной videoId для треков, которые не отдаются: id → рабочий id.
    alt: Mutex<std::collections::HashMap<String, String>>,
    /// Недавно не получившиеся треки: чтобы не долбить YouTube на каждый Range-запрос.
    failed: Mutex<std::collections::HashMap<String, std::time::Instant>>,
    /// Аккаунты YouTube и журнал загрузок.
    accounts: Arc<accounts::Accounts>,
    /// Громкость треков для выравнивания в приложении.
    loudness: Arc<loudness::Analyzer>,
    /// Почты администраторов (MUSIC_ADMINS), в нижнем регистре.
    admins: Vec<String>,
    data: std::path::PathBuf,
    started: i64,
    /// Миксы дня: пользователь → (день, миксы). Считаются раз в сутки.
    mixes: Mutex<std::collections::HashMap<i64, (i64, Vec<recs::Mix>)>>,
}

type St = State<Arc<AppState>>;
type ApiError = (StatusCode, String);
type ApiResult<T> = Result<Json<T>, ApiError>;

/// Сессия живёт 60 дней с последнего обращения.
const SESSION_TTL: i64 = 60 * 86400;
const COOKIE: &str = "music_s";

fn e500(e: impl std::fmt::Display) -> ApiError {
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
fn rate_limit(st: &AppState, key: &str, max: usize, window: std::time::Duration) -> Result<(), ApiError> {
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

// ───────────────────────── сессии ─────────────────────────

/// Токен из `Authorization: Bearer` или из cookie (нужно для `<audio src>`, он не шлёт заголовки).
fn token_from(h: &HeaderMap) -> Option<String> {
    if let Some(t) = h
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
    {
        return Some(t.trim().to_string());
    }
    h.get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == COOKIE)
        .map(|(_, v)| v.trim().to_string())
}

fn session(st: &AppState, h: &HeaderMap) -> Result<(i64, String), ApiError> {
    let token = token_from(h)
        .filter(|t| !t.is_empty() && t.len() <= 128)
        .ok_or((StatusCode::UNAUTHORIZED, "no token".into()))?;
    let db = st.db.lock().map_err(e500)?;
    let (uid, seen): (i64, i64) = db
        .query_row("SELECT user_id, seen FROM sessions WHERE token=?1", [&token], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(|_| (StatusCode::UNAUTHORIZED, "bad token".into()))?;
    let now = recs::now();
    if seen > 0 && now - seen > SESSION_TTL {
        let _ = db.execute("DELETE FROM sessions WHERE token=?1", [&token]);
        return Err((StatusCode::UNAUTHORIZED, "session expired".into()));
    }
    if now - seen > 3600 {
        let _ = db.execute("UPDATE sessions SET seen=?1 WHERE token=?2", params![now, token]);
    }
    Ok((uid, token))
}

fn uid(st: &AppState, h: &HeaderMap) -> Result<i64, ApiError> {
    session(st, h).map(|s| s.0)
}

fn session_cookie(token: &str) -> String {
    format!("{COOKIE}={token}; Path=/api; Max-Age={SESSION_TTL}; HttpOnly; Secure; SameSite=Lax")
}

fn clear_cookie() -> String {
    format!("{COOKIE}=; Path=/api; Max-Age=0; HttpOnly; Secure; SameSite=Lax")
}

fn new_session(db: &Connection, user: i64) -> Result<String, ApiError> {
    let token = hex(&rand::random::<[u8; 32]>());
    let now = recs::now();
    db.execute(
        "INSERT INTO sessions(token, user_id, created, seen) VALUES(?1, ?2, ?3, ?3)",
        params![token, user, now],
    )
    .map_err(e500)?;
    Ok(token)
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

fn clean_genres(g: &[String]) -> Result<Vec<String>, ApiError> {
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

fn check_password(pw: &str) -> Result<(), ApiError> {
    if pw.chars().count() < 6 || pw.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "пароль — минимум 6 символов".into()));
    }
    Ok(())
}

fn check_creds(c: &Creds) -> Result<(String, String), ApiError> {
    let email = c.email.trim().to_lowercase();
    if !email.contains('@') || email.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "проверь email".into()));
    }
    check_password(&c.password)?;
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

async fn register(State(st): St, h: HeaderMap, Json(c): Json<Creds>) -> Result<Response, ApiError> {
    if !c.consent {
        return Err((StatusCode::BAD_REQUEST, "нужно согласие на обработку данных".into()));
    }
    rate_limit(&st, &format!("reg:{}", client_key(&h)), 5, std::time::Duration::from_secs(600))?;
    let (email, pw) = check_creds(&c)?;
    let genres = clean_genres(&c.genres)?;
    let name = c.name.clone().unwrap_or_default().trim().chars().take(40).collect::<String>();
    let hash = hash_password(&pw).map_err(e500)?;
    let now = recs::now();
    let (id, token) = {
        let db = st.db.lock().map_err(e500)?;
        db.execute(
            "INSERT INTO users(email, pass, created, name, consent_at) VALUES(?1, ?2, ?3, ?4, ?5)",
            params![email, hash, now, name, now],
        )
        .map_err(|e| {
            if e.to_string().contains("UNIQUE") {
                (StatusCode::CONFLICT, "этот email уже зарегистрирован".into())
            } else {
                e500(e)
            }
        })?;
        let id = db.last_insert_rowid();
        for g in &genres {
            db.execute("INSERT INTO user_genres(user_id, genre) VALUES(?1, ?2)", params![id, g])
                .map_err(e500)?;
        }
        (id, new_session(&db, id)?)
    };
    let verify = send_verify(&st, id, &email).await;
    let letter_sent = st.mail.enabled();
    if let Err(e) = &verify {
        eprintln!("verify token for {email} failed: {e}");
    }
    let body = serde_json::json!({
        "token": token,
        "email": email,
        "verified": false,
        "letterSent": letter_sent,
        // Пока SMTP не настроен, отдаём ссылку, иначе подтвердить почту невозможно.
        "verifyUrl": if letter_sent { serde_json::Value::Null } else {
            verify.ok().map(|t| format!("{}/api/verify?token={}", st.mail.base_url.trim_end_matches('/'), t)).into()
        },
    });
    Ok(([(header::SET_COOKIE, session_cookie(&token))], Json(body)).into_response())
}

async fn login(State(st): St, h: HeaderMap, Json(c): Json<Creds>) -> Result<Response, ApiError> {
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
    let db = st.db.lock().map_err(e500)?;
    let token = new_session(&db, id)?;
    Ok(([(header::SET_COOKIE, session_cookie(&token))], Json(LoginResp { token, email })).into_response())
}

async fn logout(State(st): St, h: HeaderMap) -> Result<Response, ApiError> {
    if let Some(t) = token_from(&h) {
        let db = st.db.lock().map_err(e500)?;
        let _ = db.execute("DELETE FROM sessions WHERE token=?1", [t]);
    }
    Ok(([(header::SET_COOKIE, clear_cookie())], Json(())).into_response())
}

/// Выйти на всех устройствах, кроме текущего.
async fn logout_others(State(st): St, h: HeaderMap) -> ApiResult<serde_json::Value> {
    let (u, token) = session(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let n = db
        .execute("DELETE FROM sessions WHERE user_id=?1 AND token<>?2", params![u, token])
        .map_err(e500)?;
    Ok(Json(serde_json::json!({ "removed": n })))
}

async fn me(State(st): St, h: HeaderMap) -> Result<Response, ApiError> {
    let (u, token) = session(&st, &h)?;
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
    let (likes, playlists, plays, sessions): (i64, i64, i64, i64) = db
        .query_row(
            "SELECT (SELECT COUNT(*) FROM likes WHERE user_id=?1),
                    (SELECT COUNT(*) FROM playlists WHERE user_id=?1),
                    (SELECT COUNT(*) FROM history WHERE user_id=?1),
                    (SELECT COUNT(*) FROM sessions WHERE user_id=?1)",
            [u],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .map_err(e500)?;
    let admin = admin::is_admin(&st, &email);
    let body = serde_json::json!({
        "admin": admin,
        "email": email,
        "name": name,
        "genres": genres,
        "created": created,
        "likes": likes,
        "playlists": playlists,
        "plays": plays,
        "sessions": sessions,
        "verified": verified != 0,
        "consentAt": consent
    });
    // Cookie для аудио ставим и старым клиентам, которые знают только Bearer.
    Ok(([(header::SET_COOKIE, session_cookie(&token))], Json(body)).into_response())
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

#[derive(Deserialize)]
struct ForgotBody {
    email: String,
}

/// Письмо со ссылкой сброса. Ответ одинаковый, есть такой email или нет.
async fn forgot(State(st): St, h: HeaderMap, Json(b): Json<ForgotBody>) -> ApiResult<serde_json::Value> {
    rate_limit(&st, &format!("forgot:{}", client_key(&h)), 5, std::time::Duration::from_secs(600))?;
    let email = b.email.trim().to_lowercase();
    rate_limit(&st, &format!("forgot:{email}"), 3, std::time::Duration::from_secs(3600))?;
    let user: Option<i64> = {
        let db = st.db.lock().map_err(e500)?;
        db.query_row("SELECT id FROM users WHERE email=?1", [&email], |r| r.get(0)).ok()
    };
    if let Some(u) = user {
        let token = hex(&rand::random::<[u8; 24]>());
        let now = recs::now();
        {
            let db = st.db.lock().map_err(e500)?;
            db.execute("DELETE FROM password_tokens WHERE user_id=?1", [u]).map_err(e500)?;
            db.execute(
                "INSERT INTO password_tokens(token, user_id, created, expires) VALUES(?1, ?2, ?3, ?4)",
                params![token, u, now, now + 3600],
            )
            .map_err(e500)?;
        }
        if st.mail.enabled() {
            if let Err(e) = st.mail.send_reset(&email, &token).await {
                eprintln!("reset mail to {email} failed: {e}");
            }
        }
    }
    Ok(Json(serde_json::json!({ "ok": true })))
}

#[derive(Deserialize)]
struct ResetBody {
    token: String,
    password: String,
}

async fn reset_password(State(st): St, h: HeaderMap, Json(b): Json<ResetBody>) -> Result<Response, ApiError> {
    rate_limit(&st, &format!("reset:{}", client_key(&h)), 10, std::time::Duration::from_secs(600))?;
    check_password(&b.password)?;
    let hash = hash_password(&b.password).map_err(e500)?;
    let db = st.db.lock().map_err(e500)?;
    let u: i64 = db
        .query_row(
            "SELECT user_id FROM password_tokens WHERE token=?1 AND expires>?2",
            params![b.token.trim(), recs::now()],
            |r| r.get(0),
        )
        .map_err(|_| (StatusCode::BAD_REQUEST, "ссылка устарела или уже использована".into()))?;
    db.execute("UPDATE users SET pass=?1, email_verified=1 WHERE id=?2", params![hash, u]).map_err(e500)?;
    db.execute("DELETE FROM password_tokens WHERE user_id=?1", [u]).map_err(e500)?;
    db.execute("DELETE FROM sessions WHERE user_id=?1", [u]).map_err(e500)?;
    let email: String = db.query_row("SELECT email FROM users WHERE id=?1", [u], |r| r.get(0)).map_err(e500)?;
    let token = new_session(&db, u)?;
    Ok(([(header::SET_COOKIE, session_cookie(&token))], Json(LoginResp { token, email })).into_response())
}

#[derive(Deserialize)]
struct ChangeBody {
    old: String,
    new: String,
}

async fn change_password(State(st): St, h: HeaderMap, Json(b): Json<ChangeBody>) -> ApiResult<serde_json::Value> {
    let (u, token) = session(&st, &h)?;
    rate_limit(&st, &format!("chpw:{u}"), 5, std::time::Duration::from_secs(600))?;
    check_password(&b.new)?;
    let hash: String = {
        let db = st.db.lock().map_err(e500)?;
        db.query_row("SELECT pass FROM users WHERE id=?1", [u], |r| r.get(0)).map_err(e500)?
    };
    if !verify_password(&b.old, &hash) {
        return Err((StatusCode::FORBIDDEN, "текущий пароль не подходит".into()));
    }
    let new_hash = hash_password(&b.new).map_err(e500)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("UPDATE users SET pass=?1 WHERE id=?2", params![new_hash, u]).map_err(e500)?;
    let n = db
        .execute("DELETE FROM sessions WHERE user_id=?1 AND token<>?2", params![u, token])
        .map_err(e500)?;
    Ok(Json(serde_json::json!({ "ok": true, "loggedOut": n })))
}

/// Удаление аккаунта: всё связанное уходит каскадом.
async fn delete_account(State(st): St, h: HeaderMap) -> Result<Response, ApiError> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("DELETE FROM users WHERE id=?1", [u]).map_err(e500)?;
    Ok(([(header::SET_COOKIE, clear_cookie())], Json(())).into_response())
}

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

// ───────────────────────── каталог ─────────────────────────

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

/// Треки одного исполнителя по имени (старый API).
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
    for t in &found {
        if t.artist.to_lowercase().contains(&needle) && seen.insert(t.id.clone()) {
            out.push(t.clone());
        }
    }
    if out.len() < 5 {
        for t in found {
            if seen.insert(t.id.clone()) {
                out.push(t);
            }
        }
    }
    out.truncate(60);
    Ok(Json(out))
}

#[derive(Deserialize)]
struct YtArtistQ {
    id: Option<String>,
    name: Option<String>,
}

/// Страница исполнителя YouTube Music: по browseId или по имени.
async fn yt_artist(State(st): St, h: HeaderMap, Query(q): Query<YtArtistQ>) -> ApiResult<yt::ArtistPage> {
    uid(&st, &h)?;
    let id = match (q.id.filter(|s| !s.is_empty()), q.name.filter(|s| !s.trim().is_empty())) {
        (Some(id), _) => id,
        (None, Some(name)) if name.len() < 200 => yt::resolve_artist(&st.cache, &st.http, name.trim())
            .await
            .ok_or((StatusCode::NOT_FOUND, "исполнитель не найден".into()))?,
        _ => return Err((StatusCode::BAD_REQUEST, "нужен id или name".into())),
    };
    st.cache
        .artist(&st.http, &id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::BAD_GATEWAY, e.to_string()))
}

async fn yt_collection(State(st): St, h: HeaderMap, Path(id): Path<String>) -> ApiResult<yt::Collection> {
    uid(&st, &h)?;
    st.cache
        .collection(&st.http, &id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::BAD_GATEWAY, e.to_string()))
}

#[derive(Deserialize)]
struct SearchQ {
    q: Option<String>,
    region: Option<String>,
    /// `songs` — только песни (с длительностью), без клипов и подборок.
    kind: Option<String>,
}

async fn search(State(st): St, h: HeaderMap, Query(sq): Query<SearchQ>) -> ApiResult<Vec<Track>> {
    uid(&st, &h)?;
    let q = sq.q.unwrap_or_default();
    let q = q.trim();
    if q.is_empty() {
        let region = clean_region(sq.region.as_deref());
        let mut v = yt::chart_top(&st.http, &region).await.unwrap_or_default();
        if v.is_empty() && !region.is_empty() {
            v = yt::chart_top(&st.http, "").await.unwrap_or_default();
        }
        return Ok(Json(v));
    }
    if sq.kind.as_deref() == Some("songs") && q.len() <= 200 {
        return Ok(Json(st.cache.songs(&st.http, q).await));
    }
    Ok(Json(st.cache.search(&st.http, q).await))
}

/// Поиск по всем типам: треки, исполнители, альбомы, плейлисты.
async fn search_all(State(st): St, h: HeaderMap, Query(sq): Query<SearchQ>) -> ApiResult<yt::SearchAll> {
    uid(&st, &h)?;
    let q = sq.q.unwrap_or_default();
    let q = q.trim();
    if q.is_empty() || q.len() > 200 {
        return Ok(Json(yt::SearchAll::default()));
    }
    st.cache
        .search_all(&st.http, q)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::BAD_GATEWAY, e.to_string()))
}

#[derive(Deserialize)]
struct LyricsQ {
    artist: String,
    title: String,
    #[serde(default)]
    duration: i64,
}

async fn lyrics_handler(State(st): St, h: HeaderMap, Query(q): Query<LyricsQ>) -> ApiResult<lyrics::Lyrics> {
    uid(&st, &h)?;
    if q.artist.len() > 300 || q.title.len() > 300 {
        return Err((StatusCode::BAD_REQUEST, "слишком длинно".into()));
    }
    Ok(Json(lyrics::get(&st.db, &st.http, &q.artist, &q.title, q.duration).await))
}

// ───────────────────────── аудио ─────────────────────────

#[derive(Deserialize)]
struct StreamQ {
    /// Исполнитель, название и длительность (с) — чтобы найти замену, если видео не отдаётся.
    a: Option<String>,
    t: Option<String>,
    d: Option<String>,
    /// Компактная копия для скачивания (`o64`, `o96`).
    q: Option<String>,
    /// `1` — скачивание в приложение: запасные источники не используются.
    dl: Option<String>,
}

impl StreamQ {
    /// Замена из другого каталога допустима только для прослушивания: в загрузках должен лежать оригинал.
    fn want(&self) -> Option<sources::Want> {
        if self.q.is_some() || self.dl.as_deref() == Some("1") {
            return None;
        }
        let d = self.d.as_deref().and_then(|d| d.trim().parse::<f64>().ok()).filter(|d| d.is_finite()).map(|d| d.round() as i64);
        sources::Want::new(self.a.as_deref().unwrap_or_default(), self.t.as_deref().unwrap_or_default(), d)
    }
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
        // Та же песня того же исполнителя. Раньше хватало любого из двух, и вместо трека
        // могла заиграть другая песня этого исполнителя или чужой кавер.
        if sources::same_song(artist, title, &t.artist, &t.title) {
            out.push(t.id);
        }
        if out.len() >= 2 {
            break;
        }
    }
    out
}

async fn serve_file(path: std::path::PathBuf, req: Request) -> Response {
    audio::Audio::touch(&path);
    match ServeFile::new(path).oneshot(req).await {
        Ok(mut r) => {
            r.headers_mut().insert(header::CACHE_CONTROL, header::HeaderValue::from_static("private, max-age=86400"));
            r.into_response()
        }
        Err(e) => match e {},
    }
}

async fn stream(State(st): St, Path(id): Path<String>, Query(q): Query<StreamQ>, req: Request) -> Response {
    if let Err(e) = uid(&st, req.headers()) {
        return e.into_response();
    }
    // Старые id из поиска 17.11 (см. sources.rs): играют как есть, без замен.
    if let Some(rest) = id.strip_prefix("x-sc:") {
        return match sources::soundcloud_file(&st.audio.dir, rest, None).await {
            Ok(p) => serve_file(p, req).await,
            Err(e) => (StatusCode::BAD_GATEWAY, e).into_response(),
        };
    }
    if sources::is_external(&id) {
        return match sources::legacy_url(&id).await {
            Ok(u) => sources::proxy_url(&u, req.headers()).await,
            Err(e) => (StatusCode::BAD_GATEWAY, e).into_response(),
        };
    }
    if !audio::valid_id(&id) {
        return (StatusCode::BAD_REQUEST, "bad id").into_response();
    }
    // `?q=o64|o96` — компактная копия в Opus для скачивания в приложение.
    let kbps = match compact::parse(q.q.as_deref()) {
        Ok(k) => k,
        Err(()) => return (StatusCode::BAD_REQUEST, "bad q").into_response(),
    };
    if let Some(k) = kbps {
        if let Some(p) = compact::existing(&st.audio.dir, &id, k) {
            return serve_file(p, req).await;
        }
    }
    let want = q.want();
    let ready = match resolve_audio(&st, &id, &q, want.as_ref()).await {
        Ok(r) => r,
        Err(resp) => return substitute_or(&st, &id, want, req, resp).await,
    };
    let Some(k) = kbps else {
        return match ready {
            audio::Ready::Progressive(job) => {
                sources::forget_sub(&id);
                audio::serve_progressive(job, req.headers()).await
            }
            audio::Ready::File(p) => {
                sources::forget_sub(&id);
                serve_file(p, req).await
            }
            audio::Ready::Failed(e) => substitute_or(&st, &id, want, req, (StatusCode::BAD_GATEWAY, e).into_response()).await,
        };
    };
    let src = match ready {
        audio::Ready::File(p) => p,
        audio::Ready::Progressive(job) => match job.finished(std::time::Duration::from_secs(300)).await {
            Ok(p) => p,
            Err(e) => return (StatusCode::BAD_GATEWAY, format!("не удалось получить аудио: {e}")).into_response(),
        },
        audio::Ready::Failed(e) => return (StatusCode::BAD_GATEWAY, e).into_response(),
    };
    match compact::make(&st.audio.dir, &src, &id, k).await {
        Ok(p) => serve_file(p, req).await,
        Err(e) => {
            eprintln!("compact {id}@{k}: {e}");
            (StatusCode::BAD_GATEWAY, "не удалось подготовить компактную версию").into_response()
        }
    }
}

/// YouTube не отдал звук — та же запись из другого каталога (строгие правила в sources.rs), иначе исходная ошибка.
async fn substitute_or(st: &Arc<AppState>, id: &str, want: Option<sources::Want>, req: Request, err: Response) -> Response {
    let Some(want) = want else { return err };
    let Some(sub) = sources::fallback(st.audio.dir.clone(), want.clone(), st.accounts.clone(), id.to_string()).await else {
        return err;
    };
    let resp = match &sub.play {
        sources::Play::SoundCloud(hexid) => match sources::soundcloud_file(&st.audio.dir, hexid, Some(sub.duration)).await {
            Ok(p) => Some(serve_file(p, req).await),
            Err(e) => {
                eprintln!("stream {id}: замена с SoundCloud не отдалась: {e}");
                None
            }
        },
        sources::Play::Yandex(track) => Some(crate::yandex::stream(track, req.headers()).await).filter(|r| r.status().is_success()),
        sources::Play::Url(u) => Some(sources::proxy_url(u, req.headers()).await).filter(|r| r.status().is_success()),
    };
    match resp {
        Some(mut r) => {
            sources::mark(&mut r, &sub);
            sources::remember_sub(id, &sub);
            r
        }
        None => {
            // Источник перестал отдавать: в следующий раз ищем заново.
            sources::forget_lookup(&want);
            err
        }
    }
}

/// Что играет вместо трека: сайт спрашивает после старта (у `<audio>` нет доступа к заголовкам).
async fn substitute_info(State(st): St, h: HeaderMap, Path(id): Path<String>) -> ApiResult<Option<sources::Sub>> {
    uid(&st, &h)?;
    Ok(Json(sources::sub_for(&id)))
}

/// Файл трека или идущая загрузка; при неудаче пробуем другие видео той же песни на YouTube.
///
/// Замена из других каталогов ищется параллельно, как только есть повод думать, что YouTube не отдаст:
/// сервер сейчас под блоком или первая попытка не удалась. Тогда `substitute_or` забирает уже готовый результат.
async fn resolve_audio(st: &Arc<AppState>, id: &str, q: &StreamQ, want: Option<&sources::Want>) -> Result<audio::Ready, Response> {
    let id = id.to_string();
    if let Some(p) = st.audio.cached(&id) {
        return Ok(audio::Ready::File(p));
    }
    let known_alt = st.alt.lock().ok().and_then(|m| m.get(&id).cloned());
    if let Some(alt) = &known_alt {
        if let Some(p) = st.audio.cached(alt) {
            return Ok(audio::Ready::File(p));
        }
    }
    // Недавняя неудача — отвечаем сразу, чтобы плеер переключился, а не ждал.
    if let Ok(m) = st.failed.lock() {
        if m.get(&id).map(|at| at.elapsed() < std::time::Duration::from_secs(600)).unwrap_or(false) {
            return Err((StatusCode::BAD_GATEWAY, "трек временно недоступен").into_response());
        }
    }

    let mut warmed = false;
    let mut warm = || {
        if let (false, Some(w)) = (warmed, want) {
            warmed = true;
            tokio::spawn(sources::fallback(st.audio.dir.clone(), w.clone(), st.accounts.clone(), id.clone()));
        }
    };
    if st.audio.yt_blocked() {
        warm();
    }

    let mut candidates = vec![known_alt.unwrap_or_else(|| id.clone())];
    let mut last = String::new();
    let mut searched = false;
    let mut i = 0;
    while i < candidates.len() {
        let cid = candidates[i].clone();
        i += 1;
        match st.audio.ready(&cid).await {
            audio::Ready::File(p) => {
                if cid != id {
                    if let Ok(mut m) = st.alt.lock() {
                        m.insert(id.clone(), cid.clone());
                    }
                }
                return Ok(audio::Ready::File(p));
            }
            audio::Ready::Progressive(job) => {
                if cid != id {
                    eprintln!("stream {id}: заменён на {cid}");
                    if let Ok(mut m) = st.alt.lock() {
                        m.insert(id.clone(), cid.clone());
                    }
                }
                return Ok(audio::Ready::Progressive(job));
            }
            audio::Ready::Failed(e) => last = e,
        }
        warm();
        // Сервер под блоком: другие видео тоже не скачаются, только продлим блок.
        if st.audio.yt_blocked() {
            break;
        }
        if !searched {
            searched = true;
            let artist = q.a.clone().unwrap_or_default();
            let title = q.t.clone().unwrap_or_default();
            candidates.extend(alternatives(st, &id, &artist, &title).await);
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
    Err((StatusCode::BAD_GATEWAY, format!("не удалось получить аудио: {last}")).into_response())
}

#[derive(Deserialize)]
struct PrefetchBody {
    ids: Vec<String>,
}

/// Заранее качаем следующие треки очереди, чтобы они стартовали мгновенно.
async fn prefetch(State(st): St, h: HeaderMap, Json(b): Json<PrefetchBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    rate_limit(&st, &format!("prefetch:{u}"), 60, std::time::Duration::from_secs(600))?;
    for id in b.ids.iter().take(2) {
        st.audio.prefetch(id);
    }
    Ok(Json(()))
}

/// Громкость треков (LUFS и true peak) для выравнивания. Неизвестные в ответ не попадают;
/// если трек уже есть в кэше, он встаёт в очередь на анализ, и клиент спросит снова позже.
async fn loudness_handler(
    State(st): St,
    h: HeaderMap,
    Json(b): Json<PrefetchBody>,
) -> ApiResult<std::collections::HashMap<String, loudness::Loudness>> {
    uid(&st, &h)?;
    let mut out = std::collections::HashMap::new();
    for id in b.ids.iter().filter(|id| audio::valid_id(id)).take(100) {
        // Трек мог играть из запасного videoId: файл и громкость лежат под ним.
        let file_id = st.alt.lock().ok().and_then(|m| m.get(id).cloned()).unwrap_or_else(|| id.clone());
        match st.loudness.get(&file_id) {
            Some(l) => {
                out.insert(id.clone(), l);
            }
            None => {
                if let Some(p) = st.audio.cached(&file_id) {
                    st.loudness.enqueue(&file_id, &p);
                }
            }
        }
    }
    Ok(Json(out))
}

// ───────────────────────── библиотека ─────────────────────────

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
    let _ = db.execute("DELETE FROM dislikes WHERE user_id=?1 AND track_id=?2", params![u, t.id]);
    Ok(Json(()))
}

async fn unlike(State(st): St, h: HeaderMap, Path(id): Path<String>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("DELETE FROM likes WHERE user_id=?1 AND track_id=?2", params![u, id])
        .map_err(e500)?;
    Ok(Json(()))
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

async fn history(State(st): St, h: HeaderMap, Json(b): Json<HistBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let now = recs::now();
    // Время из будущего или старше месяца не принимаем: часы телефона могут врать.
    let at = b.at.filter(|a| *a > now - 30 * 86400 && *a <= now + 300).map(|a| a.min(now)).unwrap_or(now);
    let secs = b.secs.filter(|s| *s > 0).map(|s| s.min(4 * 3600));
    let t = b.track;
    let db = st.db.lock().map_err(e500)?;
    db.execute(
        "INSERT INTO history(user_id, track_id, title, artist, album, duration, thumb, played_at, secs)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![u, t.id, t.title, t.artist, t.album, t.duration, t.thumb, at, secs],
    )
    .map_err(e500)?;
    Ok(Json(()))
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

/// Недавно прослушанные треки, без повторов.
async fn history_list(State(st): St, h: HeaderMap, Query(q): Query<LimitQ>) -> ApiResult<Vec<HistItem>> {
    let u = uid(&st, &h)?;
    let limit = q.limit.unwrap_or(50).clamp(1, 200);
    let db = st.db.lock().map_err(e500)?;
    let mut stmt = db
        .prepare(
            "SELECT track_id, title, artist, album, duration, thumb, MAX(played_at), COUNT(*) FROM history
             WHERE user_id=?1 GROUP BY track_id ORDER BY MAX(played_at) DESC LIMIT ?2",
        )
        .map_err(e500)?;
    let rows = stmt
        .query_map(params![u, limit], |r| {
            Ok(HistItem { track: db::row_track(r)?, played_at: r.get(6)?, plays: r.get(7)? })
        })
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(rows))
}

async fn history_clear(State(st): St, h: HeaderMap) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("DELETE FROM history WHERE user_id=?1", [u]).map_err(e500)?;
    db.execute("DELETE FROM skips WHERE user_id=?1", [u]).map_err(e500)?;
    Ok(Json(()))
}

/// Пропуск в первые секунды — сигнал «не то» для рекомендаций.
async fn skip(State(st): St, h: HeaderMap, Json(t): Json<Track>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute(
        "INSERT INTO skips(user_id, track_id, artist, at) VALUES(?1, ?2, ?3, ?4)",
        params![u, t.id, t.artist, recs::now()],
    )
    .map_err(e500)?;
    Ok(Json(()))
}

async fn recs_handler(State(st): St, h: HeaderMap) -> ApiResult<Vec<Track>> {
    let u = uid(&st, &h)?;
    Ok(Json(recs::recommend(&st.db, &st.cache, &st.http, u).await))
}

#[derive(Deserialize)]
struct WaveQ {
    mode: Option<String>,
    mood: Option<String>,
    activity: Option<String>,
    region: Option<String>,
}

fn known(v: Option<String>, allowed: &[&str]) -> Option<String> {
    v.filter(|s| allowed.contains(&s.as_str()))
}

/// «Моя волна». Без параметров — как раньше; `mode`, `mood`, `activity` — настройки волны.
async fn wave_handler(State(st): St, h: HeaderMap, Query(q): Query<WaveQ>) -> ApiResult<Vec<recs::WaveTrack>> {
    let u = uid(&st, &h)?;
    let params = recs::WaveParams {
        mode: recs::Mode::parse(q.mode.as_deref()),
        mood: known(q.mood, &["energetic", "happy", "calm", "sad"]),
        activity: known(q.activity, &["wakeup", "road", "work", "workout", "sleep", "party"]),
        region: clean_region(q.region.as_deref()),
    };
    let chart = chart_cached(&st, &params.region).await;
    Ok(Json(recs::wave(&st.db, &st.cache, &st.http, &chart, u, &params).await))
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

/// Радио: похожее на трек (`id`) или на исполнителя (`artistId` / `artistName`).
async fn radio_handler(State(st): St, h: HeaderMap, Query(q): Query<RadioQ>) -> ApiResult<Vec<recs::WaveTrack>> {
    let u = uid(&st, &h)?;
    if let Some(id) = q.id.filter(|i| yt::valid_video_id(i)) {
        let title: String = q.title.unwrap_or_default().chars().take(300).collect();
        let artist: String = q.artist.unwrap_or_default().chars().take(300).collect();
        let seed = Track::new(id, title, artist);
        return Ok(Json(recs::track_radio(&st.db, &st.cache, &st.http, u, &seed).await));
    }
    let id = q
        .artist_id
        .filter(|i| i.starts_with("UC") && i.len() < 80 && i.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    let name = q.artist_name.unwrap_or_default();
    let name = name.trim();
    if id.is_none() && (name.is_empty() || name.len() > 200) {
        return Err((StatusCode::BAD_REQUEST, "нужен трек или исполнитель".into()));
    }
    Ok(Json(recs::artist_radio(&st.db, &st.cache, &st.http, u, id.as_deref(), name).await))
}

/// Миксы дня, забытые лайки и «на повторе» для главной.
async fn mixes_handler(State(st): St, h: HeaderMap) -> ApiResult<recs::Mixes> {
    let u = uid(&st, &h)?;
    let today = recs::today();
    let cached = st.mixes.lock().ok().and_then(|m| m.get(&u).filter(|(d, _)| *d == today).map(|(_, v)| v.clone()));
    let mixes = match cached {
        Some(v) => v,
        None => {
            let v = recs::daily_mixes(&st.db, &st.cache, &st.http, u).await;
            if let Ok(mut m) = st.mixes.lock() {
                if m.len() > 5000 {
                    m.retain(|_, (d, _)| *d == today);
                }
                // Пустые не запоминаем: возможно, YouTube на минуту не ответил.
                if !v.is_empty() {
                    m.insert(u, (today, v.clone()));
                }
            }
            v
        }
    };
    let (forgotten, repeat) = {
        let db = st.db.lock().map_err(e500)?;
        recs::personal_shelves(&db, u)
    };
    Ok(Json(recs::Mixes { mixes, forgotten, repeat }))
}

// ───────────────────────── «не нравится» ─────────────────────────

/// «Не нравится»: трек больше не попадёт ни в волну, ни в радио, исполнитель — реже. Лайк снимается.
async fn dislike(State(st): St, h: HeaderMap, Json(t): Json<Track>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    if t.id.is_empty() || t.id.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "bad track".into()));
    }
    {
        let db = st.db.lock().map_err(e500)?;
        db.execute(
            "INSERT OR REPLACE INTO dislikes(user_id, track_id, title, artist, album, duration, thumb, at)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![u, t.id, t.title, t.artist, t.album, t.duration, t.thumb, recs::now()],
        )
        .map_err(e500)?;
        db.execute("DELETE FROM likes WHERE user_id=?1 AND track_id=?2", params![u, t.id]).map_err(e500)?;
    }
    forget_mixes(&st, u);
    Ok(Json(()))
}

async fn undislike(State(st): St, h: HeaderMap, Path(id): Path<String>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    db.execute("DELETE FROM dislikes WHERE user_id=?1 AND track_id=?2", params![u, id]).map_err(e500)?;
    Ok(Json(()))
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

/// Что пользователь скрыл: треки с «не нравится» и исполнители.
async fn dislikes_list(State(st): St, h: HeaderMap) -> ApiResult<Dislikes> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let tracks = db
        .prepare(&format!("SELECT {} FROM dislikes WHERE user_id=?1 ORDER BY at DESC LIMIT 500", db::TRACK_COLS))
        .map_err(e500)?
        .query_map([u], db::row_track)
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    let artists = db
        .prepare("SELECT name, at FROM blocked_artists WHERE user_id=?1 ORDER BY at DESC")
        .map_err(e500)?
        .query_map([u], |r| Ok(BlockedArtist { name: r.get(0)?, at: r.get(1)? }))
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(Dislikes { tracks, artists }))
}

#[derive(Deserialize)]
struct ArtistNameBody {
    name: String,
}

/// «Не предлагать исполнителя»: ни в волне, ни в радио, ни в миксах.
async fn block_artist(State(st): St, h: HeaderMap, Json(b): Json<ArtistNameBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let name = b.name.split(',').next().unwrap_or("").trim().to_string();
    if name.is_empty() || name.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, "нужно имя исполнителя".into()));
    }
    {
        let db = st.db.lock().map_err(e500)?;
        db.execute(
            "INSERT OR REPLACE INTO blocked_artists(user_id, norm, name, at) VALUES(?1, ?2, ?3, ?4)",
            params![u, recs::norm_artist(&name), name, recs::now()],
        )
        .map_err(e500)?;
    }
    forget_mixes(&st, u);
    Ok(Json(()))
}

async fn unblock_artist(State(st): St, h: HeaderMap, Json(b): Json<ArtistNameBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    {
        let db = st.db.lock().map_err(e500)?;
        db.execute("DELETE FROM blocked_artists WHERE user_id=?1 AND norm=?2", params![u, recs::norm_artist(&b.name)])
            .map_err(e500)?;
    }
    forget_mixes(&st, u);
    Ok(Json(()))
}

/// Миксы считаются раз в сутки, но скрытое должно пропасть из них сразу.
fn forget_mixes(st: &AppState, u: i64) {
    if let Ok(mut m) = st.mixes.lock() {
        m.remove(&u);
    }
}

// ───────────────────────── статистика ─────────────────────────

#[derive(Deserialize)]
struct StatsQ {
    period: Option<String>,
    /// Сдвиг местного времени от UTC в минутах (Москва = 180).
    tz: Option<i64>,
    year: Option<i32>,
}

fn clean_tz(tz: Option<i64>) -> i64 {
    tz.filter(|t| (-720..=840).contains(t)).unwrap_or(180)
}

/// Фото исполнителей для первых мест: из страниц YouTube Music (кэш на 6 часов).
async fn artist_images(st: &AppState, list: &mut [stats::ArtistStat], n: usize) {
    let names: Vec<String> = list.iter().take(n).map(|a| a.name.clone()).collect();
    let jobs = names.into_iter().map(|name| async move {
        let id = yt::resolve_artist(&st.cache, &st.http, &name).await?;
        let page = st.cache.artist(&st.http, &id).await.ok()?;
        Some((id, page.thumb.map(|t| yt::thumb_size(&t, 600))))
    });
    let found = tokio::time::timeout(std::time::Duration::from_secs(6), futures_util::future::join_all(jobs)).await;
    if let Ok(found) = found {
        for (a, f) in list.iter_mut().zip(found) {
            if let Some((id, image)) = f {
                a.artist_id = Some(id);
                a.image = image;
            }
        }
    }
}

async fn stats_handler(State(st): St, h: HeaderMap, Query(q): Query<StatsQ>) -> ApiResult<stats::Stats> {
    let u = uid(&st, &h)?;
    let tz = clean_tz(q.tz);
    let mut s = {
        let db = st.db.lock().map_err(e500)?;
        stats::stats(&db, u, q.period.as_deref().unwrap_or("month"), tz)
    };
    artist_images(&st, &mut s.top_artists, 3).await;
    Ok(Json(s))
}

/// «Итоги года».
async fn wrapped_handler(State(st): St, h: HeaderMap, Query(q): Query<StatsQ>) -> ApiResult<stats::Wrapped> {
    let u = uid(&st, &h)?;
    let tz = clean_tz(q.tz);
    let (this_year, _, _) = stats::civil((recs::now() + tz * 60).div_euclid(86400));
    let year = q.year.filter(|y| (2024..=this_year).contains(y)).unwrap_or(this_year);
    let mut w = {
        let db = st.db.lock().map_err(e500)?;
        stats::wrapped(&db, u, year, tz)
    };
    artist_images(&st, &mut w.top_artists, 5).await;
    Ok(Json(w))
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
    Ok(Json(chart_cached(&st, &region).await))
}

/// Недельный чарт страны с кэшем на 6 часов; если YouTube не ответил — прошлый.
async fn chart_cached(st: &AppState, region: &str) -> Vec<Track> {
    let region = region.to_string();
    let key = if region.is_empty() { "001".to_string() } else { region.clone() };

    let mut stale = None;
    if let Ok(m) = st.top.lock() {
        if let Some((at, v)) = m.get(&key) {
            if at.elapsed() < std::time::Duration::from_secs(6 * 3600) {
                return v.clone();
            }
            stale = Some(v.clone());
        }
    }

    let mut v = yt::chart_top(&st.http, &region).await.unwrap_or_default();
    if v.is_empty() && !region.is_empty() {
        v = yt::chart_top(&st.http, "").await.unwrap_or_default();
    }
    if v.is_empty() {
        return stale.unwrap_or_default();
    }
    if let Ok(mut m) = st.top.lock() {
        m.insert(key, (std::time::Instant::now(), v.clone()));
    }
    v
}

// ───────────────────────── плейлисты ─────────────────────────

#[derive(Serialize)]
struct PlaylistRow {
    id: i64,
    name: String,
    count: i64,
    thumbs: Vec<String>,
    share: Option<String>,
}

fn playlist_thumbs(db: &Connection, id: i64) -> Vec<String> {
    let Ok(mut st) = db.prepare(
        "SELECT thumb FROM playlist_items WHERE playlist_id=?1 AND thumb IS NOT NULL AND thumb<>''
         GROUP BY thumb ORDER BY MIN(pos) LIMIT 4",
    ) else {
        return Vec::new();
    };
    st.query_map([id], |r| r.get::<_, String>(0)).map(|r| r.flatten().collect()).unwrap_or_default()
}

async fn playlists(State(st): St, h: HeaderMap) -> ApiResult<Vec<PlaylistRow>> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let mut stmt = db
        .prepare(
            "SELECT p.id, p.name, COUNT(i.track_id), p.share
             FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id=p.id
             WHERE p.user_id=?1 GROUP BY p.id ORDER BY p.created",
        )
        .map_err(e500)?;
    let rows = stmt
        .query_map([u], |r| {
            Ok(PlaylistRow { id: r.get(0)?, name: r.get(1)?, count: r.get(2)?, thumbs: Vec::new(), share: r.get(3)? })
        })
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    let rows = rows
        .into_iter()
        .map(|mut p| {
            p.thumbs = playlist_thumbs(&db, p.id);
            p
        })
        .collect();
    Ok(Json(rows))
}

#[derive(Deserialize)]
struct NewPlaylist {
    name: String,
    /// Сразу наполнить: «сохранить альбом / чужой плейлист к себе».
    #[serde(default)]
    items: Vec<Track>,
}

fn clean_name(n: &str) -> Result<String, ApiError> {
    let n = n.trim();
    if n.is_empty() || n.chars().count() > 100 {
        return Err((StatusCode::BAD_REQUEST, "название — от 1 до 100 символов".into()));
    }
    Ok(n.to_string())
}

async fn create_playlist(State(st): St, h: HeaderMap, Json(b): Json<NewPlaylist>) -> ApiResult<serde_json::Value> {
    let u = uid(&st, &h)?;
    let name = clean_name(&b.name)?;
    let db = st.db.lock().map_err(e500)?;
    let count: i64 = db
        .query_row("SELECT COUNT(*) FROM playlists WHERE user_id=?1", [u], |r| r.get(0))
        .map_err(e500)?;
    if count >= 500 {
        return Err((StatusCode::BAD_REQUEST, "слишком много плейлистов".into()));
    }
    db.execute(
        "INSERT INTO playlists(user_id, name, created) VALUES(?1, ?2, ?3)",
        params![u, name, recs::now()],
    )
    .map_err(e500)?;
    let id = db.last_insert_rowid();
    for (i, t) in b.items.iter().take(1000).enumerate() {
        db.execute(
            "INSERT OR IGNORE INTO playlist_items(playlist_id, track_id, title, artist, album, duration, thumb, pos)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![id, t.id, t.title, t.artist, t.album, t.duration, t.thumb, i as i64 + 1],
        )
        .map_err(e500)?;
    }
    Ok(Json(serde_json::json!({ "id": id })))
}

fn owns_playlist(db: &Connection, user: i64, id: i64) -> bool {
    db.query_row("SELECT 1 FROM playlists WHERE id=?1 AND user_id=?2", params![id, user], |_| Ok(()))
        .is_ok()
}

fn playlist_items(db: &Connection, id: i64) -> Result<Vec<Track>, ApiError> {
    let mut stmt = db
        .prepare(&format!("SELECT {} FROM playlist_items WHERE playlist_id=?1 ORDER BY pos", db::TRACK_COLS))
        .map_err(e500)?;
    let items = stmt
        .query_map([id], db::row_track)
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(items)
}

#[derive(Serialize)]
struct PlaylistFull {
    id: i64,
    name: String,
    share: Option<String>,
    items: Vec<Track>,
}

async fn playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<PlaylistFull> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    let (name, share): (String, Option<String>) = db
        .query_row("SELECT name, share FROM playlists WHERE id=?1", [id], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(e500)?;
    let items = playlist_items(&db, id)?;
    Ok(Json(PlaylistFull { id, name, share, items }))
}

#[derive(Deserialize)]
struct RenameBody {
    name: String,
}

async fn rename_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>, Json(b): Json<RenameBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let name = clean_name(&b.name)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    db.execute("UPDATE playlists SET name=?1 WHERE id=?2", params![name, id]).map_err(e500)?;
    Ok(Json(()))
}

#[derive(Deserialize)]
struct OrderBody {
    ids: Vec<String>,
}

async fn reorder_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>, Json(b): Json<OrderBody>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let mut db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    let tx = db.transaction().map_err(e500)?;
    for (i, t) in b.ids.iter().take(5000).enumerate() {
        tx.execute(
            "UPDATE playlist_items SET pos=?1 WHERE playlist_id=?2 AND track_id=?3",
            params![i as i64 + 1, id, t],
        )
        .map_err(e500)?;
    }
    tx.commit().map_err(e500)?;
    Ok(Json(()))
}

async fn share_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<serde_json::Value> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    let existing: Option<String> = db.query_row("SELECT share FROM playlists WHERE id=?1", [id], |r| r.get(0)).map_err(e500)?;
    let slug = match existing {
        Some(s) => s,
        None => {
            const ABC: &[u8] = b"abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            let s: String = (0..10).map(|_| ABC[rand::random::<usize>() % ABC.len()] as char).collect();
            db.execute("UPDATE playlists SET share=?1 WHERE id=?2", params![s, id]).map_err(e500)?;
            s
        }
    };
    Ok(Json(serde_json::json!({ "share": slug })))
}

async fn unshare_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    db.execute("UPDATE playlists SET share=NULL WHERE id=?1", [id]).map_err(e500)?;
    Ok(Json(()))
}

/// Публичный плейлист по ссылке — без входа.
async fn shared_playlist(State(st): St, Path(slug): Path<String>) -> ApiResult<serde_json::Value> {
    if slug.len() > 20 || !slug.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    let db = st.db.lock().map_err(e500)?;
    let (id, name, owner): (i64, String, String) = db
        .query_row(
            "SELECT p.id, p.name, u.name FROM playlists p JOIN users u ON u.id=p.user_id WHERE p.share=?1",
            [&slug],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(|_| (StatusCode::NOT_FOUND, "нет такого плейлиста".into()))?;
    let items = playlist_items(&db, id)?;
    Ok(Json(serde_json::json!({ "name": name, "owner": owner, "items": items, "share": slug })))
}

async fn delete_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<()> {
    let u = uid(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    if !owns_playlist(&db, u, id) {
        return Err((StatusCode::NOT_FOUND, "нет такого плейлиста".into()));
    }
    db.execute("DELETE FROM playlists WHERE id=?1", [id]).map_err(e500)?;
    Ok(Json(()))
}

async fn add_to_playlist(State(st): St, h: HeaderMap, Path(id): Path<i64>, Json(t): Json<Track>) -> ApiResult<()> {
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

async fn remove_from_playlist(State(st): St, h: HeaderMap, Path((id, track)): Path<(i64, String)>) -> ApiResult<()> {
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

/// Раз в полчаса: очистка кэша аудио, протухших сессий и токенов.
fn spawn_maintenance(st: Arc<AppState>) {
    let max_gb: f64 = std::env::var("MUSIC_CACHE_MAX_GB").ok().and_then(|v| v.parse().ok()).unwrap_or(15.0);
    let max_bytes = (max_gb * 1024.0 * 1024.0 * 1024.0) as u64;
    tokio::spawn(async move {
        loop {
            let a = st.audio.clone();
            if let Ok((removed, left)) = tokio::task::spawn_blocking(move || a.gc(max_bytes)).await {
                if removed > 0 {
                    eprintln!("cache gc: удалено {removed} файлов, осталось {} МБ", left / 1024 / 1024);
                }
            }
            if let Ok(db) = st.db.lock() {
                let now = recs::now();
                let _ = db.execute("DELETE FROM sessions WHERE seen > 0 AND seen < ?1", [now - SESSION_TTL]);
                let _ = db.execute("DELETE FROM password_tokens WHERE expires < ?1", [now]);
                let _ = db.execute("DELETE FROM email_tokens WHERE expires < ?1", [now - 7 * 86400]);
                let _ = db.execute("DELETE FROM skips WHERE at < ?1", [now - 90 * 86400]);
            }
            st.accounts.prune();
            tokio::time::sleep(std::time::Duration::from_secs(1800)).await;
        }
    });
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // `music fallback-check "Исполнитель" "Название" [секунды]` — что найдут запасные источники,
    // без запуска сервера и без записи в базу (файлы SoundCloud — во временную папку).
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("fallback-check") {
        let (Some(a), Some(t)) = (args.get(2), args.get(3)) else {
            anyhow::bail!("music fallback-check \"Исполнитель\" \"Название\" [секунды]");
        };
        let want = sources::Want::new(a, t, args.get(4).and_then(|d| d.parse().ok())).ok_or_else(|| anyhow::anyhow!("пустой исполнитель или название"))?;
        let dir = std::env::temp_dir().join("music-fallback-check");
        match sources::check(&dir, &want).await {
            Some(s) => println!("нашлось: {} «{}» / {} ({} c) {}", s.source_name, s.title, s.artist, s.duration, s.page),
            None => println!("замены нет"),
        }
        let _ = std::fs::remove_dir_all(&dir);
        return Ok(());
    }

    let data = std::env::var("MUSIC_DATA").unwrap_or_else(|_| "data".into());
    let cache_dir = std::path::PathBuf::from(std::env::var("MUSIC_CACHE").unwrap_or_else(|_| format!("{data}/cache")));
    let web = std::env::var("MUSIC_WEB").unwrap_or_else(|_| "web/dist".into());
    let addr = std::env::var("MUSIC_ADDR").unwrap_or_else(|_| "127.0.0.1:8030".into());
    let ytdlp = std::env::var("MUSIC_YTDLP").unwrap_or_else(|_| "yt-dlp".into());

    std::fs::create_dir_all(&data)?;
    std::fs::create_dir_all(&cache_dir)?;
    let db_path = std::path::Path::new(&data).join("music.db");
    let conn = db::open(&db_path)?;
    let accounts = Arc::new(accounts::Accounts::new(db::open(&db_path)?, std::path::Path::new(&data).join("ytaccounts"))?);
    let admins: Vec<String> = std::env::var("MUSIC_ADMINS")
        .unwrap_or_default()
        .split(',')
        .map(|s| s.trim().to_lowercase())
        .filter(|s| !s.is_empty())
        .collect();
    let loudness = loudness::Analyzer::start(db::open(&db_path)?);
    let audio = audio::Audio::new(cache_dir, ytdlp, accounts.clone());
    {
        let l = loudness.clone();
        let _ = audio.on_ready.set(Box::new(move |id: &str, path: &std::path::Path| l.enqueue(id, path)));
    }

    let state = Arc::new(AppState {
        db: Mutex::new(conn),
        http: reqwest::Client::builder()
            .user_agent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36")
            .timeout(std::time::Duration::from_secs(20))
            .build()?,
        cache: yt::SearchCache::default(),
        top: Mutex::new(std::collections::HashMap::new()),
        audio,
        mail: mail::Mailer::from_env(),
        attempts: Mutex::new(std::collections::HashMap::new()),
        alt: Mutex::new(std::collections::HashMap::new()),
        failed: Mutex::new(std::collections::HashMap::new()),
        accounts,
        loudness,
        admins,
        data: std::path::PathBuf::from(&data),
        started: recs::now(),
        mixes: Mutex::new(std::collections::HashMap::new()),
    });
    spawn_maintenance(state.clone());

    let spa = ServeDir::new(&web).not_found_service(ServeFile::new(std::path::Path::new(&web).join("index.html")));
    // Аудио и картинки не сжимаем: бессмысленно и ломает Range.
    let compress = DefaultPredicate::new().and(NotForContentType::const_new("audio/"));

    let app = Router::new()
        .route("/api/health", get(health))
        .route("/api/register", post(register))
        .route("/api/login", post(login))
        .route("/api/logout", post(logout))
        .route("/api/logout-others", post(logout_others))
        .route("/api/password/forgot", post(forgot))
        .route("/api/password/reset", post(reset_password))
        .route("/api/password/change", post(change_password))
        .route("/api/verify", get(verify))
        .route("/api/resend", post(resend))
        .route("/api/account", delete(delete_account))
        .route("/api/me", get(me))
        .route("/api/profile", post(set_profile))
        .route("/api/search", get(search))
        .route("/api/search/all", get(search_all))
        .route("/api/artists", get(artists))
        .route("/api/artist", get(artist))
        .route("/api/yt/artist", get(yt_artist))
        .route("/api/yt/collection/{id}", get(yt_collection))
        .route("/api/lyrics", get(lyrics_handler))
        .route("/api/recs", get(recs_handler))
        .route("/api/wave", get(wave_handler))
        .route("/api/radio", get(radio_handler))
        .route("/api/mixes", get(mixes_handler))
        .route("/api/dislikes", get(dislikes_list).post(dislike))
        .route("/api/dislikes/{id}", delete(undislike))
        .route("/api/blocked-artists", post(block_artist).delete(unblock_artist))
        .route("/api/stats", get(stats_handler))
        .route("/api/wrapped", get(wrapped_handler))
        .route("/api/top", get(top))
        .route("/api/stream/{id}", get(stream))
        .route("/api/substitute/{id}", get(substitute_info))
        .route("/api/prefetch", post(prefetch))
        .route("/api/loudness", post(loudness_handler))
        .route("/api/likes", get(likes).post(like))
        .route("/api/likes/{id}", delete(unlike))
        .route("/api/history", get(history_list).post(history).delete(history_clear))
        .route("/api/skip", post(skip))
        .route("/api/playlists", get(playlists).post(create_playlist))
        .route("/api/playlists/{id}", get(playlist).delete(delete_playlist).patch(rename_playlist))
        .route("/api/playlists/{id}/order", axum::routing::put(reorder_playlist))
        .route("/api/playlists/{id}/share", post(share_playlist).delete(unshare_playlist))
        .route("/api/playlists/{id}/items", post(add_to_playlist))
        .route("/api/playlists/{id}/items/{track}", delete(remove_from_playlist))
        .route("/api/shared/{slug}", get(shared_playlist))
        .route("/api/admin/overview", get(admin::overview))
        .route("/api/admin/accounts", get(admin::accounts_list).post(admin::accounts_add))
        .route("/api/admin/accounts/{id}", axum::routing::patch(admin::accounts_patch).delete(admin::accounts_delete))
        .route("/api/admin/accounts/{id}/cookies", axum::routing::put(admin::accounts_cookies))
        .route("/api/admin/accounts/{id}/test", post(admin::accounts_test))
        .route("/api/admin/events", get(admin::events))
        .route("/api/admin/logs", get(admin::logs))
        .route("/api/admin/users", get(admin::users))
        .fallback_service(spa)
        .layer(CompressionLayer::new().compress_when(compress))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    println!("listening on {addr}, web={web}");
    axum::serve(listener, app).await?;
    Ok(())
}
