#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
START_SCRIPT="$ROOT_DIR/scripts/alipay-sandbox-demo-start.sh"
STOP_SCRIPT="$ROOT_DIR/scripts/alipay-sandbox-demo-stop.sh"

if [[ ! -x "$START_SCRIPT" || ! -x "$STOP_SCRIPT" ]]; then
  echo "required Alipay sandbox demo scripts are missing or not executable" >&2
  exit 1
fi

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

order_file="$fixture/order"
fake_dev_start="$fixture/dev-start"
fake_dev_stop="$fixture/dev-stop"
fake_smoke_start="$fixture/smoke-start"
fake_smoke_stop="$fixture/smoke-stop"
fake_bin="$fixture/bin"
port_probe_count="$fixture/port-probe-count"
mkdir -p "$fake_bin"

cat > "$fake_dev_start" <<'EOF'
#!/usr/bin/env bash
echo "dev-start" >> "$ALIPAY_DEMO_TEST_ORDER"
EOF

cat > "$fake_dev_stop" <<'EOF'
#!/usr/bin/env bash
echo "dev-stop:$*" >> "$ALIPAY_DEMO_TEST_ORDER"
EOF

cat > "$fake_smoke_start" <<'EOF'
#!/usr/bin/env bash
echo "smoke-start" >> "$ALIPAY_DEMO_TEST_ORDER"
if [[ "${ALIPAY_DEMO_TEST_SMOKE_FAIL:-false}" == "true" ]]; then
  exit 23
fi
EOF

cat > "$fake_smoke_stop" <<'EOF'
#!/usr/bin/env bash
echo "smoke-stop:$*" >> "$ALIPAY_DEMO_TEST_ORDER"
EOF

chmod +x "$fake_dev_start" "$fake_dev_stop" "$fake_smoke_start" "$fake_smoke_stop"

cat > "$fake_bin/nc" <<'EOF'
#!/usr/bin/env bash
count=0
if [[ -f "$ALIPAY_DEMO_TEST_PORT_PROBE_COUNT" ]]; then
  count="$(cat "$ALIPAY_DEMO_TEST_PORT_PROBE_COUNT")"
fi
count=$((count + 1))
echo "$count" > "$ALIPAY_DEMO_TEST_PORT_PROBE_COUNT"
if (( count <= ${ALIPAY_DEMO_TEST_BUSY_PROBES:-0} )); then
  exit 0
fi
exit 1
EOF
chmod +x "$fake_bin/nc"

common_env=(
  "ALIPAY_DEMO_TEST_ORDER=$order_file"
  "ALIPAY_DEMO_DEV_START_SCRIPT=$fake_dev_start"
  "ALIPAY_DEMO_DEV_STOP_SCRIPT=$fake_dev_stop"
  "ALIPAY_DEMO_SMOKE_START_SCRIPT=$fake_smoke_start"
  "ALIPAY_DEMO_SMOKE_STOP_SCRIPT=$fake_smoke_stop"
  "ALIPAY_DEMO_BACKEND_PORT=18080"
  "ALIPAY_DEMO_PORT_WAIT_SECONDS=2"
  "ALIPAY_DEMO_TEST_PORT_PROBE_COUNT=$port_probe_count"
  "PATH=$fake_bin:$PATH"
)

env "${common_env[@]}" "$START_SCRIPT" >/dev/null
expected_success=$'dev-stop:\ndev-start\ndev-stop:--backend-only\nsmoke-start'
if [[ "$(cat "$order_file")" != "$expected_success" ]]; then
  echo "demo start orchestration order is incorrect" >&2
  cat "$order_file" >&2
  exit 1
fi

: > "$order_file"
rm -f "$port_probe_count"
env "${common_env[@]}" ALIPAY_DEMO_TEST_BUSY_PROBES=2 "$START_SCRIPT" >/dev/null
if [[ "$(cat "$port_probe_count" 2>/dev/null || true)" != "3" ]]; then
  echo "demo start did not wait until the ordinary backend port was released" >&2
  exit 1
fi
if [[ "$(cat "$order_file")" != "$expected_success" ]]; then
  echo "port-release wait changed the demo orchestration order" >&2
  exit 1
fi

: > "$order_file"
env "${common_env[@]}" "$STOP_SCRIPT" >/dev/null
if [[ "$(cat "$order_file")" != "dev-stop:" ]]; then
  echo "demo stop did not delegate to the complete dev stop" >&2
  exit 1
fi

: > "$order_file"
if env "${common_env[@]}" ALIPAY_DEMO_TEST_SMOKE_FAIL=true "$START_SCRIPT" >/dev/null 2>&1; then
  echo "demo start accepted a smoke startup failure" >&2
  exit 1
fi
expected_failure=$'dev-stop:\ndev-start\ndev-stop:--backend-only\nsmoke-start\nsmoke-stop:--quiet\ndev-stop:'
if [[ "$(cat "$order_file")" != "$expected_failure" ]]; then
  echo "demo start failure cleanup order is incorrect" >&2
  cat "$order_file" >&2
  exit 1
fi

echo "alipay sandbox demo script tests passed"
