#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dev-common.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dev-start.sh"
load_root_env "$ROOT_DIR/.env.local"

require_command() {
  local command_name="$1"
  local install_hint="$2"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "$command_name is required but was not found." >&2
    echo "$install_hint" >&2
    exit 1
  fi
}

ensure_docker_running() {
  if docker info >/dev/null 2>&1; then
    return
  fi

  echo "Docker daemon is not running or is not reachable." >&2
  echo "Start Docker Desktop, or run 'colima start' if you use Colima, then rerun this script." >&2
  exit 1
}

wait_for_container() {
  local service="$1"
  local attempts="${2:-60}"

  for ((i = 1; i <= attempts; i++)); do
    if [[ "$(compose ps -q "$service" | xargs docker inspect -f '{{.State.Running}}' 2>/dev/null || true)" == "true" ]]; then
      return
    fi
    sleep 1
  done

  echo "$service container did not start in time." >&2
  compose logs --tail=80 "$service" >&2 || true
  exit 1
}

wait_for_port() {
  local name="$1"
  local host="$2"
  local port="$3"
  local attempts="${4:-60}"

  for ((i = 1; i <= attempts; i++)); do
    if nc -z "$host" "$port" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done

  echo "$name did not become reachable at $host:$port in time." >&2
  exit 1
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local log_file="$3"
  local attempts="${4:-60}"

  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return
    fi

    if [[ -f "$log_file" ]] && grep -Eq "BUILD FAILURE|Application run failed|EADDRINUSE|error when starting dev server" "$log_file"; then
      echo "$name failed to start. Last log lines:" >&2
      tail -n 80 "$log_file" >&2
      exit 1
    fi

    if (( i % 30 == 0 )); then
      echo "Still waiting for $name at $url ($i/$attempts seconds)."
    fi

    sleep 1
  done

  echo "$name did not become reachable at $url in time. Last log lines:" >&2
  tail -n 80 "$log_file" >&2 || true
  exit 1
}

record_listening_pid() {
  local port="$1"
  local pid_file="$2"

  if ! command -v lsof >/dev/null 2>&1; then
    return
  fi

  local pid
  pid="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
  if [[ -n "$pid" ]]; then
    echo "$pid" > "$pid_file"
  fi
}

require_command docker "Install Docker Desktop, or install and start Colima."
require_command nc "Install netcat."
require_command curl "Install curl."
ensure_docker_running

cd "$ROOT_DIR/deploy"
compose up -d mysql redis mailpit
wait_for_container mysql
wait_for_container redis
wait_for_container mailpit
wait_for_port "MySQL" 127.0.0.1 "$(mysql_port)" 90
wait_for_port "Redis" 127.0.0.1 "$(redis_port)" 60
wait_for_port "Mailpit SMTP" 127.0.0.1 "${MAILPIT_SMTP_PORT:-1025}" 60
MYSQL_CONTAINER="$(compose ps -q mysql)"
if ! assert_flyway_history_healthy "$MYSQL_CONTAINER" "${MYSQL_DATABASE:-venue_reservation}"; then
  compose stop mysql redis mailpit >/dev/null 2>&1 || true
  exit 1
fi

BACKEND_HTTP_PORT="$(backend_http_port)"

assert_tcp_port_available "Backend" 127.0.0.1 "$BACKEND_HTTP_PORT" "$ROOT_DIR"
assert_tcp_port_available "Frontend" 127.0.0.1 5173 "$ROOT_DIR"

cd "$ROOT_DIR/backend"
if [[ -f "./env.sh" ]]; then
  # shellcheck disable=SC1091
  source ./env.sh
fi
clean_backend_migration_output "$ROOT_DIR"
rm -f "$RUN_DIR/backend.log" "$RUN_DIR/frontend.log"
# 本地后端默认 dev profile，放行开发 JWT 密钥（闭合 AUD-P0-01 后收紧校验所需）；
# 用户在 .env.local 显式设置 SPRING_PROFILES_ACTIVE（如 prod）时保留其选择。
if [[ -z "${SPRING_PROFILES_ACTIVE:-}" ]]; then
  export SPRING_PROFILES_ACTIVE=dev
fi
echo "Starting backend. First launch may download Maven dependencies and take several minutes."
nohup mvn spring-boot:run > "$RUN_DIR/backend.log" 2>&1 &
echo "$!" > "$RUN_DIR/backend.pid"

cd "$ROOT_DIR/frontend"
echo "Starting frontend."
nohup npm run dev -- --host 127.0.0.1 > "$RUN_DIR/frontend.log" 2>&1 &
echo "$!" > "$RUN_DIR/frontend.pid"

wait_for_url "Backend" "http://127.0.0.1:$BACKEND_HTTP_PORT/api/venues" "$RUN_DIR/backend.log" "${BACKEND_WAIT_SECONDS:-600}"
record_listening_pid "$BACKEND_HTTP_PORT" "$RUN_DIR/backend-listener.pid"
wait_for_url "Frontend" "http://127.0.0.1:5173/" "$RUN_DIR/frontend.log" "${FRONTEND_WAIT_SECONDS:-120}"
record_listening_pid 5173 "$RUN_DIR/frontend-listener.pid"

echo "Local development environment started."
echo "Frontend:       http://127.0.0.1:5173"
echo "Backend API:    http://127.0.0.1:$BACKEND_HTTP_PORT/api/venues"
echo "Backend health: http://127.0.0.1:$BACKEND_HTTP_PORT/actuator/health"
echo "Mailpit:        http://127.0.0.1:${MAILPIT_UI_PORT:-8025}"
echo "Logs:           .run/backend.log, .run/frontend.log"
