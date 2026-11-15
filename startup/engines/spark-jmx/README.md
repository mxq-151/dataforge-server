# Spark History Server JMX exporter

与 Kafka `:7071`、YARN `:18090` 同一模式：用 `jmx_prometheus_javaagent`
作为 `-javaagent` 挂到 SHS 进程，暴露 Prometheus 文本端点（默认 `:18083`），
再由 VictoriaMetrics 以 `service_type=spark` 抓取。

## 1. 准备 jar（不入库）

```bash
mkdir -p ~/dataforge-jmx
curl -fL -o ~/dataforge-jmx/jmx_prometheus_javaagent.jar \
  https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar
```

仓库只保留规则文件 [spark-shs.yml](spark-shs.yml)。宿主机目录通过
`SPARK_JMX_DIR`（默认 `~/dataforge-jmx`）挂载到容器 `/opt/spark-jmx`。

## 2. 挂载与启动

主容器编排 [docker-compose.yml](../../compose/docker-compose.yml) 已包含：

```yaml
ports:
  - "${SPARK_JMX_PORT:-18083}:18083"
environment:
  SPARK_JMX_ENABLED: ${SPARK_JMX_ENABLED:-true}
  SPARK_JMX_PORT: ${SPARK_JMX_PORT:-18083}
  SPARK_JMX_JAR: /opt/spark-jmx/jmx_prometheus_javaagent.jar
  SPARK_JMX_CONFIG: /opt/spark-jmx-conf/spark-shs.yml
volumes:
  - ${SPARK_JMX_DIR:-/Users/mxq/dataforge-jmx}:/opt/spark-jmx:ro
  - ../engines/spark-jmx:/opt/spark-jmx-conf:ro
```

`build/scripts/run-spark-shs.sh` 检测到 `SPARK_JMX_ENABLED=true` 且 jar / 规则文件
都存在时，自动追加：

```
-javaagent:/opt/spark-jmx/jmx_prometheus_javaagent.jar=18083:/opt/spark-jmx-conf/spark-shs.yml
```

jar 或规则文件缺失时只打印 `WARN`，SHS 仍正常启动，不会被指标采集阻塞。

## 3. 重启

首次挂载或修改规则后重启主容器：

```bash
cd startup/compose
docker compose up -d --force-recreate dataforge
```

只重启 SHS 进程（不重建容器）：

```bash
docker exec dataforge-allinone supervisorctl restart spark-shs
```

## 4. 验证

```bash
# 容器内直接看 exporter
docker exec dataforge-allinone curl -s http://127.0.0.1:18083/metrics | head

# 从宿主机看（compose 已映射 18083）
curl -s http://127.0.0.1:18083/metrics | grep '^spark_shs_'
```

VictoriaMetrics 侧的抓取配置见
[scrape.yml](../victoriametrics/scrape.yml) 的 `spark-shs` job。

## 5. 镜像内烘焙（可选）

如果不想每次挂载宿主机目录，可在构建镜像时把 jar 放进
`build/dist/jmx-exporter/jmx_prometheus_javaagent.jar`，并在 Dockerfile 中复制到
`/opt/spark-jmx/jmx_prometheus_javaagent.jar`；规则文件仍建议随仓库挂载，便于修改。

## 边界

SHS 只读事件日志，不创建 SparkContext，也没有 driver/executor 的运行时时间序列。
本 exporter 只覆盖 SHS 进程自身的 JVM 与 Spark MetricsSystem；作业级 executor 内存、
stage 耗时、shuffle 量属于缺口，见 README 与设计 v2 的 Spark 章节。
