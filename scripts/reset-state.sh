#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
load_root_env "$ROOT_DIR/.env.local"

bash "$SCRIPT_DIR/dev-stop.sh" || true
bash "$SCRIPT_DIR/docker-stop.sh" || true

if docker info >/dev/null 2>&1; then
  cd "$ROOT_DIR/deploy"
  if ! compose down -v; then
    echo "Compose cleanup failed. Falling back to direct Docker cleanup for known project resources." >&2
    docker rm -f venue-nginx venue-backend venue-mysql venue-redis venue-mailpit 2>/dev/null || true
    docker volume rm deploy_mysql-data venue-reservation-system_mysql-data mysql-data \
      deploy_mailpit-data venue-reservation-system_mailpit-data mailpit-data 2>/dev/null || true
    docker network rm deploy_default venue-reservation-system_default 2>/dev/null || true
  fi
else
  echo "Docker daemon is not reachable. Skipped Docker volume cleanup." >&2
fi

cd "$ROOT_DIR"
rm -rf .run backend/target frontend/dist

echo "Runtime logs, build outputs, and Docker data volume cleanup completed."
