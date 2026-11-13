#!/bin/bash
# ============================================================
# Hive Metastore schema 初始化（幂等，可重复执行）
#
# 选型：PostgreSQL（容器内 5432，库 metastore，账号 hive/hive）
#   * Derby 是嵌入式单进程库，Hive 元数据会被 Spark / Kyuubi 引擎 / beeline 三个 JVM
#     同时访问，Derby 会因文件锁互相冲突；PostgreSQL 可并发共享。
#   * PostgreSQL 数据已在 df-pgdata 命名卷上持久化，容器重建不丢。
#   * schema 由 Hive 2.3.9 官方 schematool 初始化（hive-schema-2.3.0.postgres.sql），
#     与 Spark 3.5.9 内置的 Hive 2.3.9 客户端、/opt/hive 的 beeline 版本一致。
#
# 幂等策略：
#   1) 角色/库用 pg_roles / pg_database 判断后再建；
#   2) schema 用 information_schema 判断 VERSION 表是否存在，已存在则跳过 schematool。
# ============================================================
set -u

PG_BIN=/usr/lib/postgresql/14/bin
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export HIVE_HOME=/opt/hive
export HIVE_CONF_DIR=/opt/hive/conf
export HADOOP_HOME=/opt/hadoop
export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop

log() { echo "[hive-init $(date +%H:%M:%S)] $*"; }

# 1) 等 PostgreSQL（supervisord 并发启动 postgres program）
for i in $(seq 1 90); do
  if su postgres -c "$PG_BIN/pg_isready -h 127.0.0.1 -p 5432" >/dev/null 2>&1; then
    log "postgres ready after ${i} checks"
    break
  fi
  sleep 2
done

# 2) 角色 hive
if ! su postgres -c "$PG_BIN/psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='hive'\"" | grep -q 1; then
  log "create role: hive"
  su postgres -c "$PG_BIN/psql -c \"CREATE ROLE hive LOGIN PASSWORD 'hive'\"" >/dev/null 2>&1 \
    || log "WARN: create role hive failed"
else
  log "role hive already exists"
fi

# 3) 库 metastore
if ! su postgres -c "$PG_BIN/psql -tAc \"SELECT 1 FROM pg_database WHERE datname='metastore'\"" | grep -q 1; then
  log "create database: metastore"
  su postgres -c "$PG_BIN/createdb -O hive metastore" || log "WARN: createdb metastore failed"
else
  log "database metastore already exists"
fi
su postgres -c "$PG_BIN/psql -d metastore -c 'GRANT ALL ON SCHEMA public TO hive;'" >/dev/null 2>&1 || true

# 4) Hive schema（VERSION 表存在即认为已初始化）
HAS_SCHEMA=$(su postgres -c "$PG_BIN/psql -d metastore -tAc \"SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='VERSION'\"" 2>/dev/null | tr -d '[:space:]')
if [ "$HAS_SCHEMA" != "1" ]; then
  log "init hive metastore schema (schematool -dbType postgres)"
  "$HIVE_HOME/bin/schematool" -dbType postgres -initSchema || {
    log "ERROR: schematool 初始化失败"; exit 1; }
  log "schema initialized"
else
  log "metastore schema already present, skip"
fi

log "done"
exit 0
