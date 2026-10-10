#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
load_root_env "$ROOT_DIR/.env.local"

DEV_START_SCRIPT="${ALIPAY_DEMO_DEV_START_SCRIPT:-$SCRIPT_DIR/dev-start.sh}"
DEV_STOP_SCRIPT="${ALIPAY_DEMO_DEV_STOP_SCRIPT:-$SCRIPT_DIR/dev-stop.sh}"
SMOKE_START_SCRIPT="${ALIPAY_DEMO_SMOKE_START_SCRIPT:-$SCRIPT_DIR/alipay-sandbox-smoke-start.sh}"
SMOKE_STOP_SCRIPT="${ALIPAY_DEMO_SMOKE_STOP_SCRIPT:-$SCRIPT_DIR/alipay-sandbox-smoke-stop.sh}"

for script in "$DEV_START_SCRIPT" "$DEV_STOP_SCRIPT" "$SMOKE_START_SCRIPT" "$SMOKE_STOP_SCRIPT"; do
  if [[ ! -x "$script" ]]; then
    echo "Required lifecycle script is missing or not executable: $script" >&2
    exit 1
  fi
done
if ! command -v nc >/dev/null 2>&1; then
  echo "nc is required to verify backend port release." >&2
  exit 1
fi

wait_for_backend_port_release() {
  local port="${ALIPAY_DEMO_BACKEND_PORT:-${SERVER_PORT:-8080}}"
  local wait_seconds="${ALIPAY_DEMO_PORT_WAIT_SECONDS:-30}"
  local attempt max_attempts

  if [[ ! "$port" =~ ^[0-9]+$ ]] || [[ ! "$wait_seconds" =~ ^[0-9]+$ ]]; then
    echo "Demo backend port and wait timeout must be positive integers." >&2
    return 1
  fi
  max_attempts=$((wait_seconds * 10))
  for ((attempt = 0; attempt < max_attempts; attempt++)); do
    if ! nc -z 127.0.0.1 "$port" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done

  echo "Backend port $port did not become available after backend-only stop." >&2
  return 1
}

cleanup_failed_start() {
  local status="$?"
  trap - ERR
  "$SMOKE_STOP_SCRIPT" --quiet >/dev/null 2>&1 || true
  "$DEV_STOP_SCRIPT" >/dev/null 2>&1 || true
  echo "Alipay sandbox demo startup failed; the partial local runtime was cleaned." >&2
  exit "$status"
}
trap cleanup_failed_start ERR

"$DEV_STOP_SCRIPT"
"$DEV_START_SCRIPT"
"$DEV_STOP_SCRIPT" --backend-only
wait_for_backend_port_release
"$SMOKE_START_SCRIPT"

trap - ERR
echo "Alipay sandbox demo environment started."
echo "Frontend: http://127.0.0.1:5173"
echo "Stop with: ./scripts/alipay-sandbox-demo-stop.sh"
