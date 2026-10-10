#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/dev-common.sh"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/dev-start.sh"

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

mkdir -p "$fixture/backend/src/main/resources/db/migration"
mkdir -p "$fixture/backend/target/classes/db/migration"

touch "$fixture/backend/src/main/resources/db/migration/V1__sports_reservation_schema.sql"
touch "$fixture/backend/target/classes/db/migration/V1__sports_reservation_schema.sql"
touch "$fixture/backend/target/classes/db/migration/V1__stale_schema_copy.sql"

clean_backend_migration_output "$fixture"

if [[ -e "$fixture/backend/target/classes/db/migration/V1__stale_schema_copy.sql" ]]; then
  echo "stale target migration was not removed" >&2
  exit 1
fi

if [[ ! -e "$fixture/backend/src/main/resources/db/migration/V1__sports_reservation_schema.sql" ]]; then
  echo "source migration was removed" >&2
  exit 1
fi

fake_bin="$fixture/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/nc" <<'EOS'
#!/usr/bin/env bash
exit 0
EOS
chmod +x "$fake_bin/nc"

cat > "$fake_bin/lsof" <<'EOS'
#!/usr/bin/env bash
if [[ "$*" == *"-tiTCP:5173"* ]]; then
  echo "4242"
  exit 0
fi

if [[ "$*" == *"-a -p 4242 -d cwd -Fn"* ]]; then
  echo "n/tmp/other-checkout/frontend"
  exit 0
fi

exit 1
EOS
chmod +x "$fake_bin/lsof"

cat > "$fake_bin/ps" <<'EOS'
#!/usr/bin/env bash
if [[ "$*" == *"-p 4242"* ]]; then
  echo "node /tmp/other-checkout/frontend/node_modules/.bin/vite --host 127.0.0.1"
  exit 0
fi

exit 1
EOS
chmod +x "$fake_bin/ps"

if PATH="$fake_bin:$PATH" assert_tcp_port_available "Frontend" 127.0.0.1 5173 "$fixture/project" 2> "$fixture/port-error.log"; then
  echo "occupied frontend port was not rejected" >&2
  exit 1
fi

if ! grep -q "/tmp/other-checkout/frontend" "$fixture/port-error.log"; then
  echo "occupied port error did not include listener working directory" >&2
  exit 1
fi

SERVER_PORT=18080
if [[ "$(backend_http_port)" != "18080" ]]; then
  echo "backend_http_port did not honor SERVER_PORT" >&2
  exit 1
fi
unset SERVER_PORT

if [[ "$(backend_http_port)" != "8080" ]]; then
  echo "backend_http_port did not fall back to 8080" >&2
  exit 1
fi

MYSQL_PORT=13306
if [[ "$(mysql_port)" != "13306" ]]; then
  echo "mysql_port did not honor MYSQL_PORT" >&2
  exit 1
fi
unset MYSQL_PORT

if [[ "$(mysql_port)" != "3306" ]]; then
  echo "mysql_port did not fall back to 3306" >&2
  exit 1
fi

REDIS_PORT=16379
if [[ "$(redis_port)" != "16379" ]]; then
  echo "redis_port did not honor REDIS_PORT" >&2
  exit 1
fi
unset REDIS_PORT

if [[ "$(redis_port)" != "6379" ]]; then
  echo "redis_port did not fall back to 6379" >&2
  exit 1
fi

if ! grep -q 'wait_for_port "MySQL" 127.0.0.1 "$(mysql_port)" 90' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not call mysql_port for MySQL readiness" >&2
  exit 1
fi

if ! grep -q 'wait_for_port "Redis" 127.0.0.1 "$(redis_port)" 60' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not call redis_port for Redis readiness" >&2
  exit 1
fi

if ! grep -q 'compose up -d mysql redis mailpit' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not start the Mailpit service" >&2
  exit 1
fi

if ! grep -q 'wait_for_port "Mailpit SMTP" 127.0.0.1 "${MAILPIT_SMTP_PORT:-1025}"' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not wait for the Mailpit SMTP port" >&2
  exit 1
fi

if ! grep -q 'assert_flyway_history_healthy' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not reject failed Flyway history before starting application processes" >&2
  exit 1
fi

if ! grep -q 'http://127.0.0.1:${MAILPIT_UI_PORT:-8025}' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not print the Mailpit UI URL" >&2
  exit 1
fi

if ! grep -Fq 'echo "Frontend:       http://127.0.0.1:5173"' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not identify the frontend page entry" >&2
  exit 1
fi

if ! grep -Fq 'echo "Backend API:    http://127.0.0.1:$BACKEND_HTTP_PORT/api/venues"' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not print an accessible backend API URL" >&2
  exit 1
fi

if ! grep -Fq 'echo "Backend health: http://127.0.0.1:$BACKEND_HTTP_PORT/actuator/health"' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start does not print the backend health check URL" >&2
  exit 1
fi

if grep -Fq 'echo "Backend:  http://127.0.0.1:$BACKEND_HTTP_PORT"' "$ROOT_DIR/scripts/dev-start.sh"; then
  echo "dev-start still prints the protected backend root as a page entry" >&2
  exit 1
fi

if ! grep -q 'compose stop mysql redis mailpit' "$ROOT_DIR/scripts/dev-stop.sh"; then
  echo "dev-stop does not stop the Mailpit service" >&2
  exit 1
fi

if ! grep -q 'http://127.0.0.1:${MAILPIT_UI_PORT:-8025}' "$ROOT_DIR/scripts/docker-start.sh"; then
  echo "docker-start does not print the Mailpit UI URL" >&2
  exit 1
fi

if ! grep -q 'venue-mailpit' "$ROOT_DIR/scripts/reset-state.sh"; then
  echo "reset-state fallback does not remove the Mailpit container" >&2
  exit 1
fi

for volume_name in deploy_mailpit-data venue-reservation-system_mailpit-data mailpit-data; do
  if ! grep -q "$volume_name" "$ROOT_DIR/scripts/reset-state.sh"; then
    echo "reset-state fallback does not remove known Mailpit volume $volume_name" >&2
    exit 1
  fi
done

echo "dev-start script tests passed"
