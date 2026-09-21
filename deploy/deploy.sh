#!/usr/bin/env bash
set -Eeuo pipefail

cd /opt/arm112

if [[ -z "${RELEASE_SHA:-}" || ! "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "RELEASE_SHA must be a full Git commit SHA" >&2
  exit 2
fi

if [[ ! -f .env ]]; then
  echo "/opt/arm112/.env is missing" >&2
  exit 3
fi

new_release="release.env.next"
previous_release="release.env.previous"

printf 'BACKEND_IMAGE=ghcr.io/dema-koder/lct2026-arm112-backend:%s\nFRONTEND_IMAGE=ghcr.io/dema-koder/lct2026-arm112-frontend:%s\n' \
  "$RELEASE_SHA" "$RELEASE_SHA" > "$new_release"
chmod 600 "$new_release"

compose() {
  docker compose --env-file .env --env-file release.env -f compose.production.yaml "$@"
}

if [[ -f release.env ]]; then
  cp release.env "$previous_release"
fi

mv "$new_release" release.env

rollback() {
  status=$?
  echo "Deployment failed, restoring previous release" >&2
  if [[ -f "$previous_release" ]]; then
    mv "$previous_release" release.env
    compose up -d --remove-orphans || true
  fi
  exit "$status"
}
trap rollback ERR

pull_service() {
  local service="$1"
  local attempt
  for attempt in 1 2 3; do
    if compose pull "$service"; then
      return 0
    fi
    echo "Pull of ${service} failed (${attempt}/3), retrying in 5 seconds" >&2
    sleep 5
  done
  return 1
}

# На небольшом production-сервере параллельная распаковка двух прикладных
# образов создаёт лишний пик RAM и I/O. Загружаем их последовательно и
# повторяем сетевые операции: соединение с GHCR на сервере нестабильно.
pull_service backend
pull_service frontend
compose pull --policy missing postgres proxy
compose up -d --remove-orphans

for attempt in {1..30}; do
  if curl --fail --silent --show-error http://127.0.0.1/actuator/health >/dev/null; then
    trap - ERR
    rm -f "$previous_release"
    docker image prune -f >/dev/null
    echo "Deployment ${RELEASE_SHA} is healthy"
    exit 0
  fi
  sleep 5
done

echo "Application did not become healthy in time" >&2
exit 1
