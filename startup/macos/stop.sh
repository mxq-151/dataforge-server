#!/usr/bin/env bash
# ============================================================
# 停止并移除 dataforge 容器(不删除数据卷/容器数据)
#
# 数据保留; 如需彻底清除命名卷数据, 请改用
# ./startup/macos/clear-data.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # 本脚本所在目录 startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # 编排文件目录 startup/compose/

echo "==> 停止容器"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" down --remove-orphans
echo "==> 容器已停止 (数据保留; 如需彻底清除数据卷请运行 ./startup/macos/clear-data.sh)"