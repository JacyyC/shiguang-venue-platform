#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/dev-common.sh"

if [[ "$(SERVER_PORT=18080 backend_http_port)" != "18080" ]]; then
  echo "backend_http_port did not honor SERVER_PORT" >&2
  exit 1
fi

if [[ "$(backend_http_port)" != "8080" ]]; then
  echo "backend_http_port did not fall back to 8080" >&2
  exit 1
fi

if ! grep -q 'load_root_env "$ROOT_DIR/.env.local"' "$ROOT_DIR/scripts/dev-stop.sh"; then
  echo "dev-stop.sh does not load .env.local before resolving ports" >&2
  exit 1
fi

if ! grep -q 'alipay-sandbox-smoke-stop.sh" --quiet' "$ROOT_DIR/scripts/dev-stop.sh"; then
  echo "dev-stop.sh does not stop the P1 smoke runtime idempotently" >&2
  exit 1
fi

if ! grep -q -- '--backend-only' "$ROOT_DIR/scripts/dev-stop.sh"; then
  echo "dev-stop.sh does not support the sandbox backend-only switch" >&2
  exit 1
fi

if ! grep -q 'backend_only="true"' "$ROOT_DIR/scripts/dev-stop.sh"; then
  echo "dev-stop.sh does not parse backend-only mode" >&2
  exit 1
fi

if ! grep -q 'if \[\[ "$backend_only" != "true" \]\]; then' "$ROOT_DIR/scripts/dev-stop.sh"; then
  echo "dev-stop.sh does not guard frontend and container shutdown" >&2
  exit 1
fi

echo "dev-stop script tests passed"
