use crate::db::Track;
use anyhow::{bail, Result};
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

const CLIENT_VERSION: &str = "1.20240101.01.00";
const TTL: Duration = Duration::from_secs(12 * 3600);

#[derive(Default)]
pub struct SearchCache {
    map: Mutex<HashMap<String, (Instant, Vec<Track>)>>,
}

impl SearchCache {
    pub async fn search(&self, http: &Client, q: &str) -> Vec<Track> {
        let key = q.to_lowercase();
        {
            let m = self.map.lock().unwrap();
            if let Some((at, v)) = m.get(&key) {
                if at.elapsed() < TTL {
                    return v.clone();
                }
            }
        }
        let v = search(http, q).await.unwrap_or_default();
        if !v.is_empty() {
            self.map.lock().unwrap().insert(key, (Instant::now(), v.clone()));
        }
        v
    }
}

pub async fn search(http: &Client, q: &str) -> Result<Vec<Track>> {
    let body = json!({
        "context": {"client": {
            "clientName": "WEB_REMIX",
            "clientVersion": CLIENT_VERSION,
            "hl": "ru",
            "gl": "DE"
        }},
        "query": q
    });
    let v: Value = http
        .post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
        .header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/")
        .json(&body)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let mut raw = Vec::new();
    collect(&v, &mut raw);
    Ok(raw.iter().filter_map(parse_item).collect())
}

fn collect(v: &Value, out: &mut Vec<Value>) {
    match v {
        Value::Object(m) => {
            if let Some(r) = m.get("musicResponsiveListItemRenderer") {
                out.push(r.clone());
                return;
            }
            for x in m.values() {
                collect(x, out);
            }
        }
        Value::Array(a) => {
            for x in a {
                collect(x, out);
            }
        }
        _ => {}
    }
}

fn runs_text(v: &Value) -> String {
    v["text"]["runs"]
        .as_array()
        .map(|r| {
            r.iter()
                .filter_map(|x| x["text"].as_str())
                .collect::<Vec<_>>()
                .join("")
        })
        .unwrap_or_default()
}

fn parse_duration(s: &str) -> Option<i64> {
    let parts: Vec<&str> = s.split(':').collect();
    if parts.len() < 2 || parts.len() > 3 {
        return None;
    }
    let mut secs = 0i64;
    for p in parts {
        if p.is_empty() || !p.chars().all(|c| c.is_ascii_digit()) {
            return None;
        }
        secs = secs * 60 + p.parse::<i64>().ok()?;
    }
    (secs > 0).then_some(secs)
}

fn parse_item(it: &Value) -> Option<Track> {
    let cols = it["flexColumns"].as_array()?;
    let title = runs_text(&cols.first()?["musicResponsiveListItemFlexColumnRenderer"]).trim().to_string();
    if title.is_empty() {
        return None;
    }

    let id = it["overlay"]["musicItemThumbnailOverlayRenderer"]["content"]["musicPlayButtonRenderer"]
        ["playNavigationEndpoint"]["watchEndpoint"]["videoId"]
        .as_str()
        .or_else(|| {
            cols.first()?["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"]
                .as_array()?
                .first()?["navigationEndpoint"]["watchEndpoint"]["videoId"]
                .as_str()
        })?
        .to_string();

    let sub_runs = cols
        .get(1)
        .and_then(|c| c["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"].as_array())
        .cloned()
        .unwrap_or_default();

    let mut artist = None;
    let mut album = None;
    let mut duration = 0;
    for r in &sub_runs {
        let text = r["text"].as_str().unwrap_or("");
        if let Some(d) = parse_duration(text) {
            duration = d;
            continue;
        }
        if let Some(bid) = r["navigationEndpoint"]["browseEndpoint"]["browseId"].as_str() {
            if bid.starts_with("UC") && artist.is_none() {
                artist = Some(text.to_string());
            } else if bid.starts_with("MPRE") && album.is_none() {
                album = Some(text.to_string());
            }
        }
    }
    let artist = artist?;

    let thumb = it["thumbnail"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"]
        .as_array()
        .and_then(|a| a.last())
        .and_then(|t| t["url"].as_str())
        .map(|u| match u.find("=w") {
            Some(i) => format!("{}=w300-h300-l90-rj", &u[..i]),
            None => u.to_string(),
        });

    Some(Track { id, title, artist, album, duration, thumb })
}

/// Глобальный недельный чарт треков с charts.youtube.com.
/// Недельный песенный чарт. `region` — ISO-код страны (RU, US…), пусто — весь мир.
pub async fn chart_top(http: &Client, region: &str) -> Result<Vec<Track>> {
    let gl = if region.is_empty() { "001" } else { region };
    let body = json!({
        "context": {
            "client": {
                "clientName": "WEB_MUSIC_ANALYTICS",
                "clientVersion": "2.0",
                "hl": "ru",
                "gl": gl,
                "theme": "MUSIC"
            },
            "capabilities": {},
            "request": {"internalExperimentFlags": []}
        },
        "browseId": "FEmusic_analytics_charts_home"
    });
    let v: Value = http
        .post("https://charts.youtube.com/youtubei/v1/browse?alt=json")
        .header("Origin", "https://charts.youtube.com")
        .header("Referer", "https://charts.youtube.com/")
        .json(&body)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let items = v["contents"]["sectionListRenderer"]["contents"][0]["musicAnalyticsSectionRenderer"]
        ["content"]["trackTypes"][0]["trackViews"]
        .as_array()
        .cloned()
        .unwrap_or_default();

    Ok(items.iter().filter_map(parse_chart_track).take(50).collect())
}

fn parse_chart_track(t: &Value) -> Option<Track> {
    let title = t["name"].as_str()?.trim().to_string();
    if title.is_empty() {
        return None;
    }
    // atvExternalVideoId — альбомная аудиодорожка, encryptedVideoId — клип.
    let id = t["atvExternalVideoId"]
        .as_str()
        .filter(|s| !s.is_empty())
        .or_else(|| t["encryptedVideoId"].as_str().filter(|s| !s.is_empty()))?
        .to_string();
    let artist = t["artists"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|x| x["name"].as_str())
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default();
    if artist.is_empty() {
        return None;
    }
    let thumb = t["encryptedVideoId"]
        .as_str()
        .map(|v| format!("https://i.ytimg.com/vi/{v}/hqdefault.jpg"));
    Some(Track { id, title, artist, album: None, duration: 0, thumb })
}

pub async fn ensure_audio(dir: &Path, id: &str, ytdlp: &str) -> Result<PathBuf> {
    if id.is_empty()
        || id.len() > 20
        || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        bail!("bad id");
    }
    tokio::fs::create_dir_all(dir).await?;
    let exts = ["m4a", "webm", "opus", "mp3"];
    for ext in exts {
        let p = dir.join(format!("{id}.{ext}"));
        if tokio::fs::metadata(&p).await.map(|m| m.len() > 0).unwrap_or(false) {
            return Ok(p);
        }
    }

    // YouTube иногда отвечает «Sign in to confirm you're not a bot». Это не свойство трека:
    // повтор с другого хоста и через паузу обычно проходит.
    let cookies = std::env::var("MUSIC_COOKIES").unwrap_or_default();
    let use_cookies = !cookies.is_empty() && Path::new(&cookies).exists();
    // Последние две попытки — клиенты с PO-токеном от bgutil-provider (127.0.0.1:4416),
    // они проходят проверку «not a bot», когда обычные запросы режутся.
    let sources: [(String, Option<&str>); 4] = [
        (format!("https://music.youtube.com/watch?v={id}"), None),
        (format!("https://www.youtube.com/watch?v={id}"), None),
        (format!("https://www.youtube.com/watch?v={id}"), Some("youtube:player_client=mweb")),
        (format!("https://www.youtube.com/watch?v={id}"), Some("youtube:player_client=tv_simply")),
    ];
    let mut last = String::new();
    for (i, (url, extractor)) in sources.iter().enumerate() {
        if i > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(700 * i as u64)).await;
        }
        let mut cmd = tokio::process::Command::new(ytdlp);
        cmd.args([
            "-f",
            "bestaudio[ext=m4a]/bestaudio",
            "--no-playlist",
            "--no-warnings",
            "--no-progress",
            "--socket-timeout",
            "15",
            "--retries",
            "2",
        ]);
        if use_cookies {
            cmd.args(["--cookies", &cookies]);
        }
        if let Some(e) = extractor {
            cmd.args(["--extractor-args", e]);
        }
        let out = cmd
            .arg("-o")
            .arg(dir.join(format!("{id}.%(ext)s")).to_string_lossy().to_string())
            .arg(url)
            .output()
            .await?;
        if out.status.success() {
            for ext in exts {
                let p = dir.join(format!("{id}.{ext}"));
                if tokio::fs::metadata(&p).await.map(|m| m.len() > 0).unwrap_or(false) {
                    return Ok(p);
                }
            }
        }
        last = String::from_utf8_lossy(&out.stderr).trim().to_string();
        eprintln!("yt-dlp attempt {} for {id} failed: {}", i + 1, last.lines().last().unwrap_or(""));
    }
    bail!("yt-dlp: {last}")
}
