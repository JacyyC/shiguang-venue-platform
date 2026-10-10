#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
START_SCRIPT="$ROOT_DIR/scripts/alipay-sandbox-smoke-start.sh"
STOP_SCRIPT="$ROOT_DIR/scripts/alipay-sandbox-smoke-stop.sh"

for script in "$START_SCRIPT" "$STOP_SCRIPT" "$ROOT_DIR/scripts/lib/alipay-sandbox-smoke.sh"; do
  if [[ ! -x "$script" ]]; then
    echo "required smoke script is missing or not executable: $script" >&2
    exit 1
  fi
done

fixture="$(mktemp -d)"
unrelated_pid=""
trap '
  if [[ -n "$unrelated_pid" ]] && kill -0 "$unrelated_pid" 2>/dev/null; then
    kill "$unrelated_pid" 2>/dev/null || true
  fi
  ALIPAY_SMOKE_RUN_ROOT="$fixture/run" "$STOP_SCRIPT" --quiet >/dev/null 2>&1 || true
  rm -rf "$fixture"
' EXIT

fake_bin="$fixture/bin"
run_root="$fixture/run"
env_file="$fixture/smoke.env"
capture_file="$fixture/backend-env"
stop_order_file="$fixture/stop-order"
real_lsof="$(command -v lsof)"
mkdir -p "$fake_bin" "$run_root"

cat > "$fake_bin/cloudflared" <<'EOS'
#!/usr/bin/env bash
if [[ "${ALIPAY_TEST_TUNNEL_MODE:-single}" == "multiple" ]]; then
  echo "https://first-unit.trycloudflare"".""com"
  echo "https://second-unit.trycloudflare"".""com"
else
  echo "INF Quick Tunnel ready at https://single-unit.trycloudflare"".""com"
fi
trap 'echo cloudflared >> "$FAKE_STOP_ORDER"; exit 0' TERM INT
while true; do sleep 1; done
EOS

cat > "$fake_bin/mvn" <<'EOS'
#!/usr/bin/env bash
{
  echo "enabled=${APP_PAYMENT_ALIPAY_SANDBOX_ENABLED:-}"
  echo "provider=${APP_PAYMENT_DEFAULT_PROVIDER_KEY:-}"
  echo "notify=${APP_PAYMENT_ALIPAY_SANDBOX_NOTIFY_URL:-}"
  echo "return=${APP_PAYMENT_ALIPAY_SANDBOX_RETURN_URL:-}"
} > "$FAKE_CAPTURE"
trap 'echo backend >> "$FAKE_STOP_ORDER"; exit 0' TERM INT
while true; do sleep 1; done
EOS

cat > "$fake_bin/curl" <<'EOS'
#!/usr/bin/env bash
if [[ "$*" == *"/actuator/health"* ]]; then
  [[ -f "$FAKE_CAPTURE" ]]
  exit $?
fi
exit 22
EOS

cat > "$fake_bin/nc" <<'EOS'
#!/usr/bin/env bash
exit 1
EOS

cat > "$fake_bin/lsof" <<'EOS'
#!/usr/bin/env bash
if [[ "$*" == *"-tiTCP:18080"* ]]; then
  find "$ALIPAY_SMOKE_RUN_ROOT" -maxdepth 2 -type f -name backend.pid -exec cat {} \; | head -n 1
  exit 0
fi
exec "$FAKE_REAL_LSOF" "$@"
EOS

chmod +x "$fake_bin/cloudflared" "$fake_bin/mvn" "$fake_bin/curl" "$fake_bin/nc" "$fake_bin/lsof"

cat > "$env_file" <<'EOS'
SERVER_PORT=18080
APP_PAYMENT_DEFAULT_PROVIDER_KEY=payment.dev
APP_PAYMENT_ALIPAY_SANDBOX_ENABLED=false
APP_PAYMENT_ALIPAY_SANDBOX_APP_ID=9021000000000000
APP_PAYMENT_ALIPAY_SANDBOX_APP_PRIVATE_KEY=test-private-key
APP_PAYMENT_ALIPAY_SANDBOX_ALIPAY_PUBLIC_KEY=test-public-key
APP_PAYMENT_ALIPAY_SANDBOX_GATEWAY_URL=https://openapi-sandbox.dl.alipaydev.com/gateway.do
APP_PAYMENT_ALIPAY_SANDBOX_NOTIFY_URL=
APP_PAYMENT_ALIPAY_SANDBOX_RETURN_URL=
EOS

test_path="$fake_bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
test_origin="https://single-unit.trycloudflare"".""com"
common_env=(
  "PATH=$test_path"
  "ALIPAY_SMOKE_ENV_FILE=$env_file"
  "ALIPAY_SMOKE_RUN_ROOT=$run_root"
  "ALIPAY_SMOKE_TUNNEL_WAIT_SECONDS=3"
  "ALIPAY_SMOKE_BACKEND_WAIT_SECONDS=3"
  "FAKE_CAPTURE=$capture_file"
  "FAKE_STOP_ORDER=$stop_order_file"
  "FAKE_REAL_LSOF=$real_lsof"
)

missing_path="/usr/bin:/bin:/usr/sbin:/sbin"
if PATH="$missing_path" ALIPAY_SMOKE_ENV_FILE="$env_file" ALIPAY_SMOKE_RUN_ROOT="$run_root" \
  "$START_SCRIPT" > "$fixture/missing-command.out" 2>&1; then
  echo "start script accepted a missing cloudflared command" >&2
  exit 1
fi
if find "$run_root" -mindepth 1 | grep -q .; then
  echo "missing command failure created run artifacts" >&2
  exit 1
fi

missing_key_env="$fixture/missing-key.env"
grep -v '^APP_PAYMENT_ALIPAY_SANDBOX_ALIPAY_PUBLIC_KEY=' "$env_file" > "$missing_key_env"
if env "${common_env[@]}" ALIPAY_SMOKE_ENV_FILE="$missing_key_env" \
  "$START_SCRIPT" > "$fixture/missing-key.out" 2>&1; then
  echo "start script accepted incomplete sandbox credentials" >&2
  exit 1
fi
if find "$run_root" -mindepth 1 | grep -q .; then
  echo "missing credential failure created run artifacts" >&2
  exit 1
fi

start_output="$fixture/start.out"
env "${common_env[@]}" "$START_SCRIPT" > "$start_output"

if grep -q 'trycloudflare.com' "$start_output"; then
  echo "start script exposed the tunnel URL" >&2
  exit 1
fi

active_file="$run_root/active-run"
if [[ ! -f "$active_file" ]]; then
  echo "start script did not record the active run" >&2
  exit 1
fi

active_dir="$(cat "$active_file")"
if [[ ! -d "$active_dir" || ! -f "$active_dir/owner.marker" ]]; then
  echo "start script did not create private ownership artifacts" >&2
  exit 1
fi
if [[ ! -f "$active_dir/backend-listener.pid" ]]; then
  echo "start script did not record the backend listener process" >&2
  exit 1
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  permissions="$(stat -f '%Lp' "$active_dir")"
else
  permissions="$(stat -c '%a' "$active_dir")"
fi
if [[ "$permissions" != "700" ]]; then
  echo "run directory permissions are $permissions instead of 700" >&2
  exit 1
fi

if [[ "$(cat "$active_dir/notify-url")" != "$test_origin/api/payments/callback/alipay/sandbox" ]]; then
  echo "start script did not build the fixed callback URL" >&2
  exit 1
fi

for expected in \
  'enabled=true' \
  'provider=payment.alipay.sandbox' \
  "notify=$test_origin/api/payments/callback/alipay/sandbox" \
  'return=http://127.0.0.1:5173/me/payments/alipay/sandbox/return'; do
  if ! grep -Fxq "$expected" "$capture_file"; then
    echo "backend child did not receive runtime override: $expected" >&2
    exit 1
  fi
done

env "${common_env[@]}" "$STOP_SCRIPT" --quiet
if [[ -e "$active_file" ]] || find "$run_root" -mindepth 1 -maxdepth 1 -type d | grep -q .; then
  echo "stop script did not remove the active run" >&2
  exit 1
fi
if [[ "$(sed -n '1p' "$stop_order_file")" != "cloudflared" ]] \
  || [[ "$(sed -n '2p' "$stop_order_file")" != "backend" ]]; then
  echo "stop script did not stop cloudflared before the backend" >&2
  exit 1
fi

rm -f "$stop_order_file"
if env "${common_env[@]}" ALIPAY_TEST_TUNNEL_MODE=multiple "$START_SCRIPT" > "$fixture/multiple.out" 2>&1; then
  echo "start script accepted multiple tunnel URLs" >&2
  exit 1
fi
if [[ -e "$active_file" ]] || find "$run_root" -mindepth 1 -maxdepth 1 -type d | grep -q .; then
  echo "failed start did not clean its own run artifacts" >&2
  exit 1
fi

unrelated_dir="$run_root/alipay-sandbox-smoke-unrelated"
mkdir -m 700 "$unrelated_dir"
mkdir -p "$fixture/other-worktree"
other_worktree="$(cd "$fixture/other-worktree" && pwd -P)"
original_cwd="$PWD"
cd "$other_worktree"
env ALIPAY_TEST_TUNNEL_MODE=single FAKE_STOP_ORDER="$stop_order_file" \
  "$fake_bin/cloudflared" tunnel --url http://127.0.0.1:18080 --no-autoupdate \
  > "$fixture/unrelated-tunnel.log" 2>&1 &
unrelated_pid="$!"
cd "$original_cwd"
unrelated_cwd=""
for _ in {1..20}; do
  unrelated_cwd="$(lsof -a -p "$unrelated_pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)"
  [[ "$unrelated_cwd" == "$other_worktree" ]] && break
  sleep 0.1
done
if [[ "$unrelated_cwd" != "$other_worktree" ]]; then
  echo "unrelated process did not enter its fixture worktree" >&2
  exit 1
fi
cat > "$unrelated_dir/owner.marker" <<EOF
root=$ROOT_DIR
run_id=unrelated
EOF
echo "$unrelated_pid" > "$unrelated_dir/cloudflared.pid"
echo "$unrelated_dir" > "$active_file"

if env "${common_env[@]}" "$STOP_SCRIPT" --quiet > "$fixture/unrelated.out" 2>&1; then
  echo "stop script accepted an unrelated process" >&2
  exit 1
fi
if ! kill -0 "$unrelated_pid" 2>/dev/null; then
  echo "stop script signaled an unrelated process" >&2
  exit 1
fi
kill "$unrelated_pid"
wait "$unrelated_pid" 2>/dev/null || true
unrelated_pid=""
rm -rf "$unrelated_dir" "$active_file"

echo "alipay sandbox smoke script tests passed"
