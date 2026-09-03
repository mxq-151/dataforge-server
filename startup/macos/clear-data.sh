#!/usr/bin/env bash
# ============================================================
# Clear dataforge named-volume data (WARNING: permanent, irreversible)
#
# Stops/removes containers first, then removes the df-pgdata /
# df-data named volumes.
# To restart a persistent environment: ./startup/macos/start-persist.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # directory of this script: startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # compose files directory: startup/compose/
clear_files=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.persist.yml")

echo "==> Stopping containers"
docker compose "${clear_files[@]}" down --remove-orphans || true

echo "==> Clearing named-volume data"
# `config --volumes` prints declared names (e.g. df-pgdata); the real
# volume names carry a project prefix (e.g. dataforge-docker_df-pgdata),
# so the project prefix has to be prepended.
project="$(docker compose "${clear_files[@]}" config 2>/dev/null | sed -n 's/^name:[[:space:]]*//p' | head -1)"
while read -r declared; do
  [ -z "$declared" ] && continue
  v="${project}_${declared}"
  if docker volume inspect "$v" >/dev/null 2>&1; then
    docker volume rm "$v"
    echo "    Removed named volume: $v"
  else
    echo "    Named volume not found, skipped: $v"
  fi
done < <(docker compose "${clear_files[@]}" config --volumes 2>/dev/null || true)

echo "==> Done: old volume data cleared (run ./startup/macos/start-persist.sh to restart a persistent environment)"