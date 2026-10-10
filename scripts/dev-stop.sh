#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
RUN_DIR="$ROOT_DIR/.run"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dev-common.sh"
load_root_env "$ROOT_DIR/.env.local"

backend_only="false"
case "${1:-}" in
  "") ;;
  --backend-only) backend_only="true" ;;
  *)
    echo "Unknown option: $1" >&2
    echo "Usage: $0 [--backend-only]" >&2
    exit 2
    ;;
esac

if [[ "$backend_only" != "true" ]]; then
  "$SCRIPT_DIR/alipay-sandbox-smoke-stop.sh" --quiet || true
fi

stop_descendants() {
  local parent_pid="$1"

  if ! command -v pgrep >/dev/null 2>&1; then
    return
  fi

  local child_pid
  while read -r child_pid; do
    if [[ -n "$child_pid" ]]; then
      stop_descendants "$child_pid"
      if kill -0 "$child_pid" 2>/dev/null; then
        kill "$child_pid" 2>/dev/null || true
      fi
    fi
  done < <(pgrep -P "$parent_pid" 2>/dev/null || true)
}

stop_pid_file() {
  local pid_file="$1"
  local name="$2"

  if [[ ! -f "$pid_file" ]]; then
    echo "$name is not running: pid file not found."
    return
  fi

  local pid
  pid="$(cat "$pid_file")"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    stop_descendants "$pid"
    kill "$pid"
    echo "Stopped $name with pid $pid."
  else
    echo "$name process is not alive."
  fi
  rm -f "$pid_file"
}

stop_project_listener() {
  local port="$1"
  local name="$2"

  if ! command -v lsof >/dev/null 2>&1; then
    return
  fi

  local pid command
  while read -r pid; do
    [[ -z "$pid" ]] && continue
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [[ "$command" == *"$ROOT_DIR"* ]]; then
      stop_descendants "$pid"
      kill "$pid" 2>/dev/null || true
      echo "Stopped $name listener on port $port with pid $pid."
    fi
  done < <(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
}

if [[ "$backend_only" != "true" ]]; then
  stop_pid_file "$RUN_DIR/frontend-listener.pid" "frontend listener"
  stop_pid_file "$RUN_DIR/frontend.pid" "frontend"
fi
stop_pid_file "$RUN_DIR/backend-listener.pid" "backend listener"
stop_pid_file "$RUN_DIR/backend.pid" "backend"
stop_project_listener "$(backend_http_port)" "backend"

if [[ "$backend_only" != "true" ]]; then
  stop_project_listener 5173 "frontend"
  cd "$ROOT_DIR/deploy"
  compose stop mysql redis mailpit

  echo "Local development environment stopped."
else
  echo "Local backend stopped; frontend, MySQL, Redis and Mailpit remain running."
fi
