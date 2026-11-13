#!/bin/bash
# ============================================================
# Hadoop 环境变量（RM / NM / yarn CLI 共用）
#
# 由 /opt/hadoop/bin/yarn、hadoop 脚本自动 source（HADOOP_CONF_DIR 内同名文件）。
# 这里只放**进程级**变量：JDK、日志/pid 目录、堆上限。
# 集群级参数（资源配额、目录、日志聚合）在 yarn-site.xml / core-site.xml。
#
# 堆上限刻意压小：容器默认 mem_limit 8g，RM+NM 各 512m 足够单节点开发使用，
# 需要更大时用环境变量 YARN_RM_HEAPSIZE / YARN_NM_HEAPSIZE 覆盖。
# ============================================================
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export HADOOP_HOME="${HADOOP_HOME:-/opt/hadoop}"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-/opt/hadoop/etc/hadoop}"
export HADOOP_LOG_DIR="${HADOOP_LOG_DIR:-/var/log/dataforge/yarn}"
export HADOOP_PID_DIR="${HADOOP_PID_DIR:-/var/lib/dataforge-yarn/pids}"
export HADOOP_IDENT_STRING="${HADOOP_IDENT_STRING:-dataforge-yarn}"

# 单个 YARN 守护进程的 JVM 堆（MB）
export YARN_RESOURCEMANAGER_HEAPSIZE="${YARN_RESOURCEMANAGER_HEAPSIZE:-512}"
export YARN_NODEMANAGER_HEAPSIZE="${YARN_NODEMANAGER_HEAPSIZE:-512}"

# 强制 IPv4：容器内 localhost 优先解析到 ::1 时，RM 绑定 0.0.0.0 会导致
# 客户端(RM/NM 互相注册、yarn CLI、dataforge-server 的 REST 调用)连不上。
#
# JDK17 强封装：Hadoop 3.3.x 的 Guice/cglib 在启动时用反射调 ClassLoader.defineClass，
# 不 open java.lang 会直接 InaccessibleObjectException 退出（DS 的 jvm_args_env.sh 同理）。
export HADOOP_OPTS="${HADOOP_OPTS:-} -Djava.net.preferIPv4Stack=true \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/java.text=ALL-UNNAMED \
--add-opens=java.base/java.security=ALL-UNNAMED \
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
