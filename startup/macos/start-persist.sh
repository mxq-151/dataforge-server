#!/usr/bin/env bash
# ============================================================
# Start dataforge containers (with named volumes)
#
# Mounts named volumes df-pgdata (PostgreSQL) / df-data (/opt/dataforge);
# data survives container removal/recreation.
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # directory of this script: startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # compose files directory: startup/compose/

echo "==> Persist mode: mounting named volumes df-pgdata / df-data"
docker compose \
  -f "$COMPOSE_DIR/docker-compose.yml" \
  -f "$COMPOSE_DIR/docker-compose.persist.yml" \
  up -d
echo "==> Started (with named volumes)"