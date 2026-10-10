#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/compose.sh"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dev-common.sh"
load_root_env "$ROOT_DIR/.env.local"

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not running or is not reachable." >&2
  exit 1
fi

cd "$ROOT_DIR/deploy"
compose up -d mysql redis mailpit

MYSQL_CONTAINER="$(compose ps -q mysql)"
if [[ -z "$MYSQL_CONTAINER" ]]; then
  echo "MySQL container was not created." >&2
  exit 1
fi

for _ in {1..90}; do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping -h 127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD:-root_password}" --silent >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! docker exec "$MYSQL_CONTAINER" mysqladmin ping -h 127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD:-root_password}" --silent >/dev/null 2>&1; then
  echo "MySQL did not become ready in time." >&2
  compose logs --tail=80 mysql >&2 || true
  exit 1
fi

REDIS_CONTAINER="$(compose ps -q redis)"
if [[ -z "$REDIS_CONTAINER" ]]; then
  echo "Redis container was not created." >&2
  exit 1
fi

MAILPIT_CONTAINER="$(compose ps -q mailpit)"
if [[ -z "$MAILPIT_CONTAINER" ]]; then
  echo "Mailpit container was not created." >&2
  exit 1
fi

docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-root_password}" "${MYSQL_DATABASE:-venue_reservation}" <<'SQL'
set foreign_key_checks = 0;

drop procedure if exists truncate_runtime_tables;
delimiter //
create procedure truncate_runtime_tables()
begin
  declare finished integer default 0;
  declare runtime_table varchar(128);
  declare runtime_tables cursor for
    select table_name
    from information_schema.tables
    where table_schema = database()
      and table_type = 'BASE TABLE'
      and table_name not in (
        'flyway_schema_history',
        'sys_role',
        'sys_menu',
        'sys_role_menu',
        'sys_config',
        'sys_dict_type',
        'sys_dict_data',
        'sys_user',
        'sys_user_role',
        'member_level',
        'provider_registry_entry'
      );
  declare continue handler for not found set finished = 1;

  open runtime_tables;
  truncate_loop: loop
    fetch runtime_tables into runtime_table;
    if finished = 1 then
      leave truncate_loop;
    end if;

    set @truncate_sql = concat('truncate table `', replace(runtime_table, '`', '``'), '`');
    prepare truncate_statement from @truncate_sql;
    execute truncate_statement;
    deallocate prepare truncate_statement;
  end loop;
  close runtime_tables;
end//
delimiter ;

call truncate_runtime_tables();
drop procedure if exists truncate_runtime_tables;

delete user_role
from sys_user_role user_role
left join sys_user user_account on user_account.id = user_role.user_id
where user_account.id is null
   or user_account.username <> 'admin'
   or user_account.deleted <> 0;

delete from sys_user where username <> 'admin' or deleted <> 0;

update sys_user
set last_login_time = null
where username = 'admin' and deleted = 0;

update provider_registry_entry
set status = if(enabled = 1, 'ACTIVE', 'DISABLED'),
    last_success_at = null,
    last_failure_at = null,
    consecutive_failures = 0,
    last_failure_code = null,
    last_failure_message = null
where deleted = 0;

set foreign_key_checks = 1;
SQL

for _ in {1..30}; do
  if docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q '^PONG$'; then
    break
  fi
  sleep 1
done

if ! docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q '^PONG$'; then
  echo "Redis did not become ready in time." >&2
  compose logs --tail=80 redis >&2 || true
  exit 1
fi

docker exec "$REDIS_CONTAINER" redis-cli flushall >/dev/null

for _ in {1..30}; do
  if curl -fsS "http://127.0.0.1:${MAILPIT_UI_PORT:-8025}/api/v1/messages" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:${MAILPIT_UI_PORT:-8025}/api/v1/messages" >/dev/null; then
  echo "Mailpit did not become ready in time." >&2
  compose logs --tail=80 mailpit >&2 || true
  exit 1
fi

curl -fsS -X DELETE "http://127.0.0.1:${MAILPIT_UI_PORT:-8025}/api/v1/messages" >/dev/null

echo "Runtime data cleared. Only the administrator and system reference data were preserved."
