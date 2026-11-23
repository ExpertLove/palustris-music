//! Обложки через наш домен. В России `i.ytimg.com` и `googleusercontent.com` у части операторов
//! не открываются без VPN, поэтому клиенты просят картинку у нас, а мы кэшируем её на диске.
//!
//! Открытый прокси только для известных хостов обложек, без редиректов и с лимитом размера.

use axum::body::Body;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::{Duration, SystemTime};

const MAX_BYTES: u64 = 5 * 1024 * 1024;
/// Сколько дней храним; обложки не меняются.
const KEEP: Duration = Duration::from_secs(30 * 86400);

fn client() -> &'static reqwest::Client {
    static C: OnceLock<reqwest::Client> = OnceLock::new();
    C.get_or_init(|| {
        reqwest::Client::builder()
            // Редирект мог бы увести на внутренний адрес — обложкам он не нужен.
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(6))
            .read_timeout(Duration::from_secs(15))
            .build()
            .expect("image http client")
    })
}

/// Чужие адреса не проксируем: иначе сервер станет открытым прокси.
pub fn allowed(u: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(u) else { return false };
    if url.scheme() != "https" || u.len() > 2048 {
        return false;
    }
    let host = url.host_str().unwrap_or("");
    let known = host == "i.ytimg.com"
        || host == "lh3.googleusercontent.com"
        || host.ends_with(".googleusercontent.com")
        || host.ends_with(".ggpht.com");
    known && url.path().len() > 1
}

pub async fn serve(dir: &Path, u: &str) -> Response {
    if !allowed(u) {
        return (StatusCode::BAD_REQUEST, "не обложка").into_response();
    }
    let key = format!("{:x}", Sha256::digest(u.as_bytes()));
    let path = dir.join("img").join(&key);
    if let Some(resp) = cached(&path) {
        return resp;
    }
    match fetch_and_store(&path, u).await {
        Ok(bytes) => image_response(bytes, StatusCode::OK),
        Err(e) => {
            eprintln!("img {u}: {e}");
            (StatusCode::BAD_GATEWAY, "обложка не загрузилась").into_response()
        }
    }
}

fn cached(path: &Path) -> Option<Response> {
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() == 0 || meta.len() > MAX_BYTES {
        return None;
    }
    if let Ok(at) = meta.modified() {
        if at.elapsed().map(|d| d > KEEP).unwrap_or(false) {
            return None;
        }
    }
    // Продлеваем жизнь часто используемым обложкам.
    let _ = std::fs::File::options().write(true).open(path).and_then(|f| f.set_modified(SystemTime::now()));
    let bytes = std::fs::read(path).ok()?;
    Some(image_response(bytes, StatusCode::OK))
}

async fn fetch_and_store(path: &Path, u: &str) -> Result<Vec<u8>, String> {
    let r = client().get(u).send().await.map_err(|e| e.to_string())?;
    if !r.status().is_success() {
        return Err(format!("HTTP {}", r.status()));
    }
    if let Some(len) = r.content_length() {
        if len > MAX_BYTES {
            return Err(format!("слишком большая: {len} байт"));
        }
    }
    let ct = r.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    let bytes = r.bytes().await.map_err(|e| e.to_string())?;
    if bytes.len() as u64 > MAX_BYTES {
        return Err("слишком большая".into());
    }
    if !ct.starts_with("image/") && !looks_like_image(&bytes) {
        return Err(format!("не картинка: {ct}"));
    }
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    // Пишем атомарно: параллельный запрос не прочитает половину файла.
    let tmp = path.with_extension(format!("tmp{}", std::process::id()));
    if std::fs::write(&tmp, &bytes).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    } else {
        let _ = std::fs::remove_file(&tmp);
    }
    Ok(bytes.to_vec())
}

fn looks_like_image(b: &[u8]) -> bool {
    b.starts_with(&[0xFF, 0xD8, 0xFF])
        || b.starts_with(b"\x89PNG")
        || (b.len() >= 12 && &b[0..4] == b"RIFF" && &b[8..12] == b"WEBP")
        || b.starts_with(b"GIF8")
}

fn image_response(bytes: Vec<u8>, status: StatusCode) -> Response {
    let ct = if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        "image/jpeg"
    } else if bytes.starts_with(b"\x89PNG") {
        "image/png"
    } else if bytes.len() > 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "image/webp"
    } else if bytes.starts_with(b"GIF8") {
        "image/gif"
    } else {
        "application/octet-stream"
    };
    let mut resp = Response::new(Body::from(bytes));
    *resp.status_mut() = status;
    let h = resp.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(ct));
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("public, max-age=2592000, immutable"));
    resp
}

/// Удаляет самые старые обложки, если папка больше лимита. Вызывается из обслуживания.
pub fn gc(dir: &Path, max_bytes: u64) -> (usize, u64) {
    let img = dir.join("img");
    let mut files: Vec<(SystemTime, u64, PathBuf)> = Vec::new();
    let mut total = 0u64;
    let Ok(rd) = std::fs::read_dir(&img) else { return (0, 0) };
    for e in rd.flatten() {
        let Ok(m) = e.metadata() else { continue };
        if !m.is_file() {
            continue;
        }
        let at = m.modified().unwrap_or(SystemTime::UNIX_EPOCH);
        total += m.len();
        files.push((at, m.len(), e.path()));
    }
    let mut removed = 0;
    if total > max_bytes {
        files.sort_by_key(|f| f.0);
        for (_, len, p) in files {
            if total <= max_bytes {
                break;
            }
            if std::fs::remove_file(&p).is_ok() {
                total -= len;
                removed += 1;
            }
        }
    }
    (removed, total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_image_hosts() {
        assert!(allowed("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"));
        assert!(allowed("https://lh3.googleusercontent.com/abc=w120-h120"));
        assert!(allowed("https://yt3.ggpht.com/xyz"));
        assert!(!allowed("https://example.com/x.jpg"));
        assert!(!allowed("http://i.ytimg.com/vi/x/hqdefault.jpg"));
        assert!(!allowed("https://i.ytimg.com.evil.com/x.jpg"));
        assert!(!allowed("file:///etc/passwd"));
    }

    #[test]
    fn sniffs_common_image_headers() {
        assert!(looks_like_image(&[0xFF, 0xD8, 0xFF, 0x00]));
        assert!(looks_like_image(b"\x89PNG\r\n"));
        assert!(looks_like_image(b"RIFF\0\0\0\0WEBP"));
        assert!(!looks_like_image(b"<html>"));
    }
}
