//! Рекомендации: «Моя волна», «Для вас», радио по треку и исполнителю, миксы дня.
//!
//! Откуда берутся кандидаты. Главный источник — радио YouTube Music от «зёрен»: треков, которые
//! пользователь дослушал, лайкнул или слушает чаще всего. Радио строится по совместным
//! прослушиваниям миллионов людей, поэтому похожесть там настоящая, а не по названию. Трек, который
//! встречается в радио сразу нескольких зёрен, — самое уверенное попадание.
//!
//! Как кандидаты ранжируются. Вкус к исполнителю считается из лайков, истории (с учётом того,
//! насколько трек дослушан, и с затуханием), пропусков и «не нравится». Дальше режим волны решает,
//! что важнее: знакомое («Любимое»), новое («Незнакомое») или популярное. Заблокированные
//! исполнители и треки с «не нравится» не появляются никогда.
use crate::db::Track;
use crate::yt::{RadioItem, SearchCache, VideoKind};
use futures_util::future::join_all;
use rand::seq::SliceRandom;
use rand::{Rng, SeedableRng};
use rusqlite::params;
use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

const DAY: i64 = 86400;

// ───────────────────────── исполнители ─────────────────────────

/// Имя исполнителя для сравнения.
pub fn norm_artist(a: &str) -> String {
    a.trim().to_lowercase()
}

/// Все исполнители трека: «Кино, Виктор Цой» → [кино, виктор цой]. YouTube Music с `hl=ru`
/// пишет «The Kid LAROI и Justin Bieber» — это тоже два исполнителя.
pub fn artists_of(a: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for part in a.split(',') {
        for p in part.split(" & ").flat_map(|p| p.split(" и ")).flat_map(|p| p.split(" feat. ")).flat_map(|p| p.split(" ft. ")) {
            let n = norm_artist(p);
            if !n.is_empty() && !out.contains(&n) {
                out.push(n);
            }
        }
    }
    out
}

/// Латиницей: YouTube Music пишет одного исполнителя то «Шакира», то «Shakira».
pub fn translit(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.to_lowercase().chars() {
        let r = match c {
            'а' => "a", 'б' => "b", 'в' => "v", 'г' => "g", 'д' => "d", 'е' | 'ё' | 'э' => "e", 'ж' => "zh", 'з' => "z",
            'и' | 'й' | 'і' | 'ї' => "i", 'к' => "k", 'л' => "l", 'м' => "m", 'н' => "n", 'о' => "o", 'п' => "p", 'р' => "r",
            'с' => "s", 'т' => "t", 'у' => "u", 'ф' => "f", 'х' => "h", 'ц' => "ts", 'ч' => "ch", 'ш' => "sh", 'щ' => "sch",
            'ъ' | 'ь' => "", 'ы' => "y", 'ю' => "yu", 'я' => "ya", 'є' => "e",
            c if c.is_alphanumeric() => {
                out.push(c);
                continue;
            }
            _ => " ",
        };
        out.push_str(r);
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Ключ для сравнения вкуса: согласные транслита. «Тейлор Свифт» и «Taylor Swift», «Фейд» и «Feid»
/// дают один ключ. Короткие имена — целиком, иначе слишком много совпадений.
pub fn artist_key(name: &str) -> String {
    let t = translit(name);
    let skeleton: String = t
        .chars()
        .filter(|c| c.is_alphanumeric() && !"aeiouy".contains(*c))
        .map(|c| match c {
            'w' => 'v',
            'c' => 'k',
            'q' => 'k',
            _ => c,
        })
        .collect();
    if skeleton.chars().count() >= 3 { skeleton } else { t.replace(' ', "") }
}

/// Ключи всех исполнителей трека.
pub fn keys_of(a: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for n in artists_of(a) {
        let k = artist_key(&n);
        if !k.is_empty() && !out.contains(&k) {
            out.push(k);
        }
    }
    out
}

/// «Шакира, Burna Boy» → «Шакира».
fn first_artist(a: &str) -> String {
    a.split(',').next().unwrap_or(a).trim().to_string()
}

fn has_cyrillic(s: &str) -> bool {
    s.chars().any(|c| ('\u{0400}'..='\u{04FF}').contains(&c))
}

// ───────────────────────── профиль вкуса ─────────────────────────

/// Всё, что известно о вкусе пользователя. Собирается одним проходом по базе.
#[derive(Default)]
pub struct Profile {
    /// Вкус к исполнителю, нормирован так, что самый любимый = 1. Отрицательный — пропускает.
    taste: HashMap<String, f64>,
    /// Исполнители из лайков и истории за год.
    known: HashSet<String>,
    /// Лайкнутые треки: id → (трек, когда лайкнул).
    liked: HashMap<String, (Track, i64)>,
    /// Прослушанные за год: id → (последний раз, сколько раз).
    heard: HashMap<String, (i64, i64)>,
    /// Пропущенные в первые секунды за 60 дней: id → когда.
    skipped: HashMap<String, i64>,
    disliked: HashSet<String>,
    /// Заблокированные исполнители (нормированные имена).
    blocked: HashSet<String>,
    genres: Vec<String>,
    /// Последние прослушивания, новые в конце: (трек, когда, сколько секунд слушали).
    tail: Vec<(Track, i64, Option<i64>)>,
    /// Самый «свой» трек каждого исполнителя — зерно для радио: исполнитель → (трек, вес).
    best: HashMap<String, (Track, f64)>,
    /// Имя исполнителя как его пишут (для подписей): нормированное → исходное.
    display: HashMap<String, String>,
    /// Все треки из истории за год: id → трек.
    catalog: HashMap<String, Track>,
    /// Что слушали за неделю, по исполнителю и названию: та же песня бывает под разными id.
    heard_titles: HashSet<String>,
}

impl Profile {
    pub fn load(db: &rusqlite::Connection, user: i64) -> Profile {
        let now = now();
        let mut p = Profile::default();
        let mut raw: HashMap<String, f64> = HashMap::new();
        let mut track_score: HashMap<String, (Track, f64)> = HashMap::new();

        if let Ok(mut st) = db.prepare(
            "SELECT track_id, title, artist, album, duration, thumb, created FROM likes WHERE user_id=?1",
        ) {
            let rows = st.query_map([user], |r| Ok((crate::db::row_track(r)?, r.get::<_, i64>(6)?)));
            for (t, created) in rows.into_iter().flatten().flatten() {
                let days = (now - created).max(0) as f64 / DAY as f64;
                let w = 3.0 * (0.5 + 0.5 * (-days / 180.0).exp());
                for a in keys_of(&t.artist) {
                    *raw.entry(a.clone()).or_default() += w;
                    p.known.insert(a);
                }
                remember_display(&mut p.display, &t.artist);
                track_score.entry(t.id.clone()).or_insert_with(|| (t.clone(), 0.0)).1 += 3.0;
                p.liked.insert(t.id.clone(), (t, created));
            }
        }

        if let Ok(mut st) = db.prepare(
            "SELECT track_id, title, artist, album, duration, thumb, played_at, secs FROM history
             WHERE user_id=?1 AND played_at > ?2 ORDER BY played_at, rowid",
        ) {
            let rows = st.query_map(params![user, now - 365 * DAY], |r| {
                Ok((crate::db::row_track(r)?, r.get::<_, i64>(6)?, r.get::<_, Option<i64>>(7)?))
            });
            for (t, at, secs) in rows.into_iter().flatten().flatten() {
                let days = (now - at).max(0) as f64 / DAY as f64;
                // Дослушанный трек весит больше: половина трека — уже явный интерес.
                let completion = match (secs, t.duration) {
                    (Some(s), d) if d > 0 => (s as f64 / d as f64).clamp(0.2, 1.0),
                    _ => 1.0,
                };
                // Недавнее важнее, но любимое годами не забывается.
                let w = completion * (0.7 * (-days / 30.0).exp() + 0.3 * (-days / 200.0).exp());
                for a in keys_of(&t.artist) {
                    *raw.entry(a.clone()).or_default() += w;
                    p.known.insert(a);
                }
                remember_display(&mut p.display, &t.artist);
                if at > now - 7 * DAY {
                    p.heard_titles.insert(title_key(&t));
                }
                let e = p.heard.entry(t.id.clone()).or_insert((0, 0));
                e.0 = e.0.max(at);
                e.1 += 1;
                track_score.entry(t.id.clone()).or_insert_with(|| (t.clone(), 0.0)).1 += w + 0.3;
                p.catalog.entry(t.id.clone()).or_insert_with(|| t.clone());
                p.tail.push((t, at, secs));
                if p.tail.len() > 40 {
                    p.tail.remove(0);
                }
            }
        }

        if let Ok(mut st) = db.prepare("SELECT track_id, artist, at FROM skips WHERE user_id=?1 AND at > ?2") {
            let rows = st.query_map(params![user, now - 60 * DAY], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, i64>(2)?))
            });
            for (id, artist, at) in rows.into_iter().flatten().flatten() {
                let days = (now - at).max(0) as f64 / DAY as f64;
                for a in keys_of(&artist) {
                    *raw.entry(a).or_default() -= 0.5 * (-days / 20.0).exp();
                }
                let e = p.skipped.entry(id).or_insert(0);
                *e = (*e).max(at);
            }
        }

        if let Ok(mut st) = db.prepare("SELECT track_id, artist FROM dislikes WHERE user_id=?1") {
            let rows = st.query_map([user], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)));
            for (id, artist) in rows.into_iter().flatten().flatten() {
                for a in keys_of(&artist) {
                    *raw.entry(a).or_default() -= 1.5;
                }
                p.disliked.insert(id);
            }
        }

        if let Ok(mut st) = db.prepare("SELECT norm FROM blocked_artists WHERE user_id=?1") {
            p.blocked = st.query_map([user], |r| r.get::<_, String>(0)).into_iter().flatten().flatten().map(|n| translit(&n)).collect();
        }
        p.genres = genres(db, user);

        let max = raw.values().cloned().fold(0.0f64, f64::max).max(0.001);
        p.taste = raw.into_iter().map(|(a, w)| (a, (w / max).max(-1.0))).collect();

        // Лучший трек исполнителя — тот, что слушали и лайкали больше всего.
        for (_, (t, s)) in track_score {
            if p.disliked.contains(&t.id) {
                continue;
            }
            if let Some(a) = keys_of(&t.artist).into_iter().next() {
                let e = p.best.entry(a).or_insert_with(|| (t.clone(), f64::MIN));
                if s > e.1 {
                    *e = (t, s);
                }
            }
        }
        p
    }

    pub fn is_empty(&self) -> bool {
        self.liked.is_empty() && self.heard.is_empty()
    }

    /// Вкус к треку — по самому любимому из его исполнителей.
    fn taste_of(&self, artist: &str) -> f64 {
        keys_of(artist).iter().filter_map(|a| self.taste.get(a)).cloned().fold(None, |m: Option<f64>, x| Some(m.map_or(x, |m| m.max(x)))).unwrap_or(0.0)
    }

    fn is_known(&self, artist: &str) -> bool {
        keys_of(artist).iter().any(|a| self.known.contains(a))
    }

    pub fn is_blocked(&self, artist: &str) -> bool {
        if self.blocked.is_empty() {
            return false;
        }
        self.blocked.contains(&translit(artist)) || artists_of(artist).iter().any(|a| self.blocked.contains(&translit(a)))
    }

    /// Эту песню слушали на этой неделе (возможно, в другой версии).
    fn heard_song(&self, t: &Track) -> bool {
        self.heard_within(&t.id, 7) || self.heard_titles.contains(&title_key(t))
    }

    /// Исполнители по убыванию вкуса (только те, кого любят).
    fn top_artists(&self, n: usize) -> Vec<(String, f64)> {
        let mut v: Vec<(String, f64)> = self.taste.iter().filter(|(_, w)| **w > 0.05).map(|(a, w)| (a.clone(), *w)).collect();
        v.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        v.truncate(n);
        v
    }

    fn display_name(&self, norm: &str) -> String {
        self.display.get(norm).cloned().unwrap_or_else(|| norm.to_string())
    }

    /// Доля русскоязычных исполнителей во вкусе: от неё зависит язык запросов «под настроение».
    fn russian_share(&self) -> f64 {
        let (mut ru, mut all) = (0.0, 0.0);
        for (a, w) in &self.taste {
            if *w > 0.0 {
                all += w;
                if has_cyrillic(a) {
                    ru += w;
                }
            }
        }
        if all == 0.0 {
            return if self.genres.iter().any(|g| g == "russian") { 1.0 } else { 0.0 };
        }
        ru / all
    }

    fn heard_within(&self, id: &str, days: i64) -> bool {
        self.heard.get(id).map(|(at, _)| *at > now() - days * DAY).unwrap_or(false)
    }

    fn skipped_within(&self, id: &str, days: i64) -> bool {
        self.skipped.get(id).map(|at| *at > now() - days * DAY).unwrap_or(false)
    }
}

fn remember_display(map: &mut HashMap<String, String>, artist: &str) {
    for part in artist.split(',') {
        let name = part.trim();
        if !name.is_empty() {
            map.entry(artist_key(name)).or_insert_with(|| name.to_string());
        }
    }
}

/// Песня без учёта версии: первый исполнитель + название без скобок.
fn title_key(t: &Track) -> String {
    format!("{}\u{1}{}", keys_of(&t.artist).into_iter().next().unwrap_or_default(), norm_title(&t.title))
}

pub fn genres(db: &rusqlite::Connection, user: i64) -> Vec<String> {
    let Ok(mut st) = db.prepare("SELECT genre FROM user_genres WHERE user_id=?1") else { return Vec::new() };
    st.query_map(params![user], |r| r.get::<_, String>(0))
        .map(|rows| rows.flatten().collect())
        .unwrap_or_default()
}

fn load_profile(db: &Mutex<rusqlite::Connection>, user: i64) -> Profile {
    match db.lock() {
        Ok(c) => Profile::load(&c, user),
        Err(_) => Profile::default(),
    }
}

// ───────────────────────── настройки волны ─────────────────────────

/// Характер волны, как в «Моей волне» Яндекс Музыки.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    /// Знакомое и новое вперемешку.
    All,
    /// Любимые исполнители и треки.
    Favorite,
    /// Только исполнители, которых пользователь ещё не слушал.
    Discover,
    /// Популярное сейчас, но под вкус.
    Popular,
}

impl Mode {
    pub fn parse(s: Option<&str>) -> Mode {
        match s {
            Some("favorite") => Mode::Favorite,
            Some("discover") => Mode::Discover,
            Some("popular") => Mode::Popular,
            _ => Mode::All,
        }
    }
}

#[derive(Clone, Debug)]
pub struct WaveParams {
    pub mode: Mode,
    /// energetic | happy | calm | sad
    pub mood: Option<String>,
    /// wakeup | road | work | workout | sleep | party
    pub activity: Option<String>,
    /// Страна чарта для «Популярного».
    pub region: String,
}

/// Запросы «под настроение»: по-английски или по-русски, смотря что пользователь слушает.
fn mood_queries(mood: Option<&str>, activity: Option<&str>, russian: bool, genre: Option<&str>) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let g_en = genre.map(|g| genre_query_word(g)).unwrap_or_default();
    let push = |out: &mut Vec<(String, String)>, en: &str, ru: &str, why: &str| {
        if russian {
            out.push((format!("{ru} русские песни"), why.to_string()));
            out.push((format!("{en} songs"), why.to_string()));
        } else {
            out.push((format!("{en} {g_en} songs").split_whitespace().collect::<Vec<_>>().join(" "), why.to_string()));
            out.push((format!("{en} songs"), why.to_string()));
        }
    };
    match mood {
        Some("energetic") => push(&mut out, "energetic upbeat", "бодрые", "Бодрое"),
        Some("happy") => push(&mut out, "happy feel good", "весёлые", "Весёлое"),
        Some("calm") => push(&mut out, "calm chill", "спокойные", "Спокойное"),
        Some("sad") => push(&mut out, "sad emotional", "грустные", "Грустное"),
        _ => {}
    }
    match activity {
        Some("wakeup") => push(&mut out, "morning wake up", "утренние", "Просыпаюсь"),
        Some("road") => push(&mut out, "road trip driving", "в дорогу", "В дороге"),
        Some("work") => push(&mut out, "chill lofi", "спокойные фоновые", "Работаю"),
        Some("workout") => push(&mut out, "workout gym", "для тренировки", "Тренируюсь"),
        Some("sleep") => push(&mut out, "sleep calm ambient", "колыбельные спокойные", "Засыпаю"),
        Some("party") => push(&mut out, "party dance", "для вечеринки", "Вечеринка"),
        _ => {}
    }
    out
}

fn genre_query_word(g: &str) -> String {
    match g {
        "russian" | "soundtrack" => String::new(),
        "kpop" => "k-pop".into(),
        "rnb" => "r&b".into(),
        other => other.into(),
    }
}

// ───────────────────────── зёрна и кандидаты ─────────────────────────

/// От чего строим радио.
#[derive(Clone, Debug)]
struct Seed {
    video: String,
    playlist: Option<String>,
    weight: f64,
    why: String,
    /// «похоже на «Tusa»» / «похоже на Шакиру»: для режима «Незнакомое».
    like: String,
    /// Сколько первых треков радио брать (дальше радио уходит от зерна).
    depth: usize,
}

impl Seed {
    fn track(t: &Track, weight: f64, why: String) -> Seed {
        Seed { video: t.id.clone(), playlist: None, weight, why, like: format!("похоже на {}", quote(t)), depth: 50 }
    }
}

/// Трек в выдаче + короткое объяснение, почему он здесь.
#[derive(Serialize, Clone, Debug)]
pub struct WaveTrack {
    #[serde(flatten)]
    pub track: Track,
    pub why: String,
}

struct Cand {
    track: Track,
    kind: VideoKind,
    /// Сколько «голосов» набрал трек у зёрен (с учётом позиции в радио).
    support: f64,
    /// Самый сильный голос: от него объяснение.
    best: f64,
    why: String,
    like: String,
}

#[derive(Default)]
struct Pool {
    by_id: HashMap<String, usize>,
    items: Vec<Cand>,
}

impl Pool {
    fn add(&mut self, t: &Track, kind: VideoKind, vote: f64, why: &str) {
        self.add_like(t, kind, vote, why, why)
    }

    fn add_like(&mut self, t: &Track, kind: VideoKind, vote: f64, why: &str, like: &str) {
        if let Some(&i) = self.by_id.get(&t.id) {
            let c = &mut self.items[i];
            c.support += vote;
            if vote > c.best {
                c.best = vote;
                c.why = why.to_string();
                c.like = like.to_string();
            }
            // Песня из альбома лучше клипа: обложка квадратная, без вступлений из клипа.
            if c.kind != VideoKind::Song && kind == VideoKind::Song {
                c.kind = kind;
            }
            return;
        }
        self.by_id.insert(t.id.clone(), self.items.len());
        self.items.push(Cand { track: t.clone(), kind, support: vote, best: vote, why: why.to_string(), like: like.to_string() });
    }

    /// Радио зерна: чем ближе к началу, тем похожее.
    fn add_radio(&mut self, seed: &Seed, radio: &[RadioItem]) {
        for (pos, r) in radio.iter().take(seed.depth).enumerate() {
            if r.track.id == seed.video {
                continue;
            }
            let sim = 1.0 / (1.0 + pos as f64 / 15.0);
            self.add_like(&r.track, r.kind, seed.weight * sim, &seed.why, &seed.like);
        }
    }
}

/// Пометки в названии, которые обычно значат «не та версия».
fn version_penalty(title: &str) -> f64 {
    let t = title.to_lowercase();
    const BAD: &[&str] = &["karaoke", "караоке", "nightcore", "8d audio", "8d ", "slowed", "sped up", "speed up", "reverb", "instrumental", "минус", "cover", "кавер"];
    if BAD.iter().any(|b| t.contains(b)) {
        return 0.35;
    }
    if t.contains("live") || t.contains("концерт") || t.contains("acoustic version") {
        return 0.75;
    }
    1.0
}

/// Общая чистка: никогда не показываем заблокированное, «не нравится», мусор и часовые миксы.
fn acceptable(p: &Profile, t: &Track) -> bool {
    !p.disliked.contains(&t.id)
        && !p.is_blocked(&t.artist)
        && !t.artist.trim().is_empty()
        && !is_junk(&t.title)
        && t.duration <= 720
        && (t.duration == 0 || t.duration >= 60)
}

async fn radios(cache: &SearchCache, http: &reqwest::Client, seeds: &[Seed]) -> Vec<Vec<RadioItem>> {
    join_all(seeds.iter().map(|s| cache.radio(http, &s.video, s.playlist.as_deref()))).await
}

/// Взвешенный случайный выбор `n` элементов без повторов.
fn weighted_sample<T: Clone>(items: &[(T, f64)], n: usize, rng: &mut impl Rng) -> Vec<T> {
    let mut pool: Vec<(T, f64)> = items.iter().filter(|(_, w)| *w > 0.0).cloned().collect();
    let mut out = Vec::new();
    while out.len() < n && !pool.is_empty() {
        let total: f64 = pool.iter().map(|(_, w)| *w).sum();
        let mut x = rng.gen::<f64>() * total;
        let mut idx = pool.len() - 1;
        for (i, (_, w)) in pool.iter().enumerate() {
            if x < *w {
                idx = i;
                break;
            }
            x -= w;
        }
        out.push(pool.remove(idx).0);
    }
    out
}

/// Последние дослушанные треки (новые первыми): то, что слушают прямо сейчас.
fn session_tracks(p: &Profile, within: i64, n: usize) -> Vec<Track> {
    let since = now() - within;
    let mut out: Vec<Track> = Vec::new();
    for (t, at, secs) in p.tail.iter().rev() {
        if *at < since || out.len() >= n {
            break;
        }
        let finished = match secs {
            Some(s) => *s >= 90 || (t.duration > 0 && *s * 2 >= t.duration),
            None => true,
        };
        if finished && !p.skipped_within(&t.id, 1) && !p.disliked.contains(&t.id) && !out.iter().any(|o| o.id == t.id) {
            out.push(t.clone());
        }
    }
    out
}

fn quote(t: &Track) -> String {
    format!("«{}»", t.title)
}

// ───────────────────────── «Моя волна» ─────────────────────────

pub async fn wave(
    db: &Mutex<rusqlite::Connection>,
    cache: &SearchCache,
    http: &reqwest::Client,
    chart: &[Track],
    user: i64,
    params: &WaveParams,
) -> Vec<WaveTrack> {
    let p = load_profile(db, user);
    let mut rng = rand::rngs::StdRng::from_entropy();
    let mood_on = params.mood.is_some() || params.activity.is_some();
    // Под настроение личные зёрна не нужны: их радио уводит от настроения. Вкус там только
    // поднимает знакомых исполнителей среди найденного.
    let personal = if mood_on { 0.0 } else { 1.0 };
    let mut seeds: Vec<Seed> = Vec::new();
    let mut pool = Pool::default();

    // 1. Что происходит сейчас: свежий лайк и последние дослушанные треки.
    let mut recent_likes: Vec<&(Track, i64)> = p.liked.values().filter(|(_, at)| *at > now() - 6 * 3600).collect();
    recent_likes.sort_by_key(|(_, at)| -at);
    for (t, _) in recent_likes.into_iter().take(2) {
        seeds.push(Seed::track(t, 1.3 * personal, format!("Тебе понравилась {}", quote(t))));
    }
    for (i, t) in session_tracks(&p, 3 * 3600, 2).into_iter().enumerate() {
        seeds.push(Seed::track(&t, (1.1 - i as f64 * 0.2) * personal, format!("Похоже на {}", quote(&t))));
    }

    // 2. Настроение и занятие: зёрна из поиска, они главные.
    if mood_on {
        let genre = p.genres.first().map(String::as_str);
        let queries = mood_queries(params.mood.as_deref(), params.activity.as_deref(), p.russian_share() > 0.5, genre);
        let found = join_all(queries.iter().map(|(q, _)| cache.songs(http, q))).await;
        for ((_, why), tracks) in queries.iter().zip(found) {
            let why = format!("Под настроение «{why}»");
            let good: Vec<Track> = tracks.into_iter().filter(|t| acceptable(&p, t)).take(20).collect();
            for (i, t) in good.iter().enumerate() {
                let boost = 1.0 + 0.5 * p.taste_of(&t.artist).max(0.0);
                pool.add(t, VideoKind::Song, 1.2 * boost / (1.0 + i as f64 / 10.0), &why);
            }
            // Радио от самых точных попаданий в настроение: только начало, дальше оно уходит в сторону.
            for t in good.iter().take(2) {
                let mut seed = Seed::track(t, 0.6, why.clone());
                seed.depth = 15;
                seeds.push(seed);
            }
        }
    }

    // 3. Вкус: исполнители, которых любят, — через их самые «свои» треки.
    let top = p.top_artists(10);
    let picks = weighted_sample(&top, 3, &mut rng);
    for a in picks {
        if let Some((t, _)) = p.best.get(&a) {
            let w = p.taste.get(&a).cloned().unwrap_or(0.5).max(0.35);
            let name = p.display_name(&a);
            let mut seed = Seed::track(t, 0.85 * w * personal, format!("Ты часто слушаешь {name}"));
            seed.like = format!("похоже на {name}");
            seeds.push(seed);
        }
    }
    // 4. Один из лайков: свежие лайки вероятнее.
    let likes: Vec<(Track, f64)> = p
        .liked
        .values()
        .map(|(t, at)| (t.clone(), 1.0 + 2.0 * (-((now() - at) as f64 / DAY as f64) / 60.0).exp()))
        .collect();
    for t in weighted_sample(&likes, 1, &mut rng) {
        seeds.push(Seed::track(&t, 0.65 * personal, format!("Похоже на {}", quote(&t))));
    }

    // 5. Режимы.
    let chart_ids: HashSet<String> = chart.iter().map(|t| t.id.clone()).collect();
    match params.mode {
        Mode::Popular => {
            for (i, t) in chart.iter().enumerate().take(50) {
                if acceptable(&p, t) {
                    let boost = 1.0 + 0.6 * p.taste_of(&t.artist).max(0.0);
                    pool.add(t, VideoKind::Song, 0.8 * boost / (1.0 + i as f64 / 20.0), "Популярно сейчас");
                }
            }
            // Радио от хитов, которые ближе всего к вкусу.
            let mut close: Vec<&Track> = chart.iter().take(50).filter(|t| acceptable(&p, t)).collect();
            close.sort_by(|a, b| p.taste_of(&b.artist).partial_cmp(&p.taste_of(&a.artist)).unwrap_or(std::cmp::Ordering::Equal));
            for t in close.into_iter().take(2) {
                seeds.push(Seed::track(t, 0.8, "Популярно сейчас".into()));
            }
        }
        Mode::Favorite => {
            // Любимое, которое давно не играло.
            for (t, _) in p.liked.values() {
                if !p.heard_within(&t.id, 4) && acceptable(&p, t) {
                    pool.add(t, VideoKind::Song, 0.75 + rng.gen::<f64>() * 0.3, "Из того, что тебе нравится");
                }
            }
            let mut most: Vec<(&String, &(i64, i64))> = p.heard.iter().filter(|(_, (_, n))| *n >= 3).collect();
            most.sort_by_key(|(_, (_, n))| -n);
            for (id, _) in most.into_iter().take(40) {
                if p.heard_within(id, 3) {
                    continue;
                }
                if let Some(t) = p.catalog.get(id.as_str()) {
                    if acceptable(&p, t) {
                        pool.add(t, VideoKind::Song, 0.6, "Ты часто это слушаешь");
                    }
                }
            }
        }
        _ => {}
    }

    // 6. Холодный старт: ни лайков, ни истории.
    if p.is_empty() && !mood_on {
        for g in p.genres.iter().take(4) {
            let tracks = cache.songs(http, &genre_query(g)).await;
            let why = format!("Твой жанр: {}", genre_title(g));
            for (i, t) in tracks.iter().filter(|t| acceptable(&p, t)).enumerate().take(10) {
                pool.add(t, VideoKind::Song, 0.6 / (1.0 + i as f64 / 5.0), &why);
                if i < 2 {
                    seeds.push(Seed::track(t, 0.8, why.clone()));
                }
            }
        }
        if seeds.is_empty() {
            for (i, t) in chart.iter().take(30).enumerate() {
                pool.add(t, VideoKind::Song, 0.6 / (1.0 + i as f64 / 10.0), "Популярно сейчас");
                if i < 3 {
                    seeds.push(Seed::track(t, 0.6, "Популярно сейчас".into()));
                }
            }
        }
    }

    let seeds = dedup_seeds(seeds, 7);
    for (seed, radio) in seeds.iter().zip(radios(cache, http, &seeds).await) {
        pool.add_radio(seed, &radio);
    }

    let mut scored: Vec<(f64, Track, String)> = Vec::new();
    let seed_ids: HashSet<&str> = seeds.iter().map(|s| s.video.as_str()).collect();
    for c in pool.items {
        let t = &c.track;
        if !acceptable(&p, t) || p.skipped_within(&t.id, 30) {
            continue;
        }
        let known = p.is_known(&t.artist);
        let taste = p.taste_of(&t.artist);
        let fresh_heard = p.heard_song(t);
        // Свежее не повторяем, кроме «Любимого»: там знакомое — суть режима.
        if fresh_heard && !(params.mode == Mode::Favorite && !p.heard_within(&t.id, 2)) {
            continue;
        }
        if seed_ids.contains(t.id.as_str()) && params.mode != Mode::Favorite {
            continue;
        }
        let mut s = c.support;
        let mut why = c.why.clone();
        match params.mode {
            Mode::All => {
                s *= 1.0 + 0.35 * taste.max(0.0);
                if !known {
                    s *= 1.08;
                }
            }
            Mode::Favorite => {
                if known {
                    s *= 1.5 + taste.max(0.0);
                } else {
                    s *= 0.3;
                }
            }
            Mode::Discover => {
                if known {
                    continue;
                }
                s *= 1.2;
                why = format!("Новое для тебя, {}", lower_first(&c.like));
            }
            Mode::Popular => {
                s *= 1.0 + 0.3 * taste.max(0.0);
                if chart_ids.contains(&t.id) {
                    s *= 1.4;
                }
            }
        }
        if taste < 0.0 {
            // Исполнителя пропускают или ставят «не нравится».
            s *= (1.0 + taste).max(0.15);
        }
        s *= match c.kind {
            VideoKind::Song => 1.0,
            VideoKind::Clip => 0.92,
            VideoKind::User => 0.55,
            VideoKind::Other => 0.8,
        };
        s *= version_penalty(&t.title);
        s *= 1.0 + rng.gen::<f64>() * 0.15;
        scored.push((s, c.track, why));
    }
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    let per_artist = if params.mode == Mode::Favorite { 3 } else { 2 };
    // Одно зерно не забирает всю волну: последний трек задаёт тон, но вкус остаётся.
    let mut out = pick(cap_per_reason(dedup_titles(scored), 12), 30, per_artist);

    // Совсем пусто (YouTube не ответил): старая добрая подборка из поиска, лишь бы играло.
    if out.len() < 8 {
        out.extend(fallback(&p, cache, http, chart, &out).await);
    }
    out
}

/// «Под настроение …» → «под настроение …»: объяснение идёт после запятой.
fn lower_first(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_lowercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// Не больше `max` треков с одним объяснением; лишние уходят в конец (на случай нехватки).
fn cap_per_reason(sorted: Vec<(f64, Track, String)>, max: usize) -> Vec<(f64, Track, String)> {
    let mut count: HashMap<String, usize> = HashMap::new();
    let (mut head, mut tail) = (Vec::new(), Vec::new());
    for item in sorted {
        let n = count.entry(item.2.clone()).or_default();
        *n += 1;
        if *n <= max { head.push(item) } else { tail.push(item) }
    }
    head.extend(tail);
    head
}

fn dedup_seeds(mut seeds: Vec<Seed>, max: usize) -> Vec<Seed> {
    let mut seen = HashSet::new();
    seeds.retain(|s| s.weight > 0.0 && crate::yt::valid_video_id(&s.video) && seen.insert(s.video.clone()));
    seeds.sort_by(|a, b| b.weight.partial_cmp(&a.weight).unwrap_or(std::cmp::Ordering::Equal));
    seeds.truncate(max);
    seeds
}

/// Одна песня в разных версиях («Rutba» и «Rutba (From …)») — оставляем лучшую.
fn dedup_titles(sorted: Vec<(f64, Track, String)>) -> Vec<(f64, Track, String)> {
    let mut seen = HashSet::new();
    sorted
        .into_iter()
        .filter(|(_, t, _)| seen.insert(title_key(t)))
        .collect()
}

/// Если радио недоступно: исполнители вкуса и чарт через поиск.
async fn fallback(p: &Profile, cache: &SearchCache, http: &reqwest::Client, chart: &[Track], have: &[WaveTrack]) -> Vec<WaveTrack> {
    let mut seen: HashSet<String> = have.iter().map(|w| w.track.id.clone()).collect();
    let mut out: Vec<(f64, Track, String)> = Vec::new();
    for (a, w) in p.top_artists(5) {
        let name = p.display_name(&a);
        for t in cache.songs(http, &name).await {
            if keys_of(&t.artist).contains(&a) && acceptable(p, &t) && !p.heard_song(&t) && seen.insert(t.id.clone()) {
                out.push((w + rand::random::<f64>() * 0.2, t, format!("Ты часто слушаешь {name}")));
            }
        }
    }
    for t in chart.iter().take(20) {
        if acceptable(p, t) && !p.heard_within(&t.id, 7) && seen.insert(t.id.clone()) {
            out.push((0.3 + rand::random::<f64>() * 0.2, t.clone(), "Популярно сейчас".into()));
        }
    }
    out.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    pick(out, 30usize.saturating_sub(have.len()), 2)
}

// ───────────────────────── «Для вас» ─────────────────────────

/// Новые для пользователя треки в духе его вкуса: ничего из того, что он уже слышал.
pub async fn recommend(db: &Mutex<rusqlite::Connection>, cache: &SearchCache, http: &reqwest::Client, user: i64) -> Vec<Track> {
    let p = load_profile(db, user);
    let mut rng = rand::rngs::StdRng::from_entropy();
    if p.is_empty() {
        // Новичок: жанры из регистрации.
        let mut out: Vec<(f64, Track)> = Vec::new();
        let mut seen = HashSet::new();
        for (i, g) in p.genres.iter().enumerate() {
            for t in cache.songs(http, &genre_query(g)).await {
                if acceptable(&p, &t) && seen.insert(t.id.clone()) {
                    out.push((0.6 - i as f64 * 0.03 + rng.gen::<f64>() * 0.25, t));
                }
            }
        }
        out.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        return out.into_iter().take(40).map(|(_, t)| t).collect();
    }
    let mut seeds = Vec::new();
    let top = p.top_artists(8);
    for a in weighted_sample(&top, 4, &mut rng) {
        if let Some((t, _)) = p.best.get(&a) {
            seeds.push(Seed::track(t, p.taste.get(&a).cloned().unwrap_or(0.5).max(0.4), String::new()));
        }
    }
    let likes: Vec<(Track, f64)> = p
        .liked
        .values()
        .filter(|(_, at)| *at > now() - 45 * DAY)
        .map(|(t, _)| (t.clone(), 1.0))
        .collect();
    for t in weighted_sample(&likes, 2, &mut rng) {
        seeds.push(Seed::track(&t, 0.8, String::new()));
    }
    let seeds = dedup_seeds(seeds, 6);
    let mut pool = Pool::default();
    for (seed, radio) in seeds.iter().zip(radios(cache, http, &seeds).await) {
        pool.add_radio(seed, &radio);
    }
    let mut scored: Vec<(f64, Track, String)> = pool
        .items
        .into_iter()
        .filter(|c| acceptable(&p, &c.track) && !p.heard.contains_key(&c.track.id) && !p.heard_song(&c.track) && !p.liked.contains_key(&c.track.id) && !p.skipped.contains_key(&c.track.id))
        .map(|c| {
            let taste = p.taste_of(&c.track.artist);
            let mut s = c.support * (1.0 + 0.5 * taste.max(0.0));
            if taste < 0.0 {
                s *= (1.0 + taste).max(0.15);
            }
            if c.kind == VideoKind::User {
                s *= 0.55;
            }
            s *= version_penalty(&c.track.title) * (1.0 + rng.gen::<f64>() * 0.2);
            (s, c.track, String::new())
        })
        .collect();
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    pick(dedup_titles(scored), 40, 2).into_iter().map(|w| w.track).collect()
}

// ───────────────────────── радио ─────────────────────────

/// Радио по треку: похожие песни в порядке YouTube Music, без того, что пользователь не хочет слышать.
pub async fn track_radio(
    db: &Mutex<rusqlite::Connection>,
    cache: &SearchCache,
    http: &reqwest::Client,
    user: i64,
    seed: &Track,
) -> Vec<WaveTrack> {
    let p = load_profile(db, user);
    let mut radio = cache.radio(http, &seed.id, None).await;
    if radio.len() < 10 && !seed.artist.is_empty() {
        // Ролик без своего радио (редкая загрузка): берём песню с тем же названием из поиска.
        let q = format!("{} {}", first_artist(&seed.artist), seed.title);
        if let Some(t) = cache.search(http, &q).await.into_iter().find(|t| t.id != seed.id) {
            radio = cache.radio(http, &t.id, None).await;
        }
    }
    let why = format!("Радио {}", quote(seed));
    let seed_artist = keys_of(&seed.artist).into_iter().next().unwrap_or_default();
    finish_radio(&p, radio, Some(seed), &seed_artist, &why, 3)
}

/// Радио исполнителя: сам исполнитель и похожие на него.
pub async fn artist_radio(
    db: &Mutex<rusqlite::Connection>,
    cache: &SearchCache,
    http: &reqwest::Client,
    user: i64,
    id: Option<&str>,
    name: &str,
) -> Vec<WaveTrack> {
    let p = load_profile(db, user);
    let id = match id {
        Some(i) => Some(i.to_string()),
        None => crate::yt::resolve_artist(cache, http, name).await,
    };
    let Some(id) = id else { return Vec::new() };
    let Ok(page) = cache.artist(http, &id).await else { return Vec::new() };
    let mut radio = match &page.radio {
        Some((v, pl)) => cache.radio(http, v, Some(pl)).await,
        None => Vec::new(),
    };
    if radio.len() < 10 {
        // Нет кнопки «Радио»: смешиваем радио двух главных песен.
        for t in page.tracks.iter().take(2) {
            radio.extend(cache.radio(http, &t.id, None).await);
        }
    }
    let why = format!("Радио исполнителя {}", page.name);
    finish_radio(&p, radio, None, &artist_key(&page.name), &why, 4)
}

fn finish_radio(p: &Profile, radio: Vec<RadioItem>, seed: Option<&Track>, seed_artist: &str, why: &str, seed_cap: usize) -> Vec<WaveTrack> {
    let mut rng = rand::rngs::StdRng::from_entropy();
    let seed_title = seed.map(|s| norm_title(&s.title));
    let mut scored: Vec<(f64, Track, String)> = Vec::new();
    let mut seen = HashSet::new();
    for (pos, r) in radio.into_iter().enumerate() {
        let t = r.track;
        if Some(&t.id) == seed.map(|s| &s.id) || !seen.insert(t.id.clone()) {
            continue;
        }
        // Та же песня в другой версии сразу после зерна не нужна.
        if seed_title.as_deref() == Some(norm_title(&t.title).as_str()) && keys_of(&t.artist).contains(&seed_artist.to_string()) {
            continue;
        }
        if !acceptable(p, &t) || p.skipped_within(&t.id, 30) {
            continue;
        }
        let mut s = 1.0 / (1.0 + pos as f64 / 12.0);
        let taste = p.taste_of(&t.artist);
        s *= 1.0 + 0.25 * taste.max(0.0);
        if taste < 0.0 {
            s *= (1.0 + taste).max(0.2);
        }
        if p.heard_within(&t.id, 1) {
            s *= 0.6;
        }
        s *= match r.kind {
            VideoKind::User => 0.6,
            _ => 1.0,
        } * version_penalty(&t.title)
            * (1.0 + rng.gen::<f64>() * 0.1);
        scored.push((s, t, why.to_string()));
    }
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    // У исполнителя зерна чуть больше места, чем у остальных.
    let mut per: HashMap<String, usize> = HashMap::new();
    let mut out = Vec::new();
    for (s, t, w) in dedup_titles(scored) {
        let a = keys_of(&t.artist).into_iter().next().unwrap_or_default();
        let cap = if a == seed_artist { seed_cap } else { 2 };
        let n = per.entry(a).or_default();
        if *n >= cap {
            continue;
        }
        *n += 1;
        out.push((s, t, w));
        if out.len() >= 40 {
            break;
        }
    }
    diversify(out)
}

// ───────────────────────── миксы дня ─────────────────────────

#[derive(Serialize, Clone, Debug)]
pub struct Mix {
    pub id: String,
    pub title: String,
    pub sub: String,
    pub thumbs: Vec<String>,
    pub tracks: Vec<Track>,
}

#[derive(Serialize, Clone, Debug, Default)]
pub struct Mixes {
    pub mixes: Vec<Mix>,
    /// Лайкнутые давно и давно не игравшие.
    pub forgotten: Vec<Track>,
    /// Что крутят последние недели.
    pub repeat: Vec<Track>,
}

/// Номер дня по Москве: миксы обновляются раз в сутки, в полночь.
pub fn today() -> i64 {
    (now() + 3 * 3600) / DAY
}

/// Миксы дня: любимые исполнители, разложенные на группы похожих, и к каждой группе — свои
/// треки плюс похожее новое. Считаются раз в сутки.
pub async fn daily_mixes(db: &Mutex<rusqlite::Connection>, cache: &SearchCache, http: &reqwest::Client, user: i64) -> Vec<Mix> {
    let p = load_profile(db, user);
    let mut rng = rand::rngs::StdRng::seed_from_u64((user as u64).wrapping_mul(2654435761) ^ today() as u64);
    let top: Vec<(String, f64)> = p.top_artists(12).into_iter().filter(|(a, _)| p.best.contains_key(a)).collect();
    if top.is_empty() {
        return Vec::new();
    }
    let seeds: Vec<Seed> = top
        .iter()
        .map(|(a, w)| Seed::track(&p.best[a].0, *w, String::new()))
        .collect();
    let rads = radios(cache, http, &seeds).await;
    // Кто встречается в радио кого: связь «похожи».
    let radio_artists: Vec<HashSet<String>> = rads
        .iter()
        .map(|r| r.iter().take(30).flat_map(|i| keys_of(&i.track.artist)).collect())
        .collect();
    let linked = |i: usize, j: usize| radio_artists[i].contains(&top[j].0) || radio_artists[j].contains(&top[i].0);

    let mut left: Vec<usize> = (0..top.len()).filter(|&i| !rads[i].is_empty()).collect();
    let mut groups: Vec<Vec<usize>> = Vec::new();
    while let Some(&center) = left.first() {
        if groups.len() >= 4 {
            break;
        }
        let mut g = vec![center];
        for &j in left.iter().skip(1) {
            if g.len() < 4 && linked(center, j) {
                g.push(j);
            }
        }
        left.retain(|i| !g.contains(i));
        groups.push(g);
    }

    let mut used: HashSet<String> = HashSet::new();
    let mut mixes = Vec::new();
    for g in groups {
        let members: HashSet<&String> = g.iter().map(|&i| &top[i].0).collect();
        // Свои треки этих исполнителей, которые не играли пару дней.
        let mut own: Vec<(f64, Track)> = Vec::new();
        for (id, (_, plays)) in &p.heard {
            if p.heard_within(id, 2) || used.contains(id) {
                continue;
            }
            if let Some(t) = p.catalog.get(id.as_str()) {
                if keys_of(&t.artist).iter().any(|a| members.contains(a)) && acceptable(&p, t) {
                    own.push((*plays as f64 + rng.gen::<f64>(), t.clone()));
                }
            }
        }
        for (t, _) in p.liked.values() {
            if !p.heard_within(&t.id, 2) && !used.contains(&t.id) && keys_of(&t.artist).iter().any(|a| members.contains(a)) && acceptable(&p, t) && !own.iter().any(|(_, o)| o.id == t.id) {
                own.push((2.0 + rng.gen::<f64>(), t.clone()));
            }
        }
        own.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        let own: Vec<Track> = own.into_iter().map(|(_, t)| t).take(12).collect();

        // Новое из радио группы.
        let mut pool = Pool::default();
        for &i in &g {
            pool.add_radio(&seeds[i], &rads[i]);
        }
        let mut fresh: Vec<(f64, Track, String)> = pool
            .items
            .into_iter()
            .filter(|c| acceptable(&p, &c.track) && !p.heard_within(&c.track.id, 14) && !p.skipped_within(&c.track.id, 60) && !used.contains(&c.track.id))
            .map(|c| {
                let mut s = c.support * version_penalty(&c.track.title) * (1.0 + rng.gen::<f64>() * 0.2);
                if c.kind == VideoKind::User {
                    s *= 0.55;
                }
                (s, c.track, String::new())
            })
            .collect();
        fresh.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        let fresh = pick(dedup_titles(fresh), 30usize.saturating_sub(own.len()), 2);

        // Вперемешку: своё через каждые два новых.
        let mut tracks: Vec<Track> = Vec::new();
        let (mut a, mut b) = (own.into_iter(), fresh.into_iter().map(|w| w.track));
        loop {
            let x = a.next();
            let y1 = b.next();
            let y2 = b.next();
            if x.is_none() && y1.is_none() {
                break;
            }
            tracks.extend(x.into_iter().chain(y1).chain(y2));
        }
        if tracks.len() < 10 {
            continue;
        }
        for t in &tracks {
            used.insert(t.id.clone());
        }
        let names: Vec<String> = g.iter().take(3).map(|&i| p.display_name(&top[i].0)).collect();
        let sub = if g.len() > 3 || names.len() >= 2 { format!("{} и другие", names.join(", ")) } else { format!("{} и похожие", names.join(", ")) };
        let mut thumbs = Vec::new();
        for t in &tracks {
            if let Some(th) = &t.thumb {
                if !thumbs.contains(th) {
                    thumbs.push(th.clone());
                }
            }
            if thumbs.len() >= 4 {
                break;
            }
        }
        let n = mixes.len() + 1;
        mixes.push(Mix { id: format!("mix{n}"), title: format!("Микс дня {n}"), sub, thumbs, tracks });
    }
    mixes
}

/// Забытые лайки и «на повторе»: только база, без YouTube.
pub fn personal_shelves(db: &rusqlite::Connection, user: i64) -> (Vec<Track>, Vec<Track>) {
    let now = now();
    let mut rng = rand::rngs::StdRng::seed_from_u64((user as u64).wrapping_mul(40503) ^ today() as u64);
    let mut forgotten: Vec<Track> = Vec::new();
    if let Ok(mut st) = db.prepare(
        "SELECT l.track_id, l.title, l.artist, l.album, l.duration, l.thumb FROM likes l
         WHERE l.user_id=?1 AND l.created < ?2
           AND NOT EXISTS (SELECT 1 FROM history h WHERE h.user_id=?1 AND h.track_id=l.track_id AND h.played_at > ?3)
         ORDER BY l.created",
    ) {
        forgotten = st
            .query_map(params![user, now - 21 * DAY, now - 30 * DAY], crate::db::row_track)
            .map(|r| r.flatten().collect())
            .unwrap_or_default();
    }
    forgotten.shuffle(&mut rng);
    forgotten.truncate(30);
    let mut repeat: Vec<Track> = Vec::new();
    if let Ok(mut st) = db.prepare(
        "SELECT track_id, title, artist, album, duration, thumb FROM history
         WHERE user_id=?1 AND played_at > ?2
         GROUP BY track_id HAVING COUNT(*) >= 2
         ORDER BY COUNT(*) DESC, MAX(played_at) DESC LIMIT 30",
    ) {
        repeat = st.query_map(params![user, now - 30 * DAY], crate::db::row_track).map(|r| r.flatten().collect()).unwrap_or_default();
    }
    (forgotten, repeat)
}

// ───────────────────────── отбор и чистка ─────────────────────────

/// Отбор: лимит на исполнителя, без повторов подряд, с добором отложенных в конец.
fn pick(sorted: Vec<(f64, Track, String)>, want: usize, per_artist_cap: usize) -> Vec<WaveTrack> {
    let mut per_artist: HashMap<String, usize> = HashMap::new();
    let mut deferred: Vec<(f64, Track, String)> = Vec::new();
    let mut out: Vec<(f64, Track, String)> = Vec::new();
    let mut last = String::new();
    let key = |t: &Track| keys_of(&t.artist).into_iter().next().unwrap_or_default();

    for (score, t, why) in sorted {
        if out.len() >= want {
            break;
        }
        let a = key(&t);
        if per_artist.get(&a).copied().unwrap_or(0) >= per_artist_cap {
            continue;
        }
        if a == last {
            deferred.push((score, t, why));
            continue;
        }
        *per_artist.entry(a.clone()).or_default() += 1;
        last = a;
        out.push((score, t, why));
    }
    for (score, t, why) in deferred {
        if out.len() >= want {
            break;
        }
        let a = key(&t);
        if per_artist.get(&a).copied().unwrap_or(0) >= per_artist_cap {
            continue;
        }
        *per_artist.entry(a.clone()).or_default() += 1;
        out.push((score, t, why));
    }
    diversify(out)
}

/// Сборники, часовые миксы и «топы» в рекомендации не пускаем.
fn is_junk(title: &str) -> bool {
    const BAD: &[&str] = &[
        "top 100",
        "top 50",
        "top 20",
        "top 10",
        "top hits",
        "hits songs",
        "best of",
        "лучшие песни",
        "сборник",
        "playlist",
        "megamix",
        "compilation",
        "full album",
        "1 hour",
        "hours of",
        "non-stop",
        "нові пісні",
        "хіти",
        "пісні 20",
        "songs 20",
        "music 20",
        "chill mix",
        "relaxing",
        "dj mix",
        "in the mix",
        "continuous mix",
        "continuous dj",
        "nonstop",
        "non stop",
        "hits mix",
        "mix 20",
        "mashup mix",
        "микс 20",
        "music mix",
        // Фоновые «музыка для работы/сна» — это часовые подборки, а не песни.
        "music for ",
        "music to ",
        "songs for ",
        "beats for",
        "beats to",
        "for studying",
        "for study",
        "for work",
        "for sleep",
        "for relax",
        "concentration",
        "adhd",
        "deep focus",
        "deep work",
        "focus music",
        "study music",
        "sleep music",
        "background music",
        "relaxing music",
        "meditation",
        "white noise",
        "rain sounds",
        "binaural",
        "музыка для",
        "фоновая музыка",
    ];
    // «𝐏𝐥𝐚𝐲𝐥𝐢𝐬𝐭»: стилизованные буквы Unicode — примета каналов-подборок, а не песен.
    if title.chars().any(|c| ('\u{1D400}'..='\u{1D7FF}').contains(&c)) {
        return true;
    }
    // Названия песен длиннее 90 знаков — почти всегда описание подборки.
    if title.chars().count() > 90 {
        return true;
    }
    let t = title.to_lowercase();
    if t.contains("vol.") && (t.contains("music") || t.contains("songs") || t.contains("mix")) {
        return true;
    }
    if t.matches(',').count() >= 3 {
        return true; // «Шадэ, Jony, Мальборо, Zivert» — это канал, а не песня
    }
    if t.contains('|') && t.len() > 40 {
        return true;
    }
    BAD.iter().any(|b| t.contains(b))
}

/// «Rutba (From "Kali Jotta")» и «Rutba» — один и тот же трек.
fn norm_title(title: &str) -> String {
    let mut s = String::new();
    let mut depth = 0i32;
    for c in title.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => s.extend(c.to_lowercase()),
            _ => {}
        }
    }
    let s = s.split(" - ").next().unwrap_or(&s).to_string();
    let s = s.split(" ft. ").next().unwrap_or(&s).split(" feat. ").next().unwrap_or(&s).to_string();
    let cleaned: String = s
        .chars()
        .map(|c| if c.is_alphanumeric() || c.is_whitespace() { c } else { ' ' })
        .collect();
    cleaned.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Перемешать так, чтобы рядом не стояли треки одного исполнителя: жадно берём из
/// отсортированного списка первый трек, чей артист давно не встречался.
fn diversify(items: Vec<(f64, Track, String)>) -> Vec<WaveTrack> {
    let mut rest = items;
    let mut out = Vec::new();
    let mut last: Vec<String> = Vec::new();
    while !rest.is_empty() {
        let pos = rest
            .iter()
            .position(|(_, t, _)| !last.iter().any(|a| a == &t.artist))
            .unwrap_or(0);
        let (_, t, why) = rest.remove(pos);
        last.insert(0, t.artist.clone());
        last.truncate(3);
        out.push(WaveTrack { track: t, why });
    }
    out
}

fn genre_query(g: &str) -> String {
    match g {
        "russian" => "русские хиты".into(),
        "kpop" => "k-pop hits".into(),
        "rnb" => "rnb hits".into(),
        "soundtrack" => "film soundtrack music".into(),
        other => format!("{other} hits"),
    }
}

fn genre_title(g: &str) -> &str {
    match g {
        "pop" => "Поп",
        "rock" => "Рок",
        "rap" => "Рэп",
        "electronic" => "Электроника",
        "indie" => "Инди",
        "jazz" => "Джаз",
        "classical" => "Классика",
        "metal" => "Метал",
        "punk" => "Панк",
        "rnb" => "R&B",
        "reggae" => "Регги",
        "folk" => "Фолк",
        "russian" => "Русское",
        "kpop" => "K-Pop",
        "soundtrack" => "Саундтреки",
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tr(id: &str, artist: &str) -> Track {
        Track {
            id: id.into(),
            title: id.into(),
            artist: artist.into(),
            album: None,
            duration: 200,
            thumb: None,
            artist_id: None,
            album_id: None,
        }
    }

    fn t(id: &str, artist: &str) -> (f64, Track, String) {
        (0.5, tr(id, artist), String::new())
    }

    #[test]
    fn diversify_breaks_runs_of_one_artist() {
        let out = diversify(vec![t("1", "A"), t("2", "A"), t("3", "A"), t("4", "B")]);
        let artists: Vec<&str> = out.iter().map(|w| w.track.artist.as_str()).collect();
        assert_eq!(artists, vec!["A", "B", "A", "A"]);
        assert_eq!(out.len(), 4);
    }

    #[test]
    fn junk_titles_are_dropped() {
        assert!(is_junk("Top 100 Songs 2026"));
        assert!(is_junk("Лучшие песни всех времён"));
        assert!(is_junk("Chill mix 1 hour"));
        assert!(is_junk("Electronic Dance Music Hits in the Mix (Continuous DJ Mix)"));
        assert!(!is_junk("Blinding Lights"));
        assert!(!is_junk("Rutba (From \"Kali Jotta\")"));
        assert!(is_junk("ADHD Focus Music — Upbeat Deep Work Beats for Productivity"));
        assert!(is_junk("Deep Focus - Music For Studying, Concentration and Work"));
        assert!(!is_junk("Focus"));
        assert!(is_junk("【𝐏𝐥𝐚𝐲𝐥𝐢𝐬𝐭】R&B Relax Deep Soul✨Late Night Music"));
        assert!(is_junk("R&B Soul Jazz _ Smooth & Relaxing Songs for a Cozy Mood, Vol. 02"));
        assert!(!is_junk("Akhiyaan Gulaab (From \"Teri Baaton Mein Aisa Uljha Jiya\")"));
    }

    #[test]
    fn norm_title_collapses_versions() {
        assert_eq!(norm_title("Rutba (From \"Kali Jotta\")"), "rutba");
        assert_eq!(norm_title("Bhulliye Kive’n (feat. Neeru Bajwa)"), "bhulliye kive n");
        assert_eq!(norm_title("Around the World (Radio Edit)"), "around the world");
        assert_eq!(norm_title("Dancin - Krono Remix"), "dancin");
        assert_eq!(norm_title("Starboy ft. Daft Punk"), "starboy");
    }

    #[test]
    fn pick_caps_tracks_per_artist() {
        let many: Vec<(f64, Track, String)> = (0..5)
            .map(|i| t(&format!("a{i}"), "Same"))
            .chain((0..3).map(|i| t(&format!("b{i}"), "Other")))
            .collect();
        let out = pick(many, 30, 2);
        let same = out.iter().filter(|w| w.track.artist == "Same").count();
        let other = out.iter().filter(|w| w.track.artist == "Other").count();
        assert_eq!(same, 2);
        assert_eq!(other, 2);
    }

    #[test]
    fn artists_are_split() {
        assert_eq!(artists_of("Кино, Виктор Цой"), vec!["кино", "виктор цой"]);
        assert_eq!(artists_of("The Kid LAROI и Justin Bieber"), vec!["the kid laroi", "justin bieber"]);
        assert_eq!(artists_of("Marshmello & Halsey"), vec!["marshmello", "halsey"]);
    }

    #[test]
    fn artist_keys_match_across_scripts() {
        assert_eq!(artist_key("Шакира"), artist_key("Shakira"));
        assert_eq!(artist_key("Адам Порт"), artist_key("Adam Port"));
        assert_eq!(artist_key("Фейд"), artist_key("Feid"));
        assert_eq!(artist_key("Тейлор Свифт"), artist_key("Taylor Swift"));
        assert_ne!(artist_key("Кино"), artist_key("ДДТ"));
        assert_eq!(translit("Кино"), "kino");
    }

    #[test]
    fn clip_titles_are_cleaned() {
        use crate::yt::clean_title;
        assert_eq!(clean_title("Starboy ft. Daft Punk (Official Video) (feat. Daft Punk)"), "Starboy ft. Daft Punk (feat. Daft Punk)");
        assert_eq!(clean_title("STAY (Official Video)"), "STAY");
        assert_eq!(clean_title("Hardest To Love (Audio)"), "Hardest To Love");
        assert_eq!(clean_title("Lose My Mind [From F1® The Movie]"), "Lose My Mind [From F1® The Movie]");
        assert_eq!(clean_title("Группа крови (Official Lyric Video)"), "Группа крови");
    }

    #[test]
    fn blocked_artist_hides_features_too() {
        let mut p = Profile::default();
        p.blocked.insert(translit("Halsey"));
        assert!(p.is_blocked("Marshmello & Halsey"));
        assert!(p.is_blocked("Halsey"));
        assert!(!p.is_blocked("Marshmello"));
    }

    #[test]
    fn acceptable_rejects_dislikes_and_junk() {
        let mut p = Profile::default();
        p.disliked.insert("x".into());
        assert!(!acceptable(&p, &tr("x", "A")));
        assert!(acceptable(&p, &tr("y", "A")));
        let mut long = tr("z", "A");
        long.duration = 3600;
        assert!(!acceptable(&p, &long));
    }

    #[test]
    fn mood_queries_follow_language() {
        let ru = mood_queries(Some("calm"), None, true, None);
        assert!(ru[0].0.contains("спокойные"));
        let en = mood_queries(None, Some("workout"), false, Some("rock"));
        assert_eq!(en[0].0, "workout gym rock songs");
    }

    #[test]
    fn weighted_sample_has_no_repeats() {
        let mut rng = rand::rngs::StdRng::seed_from_u64(7);
        let items: Vec<(i32, f64)> = (0..10).map(|i| (i, 1.0 + i as f64)).collect();
        let s = weighted_sample(&items, 5, &mut rng);
        let set: HashSet<i32> = s.iter().cloned().collect();
        assert_eq!(s.len(), 5);
        assert_eq!(set.len(), 5);
    }
}
