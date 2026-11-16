# AGENTS.md — music.palustris.ru

Instructions for AI coding agents (Claude Code, Codex, pi, Cursor, …). Humans: start with `README.md`.

**Talk to the owner in Russian.** Code comments, commit messages and docs are in Russian too
(this file and `android/AGENTS.md` are the English exceptions).

## Map

| Path | What | Read before touching |
|---|---|---|
| `server/` | Rust backend (axum + SQLite + yt-dlp), runs on the prod server | `docs/ARCHITECTURE.md` |
| `web/` | React 19 + Vite site, served as static files by Caddy | `docs/ARCHITECTURE.md` § Frontend |
| `android/` | Native Android app (Kotlin, Compose, Media3) | **`android/AGENTS.md` — mandatory** |
| `ops/` | Caddy config, systemd units, cron, deploy scripts | `docs/SERVER.md` |
| `docs/` | Architecture, server runbook, dev guide, YouTube notes, history | — |

## Production is live and has real users

- Server: `ssh -i ~/.ssh/palustris_admin root@music.palustris.ru` (key is NOT in the repo; ask the owner).
  The same host runs other projects of the owner. Touch only music-related things.
- **Back up before changing anything on the server**: `/root/backups/<name>-$(date +%Y%m%d-%H%M%S)`.
  `ops/deploy-*.sh` already back up code/binary/static; run `/usr/local/bin/music-db-backup` before risky DB work.
- Ship web changes to the staging copy first: `./ops/deploy-web.sh next` → check https://music.palustris.ru/next/ →
  then `./ops/deploy-web.sh prod`. The backend has no staging: `cargo test` and `cargo check` locally, then
  `./ops/deploy-server.sh` (builds on the server, ~1 min, restarts the service).
- Never `git push --force` to `master`, never rewrite pushed history.

## Secrets — never commit (THIS REPOSITORY IS PUBLIC)

SSH key, SMTP password (`/etc/systemd/system/music.service.d/smtp-auth.conf` on the server), YouTube cookies
(`/var/lib/music/ytaccounts/*.txt`), Android keystore (`~/.android-keys/`, `android/keystore.properties`),
user data (`music.db`), admin emails, user emails, server logs. Use placeholders in examples (`ops/systemd/*.example`).
Grep your diff before committing. See `SECURITY.md`.

## Commands

```bash
cd server && cargo check && cargo test                 # backend: compile + unit tests (accounts parsing, etc.)
cd web && npm ci && npm run dev                        # site on :5173, /api proxied to prod (API_UPSTREAM to override)
cd web && npx tsc -p tsconfig.app.json --noEmit         # type check
cd web && npm run build                                 # tsc -b + vite build (what CI runs)
./ops/deploy-web.sh next|prod   ./ops/deploy-server.sh  # deploy (needs the SSH key)
```

CI (`.github/workflows/ci.yml`) runs `cargo test` and `npm run build` on every push.

## Rules that bite

- **Windows dev box, Git Bash.** Heredocs with backticks or `${}` get mangled by bash — write scripts to files
  with your file tool instead. Non-ASCII in `curl -d` arguments breaks; use ASCII or a file. `.gitattributes` forces LF.
- **Two connections to SQLite** (main + `accounts.rs`), both with `busy_timeout`. Keep DB locks short and never hold a
  `std::sync::Mutex` guard across `.await`.
- **Schema changes are additive**: `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE … ADD COLUMN` in `db.rs::open`, ignore the
  "duplicate column" error. No destructive migrations without a backup and the owner's OK.
- **yt-dlp**: lives in the venv `/opt/yt-dlp`, updated nightly with automatic rollback (`ops/yt-dlp-update.sh`).
  The server cannot reach GitHub — pip works, `yt-dlp -U` does not. The first anonymous client is `visionos`
  (no 5-second ad wait). Don't reorder clients without measuring start latency.
- **YouTube blocks the server IP from time to time** ("Sign in to confirm you're not a bot"). That's expected; see
  `docs/YOUTUBE.md`. Check `/admin` → «Загрузки» before debugging audio.
- **Admins** are emails in `MUSIC_ADMINS` (systemd drop-in). Only add already-registered emails: login does not require
  email verification, so an unregistered admin email can be claimed by anyone.
- **Parallel sessions.** Several agents may work in this repo at once (site vs Android). Commit only your paths
  (`git commit -- <paths>`), don't stage or revert someone else's changes, check `git status` first.
- **UI changes must be looked at**: screenshot desktop (1440px) and mobile (390px). Puppeteer + local Chrome works
  (`C:/Program Files/Google/Chrome/Application/chrome.exe`). Log in by putting a token into `localStorage['music.token']`.
  Create throwaway accounts for tests and delete them afterwards (`DELETE /api/account`).
- **Design language** "cover sets the colour": dark, glass, accent from artwork (`web/src/lib/color.ts`), Onest font,
  tokens in `web/src/styles/base.css`. Reuse `.btn`, `.chip`, `.seg`, `.acc-card`, `Dialog`; don't introduce a UI kit.
- **Russian copy**: plain, short, no bureaucratese. Use `plural()` from `web/src/lib/format.ts` for counts.

## Definition of done

1. `cargo test` and `npm run build` pass.
2. Behaviour verified against the real server (staging for web), screenshots looked at for UI.
3. Test data removed, backups exist for anything changed on the server.
4. Docs updated if behaviour, env vars, endpoints or ops changed (`README.md`, `docs/*`, `android/docs/API.md`).
5. Commit in Russian, only your paths; push to `master`.
