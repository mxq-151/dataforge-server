#!/bin/bash
# ============================================================
# Hive Metastore 2.3.9（Thrift 9083，PostgreSQL 后端）
#
# * 以 test_tenant 运行：与 Spark / DolphinScheduler 任务用户一致，避免 warehouse 属主错乱。
# * 启动前等待 init-hive.sh 把 schema 建好（supervisord 按 priority 启动但不等待完成）。
# * 版本与 Spark 3.5.9 内置 Hive 客户端（2.3.9）完全一致。
# ============================================================
set -u

export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export HIVE_HOME=/opt/hive
export HIVE_CONF_DIR=/opt/hive/conf
export HADOOP_HOME=/opt/hadoop
export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop

PG_BIN=/usr/lib/postgresql/14/bin
LOG_DIR=/opt/dataforge/hive/logs

mkdir -p "$LOG_DIR" /tmp/hive-scratch
chown -R test_tenant:test_tenant "$LOG_DIR" /tmp/hive-scratch 2>/dev/null || true

echo "[hms $(date +%H:%M:%S)] waiting for metastore schema ..."
for i in $(seq 1 150); do
  OK=$(su postgres -c "$PG_BIN/psql -d metastore -tAc \"SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='VERSION'\"" 2>/dev/null | tr -d '[:space:]')
  if [ "$OK" = "1" ]; then
    echo "[hms $(date +%H:%M:%S)] schema ready after ${i} checks, starting metastore on 9083"
    break
  fi
  sleep 2
done

exec setpriv --reuid=test_tenant --regid=test_tenant --init-groups \
  "$HIVE_HOME/bin/hive" --service metastore -p 9083
