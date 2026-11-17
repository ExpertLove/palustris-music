//! Админка: аккаунты YouTube, журнал загрузок, логи сервера, пользователи.
//! Доступ — только у почт из MUSIC_ADMINS (через запятую).
use super::*;
use crate::accounts::{self, parse_cookies};
use std::time::Duration;

pub fn is_admin(st: &AppState, email: &str) -> bool {
    let e = email.trim().to_lowercase();
    st.admins.iter().any(|a| *a == e)
}

fn check(st: &AppState, h: &HeaderMap) -> Result<i64, ApiError> {
    let u = uid(st, h)?;
    let email: String = {
        let db = st.db.lock().map_err(e500)?;
        db.query_row("SELECT email FROM users WHERE id=?1", [u], |r| r.get(0)).map_err(e500)?
    };
    if !is_admin(st, &email) {
        return Err((StatusCode::FORBIDDEN, "нет доступа".into()));
    }
    Ok(u)
}

fn bad(msg: impl Into<String>) -> ApiError {
    (StatusCode::BAD_REQUEST, msg.into())
}

/// Запуск внешней команды с таймаутом: stdout+stderr одной строкой.
async fn run_cmd(prog: &str, args: &[&str], secs: u64) -> Result<String, String> {
    let mut cmd = tokio::process::Command::new(prog);
    cmd.args(args).kill_on_drop(true);
    match tokio::time::timeout(Duration::from_secs(secs), cmd.output()).await {
        Ok(Ok(o)) => {
            let mut s = String::from_utf8_lossy(&o.stdout).into_owned();
            let e = String::from_utf8_lossy(&o.stderr);
            if !e.trim().is_empty() {
                if !s.is_empty() && !s.ends_with('\n') {
                    s.push('\n');
                }
                s.push_str(&e);
            }
            if o.status.success() {
                Ok(s)
            } else {
                Err(if s.trim().is_empty() { format!("{prog}: код {}", o.status) } else { s })
            }
        }
        Ok(Err(e)) => Err(format!("{prog}: {e}")),
        Err(_) => Err(format!("{prog}: не ответил за {secs} с")),
    }
}

// ───────────────────────── обзор ─────────────────────────

fn dir_size(dir: &std::path::Path) -> (u64, u64) {
    let Ok(rd) = std::fs::read_dir(dir) else { return (0, 0) };
    let mut files = 0;
    let mut bytes = 0;
    for e in rd.flatten() {
        if let Ok(m) = e.metadata() {
            if m.is_file() {
                files += 1;
                bytes += m.len();
            }
        }
    }
    (files, bytes)
}

pub async fn overview(State(st): St, h: HeaderMap) -> ApiResult<serde_json::Value> {
    check(&st, &h)?;
    let now = recs::now();
    let (users, active, plays_day): (i64, i64, i64) = {
        let db = st.db.lock().map_err(e500)?;
        db.query_row(
            "SELECT (SELECT COUNT(*) FROM users),
                    (SELECT COUNT(DISTINCT user_id) FROM sessions WHERE seen >= ?1),
                    (SELECT COUNT(*) FROM history WHERE played_at >= ?2)",
            params![now - 7 * 86400, now - 86400],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(e500)?
    };
    let acc = &st.accounts;
    let list = acc.list().map_err(e500)?;
    let available = list.iter().filter(|a| a.status == "ok" || a.status == "new").count();
    let (h_ok, h_fail, h_att) = acc.counts(3600);
    let (d_ok, d_fail, d_att) = acc.counts(86400);
    let last_error = acc.last_error().map(|(at, err)| serde_json::json!({ "at": at, "err": err }));

    let cache_dir = st.audio.dir.clone();
    let (files, bytes) = tokio::task::spawn_blocking(move || dir_size(&cache_dir)).await.unwrap_or((0, 0));
    let max_gb: f64 = std::env::var("MUSIC_CACHE_MAX_GB").ok().and_then(|v| v.parse().ok()).unwrap_or(15.0);

    let ytdlp = run_cmd(&st.audio.ytdlp, &["--version"], 15).await.map(|s| s.trim().to_string()).unwrap_or_else(|e| e);
    let data = st.data.to_string_lossy().to_string();
    let disk = run_cmd("df", &["-B1", "--output=size,avail", &data], 5)
        .await
        .ok()
        .and_then(|s| {
            let line = s.lines().nth(1)?.to_string();
            let mut it = line.split_whitespace();
            Some(serde_json::json!({ "size": it.next()?.parse::<u64>().ok()?, "avail": it.next()?.parse::<u64>().ok()? }))
        });

    let block = st.audio.block_state();
    let (fb_h_ok, fb_h_miss) = acc.fallback_counts(3600);
    let (fb_d_ok, fb_d_miss) = acc.fallback_counts(86400);

    Ok(Json(serde_json::json!({
        "now": now,
        "startedAt": st.started,
        "ipFlaggedUntil": acc.ip_flagged_until(),
        "ytBlockedSince": block.since,
        "ytNextProbe": block.since.map(|_| block.last_probe.unwrap_or(now) + audio::PROBE_EVERY),
        "fallback": {
            "hour": { "ok": fb_h_ok, "miss": fb_h_miss },
            "day": { "ok": fb_d_ok, "miss": fb_d_miss },
        },
        "accounts": { "total": list.len(), "available": available },
        "hour": { "ok": h_ok, "fail": h_fail, "attemptsFailed": h_att },
        "day": { "ok": d_ok, "fail": d_fail, "attemptsFailed": d_att },
        "lastError": last_error,
        "cache": { "files": files, "bytes": bytes, "maxBytes": (max_gb * 1024.0 * 1024.0 * 1024.0) as u64 },
        "users": users,
        "activeUsers": active,
        "playsDay": plays_day,
        "ytdlp": ytdlp,
        "disk": disk,
    })))
}

// ───────────────────────── аккаунты YouTube ─────────────────────────

pub async fn accounts_list(State(st): St, h: HeaderMap) -> ApiResult<Vec<accounts::AccountInfo>> {
    check(&st, &h)?;
    Ok(Json(st.accounts.list().map_err(e500)?))
}

#[derive(Deserialize)]
pub struct NewAccount {
    label: String,
    cookies: String,
}

fn clean_label(l: &str) -> Result<String, ApiError> {
    let l = l.trim();
    if l.is_empty() {
        return Err(bad("укажите название аккаунта"));
    }
    Ok(l.chars().take(60).collect())
}

pub async fn accounts_add(State(st): St, h: HeaderMap, Json(b): Json<NewAccount>) -> ApiResult<serde_json::Value> {
    check(&st, &h)?;
    let label = clean_label(&b.label)?;
    let parsed = parse_cookies(&b.cookies).map_err(bad)?;
    let id = st.accounts.add(&label, &parsed).map_err(e500)?;
    eprintln!("admin: добавлен аккаунт YouTube #{id} «{label}» ({} cookies)", parsed.count);
    Ok(Json(serde_json::json!({ "id": id, "cookies": parsed.count })))
}

#[derive(Deserialize)]
pub struct CookiesBody {
    cookies: String,
}

pub async fn accounts_cookies(State(st): St, h: HeaderMap, Path(id): Path<i64>, Json(b): Json<CookiesBody>) -> ApiResult<serde_json::Value> {
    check(&st, &h)?;
    let parsed = parse_cookies(&b.cookies).map_err(bad)?;
    if !st.accounts.replace_cookies(id, &parsed).map_err(e500)? {
        return Err((StatusCode::NOT_FOUND, "аккаунт не найден".into()));
    }
    eprintln!("admin: обновлены cookies аккаунта #{id}");
    Ok(Json(serde_json::json!({ "cookies": parsed.count })))
}

#[derive(Deserialize)]
pub struct PatchAccount {
    label: Option<String>,
    enabled: Option<bool>,
    #[serde(default)]
    reset: bool,
}

pub async fn accounts_patch(State(st): St, h: HeaderMap, Path(id): Path<i64>, Json(b): Json<PatchAccount>) -> ApiResult<()> {
    check(&st, &h)?;
    let label = match &b.label {
        Some(l) => Some(clean_label(l)?),
        None => None,
    };
    if !st.accounts.update(id, label.as_deref(), b.enabled, b.reset).map_err(e500)? {
        return Err((StatusCode::NOT_FOUND, "аккаунт не найден".into()));
    }
    Ok(Json(()))
}

pub async fn accounts_delete(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<()> {
    check(&st, &h)?;
    if !st.accounts.delete(id).map_err(e500)? {
        return Err((StatusCode::NOT_FOUND, "аккаунт не найден".into()));
    }
    eprintln!("admin: удалён аккаунт YouTube #{id}");
    Ok(Json(()))
}

/// Cookie-заголовок для youtube.com из файла cookies.
fn cookie_header(path: &std::path::Path) -> String {
    let raw = std::fs::read_to_string(path).unwrap_or_default();
    let mut parts = Vec::new();
    for line in raw.lines() {
        let body = line.strip_prefix("#HttpOnly_").unwrap_or(line);
        if body.starts_with('#') {
            continue;
        }
        let f: Vec<&str> = body.split('\t').collect();
        if f.len() >= 7 && f[0].trim_start_matches('.').ends_with("youtube.com") {
            parts.push(format!("{}={}", f[5], f[6]));
        }
    }
    parts.join("; ")
}

/// Проверка аккаунта: видит ли YouTube вход и отдаёт ли аудио через yt-dlp (без скачивания).
pub async fn accounts_test(State(st): St, h: HeaderMap, Path(id): Path<i64>) -> ApiResult<serde_json::Value> {
    check(&st, &h)?;
    let path = st.accounts.path(id);
    if !path.exists() {
        return Err((StatusCode::NOT_FOUND, "аккаунт не найден".into()));
    }

    // 1. Вход: на главной YouTube в ytcfg есть "LOGGED_IN":true/false.
    let cookie = cookie_header(&path);
    let logged_in = match st
        .http
        .get("https://www.youtube.com/")
        .header(header::COOKIE, cookie)
        .header(header::ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9")
        .send()
        .await
    {
        Ok(r) => match r.text().await {
            Ok(t) if t.contains("\"LOGGED_IN\":true") => Some(true),
            Ok(t) if t.contains("\"LOGGED_IN\":false") => Some(false),
            _ => None,
        },
        Err(_) => None,
    };

    // 2. Аудио: yt-dlp в режиме --simulate на тестовом треке.
    let video = std::env::var("MUSIC_TEST_VIDEO").unwrap_or_else(|_| "dQw4w9WgXcQ".into());
    let url = format!("https://music.youtube.com/watch?v={video}");
    let path_s = path.to_string_lossy().to_string();
    let extractor = std::env::var("MUSIC_ACCOUNT_CLIENTS")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(|v| format!("youtube:player_client={}", v.trim()));
    let mut args: Vec<&str> = vec![
        "--simulate",
        "--no-playlist",
        "--no-warnings",
        "--cookies",
        &path_s,
        "-f",
        "bestaudio[ext=m4a]/bestaudio",
        "--print",
        "%(format_id)s %(ext)s %(abr)s",
    ];
    if let Some(e) = &extractor {
        args.push("--extractor-args");
        args.push(e);
    }
    args.push(&url);
    let started = std::time::Instant::now();
    let res = run_cmd(&st.audio.ytdlp, &args, 90).await;
    let ms = started.elapsed().as_millis() as i64;
    let client = "test";
    // Аудио может отдаться и без входа (если IP сейчас не в блоке), но такой аккаунт блок не обойдёт.
    if logged_in == Some(false) {
        let note = "YouTube не видит вход в аккаунт: cookies устарели или выгружены без входа — выгрузите заново";
        st.accounts.report_err(id, accounts::ErrKind::CookiesDead, note);
        let audio = match &res {
            Ok(out) => serde_json::json!({ "ok": true, "format": out.trim().lines().last().unwrap_or("") }),
            Err(e) => serde_json::json!({ "ok": false, "error": e.lines().filter(|l| l.contains("ERROR")).last().unwrap_or(e.trim()) }),
        };
        return Ok(Json(serde_json::json!({
            "ok": false, "loggedIn": false, "kind": "cookies", "error": note, "audio": audio, "ms": ms
        })));
    }
    let body = match res {
        Ok(out) => {
            st.accounts.report_ok(id);
            st.accounts.log_event(&video, Some(id), client, Ok(()), ms);
            serde_json::json!({ "ok": true, "loggedIn": logged_in, "format": out.trim().lines().last().unwrap_or(""), "ms": ms })
        }
        Err(err) => {
            let line = err.lines().filter(|l| l.contains("ERROR")).last().unwrap_or(err.trim()).to_string();
            let kind = accounts::classify(&line);
            st.accounts.report_err(id, kind, &line);
            st.accounts.log_event(&video, Some(id), client, Err((&line, kind)), ms);
            serde_json::json!({ "ok": false, "loggedIn": logged_in, "error": line, "kind": kind.as_str(), "ms": ms })
        }
    };
    Ok(Json(body))
}

// ───────────────────────── журнал загрузок ─────────────────────────

#[derive(Deserialize)]
pub struct EventsQ {
    limit: Option<i64>,
    #[serde(default)]
    fail: Option<String>,
    video: Option<String>,
}

pub async fn events(State(st): St, h: HeaderMap, Query(q): Query<EventsQ>) -> ApiResult<Vec<accounts::Event>> {
    check(&st, &h)?;
    let limit = q.limit.unwrap_or(200).clamp(1, 2000);
    let only_fail = matches!(q.fail.as_deref(), Some("1") | Some("true"));
    let video = q.video.as_deref().map(str::trim).filter(|v| !v.is_empty());
    Ok(Json(st.accounts.events(limit, only_fail, video).map_err(e500)?))
}

// ───────────────────────── логи сервера ─────────────────────────

#[derive(Deserialize)]
pub struct LogsQ {
    src: Option<String>,
    lines: Option<usize>,
    q: Option<String>,
}

fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\u{1b}' {
            if it.peek() == Some(&'[') {
                it.next();
                for d in it.by_ref() {
                    if d.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

pub async fn logs(State(st): St, h: HeaderMap, Query(q): Query<LogsQ>) -> ApiResult<serde_json::Value> {
    check(&st, &h)?;
    let want = q.lines.unwrap_or(300).clamp(10, 3000);
    let filter = q.q.as_deref().map(|s| s.trim().to_lowercase()).filter(|s| !s.is_empty());
    // С фильтром берём хвост побольше, иначе нужные строки могут не попасть.
    let fetch = if filter.is_some() { (want * 20).min(50_000) } else { want };
    let n = fetch.to_string();
    let src = q.src.as_deref().unwrap_or("music");
    let res = match src {
        "music" => run_cmd("journalctl", &["-u", "music", "-n", &n, "--no-pager", "-o", "short-iso"], 20).await,
        "caddy" => run_cmd("journalctl", &["-u", "caddy", "-n", &n, "--no-pager", "-o", "short-iso"], 20).await,
        "system" => run_cmd("journalctl", &["-p", "warning", "-n", &n, "--no-pager", "-o", "short-iso"], 20).await,
        "ytdlp" | "backup" => {
            let file = if src == "ytdlp" { "/var/log/music-ytdlp.log" } else { "/var/log/music-backup.log" };
            if !std::path::Path::new(file).exists() {
                Err(format!("{file} ещё не создан: ночная задача пока ни разу не запускалась по расписанию"))
            } else {
                run_cmd("tail", &["-n", &n, file], 10).await
            }
        }
        "bgutil" => run_cmd("docker", &["logs", "--tail", &n, "--timestamps", "bgutil-provider"], 20).await,
        _ => return Err(bad("неизвестный источник")),
    };
    let text = match res {
        Ok(t) => t,
        Err(e) => return Ok(Json(serde_json::json!({ "src": src, "lines": [], "error": e }))),
    };
    let mut lines: Vec<String> = text
        .lines()
        .map(|l| {
            let l = strip_ansi(l);
            if l.chars().count() > 2000 {
                l.chars().take(2000).collect::<String>() + "…"
            } else {
                l
            }
        })
        .filter(|l| match &filter {
            Some(f) => l.to_lowercase().contains(f),
            None => true,
        })
        .collect();
    if lines.len() > want {
        lines.drain(..lines.len() - want);
    }
    Ok(Json(serde_json::json!({ "src": src, "lines": lines })))
}

// ───────────────────────── пользователи ─────────────────────────

pub async fn users(State(st): St, h: HeaderMap) -> ApiResult<Vec<serde_json::Value>> {
    check(&st, &h)?;
    let db = st.db.lock().map_err(e500)?;
    let mut stmt = db
        .prepare(
            "SELECT u.id, u.email, u.name, u.created, u.email_verified,
                    (SELECT MAX(seen) FROM sessions s WHERE s.user_id=u.id),
                    (SELECT COUNT(*) FROM sessions s WHERE s.user_id=u.id),
                    (SELECT COUNT(*) FROM history x WHERE x.user_id=u.id),
                    (SELECT COUNT(*) FROM likes l WHERE l.user_id=u.id),
                    (SELECT COUNT(*) FROM playlists p WHERE p.user_id=u.id),
                    (SELECT MAX(played_at) FROM history x WHERE x.user_id=u.id)
             FROM users u ORDER BY u.id DESC",
        )
        .map_err(e500)?;
    let rows = stmt
        .query_map([], |r| {
            let email: String = r.get(1)?;
            Ok((email, r.get::<_, i64>(0)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?, r.get::<_, i64>(4)?, r.get::<_, Option<i64>>(5)?, r.get::<_, i64>(6)?, r.get::<_, i64>(7)?, r.get::<_, i64>(8)?, r.get::<_, i64>(9)?, r.get::<_, Option<i64>>(10)?))
        })
        .map_err(e500)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(e500)?;
    Ok(Json(
        rows.into_iter()
            .map(|(email, id, name, created, verified, seen, sessions, plays, likes, playlists, last_play)| {
                serde_json::json!({
                    "id": id, "admin": is_admin(&st, &email), "email": email, "name": name, "created": created,
                    "verified": verified != 0, "lastSeen": seen.unwrap_or(0), "sessions": sessions,
                    "plays": plays, "likes": likes, "playlists": playlists, "lastPlay": last_play.unwrap_or(0)
                })
            })
            .collect(),
    ))
}
