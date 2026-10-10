#!/usr/bin/env bash

clean_backend_migration_output() {
  local root_dir="$1"
  local migration_output="$root_dir/backend/target/classes/db/migration"

  if [[ -d "$migration_output" ]]; then
    rm -rf "$migration_output"
  fi
}

listener_cwd() {
  local pid="$1"

  if ! command -v lsof >/dev/null 2>&1; then
    return
  fi

  lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

assert_tcp_port_available() {
  local name="$1"
  local host="$2"
  local port="$3"
  local root_dir="$4"

  if ! nc -z "$host" "$port" >/dev/null 2>&1; then
    return
  fi

  echo "$name cannot start because $host:$port is already in use." >&2

  if command -v lsof >/dev/null 2>&1; then
    local pid command cwd
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
      cwd="$(listener_cwd "$pid" || true)"

      echo "Listening pid: $pid" >&2
      if [[ -n "$cwd" ]]; then
        echo "Working dir:   $cwd" >&2
      fi
      if [[ -n "$command" ]]; then
        echo "Command:       $command" >&2
      fi

      if [[ -n "$cwd" && "$cwd" != "$root_dir"* ]]; then
        echo "This listener is outside the current project root: $root_dir" >&2
      fi
    done < <(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  fi

  echo "Stop the process that owns this port, then rerun scripts/dev-start.sh from the intended checkout/worktree." >&2
  return 1
}
