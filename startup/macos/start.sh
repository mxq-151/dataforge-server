#!/usr/bin/env bash
# ============================================================
# Start dataforge containers (without named volumes)
#
# Data is written into the containers and is lost when the
# containers are removed; for persistent data use
# ./startup/macos/start-persist.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # directory of this script: startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # compose files directory: startup/compose/

echo "==> No-volume mode: data is written into the container (run ./startup/macos/start-persist.sh for persistence)"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" up -d
echo "==> Started"