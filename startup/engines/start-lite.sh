#!/usr/bin/env bash
# ============================================================
# 启动轻量引擎组：ClickHouse + Kafka + Pulsar + HBase（约 3.5 GiB）
#
#   ./startup/engines/start-lite.sh
#
# 只起 lite profile，不动 dataforge 主容器；两者共用 dataforge-docker_default 网络。
# ============================================================
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SELF_DIR/../compose/docker-compose.engines.yml"
ENV_FILE="$SELF_DIR/.env.engines"

if [ ! -f "$ENV_FILE" ]; then
  echo "==> 未找到 ${ENV_FILE}，复制示例配置"
  cp "$SELF_DIR/.env.engines.example" "$ENV_FILE"
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

echo "==> 启动 lite 引擎组（clickhouse / kafka / pulsar / hbase）"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile lite up -d

wait_healthy() {
  local name="$1" deadline=$((SECONDS + 180))
  while [ $SECONDS -lt $deadline ]; do
    local status
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null || echo missing)"
    if [ "$status" = "healthy" ]; then echo "    [OK] $name healthy"; return 0; fi
    sleep 3
  done
  echo "    [WARN] $name 180s 内未 healthy（当前: ${status:-unknown}）"
  return 1
}

rc=0
for c in df-clickhouse df-kafka df-pulsar df-hbase; do
  wait_healthy "$c" || rc=1
done

# ---------------- 初始化（幂等） ----------------
echo "==> 初始化 Kafka topic"
docker exec df-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic "${KAFKA_TOPIC:-dataforge-e2e}" --partitions 1 --replication-factor 1 >/dev/null 2>&1 \
  && echo "    [OK] topic ${KAFKA_TOPIC:-dataforge-e2e}" || echo "    [WARN] topic 创建失败（broker 可能未就绪）"

echo "==> 初始化 Pulsar tenant/namespace/topic"
docker exec df-pulsar bin/pulsar-admin tenants create "${PULSAR_TENANT:-dataforge}" >/dev/null 2>&1 || true
docker exec df-pulsar bin/pulsar-admin namespaces create "${PULSAR_TENANT:-dataforge}/${PULSAR_NAMESPACE:-default}" >/dev/null 2>&1 || true
docker exec df-pulsar bin/pulsar-admin topics create "persistent://${PULSAR_TENANT:-dataforge}/${PULSAR_NAMESPACE:-default}/${PULSAR_TOPIC:-e2e}" >/dev/null 2>&1 || true
echo "    [OK] persistent://${PULSAR_TENANT:-dataforge}/${PULSAR_NAMESPACE:-default}/${PULSAR_TOPIC:-e2e}"

echo "==> 初始化 HBase namespace"
docker exec df-hbase /bin/bash -lc "echo \"create_namespace '${HBASE_NAMESPACE:-dataforge}'\" | /usr/local/hbase/bin/hbase shell -n" >/dev/null 2>&1 || true
echo "    [OK] namespace ${HBASE_NAMESPACE:-dataforge}（已存在则为幂等跳过）"

echo "==> lite 引擎组启动完成"
exit $rc
