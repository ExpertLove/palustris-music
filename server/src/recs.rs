use crate::db::Track;
use crate::yt::SearchCache;
use rusqlite::params;
use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Вкус = лайки (вес 3) + история с экспоненциальным затуханием (полураспад ~21 день).
pub fn top_artists(db: &rusqlite::Connection, user: i64) -> Vec<(String, f64)> {
    let now = now();
    let mut acc: HashMap<String, f64> = HashMap::new();
    let mut stmt = match db.prepare(
        "SELECT artist FROM likes WHERE user_id=?1
         UNION ALL
         SELECT artist || char(1) || played_at FROM history WHERE user_id=?1 AND played_at > ?2",
    ) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let rows = stmt.query_map(params![user, now - 90 * 86400], |r| r.get::<_, String>(0));
    let Ok(rows) = rows else { return Vec::new() };
    for row in rows.flatten() {
        match row.split_once('\u{1}') {
            Some((artist, ts)) => {
                let ts: i64 = ts.parse().unwrap_or(now);
                let days = (now - ts) as f64 / 86400.0;
                *acc.entry(artist.to_string()).or_default() += (-days / 21.0).exp();
            }
            None => *acc.entry(row).or_default() += 3.0,
        }
    }
    let mut v: Vec<_> = acc.into_iter().collect();
    v.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    v.into_iter().take(6).collect()
}

pub fn recent_ids(db: &rusqlite::Connection, user: i64, days: i64) -> HashSet<String> {
    let mut set = HashSet::new();
    let now = now();
    if let Ok(mut st) = db.prepare(
        "SELECT track_id FROM likes WHERE user_id=?1
         UNION SELECT track_id FROM history WHERE user_id=?1 AND played_at > ?2",
    ) {
        if let Ok(rows) = st.query_map(params![user, now - days * 86400], |r| r.get::<_, String>(0)) {
            set.extend(rows.flatten());
        }
    }
    set
}

pub fn genres(db: &rusqlite::Connection, user: i64) -> Vec<String> {
    let Ok(mut st) = db.prepare("SELECT genre FROM user_genres WHERE user_id=?1") else { return Vec::new() };
    st.query_map(params![user], |r| r.get::<_, String>(0))
        .map(|rows| rows.flatten().collect())
        .unwrap_or_default()
}

/// Хвост истории в порядке прослушивания (старые первыми): (track_id, artist, played_at).
fn history_tail(db: &rusqlite::Connection, user: i64, n: usize) -> Vec<(String, String, i64)> {
    let Ok(mut st) = db.prepare(
        "SELECT track_id, artist, played_at FROM history WHERE user_id=?1
         ORDER BY played_at DESC, rowid DESC LIMIT ?2",
    ) else {
        return Vec::new();
    };
    let mut v: Vec<_> = st
        .query_map(params![user, n as i64], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .map(|rows| rows.flatten().collect())
        .unwrap_or_default();
    v.reverse();
    v
}

/// Последний трек, который пользователь, скорее всего, дослушал: пропускаем хвост из
/// подряд идущих «переключил» (треки, прожившие в плеере меньше 30 секунд).
fn seed(db: &rusqlite::Connection, user: i64, tail: &[(String, String, i64)]) -> Option<(String, String)> {
    let mut last = tail.last()?.clone();
    for _ in 0..2 {
        let earlier: Vec<&(String, String, i64)> = tail.iter().rev().skip(1).filter(|t| t.0 != last.0).collect();
        let Some(prev) = earlier.first() else { break };
        let (_, _, started): (String, String, i64) = db
            .query_row(
                "SELECT track_id, artist, played_at FROM history WHERE user_id=?1 AND track_id=?2
                 ORDER BY played_at DESC, rowid DESC LIMIT 1",
                params![user, prev.0],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap_or_default();
        // Прожил >= 30 c (или это первый раз, когда мы его видим) — считаем, что зашло.
        if last.2 - started >= 30 {
            break;
        }
        last = (*prev).clone();
    }
    Some((last.0, last.1))
}

/// Трек волны + короткое объяснение, почему он здесь.
#[derive(Serialize)]
pub struct WaveTrack {
    #[serde(flatten)]
    track: Track,
    why: String,
}

/// «Моя волна»: собираем кандидатов из вкуса, жанров и последнего дослушанного, затем
/// чистим мусор, схлопываем дубли и отбираем с лимитом на артиста.
pub async fn wave(
    db: &std::sync::Mutex<rusqlite::Connection>,
    cache: &SearchCache,
    http: &reqwest::Client,
    user: i64,
) -> Vec<WaveTrack> {
    let (artists, genres, tail, liked, seen) = {
        let conn = match db.lock() {
            Ok(c) => c,
            Err(_) => return Vec::new(),
        };
        let tail = history_tail(&conn, user, 12);
        let liked = seed(&conn, user, &tail);
        let seen = recent_ids(&conn, user, 7);
        (top_artists(&conn, user), genres(&conn, user), tail, liked, seen)
    };

    // Совсем недавнее не повторяем, остальное из истории — просто понижаем.
    let head_artists: HashSet<String> = tail.iter().rev().take(3).map(|t| t.1.to_lowercase()).collect();
    let recent_artists: HashSet<String> = tail.iter().map(|t| t.1.to_lowercase()).collect();

    let mut cand = Candidates { seen, titles: HashSet::new(), artists: HashSet::new(), out: Vec::new() };

    // 1. Треки последнего дослушанного артиста — самое точное попадание.
    if let Some((_, artist)) = &liked {
        let seed = first_artist(artist);
        let tracks = cache.search(http, &seed).await;
        cand.push(tracks, 1.0, Some(&seed), &format!("Похоже на «{seed}»"), &head_artists);
    }
    // 2. Артисты вкуса. Недавно слушанных слегка понижаем, чтобы волна не зацикливалась,
    //    но не ниже случайного чарта.
    let max = artists.first().map(|a| a.1).unwrap_or(1.0).max(0.001);
    for (i, (artist, w)) in artists.iter().enumerate() {
        // «Шакира, Burna Boy» → ищем и сверяем по первому имени.
        let seed = first_artist(artist);
        let base = 0.95 * (w / max) - i as f64 * 0.03;
        let penalty = if recent_artists.contains(&artist.to_lowercase()) { 0.15 } else { 0.0 };
        let tracks = cache.search(http, &seed).await;
        cand.push(
            tracks,
            (base - penalty).max(0.5),
            Some(&seed),
            &format!("Ты часто слушаешь {seed}"),
            &head_artists,
        );
        // Второй запрос — больше треков того же артиста (поиск отдаёт лишь часть).
        let more = cache.search(http, &format!("{seed} songs")).await;
        cand.push(
            more,
            (base - penalty - 0.1).max(0.45),
            Some(&seed),
            &format!("Ты часто слушаешь {seed}"),
            &head_artists,
        );
    }
    // 3. Жанры — откуда приходит новое.
    for (i, g) in genres.iter().enumerate() {
        let tracks = cache.search(http, &genre_query(g)).await;
        cand.push(tracks, 0.55 - i as f64 * 0.04, None, &format!("Твой жанр: {}", genre_title(g)), &head_artists);
    }
    // 4. Холодный старт и добор: чарт даёт заведомо песни, но не больше восьми,
    //    чтобы волна не превращалась в топ. Недавние артисты здесь допустимы —
    //    иначе при узком вкусе добирать нечем.
    let none: HashSet<String> = HashSet::new();
    if cand.artists.len() < 14 {
        let top = crate::yt::chart_top(http, "").await.unwrap_or_default();
        cand.push(top.into_iter().take(10).collect(), 0.45, None, "Популярное сейчас", &none);
    }
    if cand.artists.len() < 14 {
        let tracks = cache.search(http, "новые песни").await;
        cand.push(tracks.into_iter().take(8).collect(), 0.3, None, "Новинки", &none);
    }
    if cand.artists.len() < 12 {
        let tracks = cache.search(http, "лучшие песни").await;
        cand.push(tracks, 0.25, None, "Хиты, которые ты ещё не слышал", &none);
    }
    if cand.out.len() < 12 {
        let tracks = cache.search(http, "лучшие песни").await;
        cand.push(tracks, 0.25, None, "Хиты, которые ты ещё не слышал", &head_artists);
    }

    cand.out.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    pick(cand.out, 30)
}

/// Накопитель кандидатов: чистит мусор, дубли и повторы из недавнего.
struct Candidates {
    seen: HashSet<String>,
    titles: HashSet<String>,
    artists: HashSet<String>,
    out: Vec<(f64, Track, String)>,
}

/// Больше двух треков одного артиста в одной волне не берём.
const PER_ARTIST: usize = 2;

impl Candidates {
    fn push(
        &mut self,
        tracks: Vec<Track>,
        base: f64,
        artist_only: Option<&str>,
        why: &str,
        head: &HashSet<String>,
    ) {
        let norm_artist = artist_only.map(|a| a.to_lowercase());
        for t in tracks {
            let a = t.artist.to_lowercase();
            if let Some(want) = &norm_artist {
                if !a.contains(want) || a.contains("music") || a.contains("records") || a.contains("official") {
                    continue;
                }
            }
            if head.contains(&a) || is_junk(&t.title) || a.is_empty() {
                continue;
            }
            let key = format!("{a}\u{1}{}", norm_title(&t.title));
            if !self.titles.insert(key) || !self.seen.insert(t.id.clone()) {
                continue;
            }
            self.artists.insert(a);
            self.out.push((base - rand::random::<f64>() * 0.15, t, why.to_string()));
        }
    }
}

/// Отбор: лимит на артиста, без повторов подряд, с добором отложенных в конец.
fn pick(sorted: Vec<(f64, Track, String)>, want: usize) -> Vec<WaveTrack> {
    let mut per_artist: HashMap<String, usize> = HashMap::new();
    let mut deferred: Vec<(f64, Track, String)> = Vec::new();
    let mut out: Vec<(f64, Track, String)> = Vec::new();
    let mut last = String::new();

    for (score, t, why) in sorted {
        if out.len() >= want {
            break;
        }
        let a = t.artist.to_lowercase();
        if per_artist.get(&a).copied().unwrap_or(0) >= PER_ARTIST {
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
        let a = t.artist.to_lowercase();
        if per_artist.get(&a).copied().unwrap_or(0) >= PER_ARTIST {
            continue;
        }
        *per_artist.entry(a.clone()).or_default() += 1;
        out.push((score, t, why));
    }
    diversify(out)
}

/// «Шакира, Burna Boy» → «Шакира».
fn first_artist(a: &str) -> String {
    a.split(',').next().unwrap_or(a).trim().to_string()
}

/// Сборники, часовые миксы и «топы» в волну не пускаем.
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
    ];
    let t = title.to_lowercase();
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

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: &str, artist: &str) -> (f64, Track, String) {
        (
            0.5,
            Track {
                id: id.into(),
                title: id.into(),
                artist: artist.into(),
                album: None,
                duration: 200,
                thumb: None,
            },
            String::new(),
        )
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
        assert!(!is_junk("Blinding Lights"));
        assert!(!is_junk("Rutba (From \"Kali Jotta\")"));
    }

    #[test]
    fn norm_title_collapses_versions() {
        assert_eq!(norm_title("Rutba (From \"Kali Jotta\")"), "rutba");
        assert_eq!(norm_title("Bhulliye Kive’n (feat. Neeru Bajwa)"), "bhulliye kive n");
        assert_eq!(norm_title("Around the World (Radio Edit)"), "around the world");
    }

    #[test]
    fn pick_caps_tracks_per_artist() {
        let many: Vec<(f64, Track, String)> = (0..5)
            .map(|i| t(&format!("a{i}"), "Same"))
            .chain((0..3).map(|i| t(&format!("b{i}"), "Other")))
            .collect();
        let out = pick(many, 30);
        let same = out.iter().filter(|w| w.track.artist == "Same").count();
        let other = out.iter().filter(|w| w.track.artist == "Other").count();
        assert_eq!(same, PER_ARTIST);
        assert_eq!(other, PER_ARTIST);
    }
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

pub async fn recommend(
    db: &std::sync::Mutex<rusqlite::Connection>,
    cache: &SearchCache,
    http: &reqwest::Client,
    user: i64,
) -> Vec<Track> {
    let (artists, genres, mut seen) = {
        let conn = match db.lock() {
            Ok(c) => c,
            Err(_) => return Vec::new(),
        };
        (top_artists(&conn, user), genres(&conn, user), recent_ids(&conn, user, 3))
    };
    if artists.is_empty() && genres.is_empty() {
        return Vec::new();
    }

    let max = artists.first().map(|a| a.1).unwrap_or(1.0).max(0.001);
    let mut scored: Vec<(f64, Track)> = Vec::new();

    for (artist, weight) in &artists {
        let affinity = weight / max;
        for t in cache.search(http, artist).await {
            if !t.artist.to_lowercase().contains(&artist.to_lowercase()) {
                continue;
            }
            if !seen.insert(t.id.clone()) {
                continue;
            }
            let jitter = rand::random::<f64>() * 0.25;
            scored.push((affinity + jitter, t));
        }
    }

    // Жанры, выбранные при регистрации, — основа подборки для нового аккаунта.
    for (i, g) in genres.iter().enumerate() {
        for t in cache.search(http, &genre_query(g)).await {
            if !seen.insert(t.id.clone()) {
                continue;
            }
            let jitter = rand::random::<f64>() * 0.25;
            scored.push((0.6 - i as f64 * 0.03 + jitter, t));
        }
    }

    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    scored.into_iter().take(40).map(|(_, t)| t).collect()
}
