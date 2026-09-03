#!/usr/bin/env bash
# ============================================================
# 清除 dataforge 命名卷数据(⚠️ 永久删除, 不可恢复)
#
# 先停止/移除容器, 再删除 df-pgdata / df-data 命名卷。
# 重新开始持久化环境: ./startup/macos/start-persist.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # 本脚本所在目录 startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # 编排文件目录 startup/compose/
clear_files=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.persist.yml")

echo "==> 停止容器"
docker compose "${clear_files[@]}" down --remove-orphans || true

echo "==> 清除命名卷数据"
# config --volumes 返回声明名(如 df-pgdata), 实际卷名带项目前缀
# (如 dataforge-docker_df-pgdata), 需拼接 project 前缀。
project="$(docker compose "${clear_files[@]}" config 2>/dev/null | sed -n 's/^name:[[:space:]]*//p' | head -1)"
while read -r declared; do
  [ -z "$declared" ] && continue
  v="${project}_${declared}"
  if docker volume inspect "$v" >/dev/null 2>&1; then
    docker volume rm "$v"
    echo "    已删除命名卷: $v"
  else
    echo "    命名卷不存在, 跳过: $v"
  fi
done < <(docker compose "${clear_files[@]}" config --volumes 2>/dev/null || true)

echo "==> 完成: 旧卷数据已清除 (重新持久化请运行 ./startup/macos/start-persist.sh)"