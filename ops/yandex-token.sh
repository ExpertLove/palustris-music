#!/usr/bin/env bash
# Токен Яндекс Музыки через OAuth Device Flow. Запуск: ./ops/yandex-token.sh
# Открой ссылку, введи код, дождись «OK». Токен — секрет: только в systemd на сервере.
set -euo pipefail

CLIENT_ID=23cabbbdc6cd418abb4b39c32c41195d
CLIENT_SECRET=53bc75238f0c4d08a118e51fe9203300
OAUTH=https://oauth.yandex.ru

if ! command -v python3 >/dev/null; then
  echo "нужен python3: им разбираем ответы OAuth" >&2
  exit 1
fi

json() { python3 -c 'import json,sys; v=json.load(sys.stdin); print(v.get(sys.argv[1],""))' "$1"; }

code=$(curl -sS -X POST -d "client_id=$CLIENT_ID" "$OAUTH/device/code")
device=$(printf '%s' "$code" | json device_code)
if [ -z "$device" ]; then
  echo "Яндекс не выдал код: $code" >&2
  exit 1
fi
user=$(printf '%s' "$code" | json user_code)
url=$(printf '%s' "$code" | json verification_url)
interval=$(printf '%s' "$code" | json interval)
interval=${interval:-5}

echo
echo "1. Открой: $url"
echo "2. Введи код: $user"
echo
echo "Жду подтверждения (до 5 минут)..."

while :; do
  sleep "$interval"
  resp=$(curl -sS -X POST "$OAUTH/token" \
    -d grant_type=device_code -d "code=$device" -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET")
  token=$(printf '%s' "$resp" | json access_token)
  if [ -n "$token" ]; then
    refresh=$(printf '%s' "$resp" | json refresh_token)
    expires=$(printf '%s' "$resp" | json expires_in)
    echo
    echo "OK. Токен (живёт ${expires:-?} с, обновляется refresh-токеном):"
    echo "$token"
    [ -n "$refresh" ] && { echo; echo "refresh_token: $refresh"; }
    echo
    echo "На сервере: /etc/systemd/system/music.service.d/yandex.conf, права 600:"
    echo "[Service]"
    echo "Environment=YANDEX_MUSIC_TOKEN=$token"
    echo "Затем: systemctl daemon-reload && systemctl restart music"
    exit 0
  fi
  err=$(printf '%s' "$resp" | json error)
  case "$err" in
    authorization_pending) ;;
    slow_down) interval=$((interval + 5)) ;;
    *) echo "Не вышло: $err" >&2; exit 1 ;;
  esac
done
