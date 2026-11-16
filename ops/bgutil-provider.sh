#!/bin/sh
# Генератор PO-токенов YouTube для yt-dlp (плагин bgutil-ytdlp-pot-provider ходит к нему на 127.0.0.1:4416).
# Запускается один раз, дальше Docker сам поднимает контейнер после перезагрузки.
set -eu
docker pull brainicism/bgutil-ytdlp-pot-provider:latest
docker rm -f bgutil-provider 2>/dev/null || true
docker run -d --name bgutil-provider --restart unless-stopped -p 127.0.0.1:4416:4416 brainicism/bgutil-ytdlp-pot-provider:latest
