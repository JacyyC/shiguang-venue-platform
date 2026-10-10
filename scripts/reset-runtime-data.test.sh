#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESET_SCRIPT="$ROOT_DIR/scripts/reset-runtime-data.sh"

grep -q "create procedure truncate_runtime_tables" "$RESET_SCRIPT"
grep -q "table_name not in" "$RESET_SCRIPT"

reference_tables=(
  flyway_schema_history
  sys_role
  sys_menu
  sys_role_menu
  sys_config
  sys_dict_type
  sys_dict_data
  sys_user
  sys_user_role
  member_level
  provider_registry_entry
)

for reference_table in "${reference_tables[@]}"; do
  grep -q "'$reference_table'" "$RESET_SCRIPT"
done

expected_reference_tables="$(printf '%s\n' "${reference_tables[@]}" | sort)"
actual_reference_tables="$(
  sed -n '/table_name not in (/,/);/p' "$RESET_SCRIPT" \
    | sed -n "s/^[[:space:]]*'\([^']*\)'[,)]*$/\1/p" \
    | sort
)"
if [[ "$actual_reference_tables" != "$expected_reference_tables" ]]; then
  echo "runtime data reset reference-table whitelist must match the bootstrap tables exactly" >&2
  exit 1
fi

user_delete_statement="$(
  sed -n "/^delete from sys_user where .*;$/p" "$RESET_SCRIPT" \
    | tr '\n' ' ' \
    | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//'
)"
if [[ "$user_delete_statement" != "delete from sys_user where username <> 'admin' or deleted <> 0;" ]]; then
  echo "runtime data reset must delete every non-admin or soft-deleted user" >&2
  exit 1
fi

admin_login_reset_statement="$(
  sed -n "/^update sys_user$/,/^where username = 'admin' and deleted = 0;$/p" "$RESET_SCRIPT" \
    | tr '\n' ' ' \
    | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//'
)"
if [[ "$admin_login_reset_statement" != "update sys_user set last_login_time = null where username = 'admin' and deleted = 0;" ]]; then
  echo "runtime data reset must clear the preserved admin login time" >&2
  exit 1
fi

grep -Eq 'compose up -d mysql redis mailpit' "$RESET_SCRIPT"
grep -q "redis-cli ping" "$RESET_SCRIPT"
grep -q "redis-cli flushall" "$RESET_SCRIPT"
grep -q "/api/v1/messages" "$RESET_SCRIPT"

echo "runtime data reset contract tests passed"
