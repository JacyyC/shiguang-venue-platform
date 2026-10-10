#!/usr/bin/env bash

ALIPAY_SMOKE_CALLBACK_PATH="/api/payments/callback/alipay/sandbox"
ALIPAY_SMOKE_RETURN_URL="http://127.0.0.1:5173/me/payments/alipay/sandbox/return"
ALIPAY_SMOKE_GATEWAY_URL="https://openapi-sandbox.dl.alipaydev.com/gateway.do"

smoke_run_root() {
  echo "${ALIPAY_SMOKE_RUN_ROOT:-$ROOT_DIR/.run}"
}

smoke_active_file() {
  echo "$(smoke_run_root)/active-run"
}

smoke_require_command() {
  local command_name="$1"
  local install_hint="$2"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "$command_name is required but was not found." >&2
    echo "$install_hint" >&2
    return 1
  fi
}

smoke_extract_tunnel_origin() {
  local log_file="$1"
  local -a origins=()

  if [[ -f "$log_file" ]]; then
    while IFS= read -r origin; do
      origins+=("$origin")
    done < <(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$log_file" | sort -u || true)
  fi

  if (( ${#origins[@]} == 0 )); then
    return 1
  fi
  if (( ${#origins[@]} != 1 )); then
    return 2
  fi
  echo "${origins[0]}"
}

smoke_read_pid() {
  local pid_file="$1"
  local pid

  [[ -f "$pid_file" ]] || return 1
  pid="$(cat "$pid_file")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  echo "$pid"
}

smoke_process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

smoke_process_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

smoke_validate_run_dir() {
  local run_dir="$1"
  local run_root run_id marker
  run_root="$(smoke_run_root)"

  case "$run_dir" in
    "$run_root"/alipay-sandbox-smoke-*) ;;
    *)
      echo "Refusing smoke run outside $run_root." >&2
      return 1
      ;;
  esac
  if [[ ! -d "$run_dir" || -L "$run_dir" ]]; then
    echo "Smoke run directory is missing or unsafe." >&2
    return 1
  fi

  run_id="${run_dir##*/alipay-sandbox-smoke-}"
  marker="$run_dir/owner.marker"
  if [[ ! -f "$marker" ]] \
    || ! grep -Fxq "root=$ROOT_DIR" "$marker" \
    || ! grep -Fxq "run_id=$run_id" "$marker"; then
    echo "Smoke ownership marker does not match this worktree." >&2
    return 1
  fi
}

smoke_assert_process_owned() {
  local pid="$1"
  local command_token="$2"
  local run_dir="$3"
  local expected_cwd="${4:-$ROOT_DIR}"
  local command cwd

  kill -0 "$pid" 2>/dev/null || return 1
  command="$(smoke_process_command "$pid")"
  cwd="$(smoke_process_cwd "$pid")"

  if [[ "$command" != *"$command_token"* || "$cwd" != "$expected_cwd" ]]; then
    echo "Refusing to signal unowned process $pid." >&2
    return 1
  fi
  smoke_validate_run_dir "$run_dir"
}

smoke_stop_pid() {
  local pid_file="$1"
  local command_token="$2"
  local run_dir="$3"
  local expected_cwd="${4:-$ROOT_DIR}"
  local pid

  if [[ ! -f "$pid_file" ]]; then
    return 0
  fi
  if ! pid="$(smoke_read_pid "$pid_file")"; then
    echo "Smoke PID file is invalid: $pid_file" >&2
    return 1
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$pid_file"
    return 0
  fi

  if ! smoke_assert_process_owned "$pid" "$command_token" "$run_dir" "$expected_cwd"; then
    return 1
  fi
  kill "$pid"
  for _ in {1..30}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      return 0
    fi
    sleep 0.2
  done

  echo "Owned smoke process $pid did not stop." >&2
  return 1
}

smoke_stop_backend() {
  local run_dir="$1"
  local run_id="$2"
  local backend_pid listener_pid

  backend_pid="$(smoke_read_pid "$run_dir/backend.pid" 2>/dev/null || true)"
  listener_pid="$(smoke_read_pid "$run_dir/backend-listener.pid" 2>/dev/null || true)"

  if [[ -n "$listener_pid" && "$listener_pid" != "$backend_pid" ]]; then
    smoke_stop_pid \
      "$run_dir/backend-listener.pid" \
      "com.example.venue.VenueReservationApplication" \
      "$run_dir" \
      "$ROOT_DIR/backend"
  else
    rm -f "$run_dir/backend-listener.pid"
  fi

  smoke_stop_pid "$run_dir/backend.pid" "alipay.smoke.run-id=$run_id" "$run_dir"
}

smoke_wait_until_tunnel_unreachable() {
  local notify_file="$1"
  local notify_url origin
  [[ -f "$notify_file" ]] || return 0

  notify_url="$(cat "$notify_file")"
  origin="${notify_url%$ALIPAY_SMOKE_CALLBACK_PATH}"
  for _ in {1..10}; do
    if ! curl -fsS --max-time 2 "$origin" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done

  echo "Smoke tunnel still responds after cloudflared stopped." >&2
  return 1
}

smoke_stop_run() {
  local run_dir="$1"
  local quiet="${2:-false}"
  local run_id active_file stop_failed

  smoke_validate_run_dir "$run_dir"
  run_id="${run_dir##*/alipay-sandbox-smoke-}"
  active_file="$(smoke_active_file)"

  stop_failed="false"
  smoke_stop_pid "$run_dir/cloudflared.pid" "cloudflared tunnel" "$run_dir" || stop_failed="true"
  smoke_wait_until_tunnel_unreachable "$run_dir/notify-url" || stop_failed="true"
  smoke_stop_backend "$run_dir" "$run_id" || stop_failed="true"

  if [[ "$stop_failed" == "true" ]]; then
    echo "Alipay sandbox smoke runtime could not be cleaned completely." >&2
    return 1
  fi

  rm -rf "$run_dir"
  if [[ -f "$active_file" ]] && [[ "$(cat "$active_file")" == "$run_dir" ]]; then
    rm -f "$active_file"
  fi
  if [[ "$quiet" != "true" ]]; then
    echo "Alipay sandbox smoke runtime stopped."
  fi
}

smoke_stop_active() {
  local quiet="${1:-false}"
  local active_file run_dir
  active_file="$(smoke_active_file)"

  if [[ ! -f "$active_file" ]]; then
    if [[ "$quiet" != "true" ]]; then
      echo "Alipay sandbox smoke runtime is not active."
    fi
    return 0
  fi
  run_dir="$(cat "$active_file")"
  smoke_stop_run "$run_dir" "$quiet"
}
