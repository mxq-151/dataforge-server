#!/bin/bash
# dataforge-server 启动(连接容器内 PostgreSQL)
set -u
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"

# YARN / Spark History Server / DolphinScheduler 的访问地址由 supervisord 注入（默认指向本容器）：
#   YARN_RM_URL=http://127.0.0.1:8088   SPARK_SHS_URL=http://127.0.0.1:18080
#   DS_URL=http://127.0.0.1:12345/dolphinscheduler
# 这些变量被 application.yml 的 logs.sources.*.base-url / scheduler.dolphinscheduler.base-url
# 与 runtime.sources.*.yarn-rm-url 引用。
# YARN_ENABLED=false 时清空 YARN 相关地址，让 /api/logs/sources 如实显示「未配置 base-url」
# 而不是显示可用却连不上。
if [ "${YARN_ENABLED:-true}" = "false" ]; then
  export YARN_RM_URL=""
  export SPARK_SHS_URL=""
fi

# DolphinScheduler 访问令牌：不在仓库/镜像里写死，启动时从 DS 自己的库读取
# （t_ds_access_token 由 build/sql/dolphinscheduler-init-data.sql 播种）。
# 显式设置 DS_TOKEN 时以其为准。
# 注意要重试：supervisord 是并行拉起各组件的，本脚本执行时 PostgreSQL 可能还没完成监听。
lookup_ds_token_once() {
  PGPASSWORD="${PG_PASSWORD:-123456}" psql -h 127.0.0.1 -p 5432 -U postgres \
    -d dolphinscheduler -tAc 'SELECT token FROM t_ds_access_token ORDER BY id LIMIT 1' 2>/dev/null \
    | tr -d '[:space:]'
}

if [ -z "${DS_TOKEN:-}" ]; then
  for _i in $(seq 1 20); do
    DS_TOKEN="$(lookup_ds_token_once)"
    [ -n "$DS_TOKEN" ] && break
    sleep 3
  done
  export DS_TOKEN
  if [ -z "$DS_TOKEN" ]; then
    echo "[run-dataforge] WARN: 未能从 dolphinscheduler 库读到 t_ds_access_token，DS 日志源将不可用" >&2
  fi
fi

# Kyuubi REST 前端地址：与 JDBC/Thrift(10009) 分离，默认监听到 10099。
# 数据源 URL 仍保持 jdbc:hive2://...:10009（beeline/JDBC/测试连接不受影响），
# KyuubiExecutionStrategy 的 REST 调用通过本参数改走 10099。
KYUUBI_REST_BASE_URL="${KYUUBI_REST_BASE_URL:-http://127.0.0.1:10099}"

exec "${JAVA_HOME}/bin/java" -jar /opt/dataforge/dataforge-server.jar \
  --server.port=8082 \
  '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/dataforge?useUnicode=true&characterEncoding=utf-8' \
  --spring.datasource.driver-class-name=org.postgresql.Driver \
  --spring.datasource.username=postgres \
  --spring.datasource.password=123456 \
  --engine.spark.home=/opt/spark \
  --engine.kyuubi.rest-base-url="${KYUUBI_REST_BASE_URL}" \
  --engine.jar.base-path=/opt/dataforge/jars \
  '--mybatis-plus.mapper-locations=classpath:mapper/common/*.xml,classpath:mapper/postgresql/*.xml'
