#!/usr/bin/env bash
# Report Hub 生产部署：必须走 git pull，禁止 scp/rsync 覆盖代码
# 用法（线上 /opt/report_hub）：./scripts/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."
git config --global --add safe.directory "$(pwd)" >/dev/null 2>&1 || true

echo "==> git status"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "工作区有未提交改动，生产部署只接受 git pull。请先还原或提交：" >&2
  git status --porcelain >&2
  exit 1
fi

echo "==> git pull --ff-only"
git pull --ff-only origin main

echo "==> docker compose up -d --build（不使用 -v，保留 pgdata）"
docker compose up -d --build

echo "==> health"
sleep 3
curl -fsS http://127.0.0.1:${WEB_PORT:-15173}/api/health
echo
echo "==> deployed $(git log -1 --oneline)"
