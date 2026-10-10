#!/usr/bin/env bash

backend_http_port() {
  echo "${SERVER_PORT:-8080}"
}

mysql_port() {
  echo "${MYSQL_PORT:-3306}"
}

redis_port() {
  echo "${REDIS_PORT:-6379}"
}

docker_backend_port() {
  echo "${BACKEND_PORT:-8080}"
}

docker_application_url() {
  local port="${NGINX_PORT:-80}"
  if [[ "$port" == "80" ]]; then
    echo "http://localhost"
  else
    echo "http://localhost:$port"
  fi
}
