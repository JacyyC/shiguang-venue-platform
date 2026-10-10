#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV_STOP_SCRIPT="${ALIPAY_DEMO_DEV_STOP_SCRIPT:-$ROOT_DIR/scripts/dev-stop.sh}"

if [[ ! -x "$DEV_STOP_SCRIPT" ]]; then
  echo "Required lifecycle script is missing or not executable: $DEV_STOP_SCRIPT" >&2
  exit 1
fi

exec "$DEV_STOP_SCRIPT"
