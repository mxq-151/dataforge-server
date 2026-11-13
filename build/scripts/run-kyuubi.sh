#!/bin/bash
# ============================================================
# Apache Kyuubi 1.9.1 Server（Thrift/JDBC 10009，Spark SQL 引擎）
#
# * 以 test_tenant 运行：引擎 JVM 由 Kyuubi 以同一用户拉起，warehouse 属主一致。
# * 等待 Hive Metastore 9083 就绪后再启动（引擎首次连接即需要 metastore）。
# * 版本：Kyuubi 1.9.1（为 Spark 3.5.1 构建，兼容 3.5.x）+ Spark 3.5.9 + Hive 2.3.9。
# ============================================================
set -u

export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export KYUUBI_HOME=/opt/kyuubi
export KYUUBI_CONF_DIR=/opt/kyuubi/conf
export SPARK_HOME=/opt/spark
export HADOOP_HOME=/opt/hadoop
export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
export YARN_CONF_DIR=/opt/hadoop/etc/hadoop
export KYUUBI_LOG_DIR=/opt/dataforge/kyuubi/logs
# 引擎工作目录（spark-submit 的临时 conf/jar 落地处）必须可写；不放在 /opt/kyuubi 下
export KYUUBI_WORK_DIR_ROOT=/opt/dataforge/kyuubi/work
export KYUUBI_JAVA_OPTS="${KYUUBI_JAVA_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m}"

mkdir -p "$KYUUBI_LOG_DIR" /opt/dataforge/kyuubi/work
chown -R test_tenant:test_tenant /opt/dataforge/kyuubi 2>/dev/null || true

echo "[kyuubi $(date +%H:%M:%S)] waiting for hive metastore 127.0.0.1:9083 ..."
for i in $(seq 1 120); do
  if nc -z 127.0.0.1 9083 >/dev/null 2>&1; then
    echo "[kyuubi $(date +%H:%M:%S)] metastore ready after ${i} checks, starting server on 10009"
    break
  fi
  sleep 2
done

exec setpriv --reuid=test_tenant --regid=test_tenant --init-groups \
  "$KYUUBI_HOME/bin/kyuubi" run
