#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
quiet="false"

if [[ "${1:-}" == "--quiet" ]]; then
  quiet="true"
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--quiet]" >&2
  exit 2
fi

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/alipay-sandbox-smoke.sh"

smoke_stop_active "$quiet"
