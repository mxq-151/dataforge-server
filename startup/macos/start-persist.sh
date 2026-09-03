#!/usr/bin/env bash
# ============================================================
# 启动 dataforge 容器(带数据卷)
#
# 挂载命名卷 df-pgdata(PostgreSQL) / df-data(/opt/dataforge),
# 容器删除/重建后数据依旧保留。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # 本脚本所在目录 startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # 编排文件目录 startup/compose/

echo "==> 持久化模式: 挂载命名卷 df-pgdata / df-data"
docker compose \
  -f "$COMPOSE_DIR/docker-compose.yml" \
  -f "$COMPOSE_DIR/docker-compose.persist.yml" \
  up -d
echo "==> 启动完成(带数据卷)"