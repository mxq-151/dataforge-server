#!/usr/bin/env bash
# ============================================================
# Stop and remove dataforge containers (does NOT delete
# volumes/container data)
#
# Data is kept; to fully clear named-volume data use
# ./startup/macos/clear-data.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # directory of this script: startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # compose files directory: startup/compose/

echo "==> Stopping containers"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" down --remove-orphans
echo "==> Containers stopped (data kept; run ./startup/macos/clear-data.sh to fully clear named volumes)"