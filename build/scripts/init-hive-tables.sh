#!/bin/bash
# ============================================================
# 在 Hive Metastore 里注册 TPC-DS 25 张表（幂等）
#
# * DDL: /opt/dataforge/sql/tpcds-ddl.sql（由镜像同步，EXTERNAL 表，只注册元数据）
# * 数据: 镜像内 /opt/tpcds，entrypoint 首次启动播种到可写 warehouse
#         /opt/dataforge/warehouse/tpcds（df-data 卷），不再依赖宿主机 bind mount
# * 用 Spark SQL 执行 DDL：与 Spark 内置 Hive 2.3.9 客户端、HMS 2.3.9 版本一致
# * 关闭写合规扩展/事件日志：建外表是元数据操作，不走业务权限判定
#
# 旧路径修正（本脚本的关键幂等逻辑）：
#   metastore 里表记录持久化在 PostgreSQL（df-pgdata 卷）。若之前按 bind mount 建过表
#   （LOCATION=/data/tpcds/*），容器升级后这些记录仍指向旧路径，查询会"有表无数据"。
#   因此这里除比较表数量外，还校验 DB 与各表 SD 的 LOCATION 是否落在新 warehouse 下；
#   只要发现旧路径，就 DROP DATABASE test_db CASCADE（EXTERNAL 表只删元数据，
#   不删任何数据文件）后按 DDL 重建，保证 LOCATION 与新路径一致。
# ============================================================
set -u

PG_BIN=/usr/lib/postgresql/14/bin
DDL=/opt/dataforge/sql/tpcds-ddl.sql
SPARK_HOME="${SPARK_HOME:-/opt/spark}"
HMS_HOST=127.0.0.1
HMS_PORT=9083
EXPECTED_TABLES=25
WAREHOUSE_ROOT=/opt/dataforge/warehouse/tpcds

log() { echo "[hive-tables $(date +%H:%M:%S)] $*"; }

[ -f "$DDL" ] || { log "ERROR: DDL 不存在: $DDL"; exit 1; }

# 1) 等 Metastore 端口
log "waiting for hive metastore ${HMS_HOST}:${HMS_PORT} ..."
for i in $(seq 1 120); do
  if nc -z "$HMS_HOST" "$HMS_PORT" >/dev/null 2>&1; then
    log "metastore ready after ${i} checks"
    break
  fi
  sleep 2
done
nc -z "$HMS_HOST" "$HMS_PORT" >/dev/null 2>&1 || { log "WARN: metastore 未就绪，跳过建表"; exit 0; }

# 2) 直接查 metastore 库，避免每次启动都拉起 Spark
pg_scalar() {
  local sql_file; sql_file=$(mktemp)
  printf '%s\n' "$1" > "$sql_file"
  # mktemp 默认 0600 且属主 root，su postgres 读不到会导致计数为空、误判为 0 而重复执行 DDL
  chmod 644 "$sql_file"
  su postgres -c "$PG_BIN/psql -d metastore -tA -f $sql_file" 2>/dev/null | tr -d '[:space:]'
  rm -f "$sql_file"
}

CNT=$(pg_scalar "SELECT count(*)
FROM \"TBLS\" t JOIN \"DBS\" d ON t.\"DB_ID\" = d.\"DB_ID\"
WHERE d.\"NAME\" = 'test_db';")
# Hive 会把 LOCATION 规范化为 file:/opt/...（单斜杠），因此匹配新根目录时不带前导斜杠，
# 否则每次启动都会误判为"旧路径"而反复 DROP/重建。
STALE=$(pg_scalar "SELECT count(*)
FROM \"TBLS\" t
JOIN \"DBS\" d ON t.\"DB_ID\" = d.\"DB_ID\"
JOIN \"SDS\" s ON t.\"SD_ID\" = s.\"SD_ID\"
WHERE d.\"NAME\" = 'test_db' AND s.\"LOCATION\" NOT LIKE '%${WAREHOUSE_ROOT}/%';")
DBLOC=$(pg_scalar "SELECT coalesce(\"DB_LOCATION_URI\", '') FROM \"DBS\" WHERE \"NAME\" = 'test_db';")

log "registered tables in test_db: ${CNT:-0}, stale-location tables: ${STALE:-0}, db location: ${DBLOC:-<none>}"

NEED_RESET=0
[ "${STALE:-0}" -gt 0 ] && NEED_RESET=1
case "$DBLOC" in
  *"$WAREHOUSE_ROOT"*) ;;
  "") ;;
  *) NEED_RESET=1 ;;
esac

spark_sql() {
  # 以 test_tenant 运行，保持 warehouse 属主一致
  su -s /bin/bash test_tenant -c "cd /tmp && HOME=/home/test_tenant $SPARK_HOME/bin/spark-sql \
    --master 'local[2]' --driver-memory 1g \
    --conf spark.sql.catalogImplementation=hive \
    --conf spark.hadoop.hive.metastore.uris=thrift://${HMS_HOST}:${HMS_PORT} \
    --conf spark.sql.warehouse.dir=file://${WAREHOUSE_ROOT} \
    --conf spark.sql.extensions= \
    --conf spark.sql.queryExecutionListeners= \
    --conf spark.write.compliance.enabled=false \
    --conf spark.eventLog.enabled=false \
    --conf spark.ui.enabled=false \
    $*"
}

# 3) 旧 LOCATION 修正：DROP DATABASE CASCADE 只删元数据（全是 EXTERNAL 表），数据文件保留
if [ "$NEED_RESET" = "1" ]; then
  log "检测到旧 warehouse 路径（旧 LOCATION / 旧 DB 位置），重建 test_db 元数据 ..."
  spark_sql "-e 'DROP DATABASE IF EXISTS test_db CASCADE'" \
    || { log "ERROR: 删除旧 test_db 元数据失败"; exit 1; }
  CNT=0
fi

if [ "${CNT:-0}" -ge "$EXPECTED_TABLES" ]; then
  log "all ${EXPECTED_TABLES} tables already registered with correct locations, skip"
  exit 0
fi

# 4) 执行 DDL（以 test_tenant 运行，保持 warehouse 属主一致）
log "registering TPC-DS tables via spark-sql ..."
spark_sql "-f $DDL" || { log "ERROR: spark-sql 执行 DDL 失败"; exit 1; }

# 5) 结果校验
CNT2=$(pg_scalar "SELECT count(*)
FROM \"TBLS\" t JOIN \"DBS\" d ON t.\"DB_ID\" = d.\"DB_ID\"
WHERE d.\"NAME\" = 'test_db';")
log "done, registered tables: ${CNT2:-0}"
exit 0
