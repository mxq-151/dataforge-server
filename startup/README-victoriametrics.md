# VictoriaMetrics 真实数据验证（Kafka / YARN）

本文记录把 VictoriaMetrics 接入 v1 指标查询、并用 Kafka 与 YARN 真实指标跑通
`POST /api/telemetry/v1/metrics/query` 的可复现步骤。

## 1. 端口与容器

| 容器 | 镜像 | 宿主端口 | 用途 |
| --- | --- | --- | --- |
| `df-victoriametrics` | `victoriametrics/victoria-metrics:v1.106.1` | `8428` | TSDB + PromQL API |
| `df-kafka` | `apache/kafka:3.9.0` | `9092` / `7071` | Kafka Broker，JMX exporter 走 7071 |
| `df-yarn-exporter` | `dataforge-allinone:1.0`（自带 python3） | `18090` | YARN RM/NM JSON → Prometheus |
| `dataforge-allinone` | 现有 | `8088` / `8042` / `18083` | YARN RM / NM；SHS JMX exporter（18083） |
| dataforge-server | 本地 jar | `8083` | v1 接口 |

Docker 网络：`dataforge-docker_default`。上述容器名可直接互相解析。

宿主机地址：

- VictoriaMetrics：`http://127.0.0.1:8428`（UI `/vmui`）
- Kafka JMX：`http://127.0.0.1:7071/metrics`
- YARN 导出器：`http://127.0.0.1:18090/metrics`
- Spark SHS JMX：`http://127.0.0.1:18083/metrics`（配置见
  [`spark-jmx/README.md`](engines/spark-jmx/README.md)）
- v1 接口：`http://127.0.0.1:8083/api/telemetry/v1/metrics/query`

> Docker Hub 直连超时，VM 镜像经 `docker.m.daocloud.io` 拉取后重打标签：
> `docker pull docker.m.daocloud.io/victoriametrics/victoria-metrics:v1.106.1`
> `docker tag docker.m.daocloud.io/victoriametrics/victoria-metrics:v1.106.1 victoriametrics/victoria-metrics:v1.106.1`

## 2. 启动 VictoriaMetrics

编排文件：[`../compose/docker-compose.observability.yml`](../compose/docker-compose.observability.yml)

```bash
cd /Users/mxq/Desktop/dataforge-server
docker compose -f startup/compose/docker-compose.observability.yml up -d
curl -s http://127.0.0.1:8428/health          # OK
```

- 持久卷：`dataforge-docker_df-vm-data` → `/victoria-metrics-data`
- 参数：`-retentionPeriod=1`、`-selfScrapeInterval=15s`
- 抓取配置：`startup/engines/victoriametrics/scrape.yml`，挂到 `/etc/vm/scrape.yml`
- 修改抓取配置后热加载：

```bash
curl -s -X POST http://127.0.0.1:8428/-/reload
```

## 3. Kafka JMX exporter

### 3.1 下载 agent（jar 不入库）

```bash
mkdir -p /Users/mxq/dataforge-jmx
curl -sSL -o /Users/mxq/dataforge-jmx/jmx_prometheus_javaagent.jar \
  https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar
```

规则文件：[`kafka-jmx/kafka.yml`](kafka-jmx/kafka.yml)。

### 3.2 compose 变更（仅 kafka 服务）

`startup/compose/docker-compose.engines.yml` 中 kafka：

```yaml
    ports:
      - "${KAFKA_PORT:-9092}:9092"
      - "${KAFKA_JMX_PORT:-7071}:7071"
    environment:
      KAFKA_OPTS: "-javaagent:/opt/jmx-exporter/jmx_prometheus_javaagent.jar=7071:/opt/jmx-conf/kafka.yml"
    volumes:
      - df-kafka-data:/var/lib/kafka/data
      - ${DATAFORGE_LOG_ROOT:-/Users/mxq/dataforge-logs}/kafka:/opt/kafka/logs
      - ${KAFKA_JMX_DIR:-/Users/mxq/dataforge-jmx}:/opt/jmx-exporter:ro
      - ../engines/kafka-jmx:/opt/jmx-conf:ro
```

重启（只重建 Kafka，不动 HBase / 其他引擎）：

```bash
docker compose -f startup/compose/docker-compose.engines.yml --profile lite up -d kafka
docker ps --filter name=df-kafka
curl -s http://127.0.0.1:7071/metrics | grep kafka_controller_activebrokercount
```

> 注意：`KAFKA_OPTS` 会被容器内所有 Kafka CLI 继承，直接跑 `kafka-topics.sh` /
> `kafka-producer-perf-test.sh` 会因 agent 二次绑定 7071 而失败。验证时先清空：
>
> ```bash
> docker exec -e KAFKA_OPTS= df-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
> ```

已确认指标：broker topic 流量（MessagesInPerSec / BytesInPerSec）、ReplicaManager、
KafkaRequestHandlerPool、Controller（ActiveBrokerCount 等）、RequestChannel、LogManager、JVM。
**producer/consumer 客户端指标不在 broker JMX 内**，需要客户端 JVM 或 kafka-exporter，本轮未接入。

## 4. YARN 指标

### 4.1 现状

`dataforge-allinone` 内已有真实 YARN（Hadoop 3.3.6，RM + NM）：

```bash
curl -s http://127.0.0.1:8088/ws/v1/cluster/metrics
curl -s http://127.0.0.1:8042/ws/v1/node/info
```

Hadoop 3.3.6 的 RM/NM 没有 Prometheus 端点（`/prometheus`、`?format=prometheus` 均 404），
因此没有重启 RM/NM，而是加一个只读导出器。

### 4.2 启动导出器

脚本：[`yarn-exporter/exporter.py`](yarn-exporter/exporter.py)，仅标准库。

```bash
docker run -d --name df-yarn-exporter \
  --network dataforge-docker_default --restart unless-stopped \
  -p 18090:18090 \
  -v /Users/mxq/Desktop/dataforge-server/startup/engines/yarn-exporter:/opt/yarn-exporter:ro \
  -e YARN_RM_URL=http://dataforge-allinone:8088 \
  -e YARN_NM_URL=http://dataforge-allinone:8042 \
  --entrypoint python3 dataforge-allinone:1.0 /opt/yarn-exporter/exporter.py

curl -s http://127.0.0.1:18090/metrics | head
```

覆盖：应用数/容器数、内存与 VCore 分配/可用、节点状态、NM 健康与已分配资源。

## 5. VM 抓取配置与标签

`startup/engines/victoriametrics/scrape.yml`：

```yaml
scrape_configs:
  - job_name: kafka-jmx
    static_configs:
      - targets: ["df-kafka:7071"]
        labels: {service_type: kafka, component_id: kafka.broker, host_id: cluster}
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: "kafka_controller_.*"
        target_label: component_id
        replacement: kafka.controller

  - job_name: yarn
    static_configs:
      - targets: ["df-yarn-exporter:18090"]
        labels: {service_type: yarn, host_id: cluster}
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: "yarn_nodemanager_.*"
        target_label: component_id
        replacement: yarn.nodemanager
      - source_labels: [__name__]
        regex: "yarn_cluster_.*"
        target_label: component_id
        replacement: yarn.resourcemanager

  # Spark History Server 进程自身（jmx_exporter，:18083）
  - job_name: spark-shs
    static_configs:
      - targets: ["dataforge-allinone:18083"]
        labels: {service_type: spark, component_id: spark.shs, host_id: cluster}
```

标签必须与 v1 的 target 对齐，否则 `PromqlSelectorBuilder` 注入的
`service_type` / `component_id` / `host_id` 匹配不到数据。

## 6. dataforge-server 配置与启动

```bash
cat > /tmp/telemetry-vm.yml <<'EOF'
telemetry:
  backends:
    victoriametrics:
      - id: vm-local
        base-url: http://127.0.0.1:8428
        path-prefix: /api/v1
        limits:
          timeout-ms: 5000
EOF

mvn -DskipTests package
SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/dataforge' \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=123456 \
java -jar target/dataforge-server-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:/tmp/telemetry-vm.yml
```

登录（密码 AES/ECB/PKCS5，key `Bigdata2026AesK@`，明文 + 13 位毫秒时间戳）：

```bash
TS=$(python3 -c 'import time;print(int(time.time()*1000))')
KEYHEX=$(printf 'Bigdata2026AesK@' | xxd -p)
ENC=$(printf "123456$TS" | openssl enc -aes-128-ecb -K "$KEYHEX" -nosalt | base64)
TOKEN=$(curl -s -X POST http://127.0.0.1:8083/api/system/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ENC\"}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["token"])')
```

## 7. 真实查询（curl 原文）

### 7.1 catalog

```bash
curl -s -X POST http://127.0.0.1:8083/api/telemetry/v1/metrics/query \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"mode":"catalog","target":{"serviceType":"kafka","componentId":"kafka.broker","hostId":"cluster"}}'
```

返回 `components[0].metrics`：`kafka_server_broker_topic_metrics_messagesinpersec_total`、
`...bytesinpersec_total`、`kafka_network_request_channel_requestqueuesize_value`、
`kafka_server_replicamanager_produceridcount_value`，后端 `victoriametrics`（priority 10）。

YARN 把 `componentId` 换成 `yarn.resourcemanager` / `yarn.nodemanager`。

### 7.2 gauge 走 last（instant）

```bash
curl -s -X POST http://127.0.0.1:8083/api/telemetry/v1/metrics/query \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"mode":"instant","target":{"serviceType":"kafka","componentId":"kafka.controller","hostId":"cluster"},"metric":"kafka_controller_activebrokercount"}'
# data.series[0].samples = [{"timestamp":"2026-11-15T13:30:27Z","value":1.0}]
```

```bash
curl -s -X POST http://127.0.0.1:8083/api/telemetry/v1/metrics/query \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"mode":"instant","target":{"serviceType":"yarn","componentId":"yarn.resourcemanager","hostId":"cluster"},"metric":"yarn_cluster_nodes_active"}'
# value = 1.0
```

### 7.3 counter 走 rate（range）

```bash
S=$(date -u -v-6M +%Y-%m-%dT%H:%M:%SZ); E=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST http://127.0.0.1:8083/api/telemetry/v1/metrics/query \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d "{\"mode\":\"range\",\"target\":{\"serviceType\":\"kafka\",\"componentId\":\"kafka.broker\",\"hostId\":\"cluster\"},\"metric\":\"kafka_server_broker_topic_metrics_messagesinpersec_total\",\"timeRange\":{\"start\":\"$S\",\"end\":\"$E\"},\"step\":\"30s\",\"rate\":true}"
```

`rate=true` 时 `PromqlSelectorBuilder` 生成
`rate(kafka_server_broker_topic_metrics_messagesinpersec_total{service_type="kafka",component_id="kafka.broker"}[30s])`。
实测写入 500 条消息后返回 13 个点，尾部 `8.333333333333334`（500/60s）。

不传 `rate` 时仍是原始 counter（9 个点，值 500）。

### 7.4 参数错误

```bash
# instant + rate
-d '{"mode":"instant",...,"rate":true}'
# {"code":400,"message":"rate 只允许用于 range 查询，当前 mode=instant"}

# gauge + rate
-d '{"mode":"range",...,"metric":"kafka_controller_activebrokercount","rate":true}'
# {"code":400,"message":"rate 只允许用于 counter 指标，当前类型=gauge"}

# 不存在的指标
-d '{"mode":"instant",...,"metric":"no_such_metric_xyz"}'
# {"code":400,"message":"未知指标: no_such_metric_xyz"}
```

## 8. rate 的窗口与 step

- 只有 `counter` + `range` 允许 `rate=true`；`instant`、`gauge`、`histogram`、`summary` 一律
  返回 `INVALID_ARGUMENT` / 400，不静默忽略。
- 窗口取 `[step]`：请求 `step=30s` → `rate(metric[30s])`。
- PromQL 要求查询步长能被窗口整除；当前窗口与 step 相同，条件恒成立。实现保留
  `step % window == 0` 校验，未来引入独立窗口时不会自动取整。
- 缺省 `rate` 不改变任何既有行为：counter 原样返回原始样本。

## 9. 已知限制

- Kafka producer/consumer 客户端指标不在 broker JMX 中；consumer lag 需额外 `kafka-exporter`。
- Kafka 容器内执行 CLI 前必须 `-e KAFKA_OPTS=`，否则 javaagent 会二次绑定 7071。
- YARN RM/NM 无原生 Prometheus 端点，指标经 `df-yarn-exporter` 转换。
- VictoriaMetrics 默认 `-search.latencyOffset=30s`，刚写入的样本约 30 秒后才可被 instant 查询看到；
  scrape 间隔 1 分钟，验证时需等待一个抓取周期。
