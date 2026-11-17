//! Запасные источники звука, когда YouTube не отдаёт трек: Audius, SoundCloud, archive.org.
//!
//! Подмена строгая. Подходит только:
//! - запись самого исполнителя с тем же названием и длительностью в пределах ±10 % (не меньше 8 с);
//! - перезалив «Исполнитель - Название» другим пользователем, если длительность совпадает до ±3 %
//!   (не меньше 4 с) — для этого клиент должен прислать длительность (`d`).
//!
//! Записи с пометками cover/remix/live/karaoke/slowed/… отсеиваются, если таких слов нет в оригинале.
//! Ничего не подошло — честная ошибка: лучше пропустить трек, чем сыграть чужой кавер под видом оригинала.
//!
//! Подменённый ответ помечается заголовками `X-Substitute*` и `Cache-Control: no-store`, а сайт узнаёт
//! о подмене через `GET /api/substitute/{id}`. Скачиванию в приложение (`q=` или `dl=1`) замена не отдаётся.
//!
//! Треки с id `x-au:`/`x-ov:`/`x-sc:`/`x-ia:` попадали в поиск 17.11 (около полутора часов). В поиске их
//! больше нет, но сохранённые в лайках и плейлистах продолжают играть.

use crate::accounts::{Accounts, ErrKind};
use crate::recs::{artist_key, artists_of, translit};
use axum::body::Body;
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::sync::{OnceCell, Semaphore};

const UA: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36";
/// Audius просит подписывать запросы именем приложения.
const AUDIUS_APP: &str = "palustris-music";
/// Найденная замена живёт 30 минут, «не нашлось» — 5.
const FOUND_TTL: Duration = Duration::from_secs(1800);
const MISS_TTL: Duration = Duration::from_secs(300);

fn client() -> &'static reqwest::Client {
    static C: OnceLock<reqwest::Client> = OnceLock::new();
    C.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(UA)
            .connect_timeout(Duration::from_secs(8))
            // Без него зависший источник держал бы соединение плеера бесконечно.
            .read_timeout(Duration::from_secs(30))
            .build()
            .expect("external http client")
    })
}

/// yt-dlp для SoundCloud — отдельный процесс Python на каждый вызов; сервер маленький.
fn sc_slots() -> &'static Semaphore {
    static S: Semaphore = Semaphore::const_new(2);
    &S
}

// ───────────────────────── что ищем и что нашли ─────────────────────────

/// Оригинал, для которого ищем замену: исполнитель и название как в YouTube Music, длительность в секундах (0 — неизвестна).
#[derive(Clone, Debug)]
pub struct Want {
    pub artist: String,
    pub title: String,
    pub duration: i64,
}

impl Want {
    pub fn new(artist: &str, title: &str, duration: Option<i64>) -> Option<Want> {
        let (artist, title) = (artist.trim(), title.trim());
        if artist.is_empty() || title.is_empty() || artist.len() > 200 || title.len() > 200 {
            return None;
        }
        let duration = duration.filter(|d| (1..=4 * 3600).contains(d)).unwrap_or(0);
        Some(Want { artist: artist.to_string(), title: title.to_string(), duration })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Src {
    Audius,
    SoundCloud,
    Archive,
}

impl Src {
    fn code(self) -> &'static str {
        match self {
            Src::Audius => "audius",
            Src::SoundCloud => "soundcloud",
            Src::Archive => "archive",
        }
    }
    fn name(self) -> &'static str {
        match self {
            Src::Audius => "Audius",
            Src::SoundCloud => "SoundCloud",
            Src::Archive => "archive.org",
        }
    }
    /// При равном совпадении: сначала то, что играет сразу (Audius и archive.org отдаются потоком),
    /// SoundCloud приходится сначала скачать целиком.
    fn pref(self) -> u8 {
        match self {
            Src::Audius => 0,
            Src::Archive => 1,
            Src::SoundCloud => 2,
        }
    }
}

/// Как отдавать найденное.
#[derive(Clone, Debug)]
pub enum Play {
    /// Прямая ссылка на аудио, отдаём через наш прокси с Range.
    Url(String),
    /// Страница SoundCloud (hex): скачиваем в кэш и отдаём файлом.
    SoundCloud(String),
}

/// Замена, которую слышит пользователь. Уходит клиентам в `GET /api/substitute/{id}`.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Sub {
    pub source: &'static str,
    pub source_name: &'static str,
    pub title: String,
    pub artist: String,
    pub duration: i64,
    /// Страница записи у источника.
    pub page: String,
    #[serde(skip)]
    pub play: Play,
}

#[derive(Clone, Debug)]
struct Cand {
    src: Src,
    title: String,
    artist: String,
    duration: i64,
    page: String,
    play: Play,
    /// Что ещё проверить на пометки: id записи archive.org («Radiohead-Creepcover»).
    hint: String,
}

// ───────────────────────── сравнение ─────────────────────────

/// Пометки «это не оригинальная запись» (в транслите, целыми словами).
const MARKERS: &[&str] = &[
    "cover", "covers", "covered", "kaver", "remix", "remixes", "remixed", "rmx", "remiks", "mix", "miks", "bootleg",
    "flip", "edit", "mashup", "mash up", "medley", "karaoke", "instrumental", "instrumentalnaya", "acapella",
    "a cappella", "acappella", "minus", "minusovka", "live", "zhivoe", "zhivoi", "vzhivuyu", "kontsert", "concert",
    "acoustic", "akustika", "akusticheskaya", "unplugged", "slowed", "reverb", "sped up", "speed up", "spedup",
    "nightcore", "8d", "bass boosted", "boosted", "parody", "parodiya", "tribute", "piano", "guitar", "gitara",
    "drum", "drums", "violin", "cello", "orchestra", "orchestral", "symphonic", "string quartet", "lullaby", "lofi",
    "lo fi", "type beat", "demo", "rehearsal", "multitrack", "stems", "isolated", "vocals only", "no vocals", "hour",
    "hours", "chas", "chasa", "chasov", "loop", "snippet", "preview", "teaser", "tiktok", "reimagined", "rework",
    "vip", "extended", "made famous", "originally performed", "style of", "8 bit", "8bit", "chiptune", "ai",
];

/// Слова-приставки к имени исполнителя, которые ничего не меняют: «Portishead Official», «группа Кино».
const NAME_NOISE: &[&str] = &["the", "official", "music", "band", "vevo", "tv", "records", "topic", "group", "gruppa"];

/// « слово слово » в транслите: для поиска пометок целыми словами.
fn padded(s: &str) -> String {
    let t: String = translit(&s.replace(['\'', '’', '`'], "")).chars().map(|c| if c.is_alphanumeric() { c } else { ' ' }).collect();
    format!(" {} ", t.split_whitespace().collect::<Vec<_>>().join(" "))
}

/// Пометки, которые ищем и внутри слитных слов: «Creepcover», «SongRemix».
const STRONG: &[&str] = &["cover", "remix", "karaoke", "instrumental", "acoustic", "parody", "tribute", "nightcore", "slowed", "bootleg"];

/// «NirvanaLive-1991» → «Nirvana Live 1991»: границы слов в id.
fn split_camel(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    let mut prev_lower = false;
    for c in s.chars() {
        if c.is_uppercase() && prev_lower {
            out.push(' ');
        }
        prev_lower = c.is_lowercase();
        out.push(c);
    }
    out
}

/// Год в скобках («(29-Abril-2009)», «[1988]») — обычно запись с концерта или демо. Кроме «(Remastered 2011)».
fn dated_brackets(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut cur = String::new();
    for c in s.chars() {
        match c {
            '(' | '[' => {
                depth += 1;
                cur.clear();
            }
            ')' | ']' => {
                depth = (depth - 1).max(0);
                let low = cur.to_lowercase();
                if !low.contains("remaster") {
                    let digits: Vec<char> = low.chars().collect();
                    for w in digits.windows(4) {
                        let y: String = w.iter().collect();
                        if (y.starts_with("19") || y.starts_with("20")) && w.iter().all(|c| c.is_ascii_digit()) {
                            out.push(y);
                        }
                    }
                }
                cur.clear();
            }
            _ if depth > 0 => cur.push(c),
            _ => {}
        }
    }
    out
}

/// Пометка версии, которой нет в оригинале.
fn foreign_marker(want: &Want, c: &Cand) -> Option<String> {
    let w = padded(&format!("{} {}", want.title, want.artist));
    let have = padded(&format!("{} {} {}", c.title, c.artist, split_camel(&c.hint)));
    if let Some(m) = MARKERS.iter().find(|m| {
        let m = format!(" {m} ");
        have.contains(&m) && !w.contains(&m)
    }) {
        return Some(m.to_string());
    }
    let glued = c.hint.to_lowercase();
    if let Some(m) = STRONG.iter().find(|m| glued.contains(*m) && !w.contains(&format!(" {m} "))) {
        return Some(m.to_string());
    }
    dated_brackets(&c.title).into_iter().find(|y| !want.title.contains(y.as_str()))
}

/// Название для сравнения: транслит, без скобок, без «feat.», без пунктуации.
fn clean_title(s: &str) -> String {
    let mut out = String::new();
    let mut depth = 0i32;
    for c in s.chars() {
        match c {
            '(' | '[' | '{' => depth += 1,
            ')' | ']' | '}' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    // translit уже заменил точки пробелами: «feat.» → « feat ».
    let mut t = translit(&out.replace(['\'', '’', '`', '"', '«', '»'], ""));
    for cut in [" feat ", " ft ", " featuring ", " prod "] {
        let p = format!(" {t} ");
        if let Some(i) = p.find(cut) {
            t = if i <= 1 { String::new() } else { p[1..i].to_string() };
        }
    }
    let t: String = t.chars().map(|c| if c.is_alphanumeric() { c } else { ' ' }).collect();
    t.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// «01 - Song», «07. Song» в названиях файлов archive.org.
fn strip_track_no(s: &str) -> &str {
    let t = s.trim_start();
    let digits = t.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 || digits > 3 {
        return s;
    }
    let rest = &t[digits..];
    let rest2 = rest.trim_start_matches([' ', '.', '-', '_', ')']);
    if rest2.len() < rest.len() && !rest2.is_empty() {
        rest2
    } else {
        s
    }
}

/// Имя исполнителя без «шума» по краям, в транслите.
fn name_core(s: &str) -> String {
    let t = translit(s);
    let words: Vec<&str> = t.split_whitespace().collect();
    let (mut a, mut b) = (0, words.len());
    while a < b && NAME_NOISE.contains(&words[a]) {
        a += 1;
    }
    while b > a && NAME_NOISE.contains(&words[b - 1]) {
        b -= 1;
    }
    words[a..b].join(" ")
}

/// Отдельные имена: «Victor Tsoy and band Kino» → [victor tsoy, kino].
fn names(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    for part in artists_of(s) {
        for p in part.split(" and ").flat_map(|p| p.split(" x ")).flat_map(|p| p.split(" with ")).flat_map(|p| p.split(['/', '+'])) {
            let n = name_core(p);
            if !n.is_empty() && !out.contains(&n) {
                out.push(n);
            }
        }
    }
    out
}

/// Тот же исполнитель: имя совпадает (в любой раскладке), или аккаунт назван «имя + official/music/…»
/// слитно — «nirvanaofficial».
fn same_artist(want: &str, cand: &str) -> bool {
    let wants = names(want);
    let cands = names(cand);
    for w in &wants {
        let wk = artist_key(w);
        let wc = w.replace(' ', "");
        for c in &cands {
            if c == w || (!wk.is_empty() && artist_key(c) == wk) {
                return true;
            }
            let cc = c.replace(' ', "");
            if wc.chars().count() >= 4 && cc.len() > wc.len() {
                let rest = cc.strip_prefix(&wc).or_else(|| cc.strip_suffix(&wc));
                if rest.is_some_and(|r| NAME_NOISE.contains(&r)) {
                    return true;
                }
            }
        }
    }
    false
}

/// «Исполнитель - Название» → (исполнитель, название).
fn split_dash(s: &str) -> Option<(&str, &str)> {
    for sep in [" - ", " – ", " — ", " -- "] {
        if let Some((a, t)) = s.split_once(sep) {
            if !a.trim().is_empty() && !t.trim().is_empty() {
                return Some((a.trim(), t.trim()));
            }
        }
    }
    None
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum Level {
    /// Запись самого исполнителя.
    Own,
    /// Перезалив «Исполнитель - Название» с той же длительностью.
    Reupload,
}

/// Почему кандидат не подошёл — для строки в журнале.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum Miss {
    Version,
    Other,
    Duration,
}

/// Тот же исполнитель и то же название (без учёта длительности). Перезалив чужим аккаунтом — только если
/// длительность оригинала известна: без неё его не отличить от кавера без пометок.
fn level(want: &Want, c: &Cand) -> Option<Level> {
    let want_t = clean_title(&want.title);
    if want_t.is_empty() {
        return None;
    }
    // «07 - Song» в файлах archive.org, но «7 Rings» — это название целиком.
    let title_is = |t: &str| clean_title(t) == want_t || clean_title(strip_track_no(t)) == want_t;
    if same_artist(&want.artist, &c.artist) {
        let own_title = match split_dash(&c.title) {
            Some((a, t)) if same_artist(&want.artist, a) => title_is(t),
            _ => title_is(&c.title),
        };
        return own_title.then_some(Level::Own);
    }
    // На archive.org «автор», не совпадающий с исполнителем, — обычно тот, кто сам и спел («Radiohead-Creepcover»).
    match split_dash(&c.title) {
        Some((a, t)) if want.duration > 0 && c.src != Src::Archive && same_artist(&want.artist, a) && title_is(t) => {
            Some(Level::Reupload)
        }
        _ => None,
    }
}

/// Другое видео той же песни на YouTube: тот же исполнитель, то же название, без пометок cover/remix/live/…
pub fn same_song(want_artist: &str, want_title: &str, artist: &str, title: &str) -> bool {
    let Some(want) = Want::new(want_artist, want_title, None) else { return false };
    let c = Cand {
        src: Src::Audius,
        title: title.to_string(),
        artist: artist.to_string(),
        duration: 0,
        page: String::new(),
        play: Play::Url(String::new()),
        hint: String::new(),
    };
    foreign_marker(&want, &c).is_none() && level(&want, &c).is_some()
}

fn judge(want: &Want, c: &Cand) -> Result<(Level, i64), Miss> {
    if foreign_marker(want, c).is_some() {
        return Err(Miss::Version);
    }
    if c.duration <= 0 {
        return Err(Miss::Duration);
    }
    let level = level(want, c).ok_or(Miss::Other)?;

    if want.duration > 0 {
        let diff = (c.duration - want.duration).abs();
        let tol = match level {
            Level::Own => (want.duration / 10).max(8),
            // Перезалив той же записи расходится на секунду-две, кавер без пометки чаще отличается сильнее.
            Level::Reupload => (want.duration * 2 / 100).max(3),
        };
        if diff > tol {
            return Err(Miss::Duration);
        }
        Ok((level, diff))
    } else if (60..=1200).contains(&c.duration) {
        // Длительность оригинала неизвестна: хотя бы не отрывок и не «1 час подряд».
        Ok((level, 0))
    } else {
        Err(Miss::Duration)
    }
}

// ───────────────────────── поиск замены ─────────────────────────

type Slot = Arc<OnceCell<Option<Sub>>>;

fn lookups() -> &'static Mutex<HashMap<String, (Instant, Slot)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, Slot)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Тот же трек в другом каталоге. Одновременные запросы одного трека (Range, предзагрузка) ждут
/// один поиск. `video` — только для журнала загрузок.
pub async fn fallback(dir: PathBuf, want: Want, acc: Arc<Accounts>, video: String) -> Option<Sub> {
    let key = format!("{}|{}|{}", clean_title(&want.artist), clean_title(&want.title), want.duration);
    let slot: Slot = {
        let Ok(mut m) = lookups().lock() else { return None };
        let fresh: Option<Slot> = m
            .get(&key)
            .filter(|(at, slot)| match slot.get() {
                None => true,
                Some(Some(_)) => at.elapsed() < FOUND_TTL,
                Some(None) => at.elapsed() < MISS_TTL,
            })
            .map(|(_, slot)| slot.clone());
        match fresh {
            Some(slot) => slot,
            None => {
                if m.len() > 500 {
                    m.retain(|_, (at, _)| at.elapsed() < FOUND_TTL);
                }
                let slot: Slot = Arc::new(OnceCell::new());
                m.insert(key, (Instant::now(), slot.clone()));
                slot
            }
        }
    };
    slot.get_or_init(|| async {
        let started = Instant::now();
        let found = lookup(&dir, &want).await;
        let ms = started.elapsed().as_millis() as i64;
        match &found {
            Some(s) => acc.log_event(&video, None, &format!("fallback:{}", s.source), Ok(()), ms),
            None => acc.log_event(&video, None, "fallback", Err(("не нашлось такой же записи", ErrKind::Other)), ms),
        }
        found
    })
    .await
    .clone()
}

/// Убрать найденное из памяти (источник перестал отдавать), чтобы следующий запрос поискал заново.
pub fn forget_lookup(want: &Want) {
    let key = format!("{}|{}|{}", clean_title(&want.artist), clean_title(&want.title), want.duration);
    if let Ok(mut m) = lookups().lock() {
        m.remove(&key);
    }
}

/// Поиск без кэша и журнала — для `music fallback-check`.
pub async fn check(dir: &Path, want: &Want) -> Option<Sub> {
    lookup(dir, want).await
}

async fn lookup(dir: &Path, want: &Want) -> Option<Sub> {
    let first = artists_of(&want.artist).into_iter().next().unwrap_or_else(|| want.artist.clone());
    let bare: String = {
        let mut depth = 0i32;
        want.title
            .chars()
            .filter(|c| match c {
                '(' | '[' => {
                    depth += 1;
                    false
                }
                ')' | ']' => {
                    depth = (depth - 1).max(0);
                    false
                }
                _ => depth == 0,
            })
            .collect()
    };
    let q = format!("{first} {}", bare.trim());
    let (a, s, ia) = tokio::join!(
        limited("audius", audius(&q), 6),
        limited("soundcloud", soundcloud(&q), 12),
        limited("archive.org", archive(&first, bare.trim()), 10),
    );
    let total = a.len() + s.len() + ia.len();
    let mut misses: HashMap<Miss, usize> = HashMap::new();
    let mut ok: Vec<(Level, i64, u8, Cand)> = Vec::new();
    let debug = std::env::var_os("MUSIC_FALLBACK_DEBUG").is_some();
    for c in a.into_iter().chain(s).chain(ia) {
        if debug {
            eprintln!("  {:<11} {:>5} c  {} — {}  → {:?}", c.src.name(), c.duration, c.artist, c.title, judge(want, &c));
        }
        match judge(want, &c) {
            Ok((level, diff)) => ok.push((level, diff / 3, c.src.pref(), c)),
            Err(m) => *misses.entry(m).or_default() += 1,
        }
    }
    ok.sort_by(|x, y| (x.0, x.1, x.2).cmp(&(y.0, y.1, y.2)));
    let head = format!("fallback «{}» / {} ({} c)", want.title, want.artist, want.duration);
    if ok.is_empty() {
        eprintln!(
            "{head}: замены нет — кандидатов {total}, другие версии {}, другие треки {}, длительность {}",
            misses.get(&Miss::Version).unwrap_or(&0),
            misses.get(&Miss::Other).unwrap_or(&0),
            misses.get(&Miss::Duration).unwrap_or(&0),
        );
        return None;
    }
    // Часть официальных треков SoundCloud с DRM и не скачивается: больше двух попыток там не делаем.
    let mut sc_tries = 0;
    for (level, _, _, c) in ok.into_iter().take(5) {
        if c.src == Src::SoundCloud {
            sc_tries += 1;
            if sc_tries > 2 {
                continue;
            }
        }
        match playable(dir, &c).await {
            Ok(()) => {
                eprintln!("{head}: {} «{}» / {} ({} c, {:?})", c.src.name(), c.title, c.artist, c.duration, level);
                // «American Green - She Goes To France» → «She Goes To France»: исполнитель и так показан рядом.
                // Номер дорожки убираем, только если без него совпало: «7 Rings» остаётся «7 Rings».
                let shown = |t: &str| {
                    if clean_title(t) == clean_title(&want.title) { t.to_string() } else { strip_track_no(t).to_string() }
                };
                let title = match split_dash(&c.title) {
                    Some((a, t)) if same_artist(&want.artist, a) => shown(t),
                    _ => shown(&c.title),
                };
                return Some(Sub {
                    source: c.src.code(),
                    source_name: c.src.name(),
                    title,
                    artist: c.artist,
                    duration: c.duration,
                    page: c.page,
                    play: c.play,
                });
            }
            Err(e) => eprintln!("{head}: {} «{}» не играет: {e}", c.src.name(), c.title),
        }
    }
    None
}

async fn limited<F>(name: &str, f: F, secs: u64) -> Vec<Cand>
where
    F: std::future::Future<Output = Result<Vec<Cand>, String>>,
{
    match tokio::time::timeout(Duration::from_secs(secs), f).await {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            eprintln!("source {name}: {e}");
            Vec::new()
        }
        Err(_) => {
            eprintln!("source {name}: нет ответа за {secs} с");
            Vec::new()
        }
    }
}

async fn get_json(rb: reqwest::RequestBuilder) -> Result<Value, String> {
    let r = rb.send().await.map_err(|e| e.to_string())?;
    let status = r.status();
    if !status.is_success() {
        return Err(format!("HTTP {status}"));
    }
    r.json::<Value>().await.map_err(|e| e.to_string())
}

// ───────────────────────── Audius ─────────────────────────

async fn audius(q: &str) -> Result<Vec<Cand>, String> {
    let v = get_json(
        client()
            .get("https://api.audius.co/v1/tracks/search")
            .query(&[("query", q), ("limit", "10"), ("app_name", AUDIUS_APP)]),
    )
    .await?;
    let data = v["data"].as_array().ok_or("нет data")?;
    Ok(data
        .iter()
        .filter_map(|t| {
            let id = t["id"].as_str()?;
            if !token_ok(id) || t["is_streamable"].as_bool() == Some(false) || t["is_stream_gated"].as_bool() == Some(true) {
                return None;
            }
            let title = t["title"].as_str()?.trim().to_string();
            let artist = t["user"]["name"].as_str()?.trim().to_string();
            let permalink = t["permalink"].as_str().unwrap_or("");
            Some(Cand {
                src: Src::Audius,
                title,
                artist,
                duration: t["duration"].as_f64().unwrap_or(0.0).round() as i64,
                page: format!("https://audius.co{permalink}"),
                play: Play::Url(audius_stream(id)),
                hint: String::new(),
            })
        })
        .collect())
}

fn audius_stream(id: &str) -> String {
    format!("https://api.audius.co/v1/tracks/{id}/stream?app_name={AUDIUS_APP}")
}

// ───────────────────────── SoundCloud ─────────────────────────

fn ytdlp() -> String {
    std::env::var("MUSIC_YTDLP").unwrap_or_else(|_| "yt-dlp".to_string())
}

async fn soundcloud(q: &str) -> Result<Vec<Cand>, String> {
    let _slot = sc_slots().acquire().await.map_err(|e| e.to_string())?;
    let target = format!("scsearch8:{q}");
    let out = tokio::process::Command::new(ytdlp())
        .args(["--no-warnings", "--flat-playlist", "-J", target.as_str()])
        .kill_on_drop(true)
        .output()
        .await
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        return Err(err.lines().last().unwrap_or("yt-dlp error").to_string());
    }
    let v: Value = serde_json::from_slice(&out.stdout).map_err(|e| e.to_string())?;
    let entries = v["entries"].as_array().ok_or("нет entries")?;
    Ok(entries
        .iter()
        .filter_map(|e| {
            let page = e["webpage_url"].as_str().or_else(|| e["url"].as_str())?;
            sc_host(page)?;
            Some(Cand {
                src: Src::SoundCloud,
                title: e["title"].as_str()?.trim().to_string(),
                artist: e["uploader"].as_str()?.trim().to_string(),
                duration: e["duration"].as_f64().unwrap_or(0.0).round() as i64,
                page: page.to_string(),
                play: Play::SoundCloud(hex(page)),
                hint: String::new(),
            })
        })
        .collect())
}

fn sc_locks() -> &'static Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>> {
    static C: OnceLock<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

/// SoundCloud отдаёт только HLS, а `<audio>` его не играет, поэтому трек скачивается в кэш целиком
/// и дальше отдаётся обычным файлом с Range. Одновременные запросы одного трека ждут одну загрузку.
/// `expect` — длительность из поиска: у платных треков SoundCloud отдаёт только 30-секундный отрывок.
pub async fn soundcloud_file(dir: &Path, hexid: &str, expect: Option<i64>) -> Result<PathBuf, String> {
    let page = unhex(hexid).ok_or_else(|| "bad id".to_string())?;
    if sc_host(&page).is_none() {
        return Err("bad url".into());
    }
    let final_path = dir.join(format!("x-sc-{hexid}.m4a"));
    let ready = |p: &Path| std::fs::metadata(p).map(|m| m.len() > 0).unwrap_or(false);
    if ready(&final_path) {
        return Ok(final_path);
    }
    let lock = {
        let mut m = sc_locks().lock().map_err(|e| e.to_string())?;
        if m.len() > 200 {
            m.retain(|_, l| Arc::strong_count(l) > 1);
        }
        m.entry(hexid.to_string()).or_default().clone()
    };
    let _guard = lock.lock().await;
    if ready(&final_path) {
        return Ok(final_path);
    }
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let tmp = dir.join(format!("x-sc-{hexid}.part"));
    let _slot = sc_slots().acquire().await.map_err(|e| e.to_string())?;
    let mut cmd = tokio::process::Command::new(ytdlp());
    cmd.args(["--no-warnings", "--no-playlist", "-f", "bestaudio", "-o"]).arg(&tmp).arg(page.as_str()).kill_on_drop(true);
    let out = match tokio::time::timeout(Duration::from_secs(180), cmd.output()).await {
        Ok(Ok(o)) => o,
        Ok(Err(e)) => return Err(e.to_string()),
        Err(_) => {
            let _ = std::fs::remove_file(&tmp);
            return Err("yt-dlp: нет ответа за 180 с".into());
        }
    };
    if !out.status.success() || !tmp.is_file() {
        let err = String::from_utf8_lossy(&out.stderr);
        let _ = std::fs::remove_file(&tmp);
        return Err(err.lines().last().unwrap_or("yt-dlp error").to_string());
    }
    if let Some(want) = expect.filter(|d| *d > 0) {
        let got = probe_duration(&tmp).await.unwrap_or(0.0);
        if got < want as f64 * 0.85 {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!("SoundCloud отдал отрывок: {got:.0} с из {want}"));
        }
    }
    std::fs::rename(&tmp, &final_path).map_err(|e| e.to_string())?;
    Ok(final_path)
}

async fn probe_duration(p: &Path) -> Option<f64> {
    let out = tokio::process::Command::new("ffprobe")
        .args(["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0"])
        .arg(p)
        .kill_on_drop(true)
        .output()
        .await
        .ok()?;
    String::from_utf8_lossy(&out.stdout).trim().parse().ok()
}

// ───────────────────────── archive.org ─────────────────────────

/// Только буквы, цифры, пробелы и дефис: запрос уходит в Lucene.
fn lucene_safe(s: &str) -> String {
    let t: String = s.chars().map(|c| if c.is_alphanumeric() || c == '-' { c } else { ' ' }).collect();
    t.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Записи, где исполнитель и название совпадают по полям, а внутри — файлы с названиями и длительностью.
async fn archive(artist: &str, title: &str) -> Result<Vec<Cand>, String> {
    let (a, t) = (lucene_safe(artist), lucene_safe(title));
    if a.is_empty() || t.is_empty() {
        return Ok(Vec::new());
    }
    let query = format!(
        "mediatype:audio AND ((creator:(\"{a}\") AND title:(\"{t}\")) OR (title:(\"{a}\") AND title:(\"{t}\")))"
    );
    let v = get_json(client().get("https://archive.org/advancedsearch.php").query(&[
        ("q", query.as_str()),
        ("fl[]", "identifier"),
        ("rows", "6"),
        ("output", "json"),
    ]))
    .await?;
    let ids: Vec<String> = v["response"]["docs"]
        .as_array()
        .ok_or("нет docs")?
        .iter()
        .filter_map(|d| d["identifier"].as_str())
        .filter(|id| archive_id(id))
        .map(str::to_string)
        .collect();
    let mut set = tokio::task::JoinSet::new();
    for id in ids {
        set.spawn(async move { archive_files(&id).await });
    }
    let mut out = Vec::new();
    while let Some(r) = set.join_next().await {
        if let Ok(Ok(mut v)) = r {
            out.append(&mut v);
        }
    }
    Ok(out)
}

/// «245.32» или «4:05» → секунды.
fn parse_length(v: &Value) -> i64 {
    let s = match v {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        _ => return 0,
    };
    if s.contains(':') {
        s.split(':').filter_map(|p| p.trim().parse::<f64>().ok()).fold(0.0, |acc, p| acc * 60.0 + p).round() as i64
    } else {
        s.trim().parse::<f64>().map(|f| f.round() as i64).unwrap_or(0)
    }
}

fn text(v: &Value) -> String {
    match v {
        Value::String(s) => s.trim().to_string(),
        Value::Array(a) => a.iter().filter_map(|x| x.as_str()).map(str::trim).filter(|s| !s.is_empty()).collect::<Vec<_>>().join(", "),
        _ => String::new(),
    }
}

async fn archive_files(id: &str) -> Result<Vec<Cand>, String> {
    let v = get_json(client().get(format!("https://archive.org/metadata/{id}"))).await?;
    let item_title = text(&v["metadata"]["title"]);
    let item_creator = text(&v["metadata"]["creator"]);
    let files = v["files"].as_array().ok_or("нет файлов")?;
    // Один и тот же трек лежит в нескольких форматах: берём mp3 получше.
    let mut best: HashMap<String, (u8, Cand)> = HashMap::new();
    let audio: Vec<&Value> = files
        .iter()
        .filter(|f| f["private"].as_str() != Some("true"))
        .filter(|f| {
            let n = f["name"].as_str().unwrap_or("").to_lowercase();
            n.ends_with(".mp3") || n.ends_with(".ogg") || n.ends_with(".m4a")
        })
        .take(80)
        .collect();
    let single = audio.iter().filter(|f| f["name"].as_str().unwrap_or("").to_lowercase().ends_with(".mp3")).count() == 1;
    for f in audio {
        let name = f["name"].as_str().unwrap_or("");
        let fmt = f["format"].as_str().unwrap_or("").to_lowercase();
        let rank = if fmt == "vbr mp3" { 0 } else if fmt.contains("mp3") { 1 } else { 2 };
        let stem = name.rsplit('/').next().unwrap_or(name);
        let stem = stem.rsplit_once('.').map(|(s, _)| s).unwrap_or(stem).trim_end_matches("_64kb").to_string();
        let mut title = text(&f["title"]);
        if title.is_empty() {
            title = if single && !item_title.is_empty() { item_title.clone() } else { stem.clone() };
        }
        let artist = [text(&f["creator"]), text(&f["artist"]), item_creator.clone()].into_iter().find(|s| !s.is_empty()).unwrap_or_default();
        let mut u = reqwest::Url::parse(&format!("https://archive.org/download/{id}")).map_err(|e| e.to_string())?;
        {
            // Без pop_if_empty получалось «…/{id}//файл», и archive.org часто не отвечал.
            let mut seg = u.path_segments_mut().map_err(|_| "bad url".to_string())?;
            seg.pop_if_empty();
            for part in name.split('/') {
                seg.push(part);
            }
        }
        let cand = Cand {
            src: Src::Archive,
            title,
            artist,
            duration: parse_length(&f["length"]),
            page: format!("https://archive.org/details/{id}"),
            play: Play::Url(u.to_string()),
            hint: id.replace(['-', '_', '.'], " "),
        };
        match best.get(&stem) {
            Some((r, c)) if *r <= rank && c.duration > 0 => {}
            _ => {
                best.insert(stem, (rank, cand));
            }
        }
    }
    Ok(best.into_values().map(|(_, c)| c).collect())
}

// ───────────────────────── воспроизведение ─────────────────────────

/// Источник реально отдаёт аудио: проверяем до того, как отдать плееру.
async fn playable(dir: &Path, c: &Cand) -> Result<(), String> {
    match &c.play {
        Play::SoundCloud(hexid) => soundcloud_file(dir, hexid, Some(c.duration)).await.map(|_| ()),
        Play::Url(url) => {
            let r = client().get(url).header(header::RANGE, "bytes=0-0").send().await.map_err(|e| e.to_string())?;
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
    }
}

/// Прокси с поддержкой Range: браузеру отдаём тот же origin, что и у сайта.
pub async fn proxy_url(url: &str, headers: &HeaderMap) -> Response {
    let mut rb = client().get(url);
    if let Some(r) = headers.get(header::RANGE) {
        rb = rb.header(header::RANGE, r.clone());
    }
    let up = match rb.send().await {
        Ok(r) => r,
        Err(e) => {
            eprintln!("proxy {url}: {e}");
            return (StatusCode::BAD_GATEWAY, e.to_string()).into_response();
        }
    };
    let status = up.status();
    if !status.is_success() {
        eprintln!("proxy {url}: HTTP {status}");
    }
    let src = up.headers().clone();
    let mut resp = Response::new(Body::from_stream(up.bytes_stream()));
    *resp.status_mut() = status;
    let h = resp.headers_mut();
    for name in [header::CONTENT_TYPE, header::CONTENT_LENGTH, header::CONTENT_RANGE, header::ACCEPT_RANGES, header::ETAG, header::LAST_MODIFIED] {
        if let Some(v) = src.get(&name) {
            h.insert(name, v.clone());
        }
    }
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, max-age=3600"));
    resp
}

/// Пометить ответ как подмену: клиенты показывают источник и не кладут его в кэш под id оригинала.
pub fn mark(resp: &mut Response, sub: &Sub) {
    let h = resp.headers_mut();
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    h.insert("x-substitute", HeaderValue::from_static(sub.source));
    for (name, value) in [("x-substitute-title", &sub.title), ("x-substitute-artist", &sub.artist)] {
        if let Ok(v) = HeaderValue::from_str(&pct(value)) {
            h.insert(name, v);
        }
    }
}

/// Процентная кодировка UTF-8 для заголовков (они только ASCII).
fn pct(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

// ───────────── что сейчас играет вместо трека (для сайта) ─────────────

fn subs() -> &'static Mutex<HashMap<String, (Instant, Sub)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, Sub)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn remember_sub(id: &str, sub: &Sub) {
    if let Ok(mut m) = subs().lock() {
        if m.len() > 2000 {
            m.retain(|_, (at, _)| at.elapsed() < FOUND_TTL);
        }
        m.insert(id.to_string(), (Instant::now(), sub.clone()));
    }
}

/// Трек снова отдаётся из YouTube.
pub fn forget_sub(id: &str) {
    if let Ok(mut m) = subs().lock() {
        m.remove(id);
    }
}

pub fn sub_for(id: &str) -> Option<Sub> {
    let m = subs().lock().ok()?;
    let (at, s) = m.get(id)?;
    (at.elapsed() < FOUND_TTL).then(|| s.clone())
}

// ───────────── старые id из поиска (17.11) ─────────────

pub fn is_external(id: &str) -> bool {
    ["x-au:", "x-ov:", "x-sc:", "x-ia:"].iter().any(|p| id.starts_with(p))
}

/// Ссылка на звук для старых `x-au:`/`x-ov:`/`x-ia:`.
pub async fn legacy_url(id: &str) -> Result<String, String> {
    if let Some(rest) = id.strip_prefix("x-au:") {
        return if token_ok(rest) { Ok(audius_stream(rest)) } else { Err("bad id".into()) };
    }
    if let Some(rest) = id.strip_prefix("x-ov:") {
        return if uuid_ok(rest) { openverse_url(rest).await } else { Err("bad id".into()) };
    }
    if let Some(rest) = id.strip_prefix("x-ia:") {
        return if archive_id(rest) { archive_url(rest).await } else { Err("bad id".into()) };
    }
    Err("unknown source".into())
}

fn url_cache() -> &'static Mutex<HashMap<String, (Instant, String)>> {
    static C: OnceLock<Mutex<HashMap<String, (Instant, String)>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cached_url(key: &str) -> Option<String> {
    let m = url_cache().lock().ok()?;
    let (at, url) = m.get(key)?;
    (at.elapsed() < Duration::from_secs(1200)).then(|| url.clone())
}

fn remember_url(key: &str, url: &str) {
    if let Ok(mut m) = url_cache().lock() {
        if m.len() > 500 {
            m.clear();
        }
        m.insert(key.to_string(), (Instant::now(), url.to_string()));
    }
}

async fn openverse_url(uuid: &str) -> Result<String, String> {
    let key = format!("x-ov:{uuid}");
    if let Some(u) = cached_url(&key) {
        return Ok(u);
    }
    let v = get_json(client().get(format!("https://api.openverse.org/v1/audio/{uuid}/"))).await?;
    let url = v["url"].as_str().ok_or_else(|| "нет ссылки".to_string())?.to_string();
    public_url(&url)?;
    remember_url(&key, &url);
    Ok(url)
}

async fn archive_url(id: &str) -> Result<String, String> {
    let key = format!("x-ia:{id}");
    if let Some(u) = cached_url(&key) {
        return Ok(u);
    }
    let files = archive_files(id).await?;
    let pick = files.into_iter().min_by_key(|c| c.title.clone()).ok_or_else(|| "нет аудиофайла".to_string())?;
    let Play::Url(url) = pick.play else { return Err("нет ссылки".into()) };
    remember_url(&key, &url);
    Ok(url)
}

/// Не даём превратить id в запрос к внутренней сети: только https и не внутренний адрес.
fn public_url(raw: &str) -> Result<(), String> {
    let u = reqwest::Url::parse(raw).map_err(|_| "bad url".to_string())?;
    if u.scheme() != "https" {
        return Err("bad scheme".into());
    }
    let host = u.host_str().ok_or_else(|| "no host".to_string())?;
    if host == "localhost" || host.ends_with(".localhost") {
        return Err("bad host".into());
    }
    if let Ok(ip) = host.trim_matches(['[', ']']).parse::<std::net::IpAddr>() {
        let bad = match ip {
            std::net::IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified() || v4.is_broadcast(),
            std::net::IpAddr::V6(v6) => {
                let s = v6.segments();
                v6.is_loopback() || v6.is_unspecified() || (s[0] & 0xfe00) == 0xfc00 || (s[0] & 0xffc0) == 0xfe80
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
    (host == "soundcloud.com" || host.ends_with(".soundcloud.com")).then_some(u)
}

fn hex(s: &str) -> String {
    s.bytes().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Option<String> {
    if s.is_empty() || s.len() % 2 != 0 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(s.len() / 2);
    for i in (0..b.len()).step_by(2) {
        let hi = (b[i] as char).to_digit(16)?;
        let lo = (b[i + 1] as char).to_digit(16)?;
        out.push((hi * 16 + lo) as u8);
    }
    String::from_utf8(out).ok()
}

fn token_ok(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn archive_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 120 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
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

    fn want(a: &str, t: &str, d: i64) -> Want {
        Want::new(a, t, Some(d)).unwrap()
    }

    fn cand(artist: &str, title: &str, d: i64) -> Cand {
        Cand {
            src: Src::Audius,
            title: title.into(),
            artist: artist.into(),
            duration: d,
            page: String::new(),
            play: Play::Url(String::new()),
            hint: String::new(),
        }
    }

    #[test]
    fn archive_covers_are_caught() {
        // С пробного прогона: «Radiohead - Creep (29-Abril-2009)» из записи Radiohead-Creepcover.
        let w = want("Radiohead", "Creep", 238);
        let mut c = cand("Francisco Rojas", "Radiohead - Creep (29-Abril-2009)", 240);
        c.src = Src::Archive;
        c.hint = "Radiohead Creepcover".into();
        assert!(judge(&w, &c).is_err());
        c.hint.clear();
        assert!(judge(&w, &c).is_err());
        let mut own = cand("Radiohead", "Creep", 239);
        own.src = Src::Archive;
        own.hint = "NirvanaLive 1991".into();
        assert_eq!(judge(&w, &own), Err(Miss::Version));
        own.hint = "radiohead creep 202503".into();
        assert_eq!(judge(&w, &own), Ok((Level::Own, 1)));
        assert!(judge(&w, &cand("Radiohead", "Creep (Remastered 2009)", 238)).is_ok());
        assert!(judge(&w, &cand("Radiohead", "Creep [1992]", 238)).is_err());
    }

    #[test]
    fn covers_and_remixes_are_not_the_original() {
        let w = want("Nirvana", "Smells Like Teen Spirit", 301);
        // Тот самый случай с прода: кавер-микс под видом Nirvana.
        assert!(judge(&w, &cand("Dexter Gizmo", "Smells Like Teen Spirit (Nirvana Electrix Cover-Mix)", 297)).is_err());
        assert!(judge(&w, &cand("GN'R Nerd", "Nirvana - Smells Like Teen Spirit (Guitar Cover)", 301)).is_err());
        assert!(judge(&w, &cand("Nirvana", "Smells Like Teen Spirit (Live at Reading 1992)", 300)).is_err());
        assert!(judge(&w, &cand("slō", "smells like teen spirit - slowed + reverb", 330)).is_err());
        let w = want("Daft Punk, Julian Casablancas", "Instant Crush", 338);
        assert!(judge(&w, &cand("TechNoSoul", "Daft Punk - Instant Crush (TechNoSoul Remix)", 396)).is_err());
        assert!(judge(&w, &cand("Rufe Snow", "daft punk - instant crush - cover by rufe snow", 338)).is_err());
    }

    #[test]
    fn same_recording_is_accepted() {
        let w = want("Daft Punk, Julian Casablancas", "Instant Crush", 338);
        assert_eq!(judge(&w, &cand("Daft Punk", "Instant Crush (feat. Julian Casablancas)", 338)), Ok((Level::Own, 0)));
        assert_eq!(judge(&w, &cand("Daft Punk", "Daft Punk - Instant Crush [qed]", 337)), Ok((Level::Own, 1)));
        let w = want("Portishead", "Glory Box", 305);
        assert_eq!(judge(&w, &cand("Portishead Official", "Glory Box", 306)), Ok((Level::Own, 1)));
        assert_eq!(judge(&w, &cand("portisheadofficial", "Glory Box", 306)), Ok((Level::Own, 1)));
        // Перезалив с той же длительностью — да, с заметно другой — нет.
        let w = want("Кино", "Группа крови", 286);
        assert_eq!(judge(&w, &cand("Dimitri Liparteliani", "Кино - Группа крови", 287)), Ok((Level::Reupload, 1)));
        assert_eq!(judge(&w, &cand("Dimitri Liparteliani", "Кино - Группа крови", 300)), Err(Miss::Duration));
        assert_eq!(judge(&w, &cand("Victor Tsoy and band Kino", "Группа Крови", 285)), Ok((Level::Own, 1)));
        assert_eq!(judge(&w, &cand("Kino", "Gruppa krovi", 286)), Ok((Level::Own, 0)));
    }

    #[test]
    fn other_artists_and_snippets_are_rejected() {
        let w = want("Queen", "Bohemian Rhapsody", 355);
        assert_eq!(judge(&w, &cand("Queen Naija", "Butterflies", 200)), Err(Miss::Other));
        assert_eq!(judge(&w, &cand("Malia J", "Bohemian Rhapsody", 355)), Err(Miss::Other));
        assert_eq!(judge(&w, &cand("Queen", "Bohemian Rhapsody", 120)), Err(Miss::Duration));
        // Обрезка на 51 секунду с mp3cut.
        let w = want("Земфира", "Искала", 230);
        assert_eq!(judge(&w, &cand("Земфира", "Искала (Sefon.Pro)", 51)), Err(Miss::Duration));
        // Без длительности оригинала: только сам исполнитель и только «нормальной» длины.
        let w = Want::new("Queen", "Bohemian Rhapsody", None).unwrap();
        assert_eq!(judge(&w, &cand("Queen", "Bohemian Rhapsody", 355)), Ok((Level::Own, 0)));
        assert_eq!(judge(&w, &cand("rizky.rilos", "Queen - Bohemian Rhapsody", 353)), Err(Miss::Other));
        assert_eq!(judge(&w, &cand("Queen", "Bohemian Rhapsody", 3600)), Err(Miss::Duration));
    }

    #[test]
    fn markers_present_in_the_original_are_fine() {
        let w = want("Paul McCartney & Wings", "Live and Let Die", 192);
        assert_eq!(judge(&w, &cand("Wings", "Live And Let Die", 193)), Ok((Level::Own, 1)));
        let w = want("Michael Jackson", "Beat It", 258);
        assert_eq!(judge(&w, &cand("Michael Jackson", "Beat It", 258)), Ok((Level::Own, 0)));
    }

    #[test]
    fn youtube_alternatives() {
        assert!(same_song("Nirvana", "Smells Like Teen Spirit", "Nirvana", "Nirvana - Smells Like Teen Spirit (Official Music Video)"));
        assert!(same_song("Nirvana", "Smells Like Teen Spirit", "NirvanaVEVO", "Smells Like Teen Spirit"));
        // Другая песня того же исполнителя и чужой кавер — нет.
        assert!(!same_song("Nirvana", "Smells Like Teen Spirit", "Nirvana", "Come As You Are"));
        assert!(!same_song("Nirvana", "Smells Like Teen Spirit", "Malia J", "Smells Like Teen Spirit"));
        assert!(!same_song("Nirvana", "Smells Like Teen Spirit", "Nirvana", "Smells Like Teen Spirit (Live At The Paramount)"));
    }

    #[test]
    fn artist_names() {
        assert!(same_artist("Тейлор Свифт", "Taylor Swift"));
        assert!(same_artist("The Beatles", "Beatles"));
        assert!(same_artist("Nirvana", "NirvanaOfficial"));
        assert!(!same_artist("Queen", "Queen Naija"));
        assert!(!same_artist("Kino", "Kinoteatr"));
        assert!(!same_artist("U2", "U2 tribute band"));
    }

    #[test]
    fn titles() {
        assert_eq!(clean_title("Don't Stop Me Now (Remastered 2011)"), "dont stop me now");
        assert_eq!(clean_title("Instant Crush feat. Julian Casablancas"), "instant crush");
        assert_eq!(strip_track_no("07 - Bohemian Rhapsody"), "Bohemian Rhapsody");
        assert_eq!(strip_track_no("1979"), "1979");
        let w = want("Ariana Grande", "7 rings", 179);
        assert_eq!(judge(&w, &cand("Ariana Grande", "7 rings", 179)), Ok((Level::Own, 0)));
        assert_eq!(judge(&w, &cand("Ariana Grande", "03 - 7 rings", 179)), Ok((Level::Own, 0)));
        assert_eq!(parse_length(&Value::String("4:05".into())), 245);
        assert_eq!(parse_length(&Value::String("245.6".into())), 246);
    }

    #[test]
    fn ids_and_hosts() {
        let url = "https://soundcloud.com/molchatdoma/ucypjtb8gi6k";
        assert_eq!(unhex(&hex(url)).as_deref(), Some(url));
        assert!(unhex("zz").is_none());
        assert!(uuid_ok("301df5ad-c4e1-4d16-b4ef-cc1eea8db319"));
        assert!(archive_id("daft-punk-homework"));
        assert!(!archive_id("a/b"));
        assert!(sc_host("https://evil.com/a").is_none());
        assert!(public_url("http://example.com/a").is_err());
        assert!(public_url("https://127.0.0.1/a").is_err());
        assert_eq!(pct("Кино — A"), "%D0%9A%D0%B8%D0%BD%D0%BE%20%E2%80%94%20A");
        let mut u = reqwest::Url::parse("https://archive.org/download/portishead-glory-box").unwrap();
        u.path_segments_mut().unwrap().pop_if_empty().push("Portishead - Glory Box.mp3");
        assert_eq!(u.as_str(), "https://archive.org/download/portishead-glory-box/Portishead%20-%20Glory%20Box.mp3");
    }
}
