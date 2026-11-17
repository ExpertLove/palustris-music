//! Громкость треков по EBU R128 для выравнивания громкости в приложении.
//!
//! После загрузки трека (и по запросу клиента, если трек уже лежит в кэше) ffmpeg считает
//! интегральную громкость (LUFS) и пиковое значение (true peak, dBTP). Клиент сам решает,
//! насколько убавить или прибавить трек: так одно значение подходит под любую целевую громкость.
//!
//! На сервере одно ядро, поэтому анализ идёт строго по одному и с пониженным приоритетом.
//! Пятиминутный трек считается примерно за секунду.
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;

#[derive(Serialize, Clone, Copy, Debug, PartialEq)]
pub struct Loudness {
    /// Интегральная громкость, LUFS.
    pub lufs: f32,
    /// Пик с учётом межсэмпловых выбросов, dBTP.
    pub peak: f32,
}

pub struct Analyzer {
    db: Mutex<Connection>,
    tx: mpsc::UnboundedSender<(String, PathBuf)>,
    /// Уже в очереди или считается — чтобы не ставить один трек дважды.
    pending: Mutex<HashSet<String>>,
    /// Не посчитались (тишина, битый файл): не пробуем снова до перезапуска.
    failed: Mutex<HashSet<String>>,
}

impl Analyzer {
    pub fn start(db: Connection) -> Arc<Self> {
        let (tx, mut rx) = mpsc::unbounded_channel::<(String, PathBuf)>();
        let me = Arc::new(Analyzer {
            db: Mutex::new(db),
            tx,
            pending: Mutex::new(HashSet::new()),
            failed: Mutex::new(HashSet::new()),
        });
        let worker = me.clone();
        tokio::spawn(async move {
            while let Some((id, path)) = rx.recv().await {
                if worker.get(&id).is_none() {
                    match measure(&path).await {
                        Ok(l) => worker.put(&id, l),
                        Err(e) => {
                            eprintln!("loudness {id}: {e}");
                            if let Ok(mut f) = worker.failed.lock() {
                                f.insert(id.clone());
                            }
                        }
                    }
                }
                if let Ok(mut p) = worker.pending.lock() {
                    p.remove(&id);
                }
            }
        });
        me
    }

    pub fn get(&self, id: &str) -> Option<Loudness> {
        let db = self.db.lock().ok()?;
        db.query_row("SELECT lufs, peak FROM loudness WHERE track_id=?1", [id], |r| {
            Ok(Loudness { lufs: r.get::<_, f64>(0)? as f32, peak: r.get::<_, f64>(1)? as f32 })
        })
        .optional()
        .ok()
        .flatten()
    }

    fn put(&self, id: &str, l: Loudness) {
        if let Ok(db) = self.db.lock() {
            let _ = db.execute(
                "INSERT OR REPLACE INTO loudness(track_id, lufs, peak, at) VALUES(?1, ?2, ?3, ?4)",
                params![id, l.lufs as f64, l.peak as f64, crate::recs::now()],
            );
        }
    }

    /// Поставить файл в очередь, если громкость ещё не посчитана.
    pub fn enqueue(&self, id: &str, path: &Path) {
        if self.get(id).is_some() || self.failed.lock().map(|f| f.contains(id)).unwrap_or(true) {
            return;
        }
        let Ok(mut p) = self.pending.lock() else { return };
        if !p.insert(id.to_string()) {
            return;
        }
        let _ = self.tx.send((id.to_string(), path.to_path_buf()));
    }
}

/// `ffmpeg … -af ebur128=peak=true` и разбор итоговой сводки.
async fn measure(path: &Path) -> anyhow::Result<Loudness> {
    let run = tokio::process::Command::new("nice")
        .args(["-n", "15", "ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-i"])
        .arg(path)
        .args(["-map", "0:a:0", "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .output();
    let out = tokio::time::timeout(Duration::from_secs(120), run).await??;
    if !out.status.success() {
        anyhow::bail!("ffmpeg: {}", out.status);
    }
    parse(&String::from_utf8_lossy(&out.stderr)).ok_or_else(|| anyhow::anyhow!("нет сводки ebur128"))
}

/// Из сводки берём `I: … LUFS` и `Peak: … dBFS`, которые идут после строки `Summary:`.
fn parse(log: &str) -> Option<Loudness> {
    let summary = &log[log.rfind("Summary:")?..];
    let value = |key: &str| -> Option<f32> {
        summary
            .lines()
            .map(str::trim)
            .find_map(|l| l.strip_prefix(key))
            .and_then(|rest| rest.split_whitespace().next())
            .and_then(|v| v.parse::<f32>().ok())
            .filter(|v| v.is_finite())
    };
    Some(Loudness { lufs: value("I:")?, peak: value("Peak:")? })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_summary() {
        let log = "[Parsed_ebur128_0 @ 0x5] Summary:\n\n  Integrated loudness:\n    I:          -8.8 LUFS\n    Threshold: -19.0 LUFS\n\n  Loudness range:\n    LRA:         2.3 LU\n\n  True peak:\n    Peak:        1.2 dBFS\n";
        assert_eq!(parse(log), Some(Loudness { lufs: -8.8, peak: 1.2 }));
    }

    #[test]
    fn silence_is_not_finite() {
        let log = "Summary:\n    I:         -70.0 LUFS\n    Peak:       -inf dBFS\n";
        assert_eq!(parse(log), None);
    }
}
