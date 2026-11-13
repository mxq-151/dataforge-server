#!/bin/bash
# ============================================================
# Spark 环境变量（spark-submit / spark-sql / SHS 共用）
# 构建时写入镜像 /opt/spark/conf/spark-env.sh
#
# 作用：让 `spark-submit --master yarn` 能找到 YARN 的配置（core-site/yarn-site），
# 从而发现 RM、使用 Spark 自带的 hadoop-client（不依赖 /opt/hadoop/share 的 jar）。
# 不设置的话 spark-submit --master yarn 会报:
#   ... Cannot initialize Cluster. Please check your configuration for
#   mapreduce.framework.name and the correspond server addresses.
# ============================================================
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-/opt/hadoop/etc/hadoop}"
export YARN_CONF_DIR="$HADOOP_CONF_DIR"

# 容器内 localhost 优先解析到 ::1 时 Spark 驱动连 RM 会超时
export SPARK_SUBMIT_OPTS="${SPARK_SUBMIT_OPTS:-} -Djava.net.preferIPv4Stack=true"
