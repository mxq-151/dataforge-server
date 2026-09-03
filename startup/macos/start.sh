#!/usr/bin/env bash
# ============================================================
# 启动 dataforge 容器(不带数据卷)
#
# 数据写入容器内, 容器删除后数据随之丢失;
# 如需持久化数据, 请改用 ./startup/macos/start-persist.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # 本脚本所在目录 startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # 编排文件目录 startup/compose/

echo "==> 无卷模式: 数据写入容器内 (如需持久化请运行 ./startup/macos/start-persist.sh)"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" up -d
echo "==> 启动完成"