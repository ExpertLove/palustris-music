#!/bin/sh
# Ежедневное обновление yt-dlp и плагина PO-токенов. Откат — если новая версия не может достать трек.
set -u
V=/opt/yt-dlp
before=$($V/bin/yt-dlp --version 2>/dev/null)
$V/bin/pip install -q -U "yt-dlp[default]" bgutil-ytdlp-pot-provider >/dev/null 2>&1
after=$($V/bin/yt-dlp --version 2>/dev/null)
if [ "$before" != "$after" ]; then
  if $V/bin/yt-dlp -q --no-warnings -s --extractor-args youtube:player_client=visionos -f "bestaudio[ext=m4a]/bestaudio" "https://music.youtube.com/watch?v=dQw4w9WgXcQ" >/dev/null 2>&1; then
    echo "$(date -Is) yt-dlp $before -> $after ok"
  else
    $V/bin/pip install -q "yt-dlp[default]==$before" >/dev/null 2>&1
    echo "$(date -Is) yt-dlp $after broken, rolled back to $before"
  fi
fi
