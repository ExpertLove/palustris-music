//! Компактная копия трека для скачивания в приложение: Opus в Ogg вместо AAC 128 кбит/с.
//!
//! Opus 64 кбит/с весит вдвое меньше исходника и на слух почти не отличается в наушниках.
//! Перекодируем из уже скачанного файла один раз и кладём рядом с исходником
//! (`{id}.o64.ogg`), так что общая очистка кэша по давности убирает и эти файлы.
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use tokio::sync::Semaphore;

/// Разрешённые битрейты: `?q=o64` и `?q=o96`.
pub fn parse(q: Option<&str>) -> Result<Option<u32>, ()> {
    match q {
        None | Some("") => Ok(None),
        Some("o64") => Ok(Some(64)),
        Some("o96") => Ok(Some(96)),
        Some(_) => Err(()),
    }
}

fn path(dir: &Path, id: &str, kbps: u32) -> PathBuf {
    dir.join(format!("{id}.o{kbps}.ogg"))
}

/// Готовая копия, если уже есть.
pub fn existing(dir: &Path, id: &str, kbps: u32) -> Option<PathBuf> {
    let p = path(dir, id, kbps);
    std::fs::metadata(&p).ok().filter(|m| m.len() > 0).map(|_| p)
}

/// На сервере одно ядро: перекодируем строго по одному и с пониженным приоритетом,
/// чтобы обычное прослушивание не тормозило.
fn slots() -> &'static Semaphore {
    static S: OnceLock<Semaphore> = OnceLock::new();
    S.get_or_init(|| Semaphore::new(1))
}

pub async fn make(dir: &Path, src: &Path, id: &str, kbps: u32) -> anyhow::Result<PathBuf> {
    let _permit = slots().acquire().await?;
    // Пока ждали очереди, копию мог сделать параллельный запрос.
    if let Some(p) = existing(dir, id, kbps) {
        return Ok(p);
    }
    let out = path(dir, id, kbps);
    let tmp = dir.join(format!("{id}.o{kbps}.{}.tmp", std::process::id()));
    let status = tokio::process::Command::new("nice")
        .args(["-n", "10", "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i"])
        .arg(src)
        .args(["-vn", "-map_metadata", "-1", "-c:a", "libopus", "-b:a"])
        .arg(format!("{kbps}k"))
        .args(["-vbr", "on", "-application", "audio", "-f", "ogg"])
        .arg(&tmp)
        .kill_on_drop(true)
        .status()
        .await?;
    if !status.success() {
        let _ = std::fs::remove_file(&tmp);
        anyhow::bail!("ffmpeg: {status}");
    }
    std::fs::rename(&tmp, &out)?;
    Ok(out)
}
