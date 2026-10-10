#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dev-common.sh"
load_root_env "$ROOT_DIR/.env.local"

cd "$ROOT_DIR/deploy"
compose up -d --build --wait --wait-timeout 180

echo "Docker environment started."
echo "Application: $(docker_application_url)"
echo "Backend:     http://localhost:$(docker_backend_port)"
echo "Mailpit:     http://127.0.0.1:${MAILPIT_UI_PORT:-8025}"
