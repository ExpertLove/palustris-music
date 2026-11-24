//! Импорт плейлистов из других сервисов: ссылка или список строк.
//!
//! Ссылки: Яндекс Музыка (токен из `YANDEX_MUSIC_TOKEN`), Spotify (публичная embed-страница),
//! Apple Music (JSON на странице), Deezer (открытый API), YouTube Music (наш же каталог).
//! Остальные сервисы требуют токенов, для них — вставка списком.

use crate::db::Track;
use crate::yt;
use futures_util::stream::{self, StreamExt};
use serde::Serialize;
use serde_json::Value;

pub const MAX_ITEMS: usize = 200;

#[derive(Clone, Debug, Serialize)]
pub struct Wanted {
    pub artist: String,
    pub title: String,
    #[serde(default)]
    pub duration: i64,
}

#[derive(Debug, Serialize)]
pub struct Preview {
    pub name: String,
    pub total: usize,
    pub matched: Vec<Track>,
    pub missing: Vec<Wanted>,
}

fn norm(s: &str) -> String {
    s.to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// Строки «Исполнитель — Название» или CSV Spotify («Название, Исполнитель»).
pub fn parse_text(text: &str) -> Vec<Wanted> {
    let mut out = Vec::new();
    for raw in text.lines().take(2000) {
        let line = raw.trim().trim_start_matches(['-', '*', '•']).trim();
        if line.is_empty() || line.len() > 300 {
            continue;
        }
        if let Some(w) = parse_line(line) {
            out.push(w);
            if out.len() >= MAX_ITEMS {
                break;
            }
        }
    }
    out
}

fn parse_line(line: &str) -> Option<Wanted> {
    // CSV Spotify: первая колонка — трек, вторая — исполнитель.
    if line.contains(',') && (line.contains('"') || line.matches(',').count() == 1) {
        let parts: Vec<&str> = line.split(',').map(|p| p.trim().trim_matches('"').trim()).collect();
        if parts.len() >= 2
            && !parts[0].is_empty()
            && !parts[1].is_empty()
            && !parts[0].eq_ignore_ascii_case("track name")
            && !parts[0].eq_ignore_ascii_case("название")
        {
            return Some(Wanted { artist: parts[1].to_string(), title: parts[0].to_string(), duration: 0 });
        }
    }
    for sep in [" — ", " – ", " - ", "\t", " —", " –", " -"] {
        if let Some((a, t)) = line.split_once(sep) {
            let (a, t) = (a.trim(), t.trim());
            if !a.is_empty() && !t.is_empty() {
                return Some(Wanted { artist: a.to_string(), title: t.to_string(), duration: 0 });
            }
        }
    }
    None
}

struct Resolved {
    name: String,
    items: Vec<Wanted>,
    /// Уже готовые треки (YouTube Music): искать не нужно.
    tracks: Vec<Track>,
}

pub async fn resolve(http: &reqwest::Client, link: &str) -> Result<(String, Vec<Wanted>, Vec<Track>), String> {
    let u = reqwest::Url::parse(link.trim()).map_err(|_| "это не ссылка".to_string())?;
    let host = u.host_str().unwrap_or("").to_lowercase();
    let r = if host.contains("music.yandex") {
        yandex(&u).await?
    } else if host.contains("open.spotify.com") || host.contains("spotify.link") {
        spotify(http, &u).await?
    } else if host.contains("music.apple.com") {
        apple(http, &u).await?
    } else if host.contains("deezer.com") {
        deezer(http, &u).await?
    } else if host.contains("music.youtube.com") || host.contains("youtube.com") || host.contains("youtu.be") {
        youtube(http, &u).await?
    } else if host.contains("vk.com") {
        return Err("ВКонтакте закрывает плейлисты без входа. Экспортируй список и вставь текстом".into());
    } else if host.contains("soundcloud.com") {
        return Err("SoundCloud не отдаёт плейлист без ключа. Вставь список текстом".into());
    } else {
        return Err("этот сервис пока не поддерживается — вставь список строк".into());
    };
    Ok((r.name, r.items, r.tracks))
}

// ───────────────────────── Яндекс ─────────────────────────

async fn yandex(u: &reqwest::Url) -> Result<Resolved, String> {
    let parts: Vec<&str> = u.path_segments().map(|s| s.collect()).unwrap_or_default();
    let path = match parts.as_slice() {
        ["users", owner, "playlists", kind] => format!("users/{owner}/playlists/{kind}"),
        ["playlists", uuid] => format!("playlist/{uuid}"),
        _ => return Err("нужна ссылка вида music.yandex.ru/users/<кто>/playlists/<номер>".into()),
    };
    let v = crate::yandex::api(&path, &[], true).await?;
    // У разных форм ответ либо {result:{…}}, либо сам объект.
    let p = if v["result"].is_object() { &v["result"] } else { &v };
    let tracks = p["tracks"].as_array().ok_or("в плейлисте нет треков (или он закрыт)")?;
    let name = p["title"].as_str().unwrap_or("Импорт из Яндекса").to_string();
    let mut items = Vec::new();
    let mut ids = Vec::new();
    for t in tracks.iter().take(MAX_ITEMS) {
        // «Мне нравится» отдаёт трек целиком, обычный плейлист — только id и albumId.
        let full = t.get("track").filter(|x| x["title"].is_string());
        if let Some(tr) = full {
            let title = tr["title"].as_str().unwrap_or("").trim();
            let artist = tr["artists"]
                .as_array()
                .map(|a| a.iter().filter_map(|x| x["name"].as_str()).collect::<Vec<_>>().join(", "))
                .unwrap_or_default();
            if !title.is_empty() && !artist.is_empty() {
                items.push(Wanted {
                    artist,
                    title: title.to_string(),
                    duration: tr["durationMs"].as_i64().unwrap_or(0) / 1000,
                });
                continue;
            }
        }
        let num = |v: &Value| v.as_i64().or_else(|| v.as_str().and_then(|s| s.parse().ok()));
        let id = num(&t["id"]).or_else(|| num(&t["track"]["id"]));
        let album = num(&t["albumId"]).or_else(|| num(&t["track"]["albumId"]));
        if let (Some(id), Some(album)) = (id, album) {
            ids.push(format!("{id}:{album}"));
        }
    }
    for chunk in ids.chunks(100) {
        let v = crate::yandex::api("tracks", &[("trackIds", chunk.join(","))], true).await?;
        for t in v["result"].as_array().into_iter().flatten() {
            let title = t["title"].as_str().unwrap_or("").trim();
            let artist = t["artists"]
                .as_array()
                .map(|a| a.iter().filter_map(|x| x["name"].as_str()).collect::<Vec<_>>().join(", "))
                .unwrap_or_default();
            if !title.is_empty() && !artist.is_empty() {
                items.push(Wanted {
                    artist,
                    title: title.to_string(),
                    duration: t["durationMs"].as_i64().unwrap_or(0) / 1000,
                });
            }
        }
    }
    if items.is_empty() {
        return Err("в плейлисте Яндекса не нашлось треков".into());
    }
    Ok(Resolved { name, items, tracks: Vec::new() })
}

// ───────────────────────── Spotify ─────────────────────────

async fn spotify(http: &reqwest::Client, u: &reqwest::Url) -> Result<Resolved, String> {
    let parts: Vec<&str> = u.path_segments().map(|s| s.collect()).unwrap_or_default();
    let (kind, id) = match parts.as_slice() {
        [kind, id, ..] if *kind == "playlist" || *kind == "album" => (*kind, *id),
        _ => return Err("нужна ссылка на плейлист Spotify".into()),
    };
    let url = format!("https://open.spotify.com/embed/{kind}/{id}");
    let html = fetch(http, &url).await?;
    let json = html
        .split_once("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
        .and_then(|(_, r)| r.split_once("</script>"))
        .map(|(j, _)| j)
        .ok_or("Spotify не отдал данные плейлиста")?;
    let v: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let p = find_holder(&v, "trackList").ok_or("в ответе Spotify нет списка треков")?;
    let name = p["name"].as_str().unwrap_or("Импорт из Spotify").to_string();
    let list = p["trackList"].as_array().ok_or("пустой плейлист")?;
    let items = list
        .iter()
        .take(MAX_ITEMS)
        .filter_map(|t| {
            let title = t["title"].as_str()?.trim();
            let artist = t["subtitle"].as_str()?.trim();
            if title.is_empty() || artist.is_empty() {
                return None;
            }
            Some(Wanted {
                artist: artist.to_string(),
                title: title.to_string(),
                duration: t["duration"].as_i64().unwrap_or(0) / 1000,
            })
        })
        .collect();
    Ok(Resolved { name, items, tracks: Vec::new() })
}

// ───────────────────────── Apple Music ─────────────────────────

async fn apple(http: &reqwest::Client, u: &reqwest::Url) -> Result<Resolved, String> {
    let html = fetch(http, u.as_str()).await?;
    let name = ld_playlist_name(&html).unwrap_or_else(|| "Импорт из Apple Music".into());
    let json = html
        .split_once("id=\"serialized-server-data\"")
        .and_then(|(_, r)| r.split_once(">"))
        .and_then(|(_, r)| r.split_once("</script>"))
        .map(|(j, _)| j)
        .ok_or("Apple не отдал данные плейлиста")?;
    let v: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let mut tracks = Vec::new();
    collect_apple(&v, &mut tracks);
    let items = tracks
        .into_iter()
        .take(MAX_ITEMS)
        .map(|(artist, title, ms)| Wanted { artist, title, duration: ms / 1000 })
        .collect();
    Ok(Resolved { name, items, tracks: Vec::new() })
}

/// Имя плейлиста из JSON-LD (`application/ld+json`), не полагаясь на кавычки в атрибуте id.
fn ld_playlist_name(html: &str) -> Option<String> {
    let mut rest = html;
    while let Some(i) = rest.find("application/ld+json") {
        let after = &rest[i..];
        let start = after.find('>')? + 1;
        let end = after[start..].find("</script>")? + start;
        if let Ok(v) = serde_json::from_str::<Value>(&after[start..end]) {
            if v["@type"] == "MusicPlaylist" {
                return v["name"].as_str().map(str::to_string);
            }
        }
        rest = &after[end..];
    }
    None
}

fn collect_apple(v: &Value, out: &mut Vec<(String, String, i64)>) {
    match v {
        Value::Object(o) => {
            if let (Some(title), Some(artist)) = (o.get("title").and_then(Value::as_str), o.get("artistName").and_then(Value::as_str)) {
                let ms = o.get("duration").and_then(Value::as_i64).unwrap_or(0);
                if !title.is_empty() && !artist.is_empty() {
                    out.push((artist.to_string(), title.to_string(), ms));
                }
            }
            for x in o.values() {
                collect_apple(x, out);
            }
        }
        Value::Array(a) => {
            for x in a {
                collect_apple(x, out);
            }
        }
        _ => {}
    }
}

// ───────────────────────── Deezer ─────────────────────────

async fn deezer(http: &reqwest::Client, u: &reqwest::Url) -> Result<Resolved, String> {
    let parts: Vec<&str> = u.path_segments().map(|s| s.collect()).unwrap_or_default();
    let at = parts
        .iter()
        .position(|p| *p == "playlist" || *p == "album")
        .ok_or("нужна ссылка на плейлист Deezer")?;
    let kind = parts[at];
    let id = parts.get(at + 1).ok_or("в ссылке нет id плейлиста")?;
    let url = format!("https://api.deezer.com/{kind}/{id}");
    let v: Value = fetch_json(http, &url).await?;
    if v.get("error").is_some() {
        return Err("Deezer не отдал плейлист".into());
    }
    let name = v["title"].as_str().unwrap_or("Импорт из Deezer").to_string();
    let list = v["tracks"]["data"].as_array().ok_or("пустой плейлист")?;
    let items = list
        .iter()
        .take(MAX_ITEMS)
        .filter_map(|t| {
            let title = t["title"].as_str()?.trim();
            let artist = t["artist"]["name"].as_str()?.trim();
            (!title.is_empty() && !artist.is_empty()).then(|| Wanted {
                artist: artist.to_string(),
                title: title.to_string(),
                duration: t["duration"].as_i64().unwrap_or(0),
            })
        })
        .collect();
    Ok(Resolved { name, items, tracks: Vec::new() })
}

// ───────────────────────── YouTube Music ─────────────────────────

async fn youtube(http: &reqwest::Client, u: &reqwest::Url) -> Result<Resolved, String> {
    let list = u
        .query_pairs()
        .find(|(k, _)| k == "list")
        .map(|(_, v)| v.to_string())
        .ok_or("в ссылке нет параметра list")?;
    let c = yt::collection(http, &list).await.map_err(|e| e.to_string())?;
    Ok(Resolved {
        name: if c.title.is_empty() { "Импорт из YouTube Music".into() } else { c.title },
        items: Vec::new(),
        tracks: c.tracks.into_iter().take(MAX_ITEMS).collect(),
    })
}

// ───────────────────────── поиск и сверка ─────────────────────────

pub async fn preview(http: &reqwest::Client, link: Option<&str>, text: &str) -> Result<Preview, String> {
    let (name, items, ready) = match link.map(str::trim).filter(|l| !l.is_empty()) {
        Some(l) => resolve(http, l).await?,
        None => (String::new(), parse_text(text), Vec::new()),
    };
    if items.is_empty() && ready.is_empty() {
        return Err("не нашлось ни одного трека".into());
    }
    let total = items.len() + ready.len();
    let pairs: Vec<(Wanted, Option<Track>)> = stream::iter(items.into_iter())
        .map(|w| async move {
            let t = best(http, &w).await;
            (w, t)
        })
        .buffered(4)
        .collect()
        .await;
    let mut matched = ready;
    let mut missing = Vec::new();
    for (w, t) in pairs {
        match t {
            Some(t) => matched.push(t),
            None => missing.push(w),
        }
    }
    // Один и тот же трек мог встретиться дважды.
    let mut seen = std::collections::HashSet::new();
    matched.retain(|t| seen.insert(t.id.clone()));
    Ok(Preview {
        name: if name.is_empty() { "Импорт".into() } else { name },
        total,
        matched,
        missing,
    })
}

/// Совпадение оцениваем по названию, исполнителю и длительности; чужой артист без
/// подходящей длительности — не совпадение.
fn score(w: &Wanted, t: &Track) -> Option<i32> {
    let want_t = norm(&w.title);
    if want_t.is_empty() {
        return None;
    }
    let got_t = norm(&t.title);
    let title = if got_t == want_t {
        100
    } else if got_t.contains(&want_t) || want_t.contains(&got_t) {
        60
    } else {
        return None;
    };
    let want_a = norm(&w.artist);
    let got_a = norm(&t.artist);
    let artist = if want_a.is_empty() {
        0
    } else if got_a == want_a {
        50
    } else if got_a.contains(&want_a) || want_a.contains(&got_a) {
        30
    } else {
        -100
    };
    let dur = if w.duration > 0 && t.duration > 0 {
        let d = (w.duration - t.duration).abs();
        if d <= 5 {
            20
        } else if d <= 15 {
            10
        } else {
            -20
        }
    } else {
        0
    };
    if artist < 0 && dur <= 0 {
        return None;
    }
    Some(title + artist + dur)
}

async fn best(http: &reqwest::Client, w: &Wanted) -> Option<Track> {
    let q = if w.artist.is_empty() {
        w.title.clone()
    } else {
        format!("{} {}", w.artist, w.title)
    };
    let list = yt::search_songs(http, &q).await.ok()?;
    list.into_iter()
        .filter_map(|t| score(w, &t).map(|s| (s, t)))
        .max_by_key(|(s, _)| *s)
        .filter(|(s, _)| *s >= 90)
        .map(|(_, t)| t)
}

/// Объект, в котором лежит ключ (например, плейлист Spotify с `trackList`).
fn find_holder<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    match v {
        Value::Object(o) => {
            if o.contains_key(key) {
                return Some(v);
            }
            o.values().find_map(|x| find_holder(x, key))
        }
        Value::Array(a) => a.iter().find_map(|x| find_holder(x, key)),
        _ => None,
    }
}

async fn fetch(http: &reqwest::Client, url: &str) -> Result<String, String> {
    let r = http
        .get(url)
        .header(reqwest::header::ACCEPT_LANGUAGE, "ru,en;q=0.8")
        .timeout(std::time::Duration::from_secs(20))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !r.status().is_success() {
        return Err(format!("HTTP {}", r.status()));
    }
    r.text().await.map_err(|e| e.to_string())
}

async fn fetch_json(http: &reqwest::Client, url: &str) -> Result<Value, String> {
    let r = http
        .get(url)
        .timeout(std::time::Duration::from_secs(15))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !r.status().is_success() {
        return Err(format!("HTTP {}", r.status()));
    }
    r.json().await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(title: &str, artist: &str, duration: i64) -> Track {
        Track { id: "id".into(), title: title.into(), artist: artist.into(), album: None, duration, thumb: None, artist_id: None, album_id: None }
    }

    #[test]
    fn parses_lists_and_csv() {
        let list = parse_text("Кино — Группа крови\nQueen - Bohemian Rhapsody\n\nMetallica\tOne\n\"Creep\",\"Radiohead\"");
        assert_eq!(list.len(), 4);
        assert_eq!((list[0].artist.as_str(), list[0].title.as_str()), ("Кино", "Группа крови"));
        assert_eq!((list[1].artist.as_str(), list[1].title.as_str()), ("Queen", "Bohemian Rhapsody"));
        assert_eq!((list[3].artist.as_str(), list[3].title.as_str()), ("Radiohead", "Creep"));
    }

    #[test]
    fn rejects_lines_without_pair() {
        assert!(parse_text("просто текст без исполнителя").is_empty());
    }

    #[test]
    fn matches_same_song_and_rejects_cover() {
        let w = Wanted { artist: "Radiohead".into(), title: "Creep".into(), duration: 238 };
        assert!(score(&w, &t("Creep", "Radiohead", 237)).unwrap() >= 140);
        assert!(score(&w, &t("Creep", "Some Cover Band", 210)).is_none());
        assert!(score(&w, &t("Creep", "Karaoke Hits", 238)).is_some());
    }
}
