#!/usr/bin/env bash
# ============================================================
# 启动 OLAP 引擎（Doris；StarRocks 因镜像不可得未部署）
#
#   ./startup/engines/start-olap.sh doris
#
# OLAP 与 lite 组不同时全开：Doris FE 2g + BE 3g，加上主容器约 4g，
# 同时起 lite 会逼近 Docker VM（12.48 GiB）上限。脚本先停 lite 释放内存；
# 验证完可用 start-lite.sh 再起回来。
# ============================================================
set -euo pipefail

ENGINE="${1:-}"
case "$ENGINE" in
  doris)      PROFILE="olap-doris";      CONTAINER="df-doris-fe";   PORT_VAR="DORIS_FE_MYSQL_PORT";      DB_VAR="DORIS_DB";      USER_VAR="DORIS_USER";      PASS_VAR="DORIS_PASSWORD" ;;
  starrocks)  echo "StarRocks 未部署（镜像不可得），仅保留静态配置说明；OLAP 由 Doris 代表。"; exit 2 ;;
  *) echo "用法: $0 doris"; exit 2 ;;
esac

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SELF_DIR/../compose/docker-compose.engines.yml"
ENV_FILE="$SELF_DIR/.env.engines"
[ -f "$ENV_FILE" ] || cp "$SELF_DIR/.env.engines.example" "$ENV_FILE"
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

echo "==> 停止 lite 组释放内存"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile lite stop || true

echo "==> 启动 ${ENGINE}（profile=${PROFILE}）"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile "$PROFILE" up -d

echo "==> 等待 $CONTAINER 健康（最长 300s）"
deadline=$((SECONDS + 300))
while [ $SECONDS -lt $deadline ]; do
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER" 2>/dev/null || echo missing)"
  [ "$status" = "healthy" ] && break
  sleep 5
done
if [ "${status:-}" != "healthy" ]; then
  echo "!! $CONTAINER 未在 300s 内 healthy（当前: ${status:-unknown}），查看日志：docker logs $CONTAINER"
  exit 1
fi
echo "    [OK] $CONTAINER healthy"

SQL="CREATE DATABASE IF NOT EXISTS ${!DB_VAR:-dwd};"

echo "==> 初始化库 ${!DB_VAR:-dwd}"
if docker exec "$CONTAINER" bash -lc "command -v mysql" >/dev/null 2>&1; then
  docker exec "$CONTAINER" bash -lc "mysql -h127.0.0.1 -P9030 -u'${!USER_VAR:-root}' ${!PASS_VAR:+-p'${!PASS_VAR}'} -e \"$SQL\"" \
    && echo "    [OK] database ${!DB_VAR:-dwd} ready" || echo "    [WARN] 初始化 SQL 执行失败（稍后可用 verify 脚本复查）"
else
  echo "    [WARN] 镜像内无 mysql 客户端，跳过建库；可用 dataforge DDL 或在容器内手动执行：$SQL"
fi

echo "==> $ENGINE 启动完成（宿主端口: FE MySQL ${!PORT_VAR:-unknown}）"
