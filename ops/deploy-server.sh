#!/usr/bin/env bash
# Выкладка бэкенда: исходники на сервер, сборка там же, перезапуск сервиса.
set -euo pipefail
cd "$(dirname "$0")/../server"
K="-i $HOME/.ssh/palustris_admin -o IdentitiesOnly=yes -o BatchMode=yes"
H=root@music.palustris.ru
cargo check --quiet
tar czf - src Cargo.toml Cargo.lock | ssh $K $H 'set -e
cd /opt/music
tar czf /root/backups/music-src-$(date +%Y%m%d-%H%M%S).tgz src Cargo.toml Cargo.lock
tar xzf - -C /opt/music
CARGO_BUILD_JOBS=1 cargo build --release 2>&1 | tail -1
cp bin/music /root/backups/music-bin-$(date +%Y%m%d-%H%M%S)
install -m755 target/release/music bin/music
systemctl restart music
sleep 2
systemctl is-active music'
