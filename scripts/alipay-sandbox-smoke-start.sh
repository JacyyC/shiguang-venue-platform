#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dev-common.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/alipay-sandbox-smoke.sh"

ENV_FILE="${ALIPAY_SMOKE_ENV_FILE:-$ROOT_DIR/.env.local}"
load_root_env "$ENV_FILE"

smoke_require_command cloudflared "Install it with: brew install cloudflared"
smoke_require_command curl "Install curl."
smoke_require_command nc "Install netcat."
smoke_require_command lsof "Install lsof."
smoke_require_command mvn "Install Maven."

for key_name in \
  APP_PAYMENT_ALIPAY_SANDBOX_APP_ID \
  APP_PAYMENT_ALIPAY_SANDBOX_APP_PRIVATE_KEY \
  APP_PAYMENT_ALIPAY_SANDBOX_ALIPAY_PUBLIC_KEY; do
  if [[ -z "${!key_name:-}" ]]; then
    echo "$key_name must be set in the local process environment." >&2
    exit 1
  fi
done

if [[ "${APP_PAYMENT_ALIPAY_SANDBOX_GATEWAY_URL:-$ALIPAY_SMOKE_GATEWAY_URL}" != "$ALIPAY_SMOKE_GATEWAY_URL" ]]; then
  echo "The Alipay sandbox gateway URL is invalid." >&2
  exit 1
fi

server_port="$(backend_http_port)"
if nc -z 127.0.0.1 "$server_port" >/dev/null 2>&1; then
  echo "Backend port $server_port is already in use; stop the current backend before starting smoke UAT." >&2
  exit 1
fi

run_root="$(smoke_run_root)"
active_file="$(smoke_active_file)"
if [[ -e "$active_file" ]]; then
  echo "An Alipay sandbox smoke runtime is already recorded." >&2
  exit 1
fi

umask 077
mkdir -p "$run_root"
run_id="$(date '+%Y%m%d%H%M%S')-$$-$RANDOM"
run_dir="$run_root/alipay-sandbox-smoke-$run_id"
mkdir -m 700 "$run_dir"
cat > "$run_dir/owner.marker" <<EOF
root=$ROOT_DIR
run_id=$run_id
EOF

cleanup_failed_start() {
  local status="$?"
  trap - EXIT
  smoke_stop_run "$run_dir" true >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup_failed_start EXIT

(
  cd "$ROOT_DIR"
  exec nohup cloudflared tunnel --url "http://127.0.0.1:${server_port}" --no-autoupdate
) > "$run_dir/tunnel.log" 2>&1 &
echo "$!" > "$run_dir/cloudflared.pid"

tunnel_origin=""
for ((attempt = 1; attempt <= ${ALIPAY_SMOKE_TUNNEL_WAIT_SECONDS:-60}; attempt++)); do
  if ! kill -0 "$(cat "$run_dir/cloudflared.pid")" 2>/dev/null; then
    echo "cloudflared stopped before publishing a Quick Tunnel URL." >&2
    exit 1
  fi
  if tunnel_origin="$(smoke_extract_tunnel_origin "$run_dir/tunnel.log")"; then
    break
  else
    status="$?"
    if [[ "$status" == "2" ]]; then
      echo "cloudflared published more than one Quick Tunnel URL." >&2
      exit 1
    fi
  fi
  sleep 1
done
if [[ -z "$tunnel_origin" ]]; then
  echo "cloudflared did not publish a Quick Tunnel URL in time." >&2
  exit 1
fi

notify_url="$tunnel_origin$ALIPAY_SMOKE_CALLBACK_PATH"
echo "$notify_url" > "$run_dir/notify-url"

(
  cd "$ROOT_DIR/backend"
  if [[ -f ./env.sh ]]; then
    # shellcheck disable=SC1091
    source ./env.sh
  fi
  export APP_PAYMENT_DEFAULT_PROVIDER_KEY="payment.alipay.sandbox"
  export APP_PAYMENT_ALIPAY_SANDBOX_ENABLED="true"
  export APP_PAYMENT_ALIPAY_SANDBOX_NOTIFY_URL="$notify_url"
  export APP_PAYMENT_ALIPAY_SANDBOX_RETURN_URL="$ALIPAY_SMOKE_RETURN_URL"
  cd "$ROOT_DIR"
  trap '' HUP
  # 沙箱冒烟默认 dev profile，放行开发 JWT 密钥（闭合 AUD-P0-01 后收紧校验所需）；
  # 用户显式设置 SPRING_PROFILES_ACTIVE 时保留其选择。
  if [[ -z "${SPRING_PROFILES_ACTIVE:-}" ]]; then
    export SPRING_PROFILES_ACTIVE=dev
  fi
  exec mvn "-Dalipay.smoke.run-id=$run_id" -f backend/pom.xml spring-boot:run
) > "$run_dir/backend.log" 2>&1 &
echo "$!" > "$run_dir/backend.pid"

for ((attempt = 1; attempt <= ${ALIPAY_SMOKE_BACKEND_WAIT_SECONDS:-600}; attempt++)); do
  if curl -fsS --max-time 5 "http://127.0.0.1:${server_port}/actuator/health" >/dev/null 2>&1; then
    listener_pid="$(lsof -nP -tiTCP:"$server_port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
    if [[ ! "$listener_pid" =~ ^[0-9]+$ ]]; then
      echo "Sandbox backend is healthy but its listener PID could not be recorded." >&2
      exit 1
    fi
    backend_pid="$(cat "$run_dir/backend.pid")"
    if [[ "$listener_pid" == "$backend_pid" ]]; then
      smoke_assert_process_owned "$listener_pid" "alipay.smoke.run-id=$run_id" "$run_dir"
    else
      smoke_assert_process_owned \
        "$listener_pid" \
        "com.example.venue.VenueReservationApplication" \
        "$run_dir" \
        "$ROOT_DIR/backend"
    fi
    echo "$listener_pid" > "$run_dir/backend-listener.pid"
    echo "$run_dir" > "$active_file"
    trap - EXIT
    echo "Alipay sandbox smoke runtime started."
    echo "Backend: http://127.0.0.1:$server_port"
    echo "Run id: $run_id"
    exit 0
  fi
  if ! kill -0 "$(cat "$run_dir/backend.pid")" 2>/dev/null; then
    echo "Sandbox backend stopped before becoming healthy." >&2
    exit 1
  fi
  sleep 1
done

echo "Sandbox backend did not become healthy in time." >&2
exit 1
