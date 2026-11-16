#!/usr/bin/env bash
# Сборка и выкладка фронтенда. ./ops/deploy-web.sh next  — на проверочный /next/
#                               ./ops/deploy-web.sh prod  — в корень сайта
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../web"
TARGET=${1:-next}
K="-i $HOME/.ssh/palustris_admin -o IdentitiesOnly=yes -o BatchMode=yes"
H=root@music.palustris.ru
if [ "$TARGET" = prod ]; then BASE=/; DIR=/opt/music/web; else BASE=/next/; DIR=/opt/music/web-next; fi
rm -rf dist
VITE_BASE=$BASE npx vite build >/dev/null
tar czf - -C dist . | ssh $K $H "set -e; rm -rf $DIR.tmp; mkdir -p $DIR.tmp; tar xzf - -C $DIR.tmp; [ -d $DIR ] && cp -a $DIR /root/backups/\$(basename $DIR)-\$(date +%Y%m%d-%H%M%S) || true; rm -rf $DIR; mv $DIR.tmp $DIR; chown -R node:node $DIR"
echo "deployed to $TARGET ($DIR)"
