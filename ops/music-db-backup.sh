#!/bin/sh
# Ежедневный бэкап базы «Музыки», храним 14 последних.
set -eu
D=/root/backups/music-db
mkdir -p "$D"
sqlite3 /var/lib/music/music.db ".backup '$D/music-$(date +%Y%m%d-%H%M).db'"
gzip -f "$D"/music-*.db
ls -1t "$D"/music-*.db.gz | tail -n +15 | xargs -r rm -f
