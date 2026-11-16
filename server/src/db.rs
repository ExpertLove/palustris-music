use rusqlite::{Connection, Row};
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default)]
    pub duration: i64,
    #[serde(default)]
    pub thumb: Option<String>,
    /// browseId исполнителя в YouTube Music — только из свежих ответов YTM, в базе не хранится.
    #[serde(default, rename = "artistId", skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
    /// browseId альбома (MPREb_…), если известен.
    #[serde(default, rename = "albumId", skip_serializing_if = "Option::is_none")]
    pub album_id: Option<String>,
}

impl Track {
    pub fn new(id: String, title: String, artist: String) -> Self {
        Track { id, title, artist, album: None, duration: 0, thumb: None, artist_id: None, album_id: None }
    }
}

pub const TRACK_COLS: &str = "track_id, title, artist, album, duration, thumb";

pub fn row_track(r: &Row) -> rusqlite::Result<Track> {
    Ok(Track {
        id: r.get(0)?,
        title: r.get(1)?,
        artist: r.get(2)?,
        album: r.get(3)?,
        duration: r.get(4)?,
        thumb: r.get(5)?,
        artist_id: None,
        album_id: None,
    })
}

pub fn open(path: &Path) -> anyhow::Result<Connection> {
    let c = Connection::open(path)?;
    c.pragma_update(None, "journal_mode", "WAL")?;
    c.pragma_update(None, "foreign_keys", "ON")?;
    c.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS users(
            id INTEGER PRIMARY KEY,
            email TEXT NOT NULL UNIQUE,
            pass TEXT NOT NULL,
            created INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions(
            token TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS likes(
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            track_id TEXT NOT NULL,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            album TEXT,
            duration INTEGER NOT NULL DEFAULT 0,
            thumb TEXT,
            created INTEGER NOT NULL,
            PRIMARY KEY(user_id, track_id)
        );
        CREATE TABLE IF NOT EXISTS history(
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            track_id TEXT NOT NULL,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            album TEXT,
            duration INTEGER NOT NULL DEFAULT 0,
            thumb TEXT,
            played_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS history_user ON history(user_id, played_at);
        CREATE TABLE IF NOT EXISTS playlists(
            id INTEGER PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            created INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS playlist_items(
            playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
            track_id TEXT NOT NULL,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            album TEXT,
            duration INTEGER NOT NULL DEFAULT 0,
            thumb TEXT,
            pos INTEGER NOT NULL,
            PRIMARY KEY(playlist_id, track_id)
        );
        CREATE TABLE IF NOT EXISTS user_genres(
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            genre TEXT NOT NULL,
            PRIMARY KEY(user_id, genre)
        );
        CREATE TABLE IF NOT EXISTS email_tokens(
            token TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created INTEGER NOT NULL,
            expires INTEGER NOT NULL
        );
        "#,
    )?;
    // Добавлено позже, поэтому ALTER вместо правки CREATE.
    let _ = c.execute("ALTER TABLE users ADD COLUMN name TEXT NOT NULL DEFAULT ''", []);
    let _ = c.execute("ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0", []);
    let _ = c.execute("ALTER TABLE users ADD COLUMN consent_at INTEGER NOT NULL DEFAULT 0", []);
    // Сессии со скользящим сроком жизни: seen — последнее обращение.
    let _ = c.execute("ALTER TABLE sessions ADD COLUMN seen INTEGER NOT NULL DEFAULT 0", []);
    // Публичная ссылка на плейлист.
    let _ = c.execute("ALTER TABLE playlists ADD COLUMN share TEXT", []);
    c.execute_batch(
        r#"
        CREATE UNIQUE INDEX IF NOT EXISTS playlists_share ON playlists(share) WHERE share IS NOT NULL;
        CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
        CREATE TABLE IF NOT EXISTS password_tokens(
            token TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created INTEGER NOT NULL,
            expires INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS skips(
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            track_id TEXT NOT NULL,
            artist TEXT NOT NULL,
            at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS skips_user ON skips(user_id, at);
        CREATE TABLE IF NOT EXISTS lyrics(
            key TEXT PRIMARY KEY,
            synced TEXT,
            plain TEXT,
            instrumental INTEGER NOT NULL DEFAULT 0,
            found INTEGER NOT NULL DEFAULT 0,
            fetched INTEGER NOT NULL
        );
        "#,
    )?;
    Ok(c)
}
