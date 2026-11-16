//! Тексты песен из LRCLIB (lrclib.net) с кэшем в SQLite.
use reqwest::Client;
use rusqlite::{params, Connection};
use serde::Serialize;
use serde_json::Value;
use std::sync::Mutex;

#[derive(Serialize, Default, Clone)]
pub struct Lyrics {
    pub synced: Option<String>,
    pub plain: Option<String>,
    pub instrumental: bool,
    pub found: bool,
}

/// «Instant Crush (feat. Julian Casablancas) [Official Video]» → «Instant Crush».
pub fn clean_title(t: &str) -> String {
    let mut out = String::new();
    let mut depth = 0i32;
    for c in t.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    let out = out.split(" - ").last().unwrap_or(&out).to_string();
    let lower = out.to_lowercase();
    let cut = [" feat.", " ft.", " feat ", " featuring "]
        .iter()
        .filter_map(|k| lower.find(k))
        .min()
        .unwrap_or(out.len());
    out[..cut].trim().to_string()
}

fn first_artist(a: &str) -> String {
    a.split([',', '&']).next().unwrap_or(a).split(" и ").next().unwrap_or(a).trim().to_string()
}

fn from_json(v: &Value) -> Lyrics {
    let synced = v["syncedLyrics"].as_str().filter(|s| !s.trim().is_empty()).map(str::to_string);
    let plain = v["plainLyrics"].as_str().filter(|s| !s.trim().is_empty()).map(str::to_string);
    let instrumental = v["instrumental"].as_bool().unwrap_or(false);
    let found = synced.is_some() || plain.is_some() || instrumental;
    Lyrics { synced, plain, instrumental, found }
}

pub async fn get(db: &Mutex<Connection>, http: &Client, artist: &str, title: &str, duration: i64) -> Lyrics {
    let artist = first_artist(artist);
    let title = clean_title(title);
    if artist.is_empty() || title.is_empty() {
        return Lyrics::default();
    }
    let key = format!("{}\u{1}{}", artist.to_lowercase(), title.to_lowercase());
    let now = crate::recs::now();
    if let Ok(c) = db.lock() {
        let row = c.query_row(
            "SELECT synced, plain, instrumental, found, fetched FROM lyrics WHERE key=?1",
            [&key],
            |r| {
                Ok((
                    r.get::<_, Option<String>>(0)?,
                    r.get::<_, Option<String>>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, i64>(4)?,
                ))
            },
        );
        if let Ok((synced, plain, instr, found, fetched)) = row {
            // Ненайденное перепроверяем раз в 3 дня — тексты в LRCLIB добавляются.
            if found != 0 || now - fetched < 3 * 86400 {
                return Lyrics { synced, plain, instrumental: instr != 0, found: found != 0 };
            }
        }
    }

    let mut res = Lyrics::default();
    let mut url = reqwest::Url::parse("https://lrclib.net/api/get").unwrap();
    url.query_pairs_mut().append_pair("artist_name", &artist).append_pair("track_name", &title);
    if duration > 0 {
        url.query_pairs_mut().append_pair("duration", &duration.to_string());
    }
    if let Ok(r) = http.get(url).header("User-Agent", "Muzyka (https://music.palustris.ru)").send().await {
        if r.status().is_success() {
            if let Ok(v) = r.json::<Value>().await {
                res = from_json(&v);
            }
        }
    }
    if !res.found {
        let mut url = reqwest::Url::parse("https://lrclib.net/api/search").unwrap();
        url.query_pairs_mut().append_pair("artist_name", &artist).append_pair("track_name", &title);
        if let Ok(r) = http.get(url).header("User-Agent", "Muzyka (https://music.palustris.ru)").send().await {
            if let Ok(Value::Array(list)) = r.json::<Value>().await {
                // Предпочитаем синхронизированный текст и близкую длительность.
                let best = list
                    .iter()
                    .filter(|v| v["syncedLyrics"].is_string() || v["plainLyrics"].is_string())
                    .min_by_key(|v| {
                        let d = v["duration"].as_f64().unwrap_or(0.0) as i64;
                        let off = if duration > 0 { (d - duration).abs() } else { 0 };
                        (v["syncedLyrics"].is_null() as i64) * 1000 + off.min(999)
                    });
                if let Some(v) = best {
                    let d = v["duration"].as_f64().unwrap_or(0.0) as i64;
                    if duration == 0 || (d - duration).abs() <= 15 {
                        res = from_json(v);
                    }
                }
            }
        }
    }
    if let Ok(c) = db.lock() {
        let _ = c.execute(
            "INSERT OR REPLACE INTO lyrics(key, synced, plain, instrumental, found, fetched) VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
            params![key, res.synced, res.plain, res.instrumental as i64, res.found as i64, now],
        );
    }
    res
}
