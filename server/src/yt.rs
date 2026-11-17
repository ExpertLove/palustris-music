//! Неофициальный API YouTube Music: поиск, исполнители, альбомы, плейлисты, чарты.
use crate::db::Track;
use anyhow::{bail, Result};
use reqwest::Client;
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const CLIENT_VERSION: &str = "1.20240101.01.00";
const TTL: Duration = Duration::from_secs(12 * 3600);

/// Простой кэш JSON-ответов по ключу.
pub struct Cache<T: Clone> {
    map: Mutex<HashMap<String, (Instant, T)>>,
    ttl: Duration,
}

impl<T: Clone> Cache<T> {
    pub fn new(ttl: Duration) -> Self {
        Cache { map: Mutex::new(HashMap::new()), ttl }
    }
    pub fn get(&self, k: &str) -> Option<T> {
        let m = self.map.lock().ok()?;
        m.get(k).filter(|(at, _)| at.elapsed() < self.ttl).map(|(_, v)| v.clone())
    }
    pub fn put(&self, k: &str, v: T) {
        if let Ok(mut m) = self.map.lock() {
            if m.len() > 2000 {
                let ttl = self.ttl;
                m.retain(|_, (at, _)| at.elapsed() < ttl);
            }
            m.insert(k.to_string(), (Instant::now(), v));
        }
    }
}

pub struct SearchCache {
    tracks: Cache<Vec<Track>>,
    all: Cache<SearchAll>,
    artists: Cache<ArtistPage>,
    collections: Cache<Collection>,
    /// Радио по треку меняется медленно: сутки в кэше, иначе каждая волна дёргала бы YouTube.
    radios: Cache<Vec<RadioItem>>,
}

impl Default for SearchCache {
    fn default() -> Self {
        SearchCache {
            tracks: Cache::new(TTL),
            all: Cache::new(TTL),
            artists: Cache::new(Duration::from_secs(6 * 3600)),
            collections: Cache::new(Duration::from_secs(6 * 3600)),
            radios: Cache::new(Duration::from_secs(24 * 3600)),
        }
    }
}

impl SearchCache {
    pub async fn search(&self, http: &Client, q: &str) -> Vec<Track> {
        let key = q.to_lowercase();
        if let Some(v) = self.tracks.get(&key) {
            return v;
        }
        let v = search_all(http, q).await.map(|a| a.tracks).unwrap_or_default();
        if !v.is_empty() {
            self.tracks.put(&key, v.clone());
        }
        v
    }

    pub async fn search_all(&self, http: &Client, q: &str) -> Result<SearchAll> {
        let key = q.to_lowercase();
        if let Some(v) = self.all.get(&key) {
            return Ok(v);
        }
        let v = search_all(http, q).await?;
        self.all.put(&key, v.clone());
        if !v.tracks.is_empty() {
            self.tracks.put(&key, v.tracks.clone());
        }
        Ok(v)
    }

    pub async fn artist(&self, http: &Client, id: &str) -> Result<ArtistPage> {
        if let Some(v) = self.artists.get(id) {
            return Ok(v);
        }
        let v = artist_page(http, id).await?;
        self.artists.put(id, v.clone());
        Ok(v)
    }

    pub async fn collection(&self, http: &Client, id: &str) -> Result<Collection> {
        if let Some(v) = self.collections.get(id) {
            return Ok(v);
        }
        let v = collection(http, id).await?;
        self.collections.put(id, v.clone());
        Ok(v)
    }

    /// Радио YouTube Music от трека (или от исполнителя, если передан `playlist` вида RDEM…).
    /// Ошибку не пробрасываем: пустой список — просто нет кандидатов от этого зерна.
    pub async fn radio(&self, http: &Client, video: &str, playlist: Option<&str>) -> Vec<RadioItem> {
        let key = format!("{video}|{}", playlist.unwrap_or(""));
        if let Some(v) = self.radios.get(&key) {
            return v;
        }
        match radio(http, video, playlist).await {
            Ok(v) if !v.is_empty() => {
                self.radios.put(&key, v.clone());
                v
            }
            Ok(_) => Vec::new(),
            Err(e) => {
                eprintln!("radio {video}: {e}");
                Vec::new()
            }
        }
    }
}

/// Что за ролик: песня из альбома (ATV), официальный клип (OMV) или загрузка пользователя (UGC).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VideoKind {
    Song,
    Clip,
    User,
    Other,
}

#[derive(Clone, Debug)]
pub struct RadioItem {
    pub track: Track,
    pub kind: VideoKind,
}

fn video_kind(s: Option<&str>) -> VideoKind {
    match s {
        Some("MUSIC_VIDEO_TYPE_ATV") => VideoKind::Song,
        Some("MUSIC_VIDEO_TYPE_OMV") => VideoKind::Clip,
        Some("MUSIC_VIDEO_TYPE_UGC") => VideoKind::User,
        _ => VideoKind::Other,
    }
}

pub fn valid_video_id(id: &str) -> bool {
    id.len() == 11 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Бесконечное радио YouTube Music («Up next» с `RDAMVM…`): около 50 похожих треков.
/// Тот же механизм, что у кнопки «Радио» в YouTube Music, — по совместным прослушиваниям, а не по названию.
pub async fn radio(http: &Client, video: &str, playlist: Option<&str>) -> Result<Vec<RadioItem>> {
    if !valid_video_id(video) {
        bail!("bad video id");
    }
    let pl = match playlist {
        Some(p) if valid_browse_id(p) => p.to_string(),
        _ => format!("RDAMVM{video}"),
    };
    let v = ytm(
        http,
        "next",
        json!({
            "videoId": video,
            "playlistId": pl,
            "params": "wAEB",
            "isAudioOnly": true,
            "enablePersistentPlaylistPanel": true,
            "tunerSettingValue": "AUTOMIX_SETTING_NORMAL",
            "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {
                "hasPersistentPlaylistPanel": true,
                "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
            }}
        }),
    )
    .await?;
    let mut items = Vec::new();
    collect(&v, "playlistPanelVideoRenderer", &mut items);
    let mut seen = std::collections::HashSet::new();
    Ok(items.into_iter().filter_map(parse_panel_item).filter(|r| seen.insert(r.track.id.clone())).collect())
}

/// Строка очереди «Далее»: «Исполнитель • Альбом • Год» у песен, «Исполнитель • просмотры» у клипов.
fn parse_panel_item(it: &Value) -> Option<RadioItem> {
    let id = it["videoId"].as_str()?.to_string();
    let raw_title = text(&it["title"]).trim().to_string();
    if raw_title.is_empty() {
        return None;
    }
    let kind = video_kind(
        it["navigationEndpoint"]["watchEndpoint"]["watchEndpointMusicSupportedConfigs"]["watchEndpointMusicConfig"]
            ["musicVideoType"]
            .as_str(),
    );
    let mut artists = Vec::new();
    let mut artist_id = None;
    let mut album = None;
    let mut album_id = None;
    let byline = runs(&it["longBylineText"]);
    for r in &byline {
        let t = r["text"].as_str().unwrap_or("");
        match browse_id(r) {
            Some(b) if b.starts_with("UC") => {
                artists.push(t.to_string());
                artist_id.get_or_insert_with(|| b.to_string());
            }
            Some(b) if b.starts_with("MPRE") => {
                album.get_or_insert_with(|| t.to_string());
                album_id.get_or_insert_with(|| b.to_string());
            }
            _ => {}
        }
    }
    if artists.is_empty() {
        // У загрузок пользователей исполнитель без ссылки: первый кусок до « • ».
        let first = byline.first().and_then(|r| r["text"].as_str()).unwrap_or("").trim().to_string();
        if first.is_empty() {
            return None;
        }
        artists.push(first);
    }
    let duration = parse_duration(&text(&it["lengthText"])).unwrap_or(0);
    let thumb = it["thumbnail"]["thumbnails"]
        .as_array()
        .and_then(|a| a.last())
        .and_then(|t| t["url"].as_str())
        .map(|u| thumb_size(u, 300));
    let title = if kind == VideoKind::Song { raw_title } else { clean_title(&raw_title) };
    Some(RadioItem {
        track: Track { id, title, artist: artists.join(", "), album, duration, thumb, artist_id, album_id },
        kind,
    })
}

/// «Starboy ft. Daft Punk (Official Video)» → «Starboy ft. Daft Punk»: у клипов в названии служебные приписки.
pub fn clean_title(t: &str) -> String {
    const NOISE: &[&str] = &[
        "official", "video", "audio", "lyric", "visualizer", "visualiser", "клип", "премьера", "mv", "hd", "4k",
        "текст", "lyrics",
    ];
    let mut out = String::new();
    let mut rest = t;
    while let Some(start) = rest.find(['(', '[']) {
        let close = if rest[start..].starts_with('(') { ')' } else { ']' };
        let Some(len) = rest[start..].find(close) else { break };
        let inner = rest[start + 1..start + len].to_lowercase();
        out.push_str(&rest[..start]);
        if !NOISE.iter().any(|n| inner.split(|c: char| !c.is_alphanumeric()).any(|w| w == *n)) {
            out.push_str(&rest[start..=start + len]);
        }
        rest = &rest[start + len + 1..];
    }
    out.push_str(rest);
    let s = out.split_whitespace().collect::<Vec<_>>().join(" ");
    if s.is_empty() { t.trim().to_string() } else { s }
}

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
    /// Радио исполнителя в YouTube Music: (videoId, playlistId RDEM…).
    #[serde(skip)]
    pub radio: Option<(String, String)>,
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

async fn ytm(http: &Client, endpoint: &str, body: Value) -> Result<Value> {
    let mut b = json!({
        "context": {"client": {
            "clientName": "WEB_REMIX",
            "clientVersion": CLIENT_VERSION,
            "hl": "ru",
            "gl": "DE"
        }}
    });
    if let (Some(dst), Value::Object(src)) = (b.as_object_mut(), body) {
        dst.extend(src);
    }
    let v: Value = http
        .post(format!("https://music.youtube.com/youtubei/v1/{endpoint}?prettyPrint=false"))
        .header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/")
        .json(&b)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(v)
}

fn valid_browse_id(id: &str) -> bool {
    !id.is_empty() && id.len() < 80 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn collect<'a>(v: &'a Value, key: &str, out: &mut Vec<&'a Value>) {
    match v {
        Value::Object(m) => {
            if let Some(r) = m.get(key) {
                out.push(r);
                return;
            }
            for x in m.values() {
                collect(x, key, out);
            }
        }
        Value::Array(a) => {
            for x in a {
                collect(x, key, out);
            }
        }
        _ => {}
    }
}

fn runs(v: &Value) -> Vec<&Value> {
    v["runs"].as_array().map(|a| a.iter().collect()).unwrap_or_default()
}

fn text(v: &Value) -> String {
    runs(v).iter().filter_map(|r| r["text"].as_str()).collect::<Vec<_>>().join("")
}

fn browse_id(r: &Value) -> Option<&str> {
    r["navigationEndpoint"]["browseEndpoint"]["browseId"].as_str()
}

fn page_type(nav: &Value) -> Option<&str> {
    nav["browseEndpoint"]["browseEndpointContextSupportedConfigs"]["browseEndpointContextMusicConfig"]["pageType"]
        .as_str()
}

/// Увеличиваем превью: у googleusercontent размер задаётся суффиксом =wN-hN.
pub fn thumb_size(u: &str, px: u32) -> String {
    if u.contains("googleusercontent.com") || u.contains("ggpht.com") {
        if let Some(i) = u.rfind("=w") {
            return format!("{}=w{px}-h{px}-l90-rj", &u[..i]);
        }
        if let Some(i) = u.rfind("=s") {
            return format!("{}=w{px}-h{px}-l90-rj", &u[..i]);
        }
    }
    u.to_string()
}

fn best_thumb(v: &Value, px: u32) -> Option<String> {
    let mut list = Vec::new();
    collect(v, "thumbnails", &mut list);
    list.first()
        .and_then(|a| a.as_array())
        .and_then(|a| a.last())
        .and_then(|t| t["url"].as_str())
        .map(|u| thumb_size(u, px))
}

fn parse_duration(s: &str) -> Option<i64> {
    let parts: Vec<&str> = s.trim().split(':').collect();
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

/// Строка списка (поиск, альбом, плейлист, топ исполнителя) → трек.
/// `fallback` — исполнитель/обложка коллекции для альбомных строк, где их нет.
fn parse_track(it: &Value, fallback: Option<(&str, Option<&str>, Option<&str>)>) -> Option<Track> {
    let cols = it["flexColumns"].as_array()?;
    let col = |i: usize| cols.get(i).map(|c| &c["musicResponsiveListItemFlexColumnRenderer"]["text"]);
    let title = text(col(0)?).trim().to_string();
    if title.is_empty() {
        return None;
    }
    let id = it["playlistItemData"]["videoId"]
        .as_str()
        .or_else(|| {
            it["overlay"]["musicItemThumbnailOverlayRenderer"]["content"]["musicPlayButtonRenderer"]
                ["playNavigationEndpoint"]["watchEndpoint"]["videoId"]
                .as_str()
        })
        .or_else(|| runs(col(0)?).first()?["navigationEndpoint"]["watchEndpoint"]["videoId"].as_str())?
        .to_string();

    let mut artists: Vec<String> = Vec::new();
    let mut artist_id = None;
    let mut album = None;
    let mut album_id = None;
    let mut duration = 0;
    let mut kind_label = String::new();
    for (ci, c) in cols.iter().enumerate().skip(1) {
        for (ri, r) in runs(&c["musicResponsiveListItemFlexColumnRenderer"]["text"]).into_iter().enumerate() {
            let t = r["text"].as_str().unwrap_or("");
            if ci == 1 && ri == 0 && browse_id(r).is_none() {
                kind_label = t.to_string();
            }
            if let Some(d) = parse_duration(t) {
                duration = d;
                continue;
            }
            match browse_id(r) {
                Some(b) if b.starts_with("UC") => {
                    artists.push(t.to_string());
                    artist_id.get_or_insert_with(|| b.to_string());
                }
                Some(b) if b.starts_with("MPRE") => {
                    album.get_or_insert_with(|| t.to_string());
                    album_id.get_or_insert_with(|| b.to_string());
                }
                _ => {}
            }
        }
    }
    // Подкасты и выпуски нам не нужны.
    if kind_label == "Выпуск" || kind_label == "Подкаст" || kind_label == "Episode" {
        return None;
    }
    if duration == 0 {
        if let Some(fc) = it["fixedColumns"].as_array().and_then(|a| a.first()) {
            duration = parse_duration(&text(&fc["musicResponsiveListItemFixedColumnRenderer"]["text"])).unwrap_or(0);
        }
    }
    let mut artist = artists.join(", ");
    let mut thumb = it["thumbnail"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"]
        .as_array()
        .and_then(|a| a.last())
        .and_then(|t| t["url"].as_str())
        .map(|u| thumb_size(u, 300));
    if let Some((fa, fid, fthumb)) = fallback {
        if artist.is_empty() {
            artist = fa.to_string();
            artist_id = fid.map(str::to_string);
        }
        if thumb.is_none() {
            thumb = fthumb.map(|s| thumb_size(s, 300));
        }
    }
    if artist.is_empty() {
        // Строка без ссылки на исполнителя: берём второй текст колонки (не тип и не точку).
        let sub = col(1).map(text).unwrap_or_default();
        artist = sub
            .split(" • ")
            .map(str::trim)
            .find(|s| !s.is_empty() && *s != kind_label && parse_duration(s).is_none())
            .unwrap_or("")
            .to_string();
    }
    if artist.is_empty() {
        return None;
    }
    Some(Track { id, title, artist, album, duration, thumb, artist_id, album_id })
}

fn sub_text(v: &Value) -> String {
    text(v).replace('\u{a0}', " ")
}

/// Тип страницы, на которую ведёт строка поиска (исполнитель, альбом, плейлист…).
fn parse_card(it: &Value) -> Option<String> {
    page_type(&it["navigationEndpoint"]).map(str::to_string)
}

fn list_item_card(it: &Value) -> Option<(String, String, String, Vec<(String, Option<String>)>, Option<String>)> {
    let nav = &it["navigationEndpoint"];
    let id = nav["browseEndpoint"]["browseId"].as_str()?.to_string();
    let cols = it["flexColumns"].as_array()?;
    let title = text(&cols.first()?["musicResponsiveListItemFlexColumnRenderer"]["text"]);
    let sub_runs: Vec<(String, Option<String>)> = cols
        .get(1)
        .map(|c| {
            runs(&c["musicResponsiveListItemFlexColumnRenderer"]["text"])
                .into_iter()
                .map(|r| (r["text"].as_str().unwrap_or("").replace('\u{a0}', " "), browse_id(r).map(str::to_string)))
                .collect()
        })
        .unwrap_or_default();
    let thumb = best_thumb(&it["thumbnail"], 400);
    Some((id, title, page_type(nav).unwrap_or("").to_string(), sub_runs, thumb))
}

fn album_from_runs(id: String, title: String, sub: &[(String, Option<String>)], thumb: Option<String>, kind: &str) -> AlbumCard {
    let artist = sub
        .iter()
        .filter(|(_, b)| b.as_deref().map(|b| b.starts_with("UC")).unwrap_or(false))
        .map(|(t, _)| t.clone())
        .collect::<Vec<_>>()
        .join(", ");
    let parts: Vec<String> = sub
        .iter()
        .map(|(t, _)| t.trim().to_string())
        .filter(|t| !t.is_empty() && t != "•")
        .collect();
    let mut kind = kind.to_string();
    if parts.first().map(|s| s == "Сингл" || s == "Single" || s == "EP").unwrap_or(false) {
        kind = "single".into();
    }
    // Подпись без типа («Альбом») — он и так понятен по разделу.
    let sub = parts
        .iter()
        .skip(if matches!(parts.first().map(String::as_str), Some("Альбом" | "Сингл" | "Плейлист" | "EP")) { 1 } else { 0 })
        .cloned()
        .collect::<Vec<_>>()
        .join(" · ");
    AlbumCard { id, title, artist, sub, thumb, kind }
}

pub async fn search_all(http: &Client, q: &str) -> Result<SearchAll> {
    let v = ytm(http, "search", json!({ "query": q })).await?;
    let mut out = SearchAll::default();

    // Карточка «лучший результат» — обычно исполнитель.
    let mut cards = Vec::new();
    collect(&v, "musicCardShelfRenderer", &mut cards);
    if let Some(c) = cards.first() {
        let r = runs(&c["title"]).into_iter().next();
        if let Some(r) = r {
            if let Some(b) = browse_id(r).filter(|b| b.starts_with("UC")) {
                out.top = Some(ArtistCard {
                    id: b.to_string(),
                    name: r["text"].as_str().unwrap_or("").to_string(),
                    thumb: best_thumb(&c["thumbnail"], 400),
                    sub: sub_text(&c["subtitle"]).split(" • ").last().unwrap_or("").to_string(),
                });
            }
        }
    }

    let mut items = Vec::new();
    collect(&v, "musicResponsiveListItemRenderer", &mut items);
    let mut seen = std::collections::HashSet::new();
    for it in items {
        if let Some(pt) = parse_card(it) {
            let Some((id, title, _, sub, thumb)) = list_item_card(it) else { continue };
            if !seen.insert(id.clone()) {
                continue;
            }
            match pt.as_str() {
                "MUSIC_PAGE_TYPE_ARTIST" => {
                    let s = sub.iter().map(|(t, _)| t.as_str()).collect::<String>();
                    out.artists.push(ArtistCard {
                        id,
                        name: title,
                        thumb,
                        sub: s.split(" • ").last().unwrap_or("").to_string(),
                    })
                }
                "MUSIC_PAGE_TYPE_ALBUM" => out.albums.push(album_from_runs(id, title, &sub, thumb, "album")),
                "MUSIC_PAGE_TYPE_PLAYLIST" => out.playlists.push(album_from_runs(id, title, &sub, thumb, "playlist")),
                _ => {}
            }
        } else if let Some(t) = parse_track(it, None) {
            if seen.insert(t.id.clone()) {
                out.tracks.push(t);
            }
        }
    }
    if let Some(top) = &out.top {
        out.artists.retain(|a| a.id != top.id);
    }
    Ok(out)
}

fn two_row_card(it: &Value) -> Option<(String, String, String, Vec<(String, Option<String>)>, Option<String>)> {
    let nav = &it["navigationEndpoint"];
    let id = nav["browseEndpoint"]["browseId"].as_str()?.to_string();
    let title = text(&it["title"]);
    let sub: Vec<(String, Option<String>)> = runs(&it["subtitle"])
        .into_iter()
        .map(|r| (r["text"].as_str().unwrap_or("").replace('\u{a0}', " "), browse_id(r).map(str::to_string)))
        .collect();
    let thumb = best_thumb(&it["thumbnailRenderer"], 400);
    Some((id, title, page_type(nav).unwrap_or("").to_string(), sub, thumb))
}

pub async fn artist_page(http: &Client, id: &str) -> Result<ArtistPage> {
    if !valid_browse_id(id) || !id.starts_with("UC") {
        bail!("bad artist id");
    }
    let v = ytm(http, "browse", json!({ "browseId": id })).await?;
    let h = &v["header"]["musicImmersiveHeaderRenderer"];
    let h = if h.is_null() { &v["header"]["musicVisualHeaderRenderer"] } else { h };
    let mut page = ArtistPage {
        id: id.to_string(),
        name: text(&h["title"]),
        thumb: best_thumb(&h["thumbnail"], 1200),
        listeners: sub_text(&h["monthlyListenerCount"]),
        description: text(&h["description"]),
        ..Default::default()
    };
    if page.name.is_empty() {
        bail!("artist not found");
    }
    let mut we = Vec::new();
    collect(&h["startRadioButton"], "watchEndpoint", &mut we);
    if let Some(we) = we.first() {
        if let (Some(v), Some(p)) = (we["videoId"].as_str(), we["playlistId"].as_str()) {
            page.radio = Some((v.to_string(), p.to_string()));
        }
    }
    let sections = v["contents"]["singleColumnBrowseResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]
        ["sectionListRenderer"]["contents"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    for s in &sections {
        if let Some(shelf) = s.get("musicShelfRenderer") {
            page.all_tracks = runs(&shelf["title"]).first().and_then(|r| browse_id(r)).map(str::to_string);
            for it in shelf["contents"].as_array().into_iter().flatten() {
                if let Some(t) = parse_track(&it["musicResponsiveListItemRenderer"], Some((&page.name, Some(id), None))) {
                    page.tracks.push(t);
                }
            }
        } else if let Some(car) = s.get("musicCarouselShelfRenderer") {
            let title = text(&car["header"]["musicCarouselShelfBasicHeaderRenderer"]["title"]).to_lowercase();
            for c in car["contents"].as_array().into_iter().flatten() {
                let Some((cid, ctitle, pt, sub, thumb)) = two_row_card(&c["musicTwoRowItemRenderer"]) else { continue };
                match pt.as_str() {
                    "MUSIC_PAGE_TYPE_ALBUM" => {
                        let mut a = album_from_runs(cid, ctitle, &sub, thumb, "album");
                        if a.artist.is_empty() {
                            a.artist = page.name.clone();
                        }
                        if title.contains("сингл") || title.contains("single") || a.kind == "single" {
                            a.kind = "single".into();
                            page.singles.push(a);
                        } else {
                            page.albums.push(a);
                        }
                    }
                    "MUSIC_PAGE_TYPE_PLAYLIST" => page.playlists.push(album_from_runs(cid, ctitle, &sub, thumb, "playlist")),
                    "MUSIC_PAGE_TYPE_ARTIST" => page.similar.push(ArtistCard {
                        id: cid,
                        name: ctitle,
                        thumb,
                        sub: sub.iter().map(|(t, _)| t.as_str()).collect::<String>(),
                    }),
                    _ => {}
                }
            }
        }
    }
    Ok(page)
}

pub async fn collection(http: &Client, id: &str) -> Result<Collection> {
    if !valid_browse_id(id) || !(id.starts_with("MPRE") || id.starts_with("VL") || id.starts_with("OLAK")) {
        bail!("bad collection id");
    }
    let bid = if id.starts_with("OLAK") { format!("VL{id}") } else { id.to_string() };
    let v = ytm(http, "browse", json!({ "browseId": bid })).await?;
    let tc = &v["contents"]["twoColumnBrowseResultsRenderer"];
    let mut heads = Vec::new();
    collect(&tc["tabs"], "musicResponsiveHeaderRenderer", &mut heads);
    let h = heads.first().copied().unwrap_or(&Value::Null);
    let strap = runs(&h["straplineTextOne"]);
    let artist = strap.iter().filter_map(|r| r["text"].as_str()).collect::<Vec<_>>().join("");
    let artist_id = strap.iter().find_map(|r| browse_id(r)).filter(|b| b.starts_with("UC")).map(str::to_string);
    let sub_parts: Vec<String> = text(&h["subtitle"])
        .replace('\u{a0}', " ")
        .split(" • ")
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    let kind = match sub_parts.first().map(String::as_str) {
        Some("Сингл") | Some("EP") => "single",
        Some("Альбом") => "album",
        _ if id.starts_with("MPRE") => "album",
        _ => "playlist",
    };
    let mut sub = sub_parts.iter().skip(1).cloned().collect::<Vec<_>>();
    let second = sub_text(&h["secondSubtitle"]);
    if !second.is_empty() {
        sub.push(second.replace(" • ", " · "));
    }
    let thumb = best_thumb(&h["thumbnail"], 1200);
    let mut out = Collection {
        id: id.to_string(),
        kind: kind.into(),
        title: text(&h["title"]),
        artist: artist.clone(),
        artist_id: artist_id.clone(),
        sub: sub.join(" · "),
        thumb: thumb.clone(),
        description: text(&h["description"]["musicDescriptionShelfRenderer"]["description"]),
        tracks: Vec::new(),
    };
    let mut items = Vec::new();
    collect(&tc["secondaryContents"], "musicResponsiveListItemRenderer", &mut items);
    let small_thumb = thumb.as_deref().map(|t| thumb_size(t, 300));
    let album_title = out.title.clone();
    for it in items {
        let fallback = if kind == "playlist" { None } else { Some((artist.as_str(), artist_id.as_deref(), small_thumb.as_deref())) };
        if let Some(mut t) = parse_track(it, fallback) {
            if kind != "playlist" {
                t.album.get_or_insert_with(|| album_title.clone());
                if t.album_id.is_none() && id.starts_with("MPRE") {
                    t.album_id = Some(id.to_string());
                }
            }
            out.tracks.push(t);
        }
    }
    if out.title.is_empty() && out.tracks.is_empty() {
        bail!("not found");
    }
    Ok(out)
}

/// Имя → browseId исполнителя через поиск.
pub async fn resolve_artist(cache: &SearchCache, http: &Client, name: &str) -> Option<String> {
    let all = cache.search_all(http, name).await.ok()?;
    let want = name.split(',').next().unwrap_or(name).trim().to_lowercase();
    if let Some(top) = &all.top {
        if top.name.to_lowercase() == want {
            return Some(top.id.clone());
        }
    }
    if let Some(a) = all.artists.iter().find(|a| a.name.to_lowercase() == want) {
        return Some(a.id.clone());
    }
    if let Some(t) = all.tracks.iter().find(|t| t.artist.to_lowercase().starts_with(&want) && t.artist_id.is_some()) {
        return t.artist_id.clone();
    }
    all.top.map(|t| t.id).or_else(|| all.artists.first().map(|a| a.id.clone()))
}

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
    // Квадратная обложка из чарта, если есть; иначе кадр клипа.
    let thumb = t["thumbnail"]["thumbnails"]
        .as_array()
        .and_then(|a| a.last())
        .and_then(|x| x["url"].as_str())
        .map(|u| thumb_size(u, 300))
        .or_else(|| t["encryptedVideoId"].as_str().map(|v| format!("https://i.ytimg.com/vi/{v}/hqdefault.jpg")));
    let mut tr = Track::new(id, title, artist);
    tr.thumb = thumb;
    tr.duration = t["videoDuration"].as_i64().unwrap_or(0);
    Some(tr)
}
