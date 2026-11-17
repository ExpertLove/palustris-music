//! Статистика прослушиваний и «Итоги года». Считается из истории: каждое прослушивание хранится
//! со временем и (в приложении) с тем, сколько секунд трек на самом деле играл.
use crate::db::Track;
use crate::recs::{artists_of, now};
use rusqlite::params;
use serde::Serialize;
use std::collections::{HashMap, HashSet};

const DAY: i64 = 86400;

#[derive(Serialize, Clone, Debug)]
pub struct ArtistStat {
    pub name: String,
    pub plays: i64,
    pub minutes: i64,
    pub thumb: Option<String>,
    /// Фото исполнителя из YouTube Music (только у первых мест).
    pub image: Option<String>,
    #[serde(rename = "artistId", skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
pub struct TrackStat {
    #[serde(flatten)]
    pub track: Track,
    pub plays: i64,
    pub minutes: i64,
}

#[derive(Serialize, Clone, Debug)]
pub struct Bucket {
    /// Начало промежутка (unix, UTC): подпись делает клиент.
    pub start: i64,
    pub minutes: i64,
}

#[derive(Serialize, Debug)]
pub struct Stats {
    pub period: String,
    pub from: i64,
    pub minutes: i64,
    pub plays: i64,
    pub tracks: i64,
    pub artists: i64,
    /// Минуты за такой же промежуток перед этим (для «+12 % к прошлой неделе»).
    #[serde(rename = "prevMinutes")]
    pub prev_minutes: Option<i64>,
    #[serde(rename = "topArtists")]
    pub top_artists: Vec<ArtistStat>,
    #[serde(rename = "topTracks")]
    pub top_tracks: Vec<TrackStat>,
    /// Минуты по часам суток (местное время), 24 значения.
    pub hours: Vec<i64>,
    /// Минуты по дням недели, с понедельника.
    pub weekdays: Vec<i64>,
    /// Неделя и месяц — по дням, год и всё время — по месяцам.
    pub buckets: Vec<Bucket>,
    #[serde(rename = "bucketKind")]
    pub bucket_kind: String,
    /// Сколько дней подряд с музыкой, включая сегодня (или вчера, если сегодня ещё не слушали).
    pub streak: i64,
    #[serde(rename = "bestStreak")]
    pub best_streak: i64,
    /// Исполнители, которых впервые послушали в этот промежуток.
    #[serde(rename = "newArtists")]
    pub new_artists: i64,
    /// Первое прослушивание вообще.
    pub since: i64,
}

#[derive(Serialize, Debug)]
pub struct DayStat {
    pub start: i64,
    pub minutes: i64,
    pub plays: i64,
}

#[derive(Serialize, Debug)]
pub struct Wrapped {
    pub year: i32,
    pub minutes: i64,
    pub plays: i64,
    pub tracks: i64,
    pub artists: i64,
    /// В сколько дней года была музыка.
    pub days: i64,
    #[serde(rename = "topArtists")]
    pub top_artists: Vec<ArtistStat>,
    #[serde(rename = "topTracks")]
    pub top_tracks: Vec<TrackStat>,
    /// Минуты по месяцам, 12 значений.
    pub months: Vec<i64>,
    pub hours: Vec<i64>,
    #[serde(rename = "busiestDay")]
    pub busiest_day: Option<DayStat>,
    #[serde(rename = "bestStreak")]
    pub best_streak: i64,
    #[serde(rename = "newArtists")]
    pub new_artists: i64,
    #[serde(rename = "firstTrack")]
    pub first_track: Option<Track>,
    #[serde(rename = "firstAt")]
    pub first_at: i64,
    pub likes: i64,
    /// Какую долю слушателей пользователь обогнал по минутам, в процентах. Нет, если слушателей мало.
    pub percentile: Option<i64>,
}

/// Одно прослушивание.
struct Play {
    track: Track,
    at: i64,
    secs: i64,
}

/// Сколько секунд считать за прослушивание: что прислало приложение, иначе длина трека.
fn secs_of(secs: Option<i64>, duration: i64) -> i64 {
    match secs {
        Some(s) if s > 0 => s.min(3600),
        _ if duration > 0 => duration.min(900),
        _ => 180,
    }
}

fn load_plays(db: &rusqlite::Connection, user: i64, from: i64, to: i64) -> Vec<Play> {
    let Ok(mut st) = db.prepare(
        "SELECT track_id, title, artist, album, duration, thumb, played_at, secs FROM history
         WHERE user_id=?1 AND played_at >= ?2 AND played_at < ?3 ORDER BY played_at",
    ) else {
        return Vec::new();
    };
    st.query_map(params![user, from, to], |r| {
        let t = crate::db::row_track(r)?;
        let at: i64 = r.get(6)?;
        let secs = secs_of(r.get(7)?, t.duration);
        Ok(Play { track: t, at, secs })
    })
    .map(|rows| rows.flatten().collect())
    .unwrap_or_default()
}

/// Местные сутки: номер дня с 1970-01-01 при сдвиге `tz` минут от UTC.
fn local_day(ts: i64, tz: i64) -> i64 {
    (ts + tz * 60).div_euclid(DAY)
}

/// (год, месяц 1–12, день) по номеру дня с 1970-01-01 — алгоритм Говарда Хиннанта.
pub fn civil(days: i64) -> (i32, u32, u32) {
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    ((if m <= 2 { y + 1 } else { y }) as i32, m, d)
}

/// Номер дня по дате — обратное к [civil].
pub fn days_from_civil(y: i32, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y as i64 - 1 } else { y as i64 };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let m = m as i64;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// Начало местных суток `day` в UTC.
fn day_start(day: i64, tz: i64) -> i64 {
    day * DAY - tz * 60
}

struct Agg {
    minutes: i64,
    plays: i64,
    tracks: i64,
    artists: i64,
    top_artists: Vec<ArtistStat>,
    top_tracks: Vec<TrackStat>,
    hours: Vec<i64>,
    weekdays: Vec<i64>,
}

fn aggregate(plays: &[Play], tz: i64, top_n: usize) -> Agg {
    let mut secs = 0i64;
    let mut hours = vec![0i64; 24];
    let mut weekdays = vec![0i64; 7];
    // Исполнитель засчитывается по первому имени: «Кино, Виктор Цой» — это «Кино».
    let mut by_artist: HashMap<String, (String, i64, i64, Option<String>)> = HashMap::new();
    let mut by_track: HashMap<String, (Track, i64, i64)> = HashMap::new();
    for p in plays {
        secs += p.secs;
        let local = p.at + tz * 60;
        hours[(local.rem_euclid(DAY) / 3600) as usize] += p.secs;
        weekdays[((local.div_euclid(DAY) + 3).rem_euclid(7)) as usize] += p.secs;
        let name = p.track.artist.split(',').next().unwrap_or("").trim().to_string();
        if !name.is_empty() {
            // «Шакира» и «Shakira» — один исполнитель.
            let e = by_artist.entry(crate::recs::artist_key(&name)).or_insert((name, 0, 0, None));
            e.1 += 1;
            e.2 += p.secs;
            if p.track.thumb.is_some() {
                e.3 = p.track.thumb.clone();
            }
        }
        let e = by_track.entry(p.track.id.clone()).or_insert((p.track.clone(), 0, 0));
        e.1 += 1;
        e.2 += p.secs;
    }
    let mut artists: Vec<_> = by_artist.into_values().collect();
    artists.sort_by(|a, b| b.2.cmp(&a.2).then(b.1.cmp(&a.1)));
    let mut tracks: Vec<_> = by_track.into_values().collect();
    tracks.sort_by(|a, b| b.1.cmp(&a.1).then(b.2.cmp(&a.2)));
    Agg {
        minutes: secs / 60,
        plays: plays.len() as i64,
        tracks: tracks.len() as i64,
        artists: artists.len() as i64,
        top_artists: artists
            .into_iter()
            .take(top_n)
            .map(|(name, plays, s, thumb)| ArtistStat { name, plays, minutes: s / 60, thumb, image: None, artist_id: None })
            .collect(),
        top_tracks: tracks.into_iter().take(top_n).map(|(track, plays, s)| TrackStat { track, plays, minutes: s / 60 }).collect(),
        hours: hours.into_iter().map(|s| s / 60).collect(),
        weekdays: weekdays.into_iter().map(|s| s / 60).collect(),
    }
}

/// Дни с музыкой за всё время (местные).
fn active_days(db: &rusqlite::Connection, user: i64, tz: i64) -> Vec<i64> {
    let Ok(mut st) = db.prepare("SELECT DISTINCT (played_at + ?2 * 60) / 86400 FROM history WHERE user_id=?1 ORDER BY 1") else {
        return Vec::new();
    };
    st.query_map(params![user, tz], |r| r.get::<_, i64>(0)).map(|r| r.flatten().collect()).unwrap_or_default()
}

fn best_run(days: &[i64]) -> i64 {
    let (mut best, mut run, mut prev) = (0i64, 0i64, i64::MIN);
    for &d in days {
        run = if prev != i64::MIN && d == prev + 1 { run + 1 } else { 1 };
        best = best.max(run);
        prev = d;
    }
    best
}

fn current_run(days: &[i64], today: i64) -> i64 {
    let set: HashSet<i64> = days.iter().cloned().collect();
    let mut d = if set.contains(&today) { today } else { today - 1 };
    let mut n = 0;
    while set.contains(&d) {
        n += 1;
        d -= 1;
    }
    n
}

/// Первые прослушивания исполнителей: нормированное имя → когда.
fn first_heard(db: &rusqlite::Connection, user: i64) -> HashMap<String, i64> {
    let mut out: HashMap<String, i64> = HashMap::new();
    if let Ok(mut st) = db.prepare("SELECT artist, MIN(played_at) FROM history WHERE user_id=?1 GROUP BY artist") {
        if let Ok(rows) = st.query_map([user], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))) {
            for (artist, at) in rows.flatten() {
                if let Some(a) = artists_of(&artist).into_iter().next() {
                    let e = out.entry(a).or_insert(at);
                    *e = (*e).min(at);
                }
            }
        }
    }
    out
}

/// Начало (местного) месяца `months_back` месяцев назад от месяца дня `day`.
fn month_start_day(day: i64, months_back: i64) -> i64 {
    let (y, m, _) = civil(day);
    let total = y as i64 * 12 + (m as i64 - 1) - months_back;
    days_from_civil(total.div_euclid(12) as i32, (total.rem_euclid(12) + 1) as u32, 1)
}

pub fn stats(db: &rusqlite::Connection, user: i64, period: &str, tz: i64) -> Stats {
    let now = now();
    let today = local_day(now, tz);
    let days = active_days(db, user, tz);
    let since = db
        .query_row("SELECT MIN(played_at) FROM history WHERE user_id=?1", [user], |r| r.get::<_, Option<i64>>(0))
        .ok()
        .flatten()
        .unwrap_or(0);
    let (period, from_day, kind, n_buckets) = match period {
        "week" => ("week", today - 6, "day", 7),
        "year" => ("year", month_start_day(today, 11), "month", 12),
        "all" => {
            let first = if since > 0 { local_day(since, tz) } else { today };
            let (fy, fm, _) = civil(first);
            let (ty, tm, _) = civil(today);
            let months = ((ty as i64 * 12 + tm as i64) - (fy as i64 * 12 + fm as i64) + 1).clamp(1, 120);
            ("all", month_start_day(today, months - 1), "month", months)
        }
        _ => ("month", today - 29, "day", 30),
    };
    let from = day_start(from_day, tz);
    let plays = load_plays(db, user, from, now + 60);
    let agg = aggregate(&plays, tz, 10);

    let mut buckets: Vec<Bucket> = Vec::new();
    if kind == "day" {
        let mut mins = vec![0i64; n_buckets as usize];
        for p in &plays {
            let i = local_day(p.at, tz) - from_day;
            if (0..n_buckets).contains(&i) {
                mins[i as usize] += p.secs;
            }
        }
        for (i, s) in mins.into_iter().enumerate() {
            buckets.push(Bucket { start: day_start(from_day + i as i64, tz), minutes: s / 60 });
        }
    } else {
        let starts: Vec<i64> = (0..n_buckets).rev().map(|k| month_start_day(today, k)).collect();
        let mut mins = vec![0i64; starts.len()];
        for p in &plays {
            let d = local_day(p.at, tz);
            if let Some(i) = starts.iter().rposition(|s| *s <= d) {
                mins[i] += p.secs;
            }
        }
        for (s, m) in starts.into_iter().zip(mins) {
            buckets.push(Bucket { start: day_start(s, tz), minutes: m / 60 });
        }
    }

    let prev_minutes = if period == "all" {
        None
    } else {
        let len = now - from;
        let prev: i64 = load_plays(db, user, from - len, from).iter().map(|p| p.secs).sum();
        Some(prev / 60)
    };
    let first = first_heard(db, user);
    let new_artists = first.values().filter(|at| **at >= from).count() as i64;

    Stats {
        period: period.into(),
        from,
        minutes: agg.minutes,
        plays: agg.plays,
        tracks: agg.tracks,
        artists: agg.artists,
        prev_minutes,
        top_artists: agg.top_artists,
        top_tracks: agg.top_tracks,
        hours: agg.hours,
        weekdays: agg.weekdays,
        buckets,
        bucket_kind: kind.into(),
        streak: current_run(&days, today),
        best_streak: best_run(&days),
        new_artists,
        since,
    }
}

pub fn wrapped(db: &rusqlite::Connection, user: i64, year: i32, tz: i64) -> Wrapped {
    let from_day = days_from_civil(year, 1, 1);
    let to_day = days_from_civil(year + 1, 1, 1);
    let (from, to) = (day_start(from_day, tz), day_start(to_day, tz));
    let plays = load_plays(db, user, from, to);
    let agg = aggregate(&plays, tz, 5);

    let mut months = vec![0i64; 12];
    let mut per_day: HashMap<i64, (i64, i64)> = HashMap::new();
    for p in &plays {
        let d = local_day(p.at, tz);
        let (_, m, _) = civil(d);
        months[(m - 1) as usize] += p.secs;
        let e = per_day.entry(d).or_insert((0, 0));
        e.0 += p.secs;
        e.1 += 1;
    }
    let busiest_day = per_day
        .iter()
        .max_by_key(|(_, (s, _))| *s)
        .map(|(d, (s, n))| DayStat { start: day_start(*d, tz), minutes: s / 60, plays: *n });
    let mut year_days: Vec<i64> = per_day.keys().cloned().collect();
    year_days.sort();

    let first = first_heard(db, user);
    let new_artists = first.values().filter(|at| **at >= from && **at < to).count() as i64;
    let likes = db
        .query_row("SELECT COUNT(*) FROM likes WHERE user_id=?1 AND created >= ?2 AND created < ?3", params![user, from, to], |r| r.get(0))
        .unwrap_or(0);

    // Место среди слушателей года — только если их достаточно, чтобы процент что-то значил.
    let mut percentile = None;
    if let Ok(mut st) = db.prepare(
        "SELECT user_id, SUM(CASE WHEN secs > 0 THEN MIN(secs, 3600) WHEN duration > 0 THEN MIN(duration, 900) ELSE 180 END)
         FROM history WHERE played_at >= ?1 AND played_at < ?2 GROUP BY user_id",
    ) {
        let all: Vec<(i64, i64)> = st
            .query_map(params![from, to], |r| Ok((r.get(0)?, r.get(1)?)))
            .map(|r| r.flatten().collect())
            .unwrap_or_default();
        let others: Vec<i64> = all.iter().filter(|(u, s)| *u != user && *s >= 3600).map(|(_, s)| *s).collect();
        let mine: i64 = all.iter().find(|(u, _)| *u == user).map(|(_, s)| *s).unwrap_or(0);
        if others.len() >= 9 {
            let below = others.iter().filter(|s| **s < mine).count();
            percentile = Some((below * 100 / others.len()) as i64 / 5 * 5);
        }
    }

    Wrapped {
        year,
        minutes: agg.minutes,
        plays: agg.plays,
        tracks: agg.tracks,
        artists: agg.artists,
        days: year_days.len() as i64,
        top_artists: agg.top_artists,
        top_tracks: agg.top_tracks,
        months: months.into_iter().map(|s| s / 60).collect(),
        hours: agg.hours,
        busiest_day,
        best_streak: best_run(&year_days),
        new_artists,
        first_track: plays.first().map(|p| p.track.clone()),
        first_at: plays.first().map(|p| p.at).unwrap_or(0),
        likes,
        percentile,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_round_trip() {
        for d in [-1000i64, 0, 59, 60, 365, 10957, 20409, 20774] {
            let (y, m, dd) = civil(d);
            assert_eq!(days_from_civil(y, m, dd), d);
        }
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(days_from_civil(2026, 11, 17), 20774);
    }

    #[test]
    fn month_starts() {
        let today = days_from_civil(2026, 3, 15);
        assert_eq!(civil(month_start_day(today, 0)), (2026, 3, 1));
        assert_eq!(civil(month_start_day(today, 3)), (2025, 12, 1));
        assert_eq!(civil(month_start_day(today, 14)), (2025, 1, 1));
    }

    #[test]
    fn streaks() {
        assert_eq!(best_run(&[1, 2, 3, 5, 6]), 3);
        assert_eq!(current_run(&[1, 2, 3, 5, 6], 6), 2);
        assert_eq!(current_run(&[1, 2, 3, 5, 6], 7), 2);
        assert_eq!(current_run(&[1, 2, 3], 7), 0);
    }

    #[test]
    fn seconds_fallback() {
        assert_eq!(secs_of(Some(120), 200), 120);
        assert_eq!(secs_of(None, 200), 200);
        assert_eq!(secs_of(None, 5000), 900);
        assert_eq!(secs_of(None, 0), 180);
    }

    #[test]
    fn hours_and_weekdays_are_local() {
        // 2026-11-16 (понедельник) 22:30 UTC = 01:30 вторника по Москве.
        let at = day_start(days_from_civil(2026, 11, 16), 0) + 22 * 3600 + 1800;
        let t = Track::new("x".into(), "t".into(), "A".into());
        let agg = aggregate(&[Play { track: t, at, secs: 600 }], 180, 5);
        assert_eq!(agg.hours[1], 10);
        assert_eq!(agg.weekdays[1], 10);
    }
}
