#!/usr/bin/env bash
# ============================================================
# dataforge All-in-One compose 封装脚本
#
# 用法:
#   ./compose.sh up -d               # 无卷模式(默认): 数据写在容器内, 不挂载命名卷
#   PERSIST=1 ./compose.sh up -d     # 持久化模式: 挂载 df-pgdata / df-data 命名卷
#   ./compose.sh down                # 停止容器(不删卷/容器数据)
#   ./compose.sh reset               # 停止容器并删除命名卷数据(⚠️ 永久删除, 不可恢复)
#   ./compose.sh ps / logs -f ...    # 其余命令透传 docker compose
#
# 关于旧数据卷:
#   * 早期版本使用命名卷 df-pgdata / df-data 持久化; 现默认无卷。
#   * 宿主机残留旧卷想复用数据:  PERSIST=1 ./compose.sh up -d(重新挂载旧卷)
#   * 想彻底清除旧卷数据重新开始: ./compose.sh reset
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # 本脚本所在目录 startup/macos/
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/compose"  # 编排文件目录 startup/compose/(docker-compose*.yml)
FILES=(-f "$COMPOSE_DIR/docker-compose.yml")

if [ "${PERSIST:-0}" = "1" ]; then
  FILES+=(-f "$COMPOSE_DIR/docker-compose.persist.yml")
  echo "==> 持久化模式: 挂载命名卷 df-pgdata / df-data"
else
  echo "==> 无卷模式: 数据写入容器内 (如需持久化请 PERSIST=1)"
fi

CMD="${1:-up}"

case "$CMD" in
  reset)
    docker compose "${FILES[@]}" down --remove-orphans || true
    # 无论当前是否 PERSIST, 都用持久化 override 解析命名卷, 清除旧卷数据。
    # 注意: config --volumes 返回声明名(如 df-pgdata), 实际卷名带项目前缀
    # (如 dataforge-docker_df-pgdata), 需拼接 project 前缀。
    reset_files=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_DIR/docker-compose.persist.yml")
    project="$(docker compose "${reset_files[@]}" config 2>/dev/null | sed -n 's/^name:[[:space:]]*//p' | head -1)"
    while read -r declared; do
      [ -z "$declared" ] && continue
      v="${project}_${declared}"
      if docker volume inspect "$v" >/dev/null 2>&1; then
        docker volume rm "$v"
        echo "    已删除命名卷: $v"
      else
        echo "    命名卷不存在, 跳过: $v"
      fi
    done < <(docker compose "${reset_files[@]}" config --volumes 2>/dev/null || true)
    echo "==> 完成: 旧卷数据已清除 (PERSIST=1 ./compose.sh up -d 可重建持久化环境)"
    ;;
  *)
    docker compose "${FILES[@]}" "$@"
    ;;
esac
