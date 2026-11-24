//! Яндекс Музыка: поиск и звук через неофициальный `api.music.yandex.net`.
//!
//! Нужен токен аккаунта (`YANDEX_MUSIC_TOKEN`) и, для полного качества, Плюс на этом аккаунте:
//! без подписки сервер отдаёт `preview`. Готовые ссылки на CDN живут около минуты, поэтому
//! плееру отдаём не их, а наш прокси. Часть треков приходит зашифрованной AES-CTR
//! (`downloadInfo.key`) — расшифровываем на лету, Range пересчитываем по смещению.

use crate::sources;
use axum::body::Body;
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use base64::Engine as _;
use futures_util::TryStreamExt;
use hmac::{Hmac, Mac};
use serde_json::Value;
use sha2::Sha256;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const API: &str = "https://api.music.yandex.net/";
/// Так представляется веб-клиент; без этого заголовка часть ручек отвечает 451.
const CLIENT: &str = "YandexMusicWebNext/1.0.0";
/// Ключ подписи `get-file-info`, публичный для всех клиентов.
const SIGN_KEY: &[u8] = b"7tvSmFbyf5hJnIHhCimDDD";
/// Что просим: `lossless` — потолок, сервер сам отдаст лучшее доступное (обычно mp3 320).
const QUALITY: &str = "lossless";
const CODECS: &str = "flac,mp3";
const TRANSPORT: &str = "raw";
const URL_TTL: Duration = Duration::from_secs(50);
const UID_TTL: Duration = Duration::from_secs(600);

pub fn enabled() -> bool {
    token().is_some()
}

fn token() -> Option<String> {
    let t = std::env::var("YANDEX_MUSIC_TOKEN").ok()?;
    let t = t.trim();
    (!t.is_empty()).then(|| t.to_string())
}

pub(crate) async fn api(path: &str, query: &[(&str, String)], user: bool) -> Result<Value, String> {
    let uid = if user { uid().await } else { None };
    request(path, query, uid.as_deref()).await
}

async fn request(path: &str, query: &[(&str, String)], uid: Option<&str>) -> Result<Value, String> {
    let token = token().ok_or("нет YANDEX_MUSIC_TOKEN")?;
    let mut rb = sources::client()
        .get(format!("{API}{path}"))
        .header(header::AUTHORIZATION, format!("OAuth {token}"))
        .header("x-yandex-music-client", CLIENT)
        .header(header::ORIGIN, "https://music.yandex.ru")
        .header(header::REFERER, "https://music.yandex.ru/")
        .query(query);
    if let Some(uid) = uid {
        rb = rb.header("x-yandex-music-multi-auth-user-id", uid);
    }
    let r = rb.send().await.map_err(|e| e.to_string())?;
    let status = r.status();
    let body = r.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(format!("HTTP {status}: {}", body.chars().take(160).collect::<String>()));
    }
    serde_json::from_str(&body).map_err(|e| e.to_string())
}

/// uid нужен в заголовке каждой ручки; спрашиваем раз в 10 минут.
async fn uid() -> Option<String> {
    fn cache() -> &'static Mutex<Option<(Instant, String)>> {
        static C: OnceLock<Mutex<Option<(Instant, String)>>> = OnceLock::new();
        C.get_or_init(|| Mutex::new(None))
    }
    if let Ok(m) = cache().lock() {
        if let Some((_, uid)) = m.as_ref().filter(|(at, _)| at.elapsed() < UID_TTL) {
            return Some(uid.clone());
        }
    }
    let v = request("account/status", &[], None).await.ok()?;
    let uid = v["result"]["account"]["uid"].as_i64()?.to_string();
    if let Ok(mut m) = cache().lock() {
        *m = Some((Instant::now(), uid.clone()));
    }
    Some(uid)
}

// ───────────────────────── поиск ─────────────────────────

#[derive(Clone, Debug)]
pub struct Hit {
    pub title: String,
    pub artist: String,
    pub duration: i64,
    /// id трека для `get-file-info`.
    pub track: String,
    pub page: String,
}

pub async fn search(text: &str) -> Result<Vec<Hit>, String> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(Vec::new());
    }
    let v = api(
        "search",
        &[("text", text.to_string()), ("type", "track".to_string()), ("page", "0".to_string())],
        false,
    )
    .await?;
    let arr = v["result"]["tracks"]["results"].as_array().ok_or("нет tracks")?;
    Ok(arr
        .iter()
        .filter_map(|t| {
            let track = t["id"].as_i64()?.to_string();
            let album = t["albums"].as_array().and_then(|a| a.first())?.get("id")?.as_i64()?;
            let artist = t["artists"]
                .as_array()?
                .iter()
                .filter_map(|a| a["name"].as_str())
                .collect::<Vec<_>>()
                .join(", ");
            Some(Hit {
                title: t["title"].as_str()?.trim().to_string(),
                artist: artist.trim().to_string(),
                duration: t["durationMs"].as_i64().unwrap_or(0) / 1000,
                page: format!("https://music.yandex.ru/album/{album}/track/{track}"),
                track,
            })
        })
        .filter(|h| !h.title.is_empty() && !h.artist.is_empty())
        .collect())
}

// ───────────────────────── звук ─────────────────────────

#[derive(Clone, Debug)]
struct File {
    urls: Vec<String>,
    /// hex-ключ AES-CTR; пусто — файл не зашифрован.
    key: Option<String>,
    quality: String,
}

fn info_cache() -> &'static Mutex<HashMap<String, (Instant, File)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, File)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cached_info(track: &str) -> Option<File> {
    let m = info_cache().lock().ok()?;
    let (at, f) = m.get(track)?;
    (at.elapsed() < URL_TTL).then(|| f.clone())
}

fn remember_info(track: &str, f: &File) {
    if let Ok(mut m) = info_cache().lock() {
        if m.len() > 300 {
            m.retain(|_, (at, _)| at.elapsed() < URL_TTL);
        }
        m.insert(track.to_string(), (Instant::now(), f.clone()));
    }
}

async fn file_info(track: &str) -> Result<File, String> {
    if let Some(f) = cached_info(track) {
        return Ok(f);
    }
    let ts = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_secs();
    let sign = sign(ts, track, QUALITY, CODECS, TRANSPORT);
    let v = api(
        "get-file-info",
        &[
            ("ts", ts.to_string()),
            ("trackId", track.to_string()),
            ("quality", QUALITY.to_string()),
            ("codecs", CODECS.to_string()),
            ("transports", TRANSPORT.to_string()),
            ("sign", sign),
        ],
        true,
    )
    .await?;
    let info = if v["result"]["downloadInfo"].is_object() { &v["result"]["downloadInfo"] } else { &v["downloadInfo"] };
    let urls: Vec<String> = info["urls"]
        .as_array()
        .map(|a| a.iter().filter_map(|u| u.as_str().map(str::to_string)).collect())
        .unwrap_or_default();
    if urls.is_empty() {
        let msg = v["message"].as_str().or_else(|| v["result"]["message"].as_str()).unwrap_or("пусто");
        return Err(format!("нет ссылок: {msg}"));
    }
    let f = File {
        urls,
        key: info["key"].as_str().filter(|k| !k.is_empty()).map(str::to_string),
        quality: info["quality"].as_str().unwrap_or("").to_string(),
    };
    if f.quality == "preview" {
        eprintln!("yandex {track}: аккаунт отдаёт только preview — нужен Плюс");
    }
    remember_info(track, &f);
    Ok(f)
}

fn sign(timestamp: u64, track: &str, quality: &str, codecs: &str, transport: &str) -> String {
    let message = format!("{timestamp}{track}{quality}{}{transport}", codecs.replace(',', ""));
    let mut mac = Hmac::<Sha256>::new_from_slice(SIGN_KEY).expect("HMAC принимает любой ключ");
    mac.update(message.as_bytes());
    base64::engine::general_purpose::STANDARD_NO_PAD.encode(mac.finalize().into_bytes())
}

/// Источник действительно отдаёт аудио (проверка до подмены, как у остальных источников).
pub async fn reachable(track: &str) -> Result<(), String> {
    let f = file_info(track).await?;
    let url = f.urls.first().ok_or("нет ссылок")?;
    let r = sources::client()
        .get(url)
        .header(header::RANGE, "bytes=0-0")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let ct = r.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    if !r.status().is_success() {
        return Err(format!("HTTP {}", r.status()));
    }
    if ct.starts_with("audio/") || ct.starts_with("application/octet-stream") || ct.is_empty() {
        Ok(())
    } else {
        Err(format!("не аудио: {ct}"))
    }
}

/// Прокси с Range и расшифровкой. OAuth-токен на CDN не уходит.
pub async fn stream(track: &str, headers: &HeaderMap) -> Response {
    let offset = headers
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("bytes="))
        .and_then(|v| v.split('-').next())
        .and_then(|v| v.trim().parse::<u64>().ok())
        .unwrap_or(0);
    let f = match file_info(track).await {
        Ok(f) => f,
        Err(e) => {
            eprintln!("yandex {track}: {e}");
            return (StatusCode::BAD_GATEWAY, "яндекс не отдал трек").into_response();
        }
    };
    let mut dec = match f.key.as_deref().and_then(|k| Decrypt::new(k, offset)) {
        Some(d) => Some(d),
        None if f.key.is_some() => {
            eprintln!("yandex {track}: плохой ключ шифрования");
            return (StatusCode::BAD_GATEWAY, "яндекс вернул плохой ключ").into_response();
        }
        None => None,
    };
    for url in &f.urls {
        let mut rb = sources::client().get(url);
        if let Some(r) = headers.get(header::RANGE) {
            rb = rb.header(header::RANGE, r.clone());
        }
        let up = match rb.send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                eprintln!("yandex {track}: CDN HTTP {}", r.status());
                continue;
            }
            Err(e) => {
                eprintln!("yandex {track}: CDN {e}");
                continue;
            }
        };
        let src = up.headers().clone();
        let status = up.status();
        let mut resp = Response::new(Body::from_stream(up.bytes_stream().map_ok(move |chunk| {
            let mut v = chunk.to_vec();
            if let Some(d) = dec.as_mut() {
                d.apply(&mut v);
            }
            axum::body::Bytes::from(v)
        })));
        *resp.status_mut() = status;
        let h = resp.headers_mut();
        for name in [header::CONTENT_TYPE, header::CONTENT_LENGTH, header::CONTENT_RANGE, header::ACCEPT_RANGES] {
            if let Some(v) = src.get(&name) {
                h.insert(name, v.clone());
            }
        }
        h.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, max-age=3600"));
        return resp;
    }
    (StatusCode::BAD_GATEWAY, "яндекс не отдал звук").into_response()
}

// ───────────────────────── AES-CTR ─────────────────────────

/// AES-CTR с нулевым IV: чтобы начать с байта `offset`, ставим счётчик на его блок
/// и выбрасываем `offset % 16` байт гаммы.
struct Decrypt {
    cipher: Cipher,
}

enum Cipher {
    A128(ctr::Ctr128BE<aes::Aes128>),
    A192(ctr::Ctr128BE<aes::Aes192>),
    A256(ctr::Ctr128BE<aes::Aes256>),
}

impl Decrypt {
    fn new(key_hex: &str, offset: u64) -> Option<Decrypt> {
        use aes::cipher::KeyIvInit;
        let key = hex::decode(key_hex.trim()).ok()?;
        let block = offset / 16;
        let skip = (offset % 16) as usize;
        let mut iv = [0u8; 16];
        iv[8..].copy_from_slice(&block.to_be_bytes());
        let mut cipher = match key.len() {
            16 => Cipher::A128(ctr::Ctr128BE::<aes::Aes128>::new_from_slices(&key, &iv).ok()?),
            24 => Cipher::A192(ctr::Ctr128BE::<aes::Aes192>::new_from_slices(&key, &iv).ok()?),
            32 => Cipher::A256(ctr::Ctr128BE::<aes::Aes256>::new_from_slices(&key, &iv).ok()?),
            _ => return None,
        };
        if skip > 0 {
            let mut junk = [0u8; 16];
            cipher.apply(&mut junk[..skip]);
        }
        Some(Decrypt { cipher })
    }

    fn apply(&mut self, buf: &mut [u8]) {
        self.cipher.apply(buf);
    }
}

impl Cipher {
    fn apply(&mut self, buf: &mut [u8]) {
        use aes::cipher::StreamCipher;
        match self {
            Cipher::A128(c) => c.apply_keystream(buf),
            Cipher::A192(c) => c.apply_keystream(buf),
            Cipher::A256(c) => c.apply_keystream(buf),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes::cipher::{KeyIvInit, StreamCipher};

    #[test]
    fn sign_matches_the_web_client() {
        // Вектор посчитан независимой реализацией (python hmac/sha256).
        assert_eq!(
            sign(1_750_000_000, "123456", "nq", "flac,mp3", "raw"),
            "5M4S5dJWQD53S33wWK5zx+IIxTWBR03789L488fic9w"
        );
    }

    #[test]
    fn decrypt_starts_at_byte_offset() {
        let key: [u8; 16] = *b"0123456789abcdef";
        let iv = [0u8; 16];
        let plain: Vec<u8> = (0..64u8).collect();
        let mut cipher = ctr::Ctr128BE::<aes::Aes128>::new_from_slices(&key, &iv).unwrap();
        let mut encrypted = plain.clone();
        cipher.apply_keystream(&mut encrypted);

        let hex_key: String = key.iter().map(|b| format!("{b:02x}")).collect();
        for offset in [0u64, 5, 16, 33] {
            let mut d = Decrypt::new(&hex_key, offset).unwrap();
            let mut part = encrypted[offset as usize..].to_vec();
            d.apply(&mut part);
            assert_eq!(part, &plain[offset as usize..], "смещение {offset}");
        }
    }
}
