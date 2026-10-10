#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/compose.sh"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/dev-common.sh"

(
  unset APP_NOTICE_SMTP_FROM_NAME
  load_root_env "$ROOT_DIR/.env.example"
  if [[ "${APP_NOTICE_SMTP_FROM_NAME:-}" != "Venue Reservation Sandbox" ]]; then
    echo ".env.example must load shell-safe values without changing spaces" >&2
    exit 1
  fi
)

compose_config="$(cd "$ROOT_DIR/deploy" && compose config)"

grep -q 'mailpit:' <<< "$compose_config"
grep -q 'axllent/mailpit:v1.30.4' <<< "$compose_config"
if grep -q 'container_name:' <<< "$compose_config"; then
  echo 'Fixed container names prevent isolated Compose projects' >&2
  exit 1
fi
grep -q 'mailpit-data:' <<< "$compose_config"
grep -q 'APP_NOTICE_SMTP_HOST: mailpit' <<< "$compose_config"
grep -q 'MP_DATABASE: /data/mailpit.db' <<< "$compose_config"
grep -q 'source: mailpit-data' <<< "$compose_config"
grep -q 'target: /data' <<< "$compose_config"
grep -q 'target: 1025' <<< "$compose_config"
grep -q 'published: "1025"' <<< "$compose_config"
grep -q 'target: 8025' <<< "$compose_config"
grep -q 'published: "8025"' <<< "$compose_config"
if [[ "$(grep -c 'host_ip: 127.0.0.1' <<< "$compose_config")" -lt 2 ]]; then
  echo "Mailpit SMTP and UI ports must bind only to loopback" >&2
  exit 1
fi
grep -Fq '127.0.0.1:${MAILPIT_SMTP_PORT:-1025}:1025' "$ROOT_DIR/deploy/docker-compose.yml"
grep -Fq '127.0.0.1:${MAILPIT_UI_PORT:-8025}:8025' "$ROOT_DIR/deploy/docker-compose.yml"
if grep -Eqi 'relay|forward|webhook' "$ROOT_DIR/deploy/docker-compose.yml"; then
  echo "Mailpit relay/forward/webhook configuration is forbidden" >&2
  exit 1
fi

for env_name in \
  APP_PAYMENT_DEFAULT_PROVIDER_KEY \
  APP_COMMUNITY_RETENTION_ENABLED \
  APP_COMMUNITY_RETENTION_INITIAL_DELAY_MS \
  APP_COMMUNITY_RETENTION_FIXED_DELAY_MS \
  APP_COMMUNITY_RETENTION_BATCH_SIZE \
  APP_COMMUNITY_RETENTION_MAX_BATCHES_PER_RUN \
  APP_NOTICE_OUTBOX_POLL_DELAY_MS \
  APP_NOTICE_OUTBOX_BATCH_SIZE \
  APP_NOTICE_OUTBOX_LEASE_SECONDS \
  APP_NOTICE_OUTBOX_MAX_ATTEMPTS \
  APP_NOTICE_OUTBOX_RETRY_BASE_SECONDS; do
  if ! grep -q "${env_name}:" <<< "$compose_config"; then
    echo "Docker backend environment is missing $env_name" >&2
    exit 1
  fi
done

for env_name in \
  APP_COMMUNITY_RETENTION_ENABLED \
  APP_COMMUNITY_RETENTION_INITIAL_DELAY_MS \
  APP_COMMUNITY_RETENTION_FIXED_DELAY_MS \
  APP_COMMUNITY_RETENTION_BATCH_SIZE \
  APP_COMMUNITY_RETENTION_MAX_BATCHES_PER_RUN \
  APP_NOTICE_SMTP_ENABLED \
  APP_NOTICE_SMTP_SANDBOX_ONLY \
  APP_NOTICE_SMTP_HOST \
  APP_NOTICE_SMTP_PORT \
  APP_NOTICE_SMTP_AUTH \
  APP_NOTICE_SMTP_STARTTLS \
  APP_NOTICE_SMTP_USERNAME \
  APP_NOTICE_SMTP_PASSWORD \
  APP_NOTICE_SMTP_FROM_ADDRESS \
  APP_NOTICE_SMTP_FROM_NAME \
  APP_NOTICE_SMTP_CONNECT_TIMEOUT_MS \
  APP_NOTICE_SMTP_READ_TIMEOUT_MS \
  APP_NOTICE_SMTP_WRITE_TIMEOUT_MS \
  MAILPIT_SMTP_PORT \
  MAILPIT_UI_PORT \
  MAILPIT_IMAGE; do
  if ! grep -q "^${env_name}=" "$ROOT_DIR/.env.example"; then
    echo ".env.example is missing $env_name" >&2
    exit 1
  fi
done

if [[ "$(BACKEND_PORT=18081 docker_backend_port)" != "18081" ]]; then
  echo "docker_backend_port did not honor BACKEND_PORT" >&2
  exit 1
fi

if [[ "$(NGINX_PORT=8088 docker_application_url)" != "http://localhost:8088" ]]; then
  echo "docker_application_url did not honor NGINX_PORT" >&2
  exit 1
fi

if [[ "$(docker_application_url)" != "http://localhost" ]]; then
  echo "docker_application_url did not fall back to http://localhost" >&2
  exit 1
fi

echo "docker compose script tests passed"
