#!/bin/bash
# DolphinScheduler standalone 启动包装(单进程内 api/master/worker/alert + PostgreSQL 库)
# 数据库: 容器内同一 PostgreSQL 实例,库名 dolphinscheduler
# (由 init-dataforge.sh 建库并导入官方 PG schema + 补充初始化数据)
set -u
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export SPARK_HOME="${SPARK_HOME:-/opt/spark}"
export DOLPHINSCHEDULER_HOME="${DOLPHINSCHEDULER_HOME:-/opt/dolphinscheduler}"

PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-123456}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
DS_DB=dolphinscheduler

# 等待 PostgreSQL 就绪且 dolphinscheduler 库 schema 已初始化(init-dataforge.sh 完成)
# 避免 DS 在 PG 建库/建表前启动导致连接失败;令牌/todo 由 init 准备
for i in $(seq 1 90); do
  if PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$DS_DB" \
      -tAc "SELECT 1 FROM t_ds_user" >/dev/null 2>&1; then
    echo "[ds] postgres dolphinscheduler db ready after ${i} checks"
    break
  fi
  sleep 2
done

# 覆盖 Spring Boot 数据源为 PostgreSQL(替代官方默认及旧的 H2 file 库)
export SPRING_DATASOURCE_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${DS_DB}"
export SPRING_DATASOURCE_USERNAME="$PG_USER"
export SPRING_DATASOURCE_PASSWORD="$PG_PASSWORD"
export SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver

echo "[ds] starting dolphinscheduler standalone server (sql: postgresql://${PG_HOST}:${PG_PORT}/${DS_DB}) ..."
cd "$DOLPHINSCHEDULER_HOME" || exit 1
# 重启/残留时先停旧实例,避免端口冲突
bash bin/dolphinscheduler-daemon.sh stop standalone-server >/dev/null 2>&1 || true
bash bin/dolphinscheduler-daemon.sh start standalone-server

# 轮询等待 java 进程就绪(首启需初始化 PG 连接池 + 4g 堆,可能超过 60s)
for i in $(seq 1 40); do
  if pgrep -f "org.apache.dolphinscheduler.StandaloneServer" >/dev/null 2>&1; then
    break
  fi
  sleep 5
done

# 进程退出时本脚本也退出(supervisord 自动重启)
# 注意:匹配 java 真实进程名 org.apache.dolphinscheduler.StandaloneServer,
# 不能匹配 dolphinscheduler-standalone-server(守护脚本名,永远匹配不到)
while pgrep -f "org.apache.dolphinscheduler.StandaloneServer" >/dev/null 2>&1; do
  sleep 15
done
echo "[ds] standalone server process exited"
exit 1