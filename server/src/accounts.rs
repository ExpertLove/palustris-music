//! Аккаунты YouTube для yt-dlp и журнал загрузок.
//!
//! Cookies каждого аккаунта лежат файлом `{data}/ytaccounts/{id}.txt` (0600): yt-dlp сам
//! дописывает в него обновлённые cookies, так сессия живёт дольше. Метаданные и счётчики — в базе.
//!
//! Ротация: берём включённый аккаунт, который дольше всех не использовался и не «остывает».
//! «Sign in to confirm you're not a bot» на аккаунте — пауза 30 мин, 429 — 60 мин,
//! «cookies are no longer valid» — аккаунт помечается мёртвым до замены cookies.
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const COOLDOWN_BOT: i64 = 30 * 60;
pub const COOLDOWN_429: i64 = 60 * 60;
/// Сколько держим пометку «IP сервера под подозрением» после ошибки без аккаунта.
pub const IP_FLAG: i64 = 15 * 60;
const MAX_COOKIES: usize = 1024 * 1024;

pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrKind {
    /// «Sign in to confirm you're not a bot».
    Bot,
    /// Cookies аккаунта больше не действуют.
    CookiesDead,
    /// 429 / too many requests.
    RateLimit,
    /// Проблема самого видео: удалено, приватное, недоступно в регионе.
    Video,
    Network,
    Other,
}

impl ErrKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ErrKind::Bot => "bot",
            ErrKind::CookiesDead => "cookies",
            ErrKind::RateLimit => "ratelimit",
            ErrKind::Video => "video",
            ErrKind::Network => "network",
            ErrKind::Other => "other",
        }
    }
}

pub fn classify(err: &str) -> ErrKind {
    let l = err.to_lowercase();
    if l.contains("not a bot") || l.contains("sign in to confirm") {
        ErrKind::Bot
    } else if l.contains("cookies are no longer valid") || l.contains("cookies are invalid") {
        ErrKind::CookiesDead
    } else if l.contains("http error 429") || l.contains("too many requests") || l.contains("rate-limited") || l.contains("rate limited") {
        ErrKind::RateLimit
    } else if l.contains("requested format") {
        ErrKind::Other
    } else if l.contains("video unavailable")
        || l.contains("private video")
        || l.contains("has been removed")
        || l.contains("not available in your country")
        || l.contains("copyright")
        || l.contains("confirm your age")
        || l.contains("members-only")
        || l.contains("premieres in")
    {
        ErrKind::Video
    } else if l.contains("timed out") || l.contains("connection") || l.contains("unable to download") || l.contains("temporary failure") {
        ErrKind::Network
    } else {
        ErrKind::Other
    }
}

#[derive(Serialize)]
pub struct AccountInfo {
    pub id: i64,
    pub label: String,
    pub enabled: bool,
    /// ok | new | cooldown | dead | off
    pub status: String,
    pub note: Option<String>,
    pub created: i64,
    pub updated: i64,
    #[serde(rename = "lastUsed")]
    pub last_used: i64,
    #[serde(rename = "lastOk")]
    pub last_ok: i64,
    #[serde(rename = "lastErr")]
    pub last_err: i64,
    #[serde(rename = "cooldownUntil")]
    pub cooldown_until: i64,
    #[serde(rename = "okCount")]
    pub ok_count: i64,
    #[serde(rename = "errCount")]
    pub err_count: i64,
    /// Удачных загрузок за последний час — чтобы видеть нагрузку на аккаунт.
    #[serde(rename = "hourOk")]
    pub hour_ok: i64,
    /// Сколько cookies в файле и есть ли среди них cookies входа.
    pub cookies: usize,
    #[serde(rename = "loggedIn")]
    pub logged_in: bool,
}

#[derive(Serialize)]
pub struct Event {
    pub id: i64,
    pub at: i64,
    pub video: String,
    pub title: Option<String>,
    pub account: Option<i64>,
    #[serde(rename = "accountLabel")]
    pub account_label: Option<String>,
    pub client: String,
    pub ok: bool,
    pub kind: Option<String>,
    pub err: Option<String>,
    pub ms: i64,
}

pub struct Accounts {
    db: Mutex<Connection>,
    dir: PathBuf,
    ip_flag_until: Mutex<i64>,
}

/// Результат разбора загруженного файла cookies.
pub struct ParsedCookies {
    pub text: String,
    pub count: usize,
}

/// Cookies, по которым видно, что в браузере выполнен вход в Google.
const LOGIN_COOKIES: [&str; 6] = ["SAPISID", "__Secure-3PAPISID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "SID"];

fn domain_ok(d: &str) -> bool {
    let d = d.trim_start_matches('.').to_ascii_lowercase();
    d == "youtube.com" || d.ends_with(".youtube.com") || d == "google.com" || d.ends_with(".google.com")
}

/// Принимаем Netscape cookies.txt и JSON-экспорт (Cookie-Editor, EditThisCookie).
/// Оставляем только cookies youtube.com / google.com — остальное из файла выбрасываем.
pub fn parse_cookies(raw: &str) -> Result<ParsedCookies, String> {
    if raw.len() > MAX_COOKIES {
        return Err("файл слишком большой".into());
    }
    let raw = raw.trim_start_matches('\u{feff}').trim();
    if raw.is_empty() {
        return Err("файл пустой".into());
    }
    let mut lines: Vec<String> = Vec::new();
    let mut names: Vec<String> = Vec::new();
    if raw.starts_with('[') || raw.starts_with('{') {
        let v: serde_json::Value = serde_json::from_str(raw).map_err(|e| format!("не похоже на JSON с cookies: {e}"))?;
        let arr = match &v {
            serde_json::Value::Array(a) => a.clone(),
            serde_json::Value::Object(o) => o.get("cookies").and_then(|c| c.as_array()).cloned().unwrap_or_default(),
            _ => vec![],
        };
        for c in arr {
            let s = |k: &str| c.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
            let (domain, name, value) = (s("domain"), s("name"), s("value"));
            if domain.is_empty() || name.is_empty() || !domain_ok(&domain) {
                continue;
            }
            let path = Some(s("path")).filter(|p| !p.is_empty()).unwrap_or_else(|| "/".into());
            let secure = c.get("secure").and_then(|x| x.as_bool()).unwrap_or(false);
            let http_only = c.get("httpOnly").and_then(|x| x.as_bool()).unwrap_or(false);
            let host_only = c.get("hostOnly").and_then(|x| x.as_bool()).unwrap_or(!domain.starts_with('.'));
            let exp = c
                .get("expirationDate")
                .or_else(|| c.get("expires"))
                .and_then(|x| x.as_f64())
                .map(|x| x as i64)
                .unwrap_or(0);
            let dom = if host_only { domain.trim_start_matches('.').to_string() } else if domain.starts_with('.') { domain.clone() } else { format!(".{domain}") };
            let prefix = if http_only { "#HttpOnly_" } else { "" };
            lines.push(format!(
                "{prefix}{dom}\t{}\t{path}\t{}\t{exp}\t{name}\t{value}",
                if host_only { "FALSE" } else { "TRUE" },
                if secure { "TRUE" } else { "FALSE" }
            ));
            names.push(name);
        }
    } else {
        for line in raw.lines() {
            let line = line.trim_end_matches('\r');
            let body = line.strip_prefix("#HttpOnly_").unwrap_or(line);
            if body.starts_with('#') || body.trim().is_empty() {
                continue;
            }
            let f: Vec<&str> = body.split('\t').collect();
            if f.len() < 7 {
                return Err("строки должны быть в формате Netscape cookies.txt (7 полей через табуляцию)".into());
            }
            if !domain_ok(f[0]) {
                continue;
            }
            names.push(f[5].to_string());
            lines.push(line.to_string());
        }
    }
    if lines.is_empty() {
        return Err("в файле нет cookies youtube.com — выгрузите их со страницы music.youtube.com".into());
    }
    let logged_in = names.iter().any(|n| LOGIN_COOKIES.contains(&n.as_str()));
    if !logged_in {
        return Err("в файле нет cookies входа (SAPISID, LOGIN_INFO…) — вы точно вошли в аккаунт перед выгрузкой?".into());
    }
    let mut text = String::from("# Netscape HTTP Cookie File\n# Загружено через админку music.palustris.ru\n\n");
    for l in &lines {
        text.push_str(l);
        text.push('\n');
    }
    Ok(ParsedCookies { text, count: lines.len() })
}

fn cookie_stats(path: &Path) -> (usize, bool) {
    let Ok(raw) = std::fs::read_to_string(path) else { return (0, false) };
    let mut n = 0;
    let mut logged = false;
    for line in raw.lines() {
        let body = line.strip_prefix("#HttpOnly_").unwrap_or(line);
        if body.starts_with('#') || body.trim().is_empty() {
            continue;
        }
        let f: Vec<&str> = body.split('\t').collect();
        if f.len() >= 7 {
            n += 1;
            if LOGIN_COOKIES.contains(&f[5]) {
                logged = true;
            }
        }
    }
    (n, logged)
}

fn write_private(path: &Path, text: &str) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, text)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
    }
    std::fs::rename(&tmp, path)
}

impl Accounts {
    pub fn new(db: Connection, dir: PathBuf) -> anyhow::Result<Self> {
        std::fs::create_dir_all(&dir)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
        }
        Ok(Accounts { db: Mutex::new(db), dir, ip_flag_until: Mutex::new(0) })
    }

    pub fn path(&self, id: i64) -> PathBuf {
        self.dir.join(format!("{id}.txt"))
    }

    // ───────────── IP сервера ─────────────

    pub fn ip_flagged_until(&self) -> i64 {
        self.ip_flag_until.lock().map(|v| *v).unwrap_or(0)
    }
    pub fn ip_flagged(&self) -> bool {
        self.ip_flagged_until() > now()
    }
    pub fn flag_ip(&self) {
        if let Ok(mut v) = self.ip_flag_until.lock() {
            *v = now() + IP_FLAG;
        }
    }
    pub fn unflag_ip(&self) {
        if let Ok(mut v) = self.ip_flag_until.lock() {
            *v = 0;
        }
    }

    // ───────────── ротация ─────────────

    /// Есть ли хоть один аккаунт, который можно взять прямо сейчас.
    pub fn any_available(&self) -> bool {
        let Ok(db) = self.db.lock() else { return false };
        db.query_row(
            "SELECT COUNT(*) FROM yt_accounts WHERE enabled=1 AND status!='dead' AND cooldown_until<=?1",
            [now()],
            |r| r.get::<_, i64>(0),
        )
        .map(|n| n > 0)
        .unwrap_or(false)
    }

    /// Взять следующий аккаунт (дольше всех не использовавшийся), кроме уже опробованных.
    pub fn pick(&self, exclude: &[i64]) -> Option<(i64, PathBuf)> {
        let db = self.db.lock().ok()?;
        let t = now();
        let mut stmt = db
            .prepare("SELECT id FROM yt_accounts WHERE enabled=1 AND status!='dead' AND cooldown_until<=?1 ORDER BY last_used ASC, id ASC")
            .ok()?;
        let ids: Vec<i64> = stmt.query_map([t], |r| r.get(0)).ok()?.flatten().collect();
        for id in ids {
            if exclude.contains(&id) {
                continue;
            }
            let p = self.path(id);
            if !p.exists() {
                continue;
            }
            let _ = db.execute("UPDATE yt_accounts SET last_used=?1 WHERE id=?2", params![t, id]);
            return Some((id, p));
        }
        None
    }

    pub fn report_ok(&self, id: i64) {
        if let Ok(db) = self.db.lock() {
            let _ = db.execute(
                "UPDATE yt_accounts SET status='ok', note=NULL, last_ok=?1, ok_count=ok_count+1, cooldown_until=0 WHERE id=?2",
                params![now(), id],
            );
        }
    }

    pub fn report_err(&self, id: i64, kind: ErrKind, err: &str) {
        let t = now();
        let (status, cooldown) = match kind {
            ErrKind::Bot => ("cooldown", t + COOLDOWN_BOT),
            ErrKind::RateLimit => ("cooldown", t + COOLDOWN_429),
            ErrKind::CookiesDead => ("dead", 0),
            // Ошибки видео и сети — не вина аккаунта.
            _ => ("", 0),
        };
        let note: String = err.chars().take(400).collect();
        if let Ok(db) = self.db.lock() {
            if status.is_empty() {
                let _ = db.execute(
                    "UPDATE yt_accounts SET note=?1, last_err=?2, err_count=err_count+1 WHERE id=?3",
                    params![note, t, id],
                );
            } else {
                let _ = db.execute(
                    "UPDATE yt_accounts SET status=?1, cooldown_until=?2, note=?3, last_err=?4, err_count=err_count+1 WHERE id=?5",
                    params![status, cooldown, note, t, id],
                );
            }
        }
        if status == "dead" || status == "cooldown" {
            eprintln!("yt account #{id}: {status} ({})", kind.as_str());
        }
    }

    // ───────────── журнал ─────────────

    pub fn log_event(&self, video: &str, account: Option<i64>, client: &str, res: Result<(), (&str, ErrKind)>, ms: i64) {
        let Ok(db) = self.db.lock() else { return };
        let (ok, kind, err) = match res {
            Ok(()) => (1, None, None),
            Err((e, k)) => (0, Some(k.as_str()), Some(e.chars().take(600).collect::<String>())),
        };
        let _ = db.execute(
            "INSERT INTO dl_events(at, video, account, client, ok, kind, err, ms) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
            params![now(), video, account, client, ok, kind, err, ms],
        );
    }

    pub fn prune(&self) {
        if let Ok(db) = self.db.lock() {
            let _ = db.execute("DELETE FROM dl_events WHERE at < ?1", [now() - 14 * 86400]);
        }
    }

    pub fn events(&self, limit: i64, only_fail: bool, video: Option<&str>) -> rusqlite::Result<Vec<Event>> {
        let db = self.db.lock().map_err(|_| rusqlite::Error::InvalidQuery)?;
        let mut stmt = db.prepare(
            "SELECT e.id, e.at, e.video,
                    COALESCE(
                      (SELECT title || ' — ' || artist FROM history h WHERE h.track_id=e.video LIMIT 1),
                      (SELECT title || ' — ' || artist FROM likes l WHERE l.track_id=e.video LIMIT 1),
                      (SELECT title || ' — ' || artist FROM playlist_items p WHERE p.track_id=e.video LIMIT 1)),
                    e.account, a.label, e.client, e.ok, e.kind, e.err, e.ms
             FROM dl_events e LEFT JOIN yt_accounts a ON a.id=e.account
             WHERE (?1=0 OR e.ok=0) AND (?2 IS NULL OR e.video=?2)
             ORDER BY e.id DESC LIMIT ?3",
        )?;
        let rows = stmt.query_map(params![only_fail as i64, video, limit], |r| {
            Ok(Event {
                id: r.get(0)?,
                at: r.get(1)?,
                video: r.get(2)?,
                title: r.get(3)?,
                account: r.get(4)?,
                account_label: r.get(5)?,
                client: r.get(6)?,
                ok: r.get::<_, i64>(7)? != 0,
                kind: r.get(8)?,
                err: r.get(9)?,
                ms: r.get(10)?,
            })
        })?;
        rows.collect()
    }

    /// (успешно, ошибок) за последние `secs` секунд. Ошибки считаем по трекам, а не попыткам:
    /// трек, который в итоге скачался, неудачей не считается.
    pub fn counts(&self, secs: i64) -> (i64, i64, i64) {
        let Ok(db) = self.db.lock() else { return (0, 0, 0) };
        let since = now() - secs;
        db.query_row(
            "SELECT
               (SELECT COUNT(DISTINCT video) FROM dl_events WHERE at>=?1 AND ok=1),
               (SELECT COUNT(DISTINCT video) FROM dl_events e WHERE at>=?1 AND ok=0
                  AND NOT EXISTS(SELECT 1 FROM dl_events s WHERE s.video=e.video AND s.ok=1 AND s.at>=e.at)),
               (SELECT COUNT(*) FROM dl_events WHERE at>=?1 AND ok=0)",
            [since],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap_or((0, 0, 0))
    }

    pub fn last_error(&self) -> Option<(i64, String)> {
        let db = self.db.lock().ok()?;
        db.query_row("SELECT at, err FROM dl_events WHERE ok=0 ORDER BY id DESC LIMIT 1", [], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()
            .ok()
            .flatten()
    }

    // ───────────── управление ─────────────

    pub fn list(&self) -> rusqlite::Result<Vec<AccountInfo>> {
        let db = self.db.lock().map_err(|_| rusqlite::Error::InvalidQuery)?;
        let t = now();
        let mut stmt = db.prepare(
            "SELECT a.id, a.label, a.enabled, a.status, a.note, a.created, a.updated, a.last_used, a.last_ok, a.last_err,
                    a.cooldown_until, a.ok_count, a.err_count,
                    (SELECT COUNT(*) FROM dl_events e WHERE e.account=a.id AND e.ok=1 AND e.at>=?1)
             FROM yt_accounts a ORDER BY a.id",
        )?;
        let rows = stmt.query_map([t - 3600], |r| {
            let id: i64 = r.get(0)?;
            let enabled = r.get::<_, i64>(2)? != 0;
            let raw: String = r.get(3)?;
            let cooldown_until: i64 = r.get(10)?;
            let status = if !enabled {
                "off".to_string()
            } else if raw == "dead" {
                "dead".into()
            } else if cooldown_until > t {
                "cooldown".into()
            } else if raw == "cooldown" {
                "ok".into()
            } else {
                raw
            };
            Ok(AccountInfo {
                id,
                label: r.get(1)?,
                enabled,
                status,
                note: r.get(4)?,
                created: r.get(5)?,
                updated: r.get(6)?,
                last_used: r.get(7)?,
                last_ok: r.get(8)?,
                last_err: r.get(9)?,
                cooldown_until,
                ok_count: r.get(11)?,
                err_count: r.get(12)?,
                hour_ok: r.get(13)?,
                cookies: 0,
                logged_in: false,
            })
        })?;
        let mut v: Vec<AccountInfo> = rows.collect::<Result<_, _>>()?;
        drop(stmt);
        drop(db);
        for a in &mut v {
            let (n, l) = cookie_stats(&self.path(a.id));
            a.cookies = n;
            a.logged_in = l;
        }
        Ok(v)
    }

    pub fn add(&self, label: &str, cookies: &ParsedCookies) -> anyhow::Result<i64> {
        let t = now();
        let id = {
            let db = self.db.lock().map_err(|_| anyhow::anyhow!("db lock"))?;
            db.execute(
                "INSERT INTO yt_accounts(label, enabled, status, created, updated) VALUES(?1, 1, 'new', ?2, ?2)",
                params![label, t],
            )?;
            db.last_insert_rowid()
        };
        if let Err(e) = write_private(&self.path(id), &cookies.text) {
            if let Ok(db) = self.db.lock() {
                let _ = db.execute("DELETE FROM yt_accounts WHERE id=?1", [id]);
            }
            return Err(e.into());
        }
        Ok(id)
    }

    pub fn replace_cookies(&self, id: i64, cookies: &ParsedCookies) -> anyhow::Result<bool> {
        let db = self.db.lock().map_err(|_| anyhow::anyhow!("db lock"))?;
        let exists: bool = db.query_row("SELECT COUNT(*) FROM yt_accounts WHERE id=?1", [id], |r| r.get::<_, i64>(0))? > 0;
        if !exists {
            return Ok(false);
        }
        write_private(&self.path(id), &cookies.text)?;
        db.execute(
            "UPDATE yt_accounts SET status='new', note=NULL, cooldown_until=0, updated=?1 WHERE id=?2",
            params![now(), id],
        )?;
        Ok(true)
    }

    pub fn update(&self, id: i64, label: Option<&str>, enabled: Option<bool>, reset: bool) -> anyhow::Result<bool> {
        let db = self.db.lock().map_err(|_| anyhow::anyhow!("db lock"))?;
        let mut n = 0;
        if let Some(l) = label {
            n += db.execute("UPDATE yt_accounts SET label=?1 WHERE id=?2", params![l, id])?;
        }
        if let Some(e) = enabled {
            n += db.execute("UPDATE yt_accounts SET enabled=?1 WHERE id=?2", params![e as i64, id])?;
        }
        if reset {
            n += db.execute("UPDATE yt_accounts SET cooldown_until=0, status=CASE WHEN status='dead' THEN 'new' ELSE status END WHERE id=?1", [id])?;
        }
        Ok(n > 0)
    }

    pub fn delete(&self, id: i64) -> anyhow::Result<bool> {
        let db = self.db.lock().map_err(|_| anyhow::anyhow!("db lock"))?;
        let n = db.execute("DELETE FROM yt_accounts WHERE id=?1", [id])?;
        let _ = std::fs::remove_file(self.path(id));
        Ok(n > 0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn netscape_keeps_only_youtube_and_httponly() {
        let raw = "# Netscape HTTP Cookie File\n\
.youtube.com\tTRUE\t/\tTRUE\t1999999999\tSAPISID\tabc\n\
#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1999999999\t__Secure-3PSID\tdef\n\
.example.com\tTRUE\t/\tFALSE\t0\tfoo\tbar\n";
        let p = parse_cookies(raw).unwrap();
        assert_eq!(p.count, 2);
        assert!(p.text.contains("#HttpOnly_.youtube.com"));
        assert!(!p.text.contains("example.com"));
    }

    #[test]
    fn json_export_is_converted() {
        let raw = r#"[{"domain":".youtube.com","name":"LOGIN_INFO","value":"x","path":"/","secure":true,"httpOnly":true,"hostOnly":false,"expirationDate":1999999999.5},
                     {"domain":"mail.ru","name":"a","value":"b"}]"#;
        let p = parse_cookies(raw).unwrap();
        assert_eq!(p.count, 1);
        assert!(p.text.contains("#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1999999999\tLOGIN_INFO\tx"));
    }

    #[test]
    fn rejects_guest_cookies() {
        let raw = ".youtube.com\tTRUE\t/\tTRUE\t0\tVISITOR_INFO1_LIVE\tzzz\n";
        assert!(parse_cookies(raw).is_err());
    }

    #[test]
    fn classify_errors() {
        assert_eq!(classify("ERROR: [youtube] x: Sign in to confirm you’re not a bot."), ErrKind::Bot);
        assert_eq!(classify("ERROR: [youtube] x: The provided YouTube account cookies are no longer valid."), ErrKind::CookiesDead);
        assert_eq!(classify("ERROR: unable to download video data: HTTP Error 429: Too Many Requests"), ErrKind::RateLimit);
        assert_eq!(classify("ERROR: [youtube] x: Video unavailable"), ErrKind::Video);
        assert_eq!(classify("ERROR: [youtube] x: Requested format is not available"), ErrKind::Other);
    }
}
