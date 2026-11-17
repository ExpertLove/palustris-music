//! Бесплатные каталоги помимо YouTube: Audius, Openverse (Jamendo/FMA/Wikimedia),
//! SoundCloud и archive.org. Треки живут в общих списках с префиксом `x-<src>:`,
//! а звук всегда идёт через наш /api/stream — иначе `<audio crossorigin>` упрётся в CORS.

use crate::db::Track;
use axum::body::Body;
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

const UA: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36";

fn client() -> &'static reqwest::Client {
    static C: OnceLock<reqwest::Client> = OnceLock::new();
    C.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(UA)
            .connect_timeout(Duration::from_secs(10))
            .build()
            .expect("external http client")
    })
}

fn search_cache() -> &'static Mutex<HashMap<String, (Instant, Vec<Track>)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, Vec<Track>)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

fn url_cache() -> &'static Mutex<HashMap<String, (Instant, String)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, String)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Подмешивает бесплатные источники к результатам YouTube. Ошибки источников
/// не пробрасываются: нет ответа — просто нет дополнительных треков.
pub async fn augment(q: &str, out: &mut Vec<Track>, full: bool) {
    let q = q.trim();
    if q.is_empty() {
        return;
    }
    let key = format!("{}|{}", u8::from(full), q.to_lowercase());
    let cached = search_cache().lock().ok().and_then(|c| c.get(&key).cloned());
    if let Some((at, v)) = cached {
        if at.elapsed() < Duration::from_secs(600) {
            merge(out, v);
            return;
        }
    }

    let (a, o, s, ia) = if full {
        tokio::join!(
            limited(audius(q), 4),
            limited(openverse(q), 5),
            limited(soundcloud(q), 8),
            limited(archive(q), 6),
        )
    } else {
        tokio::join!(
            limited(audius(q), 3),
            async { Vec::new() },
            async { Vec::new() },
            async { Vec::new() },
        )
    };

    let mut got = Vec::new();
    for list in [a, o, s, ia] {
        merge(&mut got, list);
    }
    if let Ok(mut c) = search_cache().lock() {
        if c.len() > 400 {
            c.clear();
        }
        c.insert(key, (Instant::now(), got.clone()));
    }
    merge(out, got);
}

async fn limited<F>(f: F, secs: u64) -> Vec<Track>
where
    F: std::future::Future<Output = Vec<Track>>,
{
    tokio::time::timeout(Duration::from_secs(secs), f).await.unwrap_or_default()
}

/// Слияние без дублей: одинаковые «исполнитель + название» из разных источников
/// и из выдачи YouTube остаются в одном экземпляре.
fn merge(out: &mut Vec<Track>, items: Vec<Track>) {
    let mut seen: HashSet<String> = out.iter().map(key).collect();
    for t in items {
        if t.title.trim().is_empty() || t.artist.trim().is_empty() {
            continue;
        }
        let k = key(&t);
        if !k.is_empty() && seen.insert(k) {
            out.push(t);
        }
    }
}

fn key(t: &Track) -> String {
    format!("{}|{}", norm(&t.artist), norm(&t.title))
}

/// Нижний регистр без скобочных уточнений: «Song (Remastered)» = «song».
fn norm(s: &str) -> String {
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

fn audio(id: String, title: String, artist: String, album: &str, duration: i64, thumb: Option<String>) -> Track {
    Track {
        id,
        title,
        artist,
        album: Some(album.to_string()),
        duration,
        thumb,
        artist_id: None,
        album_id: None,
    }
}

// ───────────────────────── Audius ─────────────────────────

async fn audius(q: &str) -> Vec<Track> {
    let Ok(r) = client()
        .get("https://api.audius.co/v1/tracks/search")
        .query(&[("query", q), ("limit", "4")])
        .send()
        .await
    else {
        return Vec::new();
    };
    let Ok(v) = r.json::<Value>().await else {
        return Vec::new();
    };
    let Some(data) = v["data"].as_array() else {
        return Vec::new();
    };
    data.iter()
        .filter_map(|t| {
            let id = t["id"].as_str()?;
            let title = t["title"].as_str()?.trim();
            let artist = t["user"]["name"].as_str()?.trim();
            if !token_ok(id) || title.is_empty() || artist.is_empty() {
                return None;
            }
            let duration = t["duration"].as_f64().unwrap_or(0.0) as i64;
            let thumb = t["artwork"]["480x480"].as_str().map(str::to_string);
            Some(audio(format!("x-au:{id}"), title.to_string(), artist.to_string(), "Audius", duration, thumb))
        })
        .take(4)
        .collect()
}

// ───────────────────────── Openverse ─────────────────────────

async fn openverse(q: &str) -> Vec<Track> {
    let mut req = client()
        .get("https://api.openverse.org/v1/audio/")
        .query(&[("q", q), ("page_size", "4"), ("category", "music")]);
    if let Ok(tok) = std::env::var("MUSIC_OPENVERSE_TOKEN") {
        if !tok.trim().is_empty() {
            req = req.bearer_auth(tok.trim());
        }
    }
    let Ok(r) = req.send().await else {
        return Vec::new();
    };
    let Ok(v) = r.json::<Value>().await else {
        return Vec::new();
    };
    let Some(items) = v["results"].as_array() else {
        return Vec::new();
    };
    items
        .iter()
        .filter_map(|t| {
            let id = t["id"].as_str()?;
            let title = t["title"].as_str()?.trim();
            let artist = t["creator"].as_str()?.trim();
            if !uuid_ok(id) || title.is_empty() || artist.is_empty() {
                return None;
            }
            let source = t["source"].as_str().unwrap_or("CC");
            let duration = t["duration"].as_i64().unwrap_or(0) / 1000;
            let thumb = t["thumbnail"].as_str().map(str::to_string);
            Some(audio(
                format!("x-ov:{id}"),
                title.to_string(),
                artist.to_string(),
                &format!("Openverse · {source}"),
                duration,
                thumb,
            ))
        })
        .take(4)
        .collect()
}

// ───────────────────────── SoundCloud ─────────────────────────

async fn soundcloud(q: &str) -> Vec<Track> {
    let bin = std::env::var("MUSIC_YTDLP").unwrap_or_else(|_| "yt-dlp".to_string());
    let target = format!("scsearch5:{q}");
    let mut cmd = tokio::process::Command::new(bin);
    cmd.args(["--no-warnings", "--flat-playlist", "-J", target.as_str()]).kill_on_drop(true);
    let Ok(out) = cmd.output().await else {
        return Vec::new();
    };
    if !out.status.success() {
        return Vec::new();
    }
    let Ok(v) = serde_json::from_slice::<Value>(&out.stdout) else {
        return Vec::new();
    };
    let Some(entries) = v["entries"].as_array() else {
        return Vec::new();
    };
    entries
        .iter()
        .filter_map(|e| {
            let page = e["webpage_url"].as_str()?;
            if sc_host(page).is_none() {
                return None;
            }
            let duration = e["duration"].as_f64()?;
            // 30-секундные превью лейблов в поиске не нужны.
            if duration <= 31.0 || duration > 3600.0 {
                return None;
            }
            let title = e["title"].as_str()?.trim();
            let artist = e["uploader"].as_str()?.trim();
            if title.is_empty() || artist.is_empty() {
                return None;
            }
            let thumb = e["thumbnails"]
                .as_array()
                .and_then(|a| a.iter().rev().find_map(|t| t["url"].as_str()))
                .map(str::to_string);
            Some(audio(
                format!("x-sc:{}", hex(page)),
                title.to_string(),
                artist.to_string(),
                "SoundCloud",
                duration.round() as i64,
                thumb,
            ))
        })
        .take(3)
        .collect()
}

// ───────────────────────── archive.org ─────────────────────────

async fn archive(q: &str) -> Vec<Track> {
    let safe: String = q.chars().filter(|c| c.is_alphanumeric() || c.is_whitespace() || *c == '-').collect();
    let safe = safe.trim();
    if safe.is_empty() {
        return Vec::new();
    }
    let query = format!("mediatype:audio AND format:MP3 AND (title:({safe}) OR creator:({safe}))");
    let Ok(r) = client()
        .get("https://archive.org/advancedsearch.php")
        .query(&[
            ("q", query.as_str()),
            ("fl[]", "identifier"),
            ("fl[]", "title"),
            ("fl[]", "creator"),
            ("rows", "5"),
            ("page", "1"),
            ("output", "json"),
        ])
        .send()
        .await
    else {
        return Vec::new();
    };
    let Ok(v) = r.json::<Value>().await else {
        return Vec::new();
    };
    let Some(docs) = v["response"]["docs"].as_array() else {
        return Vec::new();
    };
    docs.iter()
        .filter_map(|d| {
            let id = d["identifier"].as_str()?;
            if !archive_id(id) {
                return None;
            }
            let title = d["title"].as_str()?.trim();
            if title.is_empty() {
                return None;
            }
            let low = title.to_lowercase();
            if ["interview", "podcast", "debate", "lecture", "audiobook"].iter().any(|w| low.contains(w)) {
                return None;
            }
            let artist = text(&d["creator"]);
            if artist.matches(',').count() >= 4 {
                // Сборники с десятком исполнителей в поиске — шум.
                return None;
            }
            let artist = if artist.is_empty() { "archive.org".to_string() } else { artist };
            let thumb = Some(format!("https://archive.org/services/img/{id}"));
            Some(audio(format!("x-ia:{id}"), title.to_string(), artist, "archive.org", 0, thumb))
        })
        .take(3)
        .collect()
}

fn text(v: &Value) -> String {
    match v {
        Value::String(s) => s.trim().to_string(),
        Value::Array(a) => a
            .iter()
            .filter_map(|x| x.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join(", "),
        _ => String::new(),
    }
}

// ───────────────────────── воспроизведение ─────────────────────────

pub fn is_external(id: &str) -> bool {
    id.starts_with("x-au:") || id.starts_with("x-ov:") || id.starts_with("x-sc:") || id.starts_with("x-ia:")
}

fn source_of(id: &str) -> &'static str {
    if id.starts_with("x-au:") {
        "au"
    } else if id.starts_with("x-ov:") {
        "ov"
    } else if id.starts_with("x-sc:") {
        "sc"
    } else if id.starts_with("x-ia:") {
        "ia"
    } else {
        "yt"
    }
}

fn fb_cache() -> &'static Mutex<HashMap<String, (Instant, Option<Track>)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, Option<Track>)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Тот же трек в другом каталоге, когда основной источник не отдал звук.
/// `failed_id` исключается вместе со своим сервисом: смысл — уйти на другой.
pub async fn fallback(dir: &std::path::Path, artist: &str, title: &str, failed_id: &str) -> Option<Track> {
    let (artist, title) = (artist.trim(), title.trim());
    if artist.is_empty() || title.is_empty() || artist.len() > 200 || title.len() > 200 {
        return None;
    }
    let src = source_of(failed_id);
    let key = format!("{src}|{}|{}", norm(artist), norm(title));
    if let Some((at, val)) = fb_cache().lock().ok().and_then(|c| c.get(&key).cloned()) {
        let ttl = if val.is_some() { 1800 } else { 300 };
        if at.elapsed() < Duration::from_secs(ttl) {
            return val;
        }
    }

    let q = format!("{artist} {title}");
    let (a, s, o, ia) = tokio::join!(
        limited(audius(&q), 4),
        limited(soundcloud(&q), 8),
        limited(openverse(&q), 5),
        limited(archive(&q), 6),
    );
    let (want_a, want_t) = (norm(artist), norm(title));
    let mut cands: Vec<(i32, u8, Track)> = Vec::new();
    for (pref, list) in [(0u8, a), (1, s), (2, o), (3, ia)] {
        for t in list {
            if t.id == failed_id || source_of(&t.id) == src {
                continue;
            }
            let sc = score(&want_a, &want_t, &t);
            if sc > 0 {
                cands.push((sc, pref, t));
            }
        }
    }
    cands.sort_by(|x, y| y.0.cmp(&x.0).then(x.1.cmp(&y.1)));

    let mut found = None;
    for (_, _, t) in cands.into_iter().take(6) {
        if playable(dir, &t).await {
            eprintln!("fallback «{title}» / {artist}: {}", t.id);
            found = Some(t);
            break;
        }
    }
    if let Ok(mut c) = fb_cache().lock() {
        if c.len() > 300 {
            c.clear();
        }
        c.insert(key, (Instant::now(), found.clone()));
    }
    found
}

/// 0 — не тот трек; больше — точнее совпадение.
fn score(want_artist: &str, want_title: &str, t: &Track) -> i32 {
    let a = norm(&t.artist);
    let ta = norm(&t.title);
    if want_title.is_empty() || ta.is_empty() {
        return 0;
    }
    let mut s = if ta == want_title {
        4
    } else if ta.contains(want_title) || want_title.contains(&ta) {
        2
    } else {
        return 0;
    };
    if !want_artist.is_empty() && (a == want_artist || a.contains(want_artist) || want_artist.contains(&a)) {
        s += 2;
    }
    s
}

/// Проверяем, что источник реально отдаёт аудио, до того как отдать его плееру.
async fn playable(dir: &std::path::Path, t: &Track) -> bool {
    if let Some(rest) = t.id.strip_prefix("x-sc:") {
        return soundcloud_file(dir, rest).await.is_ok();
    }
    let url = if let Some(rest) = t.id.strip_prefix("x-au:") {
        if !token_ok(rest) {
            return false;
        }
        format!("https://api.audius.co/v1/tracks/{rest}/stream")
    } else if let Some(rest) = t.id.strip_prefix("x-ov:") {
        if !uuid_ok(rest) {
            return false;
        }
        match openverse_url(rest).await {
            Ok(u) => u,
            Err(_) => return false,
        }
    } else if let Some(rest) = t.id.strip_prefix("x-ia:") {
        if !archive_id(rest) {
            return false;
        }
        match archive_url(rest).await {
            Ok(u) => u,
            Err(_) => return false,
        }
    } else {
        return false;
    };
    match client().get(&url).header(header::RANGE, "bytes=0-0").send().await {
        Ok(r) => {
            let ct = r
                .headers()
                .get(header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("");
            r.status().is_success()
                && (ct.starts_with("audio/") || ct.starts_with("application/octet-stream") || ct.is_empty())
        }
        Err(_) => false,
    }
}

/// Прокси с поддержкой Range: браузеру отдаём тот же origin, что и у сайта.
pub async fn proxy(id: &str, headers: &HeaderMap) -> Response {
    let url = match resolve(id).await {
        Ok(u) => u,
        Err(e) => return (StatusCode::BAD_GATEWAY, e).into_response(),
    };
    let range = headers.get(header::RANGE).cloned();
    let mut rb = client().get(&url);
    if let Some(r) = range {
        rb = rb.header(header::RANGE, r);
    }
    let up = match rb.send().await {
        Ok(r) => r,
        Err(e) => return (StatusCode::BAD_GATEWAY, e.to_string()).into_response(),
    };
    let status = up.status();
    let src = up.headers().clone();
    let mut resp = Response::new(Body::from_stream(up.bytes_stream()));
    *resp.status_mut() = status;
    let h = resp.headers_mut();
    for name in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::ETAG,
        header::LAST_MODIFIED,
    ] {
        if let Some(v) = src.get(&name) {
            h.insert(name, v.clone());
        }
    }
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, max-age=3600"));
    resp
}

async fn resolve(id: &str) -> Result<String, String> {
    if let Some(rest) = id.strip_prefix("x-au:") {
        if !token_ok(rest) {
            return Err("bad id".into());
        }
        return Ok(format!("https://api.audius.co/v1/tracks/{rest}/stream"));
    }
    if let Some(rest) = id.strip_prefix("x-ov:") {
        if !uuid_ok(rest) {
            return Err("bad id".into());
        }
        return openverse_url(rest).await;
    }
    if let Some(rest) = id.strip_prefix("x-ia:") {
        if !archive_id(rest) {
            return Err("bad id".into());
        }
        return archive_url(rest).await;
    }
    Err("unknown source".into())
}

/// SoundCloud отдаёт только HLS, а `<audio>` его не проигрывает, поэтому трек
/// один раз скачивается в кэш и дальше отдаётся обычным файлом с Range.
/// shortcut: первый запуск ждёт полной загрузки, при жалобах — отдавать потоком.
pub async fn soundcloud_file(dir: &std::path::Path, hexid: &str) -> Result<std::path::PathBuf, String> {
    let page = unhex(hexid).ok_or_else(|| "bad id".to_string())?;
    if sc_host(&page).is_none() {
        return Err("bad url".into());
    }
    let final_path = dir.join(format!("x-sc-{hexid}.m4a"));
    if std::fs::metadata(&final_path).map(|m| m.len() > 0).unwrap_or(false) {
        return Ok(final_path);
    }
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let n = SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let tmp = dir.join(format!("x-sc-{hexid}.{n}.part"));
    let bin = std::env::var("MUSIC_YTDLP").unwrap_or_else(|_| "yt-dlp".to_string());
    let mut cmd = tokio::process::Command::new(bin);
    cmd.args(["--no-warnings", "--no-playlist", "-f", "bestaudio", "-o"])
        .arg(&tmp)
        .arg(page.as_str())
        .kill_on_drop(true);
    let out = match tokio::time::timeout(Duration::from_secs(300), cmd.output()).await {
        Ok(Ok(o)) => o,
        Ok(Err(e)) => return Err(e.to_string()),
        Err(_) => {
            let _ = std::fs::remove_file(&tmp);
            return Err("yt-dlp timeout".into());
        }
    };
    if !out.status.success() || !tmp.is_file() {
        let err = String::from_utf8_lossy(&out.stderr);
        let _ = std::fs::remove_file(&tmp);
        return Err(err.lines().last().unwrap_or("yt-dlp error").to_string());
    }
    std::fs::rename(&tmp, &final_path).map_err(|e| e.to_string())?;
    Ok(final_path)
}

async fn openverse_url(uuid: &str) -> Result<String, String> {
    let key = format!("x-ov:{uuid}");
    if let Some(u) = cached(&key) {
        return Ok(u);
    }
    let mut req = client().get(format!("https://api.openverse.org/v1/audio/{uuid}/"));
    if let Ok(tok) = std::env::var("MUSIC_OPENVERSE_TOKEN") {
        if !tok.trim().is_empty() {
            req = req.bearer_auth(tok.trim());
        }
    }
    let v = req
        .send()
        .await
        .map_err(|e| e.to_string())?
        .json::<Value>()
        .await
        .map_err(|e| e.to_string())?;
    let url = v["url"].as_str().ok_or_else(|| "нет ссылки".to_string())?.to_string();
    public_url(&url)?;
    remember(&key, &url);
    Ok(url)
}

async fn archive_url(id: &str) -> Result<String, String> {
    let key = format!("x-ia:{id}");
    if let Some(u) = cached(&key) {
        return Ok(u);
    }
    let v = client()
        .get(format!("https://archive.org/metadata/{id}"))
        .send()
        .await
        .map_err(|e| e.to_string())?
        .json::<Value>()
        .await
        .map_err(|e| e.to_string())?;
    let files = v["files"].as_array().ok_or_else(|| "нет файлов".to_string())?;
    let pick = files
        .iter()
        .filter(|f| f["private"].as_str() != Some("true"))
        .filter_map(|f| {
            let name = f["name"].as_str()?;
            let low = name.to_lowercase();
            if !(low.ends_with(".mp3") || low.ends_with(".ogg") || low.ends_with(".m4a")) {
                return None;
            }
            let fmt = f["format"].as_str().unwrap_or("").to_lowercase();
            let score = if fmt == "vbr mp3" {
                0
            } else if fmt.contains("mp3") {
                1
            } else {
                2
            };
            Some((score, name))
        })
        .min_by_key(|(s, _)| *s)
        .ok_or_else(|| "нет аудиофайла".to_string())?;
    let mut u = reqwest::Url::parse(&format!("https://archive.org/download/{id}/")).map_err(|e| e.to_string())?;
    {
        let mut seg = u.path_segments_mut().map_err(|_| "bad url".to_string())?;
        for part in pick.1.split('/') {
            seg.push(part);
        }
    }
    let url = u.to_string();
    remember(&key, &url);
    Ok(url)
}

fn cached(key: &str) -> Option<String> {
    let m = url_cache().lock().ok()?;
    let (at, url) = m.get(key)?;
    if at.elapsed() < Duration::from_secs(1200) {
        Some(url.clone())
    } else {
        None
    }
}

fn remember(key: &str, url: &str) {
    if let Ok(mut m) = url_cache().lock() {
        if m.len() > 500 {
            m.clear();
        }
        m.insert(key.to_string(), (Instant::now(), url.to_string()));
    }
}

/// Не даём превратить id в SSRF: только https и не внутренний адрес.
fn public_url(raw: &str) -> Result<(), String> {
    let u = reqwest::Url::parse(raw).map_err(|_| "bad url".to_string())?;
    if u.scheme() != "https" {
        return Err("bad scheme".into());
    }
    let host = u.host_str().ok_or_else(|| "no host".to_string())?;
    if host == "localhost" || host.ends_with(".localhost") {
        return Err("bad host".into());
    }
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        let bad = match ip {
            std::net::IpAddr::V4(v4) => {
                v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified() || v4.is_broadcast()
            }
            std::net::IpAddr::V6(v6) => {
                let s = v6.segments();
                v6.is_loopback()
                    || v6.is_unspecified()
                    || (s[0] & 0xfe00) == 0xfc00
                    || (s[0] & 0xffc0) == 0xfe80
                    || (s[0] & 0xffc0) == 0xfec0
            }
        };
        if bad {
            return Err("bad host".into());
        }
    }
    Ok(())
}

fn sc_host(raw: &str) -> Option<reqwest::Url> {
    let u = reqwest::Url::parse(raw).ok()?;
    if u.scheme() != "https" {
        return None;
    }
    let host = u.host_str()?;
    if host == "soundcloud.com" || host.ends_with(".soundcloud.com") {
        Some(u)
    } else {
        None
    }
}

fn hex(s: &str) -> String {
    s.bytes().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Option<String> {
    if s.is_empty() || s.len() % 2 != 0 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let mut out = Vec::with_capacity(s.len() / 2);
    let b = s.as_bytes();
    for i in (0..b.len()).step_by(2) {
        let hi = (b[i] as char).to_digit(16)?;
        let lo = (b[i + 1] as char).to_digit(16)?;
        out.push((hi * 16 + lo) as u8);
    }
    String::from_utf8(out).ok()
}

fn token_ok(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn archive_id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 120
        && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
}

fn uuid_ok(s: &str) -> bool {
    s.len() == 36
        && s.chars().enumerate().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit(),
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_roundtrip() {
        let url = "https://soundcloud.com/molchatdoma/ucypjtb8gi6k";
        assert_eq!(unhex(&hex(url)).as_deref(), Some(url));
        assert!(unhex("zz").is_none());
    }

    #[test]
    fn ids() {
        assert!(uuid_ok("301df5ad-c4e1-4d16-b4ef-cc1eea8db319"));
        assert!(!uuid_ok("301df5adc4e14d16b4efcc1eea8db319"));
        assert!(archive_id("daft-punk-homework"));
        assert!(!archive_id("a/b"));
    }

    #[test]
    fn dedupe_key() {
        let a = Track::new("1".into(), "Song (Remastered)".into(), "The Band".into());
        let b = Track::new("2".into(), "song".into(), "the band".into());
        assert_eq!(key(&a), key(&b));
    }

    #[test]
    fn host_guard() {
        assert!(sc_host("https://soundcloud.com/a/b").is_some());
        assert!(sc_host("https://evil.com/a").is_none());
        assert!(public_url("http://example.com/a").is_err());
        assert!(public_url("https://127.0.0.1/a").is_err());
    }

    #[test]
    fn fallback_score() {
        let t = Track::new("x-au:1".into(), "Instant Crush (feat. Julian Casablancas)".into(), "Daft Punk".into());
        assert_eq!(score(&norm("Daft Punk, Julian Casablancas"), &norm("Instant Crush"), &t), 6);
        let remix = Track::new("x-au:2".into(), "Daft Punk - Instant Crush (Remix)".into(), "DJ".into());
        assert_eq!(score(&norm("Daft Punk"), &norm("Instant Crush"), &remix), 2);
        let other = Track::new("x-au:3".into(), "Get Lucky".into(), "Daft Punk".into());
        assert_eq!(score(&norm("Daft Punk"), &norm("Instant Crush"), &other), 0);
        assert_eq!(source_of("x-sc:abc"), "sc");
        assert_eq!(source_of("dQw4w9WgXcQ"), "yt");
    }
}
