#!/usr/bin/env bash

load_root_env() {
  local env_file="${1:-}"
  if [[ -z "$env_file" ]]; then
    echo "Environment file path is required." >&2
    return 1
  fi

  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
}

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi

  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi

  echo "Docker Compose is required but was not found." >&2
  echo "Install Docker Desktop with Compose, or run: brew install docker-compose" >&2
  return 127
}

assert_flyway_history_healthy() {
  local mysql_container="$1"
  local database="${2:-${MYSQL_DATABASE:-venue_reservation}}"
  local root_password="${3:-${MYSQL_ROOT_PASSWORD:-root_password}}"
  local history_table_exists
  local history_ready="false"

  if [[ -z "$mysql_container" ]]; then
    echo "MySQL container was not found while checking Flyway history." >&2
    return 1
  fi

  for ((attempt = 1; attempt <= 90; attempt++)); do
    if history_table_exists="$(docker exec -e MYSQL_PWD="$root_password" "$mysql_container" \
      mysql -uroot --batch --skip-column-names 2>/dev/null \
      -e "select count(*) from information_schema.tables where table_schema = '${database}' and table_name = 'flyway_schema_history';")"; then
      history_ready="true"
      break
    fi
    sleep 1
  done

  if [[ "$history_ready" != "true" ]]; then
    echo "Could not inspect Flyway history in database '$database'." >&2
    return 1
  fi

  if [[ "$history_table_exists" != "1" ]]; then
    return 0
  fi

  local failed_version
  if ! failed_version="$(docker exec -e MYSQL_PWD="$root_password" "$mysql_container" \
    mysql -uroot --batch --skip-column-names "$database" \
    -e "select coalesce(cast(version as char), '<baseline>') from flyway_schema_history where success = 0 order by installed_rank limit 1;")"; then
    echo "Could not read failed Flyway migrations from database '$database'." >&2
    return 1
  fi

  if [[ -z "$failed_version" ]]; then
    return 0
  fi

  echo "Flyway migration version $failed_version is recorded as failed; the backend cannot start safely." >&2
  echo "For an empty local development database, run ./scripts/reset-state.sh and rerun the start command." >&2
  echo "If this database contains data, do not delete its volume; perform a reviewed migration recovery instead." >&2
  return 1
}
