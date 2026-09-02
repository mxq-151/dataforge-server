#!/bin/bash
# dataforge-server 启动(连接容器内 PostgreSQL)
set -u
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"

exec "${JAVA_HOME}/bin/java" -jar /opt/dataforge/dataforge-server.jar \
  --server.port=8082 \
  '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/dataforge?useUnicode=true&characterEncoding=utf-8' \
  --spring.datasource.driver-class-name=org.postgresql.Driver \
  --spring.datasource.username=postgres \
  --spring.datasource.password=123456 \
  --engine.spark.home=/opt/spark \
  --engine.jar.base-path=/opt/dataforge/jars \
  '--mybatis-plus.mapper-locations=classpath:mapper/common/*.xml,classpath:mapper/postgresql/*.xml'