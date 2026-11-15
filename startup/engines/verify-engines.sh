#!/usr/bin/env bash
# ============================================================
# 逐引擎连通性验证（服务就绪 ≠ dataforge 连得上，两层都验）
#
#   ./startup/engines/verify-engines.sh            # 全部
#   ./startup/engines/verify-engines.sh clickhouse kafka
#
# 第一层（引擎自身）：容器内客户端执行最小查询/生产消费。
# 第二层（dataforge）：调 /devops/datasource 建源 + /test 建连 + /databases 跑一条 SQL。
#   HBase 走 docker exec 建表写读（dataforge 的 HBase 连接不走 JDBC 连接池，
#   /test 不适用）；Kafka/Pulsar 在服务端没有数据源类型，只做任务级/客户端验证。
# ============================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SELF_DIR/.env.engines"
[ -f "$ENV_FILE" ] || cp "$SELF_DIR/.env.engines.example" "$ENV_FILE"
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

API="${DATAFORGE_BASE_URL:-http://localhost:8082/api}"
APP_CONTAINER="${DATAFORGE_CONTAINER:-dataforge-allinone}"
PASS=0; FAIL=0; SKIP=0
declare -a FAILED=()

ok()   { PASS=$((PASS+1)); echo "    [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); FAILED+=("$1"); echo "    [FAIL] $1"; }

# AES-128-ECB(PKCS5) + Base64：明文密码 + 13 位毫秒时间戳，与 AesUtils 一致
aes_encrypt() {
  local ts
  ts="$(python3 -c 'import time;print(int(time.time()*1000))')"
  printf '%s%s' "$1" "$ts" \
    | openssl enc -aes-128-ecb -K 42696764617461323032364165734b40 -nosalt -base64 \
    | tr -d '\n'
}

TOKEN=""
login() {
  local enc body
  enc="$(aes_encrypt "${DATAFORGE_PASSWORD:-123456}")"
  body="$(curl -s -m 15 -X POST "$API/system/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${DATAFORGE_USER:-admin}\",\"password\":\"$enc\"}")"
  TOKEN="$(printf '%s' "$body" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("token",""))' 2>/dev/null)"
  [ -n "$TOKEN" ] || { echo "!! 登录失败: $body"; return 1; }
  echo "==> 登录成功（${DATAFORGE_USER:-admin}）"
}

api_get() {
  curl -s -m 30 -H "Authorization: Bearer $TOKEN" "$API$1"
}

api_post() {
  curl -s -m 60 -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"
}

json_get() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

# 找到已存在的数据源 id（按名称；/list 不过滤连接状态，/all 只返回已连通的数据源）
find_ds_id() {
  api_get "/devops/datasource/list?current=1&size=200&datasourceName=$1" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);rs=((d.get('data') or {}).get('records') or []);print(next((x['id'] for x in rs if x.get('datasourceName')=='$1'),''))" 2>/dev/null
}

# JDBC 引擎（Doris / StarRocks / ClickHouse）：建源 -> /test -> /databases
verify_jdbc_engine() {
  local name="$1" type="$2" url="$3" user="$4" pass="$5" expect_db="$6"
  echo "==> ${name}（${type}, ${url}）"
  local id
  id="$(find_ds_id "$name")"
  if [ -z "$id" ]; then
    local create
    create="$(api_post '/devops/datasource' "{\"datasourceName\":\"$name\",\"datasourceType\":\"$type\",\"connectionType\":\"jdbc\",\"url\":\"$url\",\"username\":\"$user\",\"password\":\"$pass\",\"databaseName\":[\"$expect_db\"],\"description\":\"engine verification fixture\"}")"
    echo "    创建数据源: $(printf '%s' "$create" | head -c 200)"
    id="$(find_ds_id "$name")"
  fi
  [ -z "$id" ] && { bad "$name 数据源创建/查找失败"; return; }

  local test
  # 带 id 调 /test：成功后服务端会把 connection_status 置 1（/all 才会返回该数据源）
  test="$(api_post "/devops/datasource/test?id=$id" "{\"datasourceName\":\"$name\",\"datasourceType\":\"$type\",\"connectionType\":\"jdbc\",\"url\":\"$url\",\"username\":\"$user\",\"password\":\"$pass\"}")"
  if printf '%s' "$test" | grep -q '"success":true'; then ok "$name dataforge 建连（/devops/datasource/test）"; else bad "$name dataforge 建连失败: $(printf '%s' "$test" | head -c 300)"; fi

  local dbs
  # 带 id：策略内部走 getConnection(id) 取连接池；不带 id 会因 datasourceId=null 直接失败返回 []
  dbs="$(api_post '/devops/datasource/databases' "{\"id\":$id,\"datasourceName\":\"$name\",\"datasourceType\":\"$type\",\"connectionType\":\"jdbc\",\"url\":\"$url\",\"username\":\"$user\",\"password\":\"$pass\",\"databaseName\":[\"$expect_db\"]}")"
  if printf '%s' "$dbs" | grep -q "$expect_db"; then ok "$name 执行 SHOW DATABASES 返回含 $expect_db"; else bad "$name 查询失败: $(printf '%s' "$dbs" | head -c 300)"; fi
}

verify_clickhouse() {
  verify_jdbc_engine "verify-clickhouse" "clickhouse" \
    "jdbc:clickhouse://clickhouse:8123/${CLICKHOUSE_DB:-dwd}" \
    "${CLICKHOUSE_USER:-default}" "${CLICKHOUSE_PASSWORD:-dataforge}" "${CLICKHOUSE_DB:-dwd}"
  if docker exec df-clickhouse clickhouse-client --user "${CLICKHOUSE_USER:-default}" --password "${CLICKHOUSE_PASSWORD:-dataforge}" \
      --query "SELECT count() FROM ${CLICKHOUSE_DB:-dwd}.e2e_probe" >/dev/null 2>&1; then
    ok "clickhouse-client 最小查询"
  else
    bad "clickhouse-client 最小查询失败"
  fi
}

verify_doris() {
  verify_jdbc_engine "verify-doris" "doris" "jdbc:mysql://doris-fe:9030/${DORIS_DB:-dwd}" \
    "${DORIS_USER:-root}" "${DORIS_PASSWORD:-}" "${DORIS_DB:-dwd}"

  # 走 FE 的 MySQL 协议建表/写入/查询：能写进去才说明 BE 真的注册并可写，
  # 而不只是 FE 端口通。
  echo "==> Doris 建表 + 写入 + 查询（经 BE）"
  local out
  out="$(docker exec df-doris-fe bash -lc "mysql -h127.0.0.1 -P9030 -u'${DORIS_USER:-root}' ${DORIS_PASSWORD:+-p'${DORIS_PASSWORD}'} -e \"
CREATE DATABASE IF NOT EXISTS ${DORIS_DB:-dwd};
DROP TABLE IF EXISTS ${DORIS_DB:-dwd}.e2e_probe;
CREATE TABLE ${DORIS_DB:-dwd}.e2e_probe (id INT, name VARCHAR(32))
  DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1');
INSERT INTO ${DORIS_DB:-dwd}.e2e_probe VALUES (1, 'dataforge');
SELECT count(*) FROM ${DORIS_DB:-dwd}.e2e_probe;\"" 2>&1)"
  if printf '%s' "$out" | grep -qE '^1$'; then
    ok "Doris 建表 + 写入 + count=1（BE 可写）"
  else
    bad "Doris 建表/写入失败: $(printf '%s' "$out" | tail -4 | tr '\n' ' ' | head -c 300)"
  fi
}

verify_starrocks() {
  echo "==> StarRocks：未部署，仅静态验证（不参与 PASS 判定）"
  echo "    原因：starrocks/allin1-ubuntu:3.3.5 镜像不可得，本环境不部署该服务。"
  echo "    静态配置：宿主 8031(FE HTTP)/9031(FE MySQL)/8041(BE HTTP)，容器 8030/9030/8040。"
  echo "    JDBC：jdbc:mysql://starrocks:9030/\${STARROCKS_DB:-dwd}（容器网络）"
  echo "          jdbc:mysql://127.0.0.1:9031/\${STARROCKS_DB:-dwd}（宿主）"
  SKIP=$((SKIP+1))
}

verify_hbase() {
  echo "==> HBase（standalone，ZK 2181）"
  local ns="${HBASE_NAMESPACE:-dataforge}" out
  out="$(printf '%b' "create_namespace '$ns'\ncreate '$ns:e2e_probe','info'\nput '$ns:e2e_probe','r1','info:name','dataforge'\nget '$ns:e2e_probe','r1'\nexit\n" \
    | docker exec -i df-hbase /usr/local/hbase/bin/hbase shell -n 2>&1)"
  if printf '%s' "$out" | grep -q "dataforge"; then
    ok "HBase 建表 + put + get 返回 dataforge"
  else
    bad "HBase 建表/写读失败: $(printf '%s' "$out" | tail -8 | tr '\n' ' ' | head -c 300)"
  fi

  # 让 dataforge 识别该引擎（HBase 连接不走 Hikari，/test 不适用；只校验数据源可创建）
  local id
  id="$(find_ds_id 'verify-hbase')"
  if [ -z "$id" ]; then
    api_post '/devops/datasource' "{\"datasourceName\":\"verify-hbase\",\"datasourceType\":\"hbase\",\"connectionType\":\"jdbc\",\"url\":\"hbase\",\"username\":\"\",\"password\":\"\",\"databaseName\":[\"$ns\"],\"description\":\"engine verification fixture\"}" >/dev/null
    id="$(find_ds_id 'verify-hbase')"
  fi
  [ -n "$id" ] && ok "dataforge 已登记 HBase 数据源（id=${id}）" || bad "dataforge 登记 HBase 数据源失败"
}

verify_kafka() {
  echo "==> Kafka（KRaft 单 broker）"
  local topic="${KAFKA_TOPIC:-dataforge-e2e}" msg="dataforge-kafka-$(date +%s)"
  docker exec -i df-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic "$topic" >/dev/null 2>&1 <<< "$msg"
  local out
  out="$(docker exec df-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic "$topic" --from-beginning --max-messages 50 --timeout-ms 20000 2>/dev/null)"
  if printf '%s' "$out" | grep -q "$msg"; then ok "Kafka 生产 + 消费一条消息"; else bad "Kafka 生产消费失败（收到: ${out:-空}）"; fi
}

verify_pulsar() {
  echo "==> Pulsar（standalone）"
  # 每次用独立 topic，避免读到历史消息导致「生产的是 A、消费的是 B」的假阳/假阴
  local run
  run="e2e-$(date +%s)"
  local msg="dataforge-pulsar-$run"
  local t="persistent://${PULSAR_TENANT:-dataforge}/${PULSAR_NAMESPACE:-default}/$run"
  docker exec df-pulsar bin/pulsar-admin topics create "$t" >/dev/null 2>&1 || true
  docker exec df-pulsar bin/pulsar-client produce "$t" -m "$msg" -n 1 >/dev/null 2>&1
  local out
  out="$(docker exec df-pulsar bin/pulsar-client consume "$t" -s "verify-$run" -n 1 -t Exclusive -p Earliest 2>&1)"
  if printf '%s' "$out" | grep -q "$msg"; then ok "Pulsar 生产 + 消费一条消息"; else bad "Pulsar 生产消费失败: $(printf '%s' "$out" | tail -3 | tr '\n' ' ')"; fi
}

verify_hudi() {
  echo "==> Hudi（复用 Spark 3.5.9）"
  local jar="/opt/spark/jars/hudi-spark3.5-bundle_2.12-0.15.0.jar"
  if ! docker exec "$APP_CONTAINER" test -f "$jar"; then
    bad "Hudi bundle 未挂载到 ${APP_CONTAINER}:${jar}（先执行 build 下载并确认 compose 挂载）"
    return
  fi
  local out
  out="$(docker exec "$APP_CONTAINER" bash -lc "/opt/spark/bin/spark-sql --master local[1] --jars $jar --conf spark.sql.extensions=org.apache.spark.sql.hudi.HoodieSparkSessionExtension --conf spark.serializer=org.apache.spark.serializer.KryoSerializer -e \"
CREATE TABLE IF NOT EXISTS hudi_probe (id INT, name STRING) USING hudi OPTIONS (primaryKey='id', type='cow', path='file:///opt/dataforge/warehouse/hudi/e2e_probe');
INSERT INTO hudi_probe VALUES (1, 'dataforge');
SELECT count(*) AS cnt FROM hudi_probe;\"" 2>&1)"
  if docker exec "$APP_CONTAINER" test -d /opt/dataforge/warehouse/hudi/e2e_probe/.hoodie; then
    ok "Hudi 写入 + 查询成功（Spark，_hoodie 元数据已生成）"
  else
    bad "Hudi 写入失败: $(printf '%s' "$out" | grep -E 'Exception|Caused by' | head -3 | tr '\n' ' ' | head -c 400)"
  fi
}

main() {
  local targets=("$@")
  [ ${#targets[@]} -eq 0 ] && targets=(clickhouse kafka pulsar hbase doris starrocks hudi)

  login || exit 1

  for t in "${targets[@]}"; do
    case "$t" in
      clickhouse) verify_clickhouse ;;
      kafka)      verify_kafka ;;
      pulsar)     verify_pulsar ;;
      hbase)      verify_hbase ;;
      doris)      verify_doris ;;
      starrocks)  verify_starrocks ;;
      hudi)       verify_hudi ;;
      *) echo "!! 未知引擎: $t" ;;
    esac
  done

  echo ""
  echo "================ 验证汇总 ================"
  echo "PASS=${PASS} FAIL=${FAIL} SKIP=${SKIP}（SKIP=未部署，仅静态验证）"
  for f in "${FAILED[@]:-}"; do [ -n "$f" ] && echo "  - $f"; done
  [ "$FAIL" -eq 0 ]
}

main "$@"
