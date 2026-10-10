#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
load_root_env "$ROOT_DIR/.env.local"

cd "$ROOT_DIR/deploy"
compose down

echo "Docker environment stopped. MySQL data volume is preserved."
