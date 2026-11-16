//! Аудио: загрузка через yt-dlp с отдачей «на лету», предзагрузка и очистка кэша.
//!
//! yt-dlp сначала печатает размер файла (`--print before_dl:`), потом пишет `{id}.{ext}.part`.
//! Как только размер известен, клиент получает ответ с полным Content-Length, а байты
//! дочитываются из растущего файла. Так трек начинает играть, не дожидаясь конца загрузки.
use axum::body::{Body, Bytes};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncSeekExt, BufReader};
use tokio::sync::{Notify, Semaphore};

pub const EXTS: [&str; 4] = ["m4a", "webm", "opus", "mp3"];

#[derive(Clone, Default, Debug)]
pub struct JobState {
    pub size: Option<u64>,
    pub part: Option<PathBuf>,
    pub ext: String,
    pub done: Option<Result<PathBuf, String>>,
}

#[derive(Default)]
pub struct Job {
    st: Mutex<JobState>,
    notify: Notify,
}

impl Job {
    pub fn state(&self) -> JobState {
        self.st.lock().map(|s| s.clone()).unwrap_or_default()
    }
    fn update(&self, f: impl FnOnce(&mut JobState)) {
        if let Ok(mut s) = self.st.lock() {
            f(&mut s);
        }
        self.notify.notify_waiters();
    }
    async fn wait(&self) {
        let _ = tokio::time::timeout(Duration::from_millis(150), self.notify.notified()).await;
    }
}

pub struct Audio {
    pub dir: PathBuf,
    pub ytdlp: String,
    jobs: Mutex<HashMap<String, Arc<Job>>>,
    play_slots: Semaphore,
    prefetch_slots: Semaphore,
}

pub enum Ready {
    File(PathBuf),
    Progressive(Arc<Job>),
    Failed(String),
}

pub fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 20 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn mime(ext: &str) -> &'static str {
    match ext {
        "m4a" => "audio/mp4",
        "webm" | "opus" => "audio/webm",
        "mp3" => "audio/mpeg",
        _ => "application/octet-stream",
    }
}

impl Audio {
    pub fn new(dir: PathBuf, ytdlp: String) -> Arc<Self> {
        Arc::new(Audio {
            dir,
            ytdlp,
            jobs: Mutex::new(HashMap::new()),
            play_slots: Semaphore::new(3),
            prefetch_slots: Semaphore::new(1),
        })
    }

    pub fn cached(&self, id: &str) -> Option<PathBuf> {
        if !valid_id(id) {
            return None;
        }
        EXTS.iter()
            .map(|e| self.dir.join(format!("{id}.{e}")))
            .find(|p| std::fs::metadata(p).map(|m| m.len() > 0).unwrap_or(false))
    }

    /// Отметить файл как недавно использованный — для очистки по давности.
    pub fn touch(path: &Path) {
        if let Ok(f) = std::fs::File::options().write(true).open(path) {
            let _ = f.set_modified(SystemTime::now());
        }
    }

    /// Текущая загрузка трека или новая.
    pub fn job(self: &Arc<Self>, id: &str, prefetch: bool) -> Arc<Job> {
        let mut jobs = self.jobs.lock().unwrap();
        if let Some(j) = jobs.get(id) {
            return j.clone();
        }
        let job = Arc::new(Job::default());
        jobs.insert(id.to_string(), job.clone());
        let me = self.clone();
        let id = id.to_string();
        let j = job.clone();
        tokio::spawn(async move {
            let res = me.run(&id, &j, prefetch).await;
            j.update(|s| s.done = Some(res.map_err(|e| e.to_string())));
            if let Ok(mut m) = me.jobs.lock() {
                m.remove(&id);
            }
        });
        job
    }

    pub fn prefetch(self: &Arc<Self>, id: &str) {
        if !valid_id(id) || self.cached(id).is_some() {
            return;
        }
        if self.jobs.lock().map(|m| m.contains_key(id)).unwrap_or(true) {
            return;
        }
        self.job(id, true);
    }

    async fn run(&self, id: &str, job: &Job, prefetch: bool) -> anyhow::Result<PathBuf> {
        let _permit = if prefetch { self.prefetch_slots.acquire().await? } else { self.play_slots.acquire().await? };
        if let Some(p) = self.cached(id) {
            return Ok(p);
        }
        tokio::fs::create_dir_all(&self.dir).await?;
        let cookies = std::env::var("MUSIC_COOKIES").unwrap_or_default();
        let use_cookies = !cookies.is_empty() && Path::new(&cookies).exists();
        // Последние две попытки — клиенты с PO-токеном от bgutil-provider (127.0.0.1:4416):
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
                tokio::time::sleep(Duration::from_millis(500 * i as u64)).await;
            }
            let mut cmd = tokio::process::Command::new(&self.ytdlp);
            cmd.args([
                "-f",
                "bestaudio[ext=m4a]/bestaudio",
                "--no-playlist",
                "--no-warnings",
                "--no-simulate",
                "--fixup",
                "never",
                "--socket-timeout",
                "15",
                "--retries",
                "3",
                "--print",
                "before_dl:%(filesize)s %(ext)s",
            ]);
            if use_cookies {
                cmd.args(["--cookies", &cookies]);
            }
            if let Some(e) = extractor {
                cmd.args(["--extractor-args", e]);
            }
            cmd.arg("-o")
                .arg(self.dir.join(format!("{id}.%(ext)s")).to_string_lossy().to_string())
                .arg(url)
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .kill_on_drop(true);
            let mut child = cmd.spawn()?;
            let stderr = child.stderr.take();
            let err_task = tokio::spawn(async move {
                let mut s = String::new();
                if let Some(mut e) = stderr {
                    let _ = e.read_to_string(&mut s).await;
                }
                s
            });
            let mut announced = false;
            if let Some(out) = child.stdout.take() {
                let mut lines = BufReader::new(out).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let mut it = line.split_whitespace();
                    let (Some(size), Some(ext)) = (it.next(), it.next()) else { continue };
                    if let Ok(size) = size.parse::<u64>() {
                        if size > 0 && !announced {
                            announced = true;
                            let part = self.dir.join(format!("{id}.{ext}.part"));
                            let ext = ext.to_string();
                            job.update(|s| {
                                s.size = Some(size);
                                s.part = Some(part);
                                s.ext = ext;
                            });
                        }
                    }
                }
            }
            let status = child.wait().await?;
            let err = err_task.await.unwrap_or_default();
            if status.success() {
                if let Some(p) = self.cached(id) {
                    return Ok(p);
                }
            }
            last = err.trim().lines().last().unwrap_or("yt-dlp failed").to_string();
            eprintln!("yt-dlp attempt {} for {id} failed: {last}", i + 1);
            if announced {
                // Клиент уже получает байты этой попытки — переключаться на другую нельзя.
                break;
            }
        }
        anyhow::bail!("yt-dlp: {last}")
    }

    /// Ждём, пока можно отвечать: файл готов, известен размер или всё сломалось.
    pub async fn ready(self: &Arc<Self>, id: &str) -> Ready {
        if let Some(p) = self.cached(id) {
            return Ready::File(p);
        }
        let job = self.job(id, false);
        let started = Instant::now();
        loop {
            let s = job.state();
            match &s.done {
                Some(Ok(p)) => return Ready::File(p.clone()),
                Some(Err(e)) => return Ready::Failed(e.clone()),
                None => {}
            }
            if let (Some(_), Some(part)) = (s.size, &s.part) {
                if part.exists() {
                    return Ready::Progressive(job);
                }
            }
            if started.elapsed() > Duration::from_secs(150) {
                return Ready::Failed("timeout".into());
            }
            job.wait().await;
        }
    }

    /// Удаляем давно не игравшие файлы, пока кэш больше лимита.
    pub fn gc(&self, max_bytes: u64) -> (usize, u64) {
        let Ok(rd) = std::fs::read_dir(&self.dir) else { return (0, 0) };
        let mut files: Vec<(SystemTime, u64, PathBuf)> = rd
            .flatten()
            .filter_map(|e| {
                let p = e.path();
                let name = p.file_name()?.to_str()?.to_string();
                let m = e.metadata().ok()?;
                // .part старше суток — брошенная загрузка.
                if name.ends_with(".part") {
                    let old = m.modified().ok()?.elapsed().ok()? > Duration::from_secs(86400);
                    if old {
                        let _ = std::fs::remove_file(&p);
                    }
                    return None;
                }
                Some((m.modified().ok()?, m.len(), p))
            })
            .collect();
        let total: u64 = files.iter().map(|f| f.1).sum();
        if total <= max_bytes {
            return (0, total);
        }
        files.sort_by_key(|f| f.0);
        let target = max_bytes / 10 * 9;
        let (mut left, mut removed) = (total, 0);
        for (_, len, p) in files {
            if left <= target {
                break;
            }
            if std::fs::remove_file(&p).is_ok() {
                left -= len;
                removed += 1;
            }
        }
        (removed, left)
    }
}

fn parse_range(h: &HeaderMap, size: u64) -> Option<Result<(u64, u64), ()>> {
    let v = h.get(header::RANGE)?.to_str().ok()?;
    let spec = v.strip_prefix("bytes=")?;
    if spec.contains(',') {
        return None;
    }
    let (a, b) = spec.split_once('-')?;
    let (start, end) = if a.is_empty() {
        let n: u64 = b.parse().ok()?;
        (size.saturating_sub(n), size - 1)
    } else {
        let s: u64 = a.parse().ok()?;
        let e: u64 = if b.is_empty() { size - 1 } else { b.parse::<u64>().ok()?.min(size - 1) };
        (s, e)
    };
    if start > end || start >= size {
        return Some(Err(()));
    }
    Some(Ok((start, end)))
}

/// Отдача растущего файла с поддержкой Range.
pub async fn serve_progressive(job: Arc<Job>, headers: &HeaderMap) -> Response {
    let s = job.state();
    let (Some(size), Some(part)) = (s.size, s.part.clone()) else {
        return (StatusCode::BAD_GATEWAY, "нет данных").into_response();
    };
    let file = match tokio::fs::File::open(&part).await {
        Ok(f) => f,
        Err(_) => {
            // Уже переименован — значит, догрузился: отдаём готовый файл тем же способом.
            let done = job.state().done;
            match done {
                Some(Ok(p)) => match tokio::fs::File::open(&p).await {
                    Ok(f) => f,
                    Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
                },
                _ => return (StatusCode::BAD_GATEWAY, "файл пропал").into_response(),
            }
        }
    };
    let (status, start, end) = match parse_range(headers, size) {
        Some(Ok((a, b))) => (StatusCode::PARTIAL_CONTENT, a, b),
        Some(Err(())) => {
            return (StatusCode::RANGE_NOT_SATISFIABLE, [(header::CONTENT_RANGE, format!("bytes */{size}"))]).into_response()
        }
        None => (StatusCode::OK, 0, size - 1),
    };

    struct St {
        file: tokio::fs::File,
        pos: u64,
        end: u64,
        job: Arc<Job>,
        idle: Instant,
    }
    let state = St { file, pos: start, end, job, idle: Instant::now() };
    let stream = futures_util::stream::unfold(state, |mut st| async move {
        loop {
            if st.pos > st.end {
                return None;
            }
            let len = st.file.metadata().await.map(|m| m.len()).unwrap_or(0);
            if len > st.pos {
                let want = (len - st.pos).min(st.end + 1 - st.pos).min(64 * 1024) as usize;
                let mut buf = vec![0u8; want];
                if st.file.seek(std::io::SeekFrom::Start(st.pos)).await.is_err() {
                    return None;
                }
                match st.file.read(&mut buf).await {
                    Ok(0) => {}
                    Ok(n) => {
                        buf.truncate(n);
                        st.pos += n as u64;
                        st.idle = Instant::now();
                        return Some((Ok::<Bytes, std::io::Error>(Bytes::from(buf)), st));
                    }
                    Err(e) => return Some((Err(e), st)),
                }
            }
            match st.job.state().done {
                Some(Ok(_)) => {
                    let len = st.file.metadata().await.map(|m| m.len()).unwrap_or(0);
                    if len <= st.pos {
                        return None;
                    }
                    continue;
                }
                Some(Err(e)) => return Some((Err(std::io::Error::other(e)), st)),
                None => {}
            }
            if st.idle.elapsed() > Duration::from_secs(60) {
                return Some((Err(std::io::Error::other("загрузка зависла")), st));
            }
            st.job.wait().await;
        }
    });

    let mut resp = Response::new(Body::from_stream(stream));
    *resp.status_mut() = status;
    let h = resp.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime(&s.ext)));
    h.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, no-store"));
    if let Ok(v) = HeaderValue::from_str(&(end - start + 1).to_string()) {
        h.insert(header::CONTENT_LENGTH, v);
    }
    if status == StatusCode::PARTIAL_CONTENT {
        if let Ok(v) = HeaderValue::from_str(&format!("bytes {start}-{end}/{size}")) {
            h.insert(header::CONTENT_RANGE, v);
        }
    }
    resp
}
