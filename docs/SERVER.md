# Сервер

| | |
|---|---|
| Адрес | `music.palustris.ru`, Ubuntu 26.04 LTS, 1 ядро, диск 50 ГБ |
| Доступ | `ssh -i ~/.ssh/palustris_admin root@music.palustris.ru` — только по ключу (пароли отключены); ключ у владельца, в git его нет |
| Соседи | на том же хосте работают другие проекты владельца — **трогать только то, что относится к «Музыке»** |
| Сеть | наружу открыты только SSH, HTTP(S) и почта; бэкенд слушает `127.0.0.1:8030` |

> Правило: **перед любым изменением на сервере — копия в `/root/backups/`.** Скрипты выкладки делают это сами.

## Что где лежит

| Путь | Что |
|---|---|
| `/opt/music/src`, `Cargo.toml`, `Cargo.lock` | исходники бэкенда (заливает `ops/deploy-server.sh`, собирается на месте) |
| `/opt/music/bin/music` | рабочий бинарник |
| `/opt/music/web` | статика сайта (владелец `node:node`) |
| `/opt/music/web-next` | проверочная копия сайта → https://music.palustris.ru/next/ |
| `/var/lib/music/music.db` | база SQLite (WAL) |
| `/var/lib/music/cache/` | кэш аудио `{id}.m4a` и Opus-копии `{id}.o64.ogg` |
| `/var/lib/music/ytaccounts/{id}.txt` | cookies аккаунтов YouTube (каталог 700, файлы 600) |
| `/opt/yt-dlp/` | venv: `yt-dlp[default]`, `bgutil-ytdlp-pot-provider`, `yt-dlp-ejs` |
| `/etc/systemd/system/music.service` (+ `.d/*.conf`) | служба и переменные — копии в `ops/systemd/` (секреты и почты админов — заглушками в `*.example`) |
| `/etc/caddy/sites-enabled/music.caddy` | сайт в Caddy — копия в `ops/music.caddy` |
| `/usr/local/bin/music-ytdlp-update`, `music-db-backup` | ночные задачи — копии в `ops/` |
| `/root/backups/` | все бэкапы (см. ниже) |
| `/var/log/music-ytdlp.log`, `/var/log/music-backup.log` | логи ночных задач |

## Службы

| Что | Как устроено | Управление |
|---|---|---|
| `music.service` | systemd, `Restart=always`, `MemoryMax=600M`, работает от root | `systemctl status|restart music`, `journalctl -u music -f` |
| Caddy | systemd, HTTPS-сертификаты сам | `systemctl reload caddy` после правки конфига |
| `bgutil-provider` | Docker, `--restart unless-stopped`, `127.0.0.1:4416` | `docker logs bgutil-provider`, запуск — `ops/bgutil-provider.sh` |
| `pmail` | Docker, почтовый сервер всего домена; учётка `noreply` для сайта | веб-панель pmail |
| cron | 04:15 обновление yt-dlp (с откатом), 04:25 бэкап базы | `crontab -l`, `ops/crontab` |

Всё это видно и в админке сайта: `/admin` → «Логи».

## Переменные окружения

Задаются в `/etc/systemd/system/music.service` и `music.service.d/*.conf`. После правки:
`systemctl daemon-reload && systemctl restart music`.

| Переменная | По умолчанию | Значение на проде | Зачем |
|---|---|---|---|
| `MUSIC_DATA` | `data` | `/var/lib/music` | база и `ytaccounts/` |
| `MUSIC_CACHE` | `$MUSIC_DATA/cache` | `/var/lib/music/cache` | кэш аудио |
| `MUSIC_CACHE_MAX_GB` | `15` | `15` | лимит кэша, дальше LRU-очистка |
| `MUSIC_WEB` | `web/dist` | `/opt/music/web` | статика (на проде её отдаёт Caddy, это запасной путь) |
| `MUSIC_ADDR` | `127.0.0.1:8030` | `127.0.0.1:8030` | адрес API |
| `MUSIC_YTDLP` | `yt-dlp` | `/opt/yt-dlp/bin/yt-dlp` | бинарник yt-dlp |
| `PATH` | — | `/opt/yt-dlp/bin:…` | чтобы yt-dlp видел свои зависимости |
| `MUSIC_ADMINS` | пусто | задано на сервере (`admins.conf`) | почты админов через запятую. **Только зарегистрированные!** |
| `MUSIC_ACCOUNT_CLIENTS` | пусто (выбор yt-dlp) | не задана | `player_client` для попыток с аккаунтом, напр. `tv_downgraded,web_safari` |
| `MUSIC_TEST_VIDEO` | `dQw4w9WgXcQ` | не задана | трек для кнопки «Проверить» аккаунт |
| `MUSIC_SMTP_HOST` | пусто = писем нет | `pmail.palustris.ru` | почта |
| `MUSIC_SMTP_PORT` | `25` | `25` | STARTTLS |
| `MUSIC_SMTP_USER` / `MUSIC_SMTP_PASS` | пусто | `noreply` / **секрет** | авторизация в pmail (без неё — `relay denied`) |
| `MUSIC_SMTP_FROM` | `noreply@palustris.ru` | то же | отправитель |
| `MUSIC_BASE_URL` | `https://music.palustris.ru` | то же | ссылки в письмах |
| `MUSIC_COOKIES` | — | осталась в `smtp.conf` | **устарела, не читается**; аккаунты YouTube теперь в админке |

## Секреты

В репозитории их нет и быть не должно.

| Секрет | Где | Если потерян |
|---|---|---|
| SSH-ключ `palustris_admin` | у владельца; у разработчика `~/.ssh/palustris_admin` | выпустить новый, добавить в `/root/.ssh/authorized_keys` через панель хостинга |
| Пароль SMTP `noreply` | `/etc/systemd/system/music.service.d/smtp-auth.conf` (600) | сменить пароль учётки в pmail, обновить файл, перезапустить `music` |
| Cookies аккаунтов YouTube | `/var/lib/music/ytaccounts/*.txt` | выгрузить заново, загрузить в админке |
| Ключ подписи Android | `~/.android-keys/palustris-music-release.jks` + `android/keystore.properties` (git-ignored) | **не восстановить**: обновления поверх установленных APK станут невозможны. Храните копию в надёжном месте |
| Почты админов | `/etc/systemd/system/music.service.d/admins.conf` | взять у владельца |
| Пароли пользователей | argon2-хеши в `users.pass` | — |

## Выкладка

С машины разработчика (нужен SSH-ключ):

```bash
./ops/deploy-web.sh next     # сборка с VITE_BASE=/next/ → /opt/music/web-next
./ops/deploy-web.sh prod     # → /opt/music/web; старая копия → /root/backups/web-<дата>
./ops/deploy-server.sh       # cargo check локально → tar исходников → сборка на сервере (~1 мин, CARGO_BUILD_JOBS=1)
                             # → старый бинарник в /root/backups/music-bin-<дата> → restart → is-active
```

Изменения в Caddy: правим `ops/music.caddy`, копируем в `/etc/caddy/sites-enabled/music.caddy`
(сначала бэкап), `caddy validate --config /etc/caddy/Caddyfile`, `systemctl reload caddy`.

## Бэкапы

| Что | Где | Когда |
|---|---|---|
| База | `/root/backups/music-db/music-<дата>.db.gz`, 14 последних | каждую ночь 04:25; вручную — `music-db-backup` |
| Исходники бэкенда | `/root/backups/music-src-<дата>.tgz` | при каждом `deploy-server.sh` |
| Бинарник | `/root/backups/music-bin-<дата>` | при каждом `deploy-server.sh` |
| Статика сайта | `/root/backups/web-<дата>`, `web-next-<дата>` | при каждом `deploy-web.sh` |
| Прочее | `admins.conf-<дата>`, исходный старый фронтенд и т. п. | при ручных правках |

Бэкапы лежат **на том же сервере**. Внешней копии нет — см. планы в `docs/HISTORY.md`.

## Откат

```bash
# бэкенд: предыдущий бинарник
ls -t /root/backups/music-bin-* | head -3
install -m755 /root/backups/music-bin-<дата> /opt/music/bin/music && systemctl restart music

# сайт
ls -dt /root/backups/web-2* | head -3
rm -rf /opt/music/web && cp -a /root/backups/web-<дата> /opt/music/web && chown -R node:node /opt/music/web

# база (сначала остановить службу и сохранить текущую!)
systemctl stop music
cp /var/lib/music/music.db /root/backups/music.db-before-restore-$(date +%Y%m%d-%H%M%S)
gunzip -c /root/backups/music-db/music-<дата>.db.gz > /var/lib/music/music.db && rm -f /var/lib/music/music.db-wal /var/lib/music/music.db-shm
systemctl start music
```

Миграции схемы только добавляющие, поэтому старый бинарник работает с новой базой.

## Диагностика

| Симптом | Где смотреть |
|---|---|
| Треки не играют | `/admin` → «Обзор» (помечен ли IP) и «Загрузки» (тип ошибки); подробно — `docs/YOUTUBE.md` |
| 502 на всём сайте | `systemctl status music`, `journalctl -u music -n 100` |
| Письма не приходят | `journalctl -u music | grep -i mail`, логи pmail; `relay denied` = нет `smtp-auth.conf` |
| Диск | `/admin` → «Обзор»; `du -sh /var/lib/music/cache`; лимит `MUSIC_CACHE_MAX_GB` |
| yt-dlp сломался после обновления | `/var/log/music-ytdlp.log` (откат автоматический); вручную `/opt/yt-dlp/bin/pip install "yt-dlp[default]==<версия>"` |

## Поднять с нуля

На чистой Ubuntu (порядок важен):

```bash
apt install -y caddy sqlite3 ffmpeg python3-venv docker.io build-essential pkg-config
curl https://sh.rustup.rs -sSf | sh -s -- -y        # или rustc/cargo из apt (на проде 1.93)

# yt-dlp + плагин PO-токенов
python3 -m venv /opt/yt-dlp
/opt/yt-dlp/bin/pip install -U "yt-dlp[default]" bgutil-ytdlp-pot-provider
sh ops/bgutil-provider.sh

# код
mkdir -p /opt/music/bin /var/lib/music /root/backups
#   затем с машины разработчика: ./ops/deploy-server.sh (упадёт на install/restart при первом запуске — это нормально)
#   и ./ops/deploy-web.sh prod
cp ops/systemd/music.service /etc/systemd/system/
mkdir -p /etc/systemd/system/music.service.d && cp ops/systemd/music.service.d/*.conf /etc/systemd/system/music.service.d/
cp ops/systemd/music.service.d/admins.conf.example /etc/systemd/system/music.service.d/admins.conf      # вписать почты админов
cp ops/systemd/music.service.d/smtp-auth.conf.example /etc/systemd/system/music.service.d/smtp-auth.conf  # вписать пароль, chmod 600
systemctl daemon-reload && systemctl enable --now music

# Caddy
cp ops/music.caddy /etc/caddy/sites-enabled/   # Caddyfile должен делать `import sites-enabled/*`
systemctl reload caddy

# ночные задачи
install -m755 ops/yt-dlp-update.sh /usr/local/bin/music-ytdlp-update
install -m755 ops/music-db-backup.sh /usr/local/bin/music-db-backup
crontab -l | cat - ops/crontab | crontab -

# данные: перенести music.db из бэкапа; аккаунты YouTube добавить в /admin заново
```
