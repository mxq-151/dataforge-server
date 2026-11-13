#!/bin/bash
# ============================================================
# Spark History Server（SHS）前台启动脚本
#
# 为什么需要它：Spark on YARN 作业结束后，driver/executor 的 JVM 与 Spark UI(4040) 都消失，
# /api/runtime/** 的 live 源（经 RM trackingUrl 反查 4040）随即失效。要让「作业结束后
# 还能查 executor 指标 / 配置」，必须：
#   1) 作业侧写事件日志    spark.eventLog.enabled=true + spark.eventLog.dir
#   2) SHS 读同一目录      spark.history.fs.logDirectory
#   3) /api/runtime/sources 里 spark-shs-runtime-default（mode=history）指向 SHS 的 18080
#
# 事件日志目录放在 df-data 卷（/opt/dataforge/spark-events），容器重建后历史仍在。
# 写事件日志的是作业提交用户（test_tenant），目录属主由 docker-entrypoint.sh 每次启动校正。
#
# 环境变量：
#   SPARK_SHS_ENABLED=true|false   默认跟随 YARN_ENABLED（YARN 不开也没必要开 SHS）
#   SPARK_EVENT_LOG_DIR=/opt/dataforge/spark-events
#   SPARK_SHS_PORT=18080
# ============================================================
set -u

export SPARK_HOME="${SPARK_HOME:-/opt/spark}"
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-/opt/hadoop/etc/hadoop}"
export YARN_CONF_DIR="$HADOOP_CONF_DIR"

EVENT_LOG_DIR="${SPARK_EVENT_LOG_DIR:-/opt/dataforge/spark-events}"
SHS_PORT="${SPARK_SHS_PORT:-18080}"
LOG_DIR=/var/log/dataforge

log() { echo "[spark-shs $(date +%H:%M:%S)] $*"; }

if [ "${SPARK_SHS_ENABLED:-${YARN_ENABLED:-true}}" = "false" ]; then
  log "SPARK_SHS_ENABLED=false（或 YARN_ENABLED=false），跳过 History Server"
  exit 0
fi

mkdir -p "$EVENT_LOG_DIR" "$LOG_DIR" 2>/dev/null || true

log "启动 Spark History Server: port=${SHS_PORT} eventLog=${EVENT_LOG_DIR}"
export SPARK_HISTORY_OPTS="\
-Dspark.history.fs.logDirectory=file://${EVENT_LOG_DIR} \
-Dspark.history.ui.port=${SHS_PORT} \
-Dspark.history.retainedApplications=50 \
-Dspark.history.fs.cleaner.enabled=false"

# spark-class 会 exec java，supervisord 的 SIGTERM 可直接到达 JVM（触发优雅关闭）
exec "$SPARK_HOME/bin/spark-class" org.apache.spark.deploy.history.HistoryServer
