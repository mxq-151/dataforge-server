# ============================================================
# dataforge All-in-One 镜像
# 包含: dataforge-server + JDK17 + PostgreSQL14 + Spark3.5.9(local)
#       + DolphinScheduler3.4.1(standalone)
#       + Marquez0.51.1(源码自建 api + web)
#
# 构建(需要代理时):
#   docker build --network=host \
#     --build-arg BUILD_HTTP_PROXY=http://127.0.0.1:7890 \
#     --build-arg BUILD_HTTPS_PROXY=http://127.0.0.1:7890 \
#     -t dataforge-allinone:1.0 .
# ============================================================

# ---------- Stage 1: 构建 dataforge-server ----------
FROM maven:3.9-eclipse-temurin-17 AS app-builder
ARG BUILD_HTTP_PROXY=
ARG BUILD_HTTPS_PROXY=
ENV http_proxy=${BUILD_HTTP_PROXY} \
    https_proxy=${BUILD_HTTPS_PROXY} \
    HTTP_PROXY=${BUILD_HTTP_PROXY} \
    HTTPS_PROXY=${BUILD_HTTPS_PROXY} \
    no_proxy=127.0.0.1,localhost \
    NO_PROXY=127.0.0.1,localhost
WORKDIR /build
# 构建根=本目录(context=dataforge-server);只复制 maven 构建所需,
# 避免把 dist/(数百MB发行包) 等多余文件带入构建 stage
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests -f pom.xml package

# ---------- Stage 2: Marquez API 0.51.1(源码构建,不依赖官方镜像) ----------
FROM gradle:8.7-jdk17 AS marquez-builder
ARG BUILD_HTTP_PROXY=
ARG BUILD_HTTPS_PROXY=
ENV http_proxy=${BUILD_HTTP_PROXY} \
    https_proxy=${BUILD_HTTPS_PROXY} \
    HTTP_PROXY=${BUILD_HTTP_PROXY} \
    HTTPS_PROXY=${BUILD_HTTPS_PROXY} \
    no_proxy=127.0.0.1,localhost \
    NO_PROXY=127.0.0.1,localhost
# 源码包已预下载到本地 build/dist/(见 build.sh ensure_dist),构建不再联网下载
COPY build/dist/marquez-0.51.1.tar.gz /tmp/marquez.tar.gz
RUN mkdir -p /opt/marquez-src \
 && tar xzf /tmp/marquez.tar.gz -C /opt/marquez-src --strip-components=1 \
 && rm -f /tmp/marquez.tar.gz
WORKDIR /opt/marquez-src
RUN ./gradlew --no-daemon -q :api:shadowJar -x test

# ---------- Stage 3: Marquez Web(源码构建,不依赖官方镜像) ----------
FROM node:18-bullseye AS marquez-web-builder
ARG BUILD_HTTP_PROXY=
ARG BUILD_HTTPS_PROXY=
ENV http_proxy=${BUILD_HTTP_PROXY} \
    https_proxy=${BUILD_HTTPS_PROXY} \
    HTTP_PROXY=${BUILD_HTTP_PROXY} \
    HTTPS_PROXY=${BUILD_HTTPS_PROXY} \
    no_proxy=127.0.0.1,localhost \
    NO_PROXY=127.0.0.1,localhost
COPY --from=marquez-builder /opt/marquez-src /opt/marquez-src
WORKDIR /opt/marquez-src/web
# 构建机内存受限时防止 webpack OOM（Killed/ResourceExhausted）：
#   1) 关闭生产 sourcemap（devtool: false）——省去大块 map 的生成/驻留内存
#   2) minimize: false —— 不跑 terser，避免默认按 CPU 核数 fork 多个压缩 worker 进程
#   3) NODE_OPTIONS 限制 V8 堆上限，避免堆无节制膨胀
RUN node -e 'const fs=require("fs");let s=fs.readFileSync("webpack.prod.js","utf8");s=s.replace(/devtool: '\''source-map'\''/,"devtool: false");s=s.replace("const webpackProd = {","const webpackProd = {\n  optimization: { minimize: false },");fs.writeFileSync("webpack.prod.js",s);' \
 && npm ci --no-audit --no-fund \
 && NODE_OPTIONS=--max-old-space-size=2048 npm run build

# ---------- Stage 4: 运行时镜像 ----------
FROM eclipse-temurin:17-jammy

# 构建期代理(配合 --network=host 使用;最终会被清空,不写入运行时)
ARG BUILD_HTTP_PROXY=
ARG BUILD_HTTPS_PROXY=
ENV http_proxy=${BUILD_HTTP_PROXY} \
    https_proxy=${BUILD_HTTPS_PROXY} \
    HTTP_PROXY=${BUILD_HTTP_PROXY} \
    HTTPS_PROXY=${BUILD_HTTPS_PROXY} \
    no_proxy=127.0.0.1,localhost \
    NO_PROXY=127.0.0.1,localhost

# 系统依赖:PostgreSQL14 / Nginx / Supervisor / sudo / 常用工具
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-14 postgresql-client \
        nginx supervisor sudo \
        curl ca-certificates netcat-openbsd procps vim \
    && rm -rf /var/lib/apt/lists/*

# 目录结构(注意:spark/dolphinscheduler 由下方解压 mv 时创建,不能预建,
# 否则 mv 会把解压目录嵌套移入已存在的同名空目录)
RUN mkdir -p /opt/marquez /opt/marquez-web \
             /opt/dataforge/jars /opt/dataforge/sql /opt/dataforge/scripts \
             /opt/dataforge/warehouse /opt/dataforge/ds-h2 \
             /var/log/dataforge

# 任务执行用户:DolphinScheduler 以租户(OS 用户)运行任务,
# 需要非 root 的 `test_tenant` 用户 + 免密 sudo,DS(root 进程)才能 sudo -u test_tenant 执行任务。
RUN useradd -rm -s /bin/bash -d /home/test_tenant test_tenant \
 && echo "test_tenant ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/test_tenant \
 && chmod 440 /etc/sudoers.d/test_tenant \
 # Spark 任务输出写到 warehouse,须对任务用户(test_tenant)可写
 && chown -R test_tenant:test_tenant /opt/dataforge/warehouse

# ---------- Spark 3.5.9 (仅 local 模式) ----------
# 发行包已预下载到本地 build/dist/(见 build.sh ensure_dist),构建不再联网下载
COPY build/dist/spark-3.5.9-bin-hadoop3.tgz /tmp/spark.tgz
RUN tar xzf /tmp/spark.tgz -C /opt \
 && mv /opt/spark-3.5.9-bin-hadoop3 /opt/spark \
 && rm -f /tmp/spark.tgz \
 && test -x /opt/spark/bin/spark-class \
 # local 模式默认配置(含 OpenLineage 数据血缘,上报容器内 Marquez http://localhost:5000)
 && printf 'spark.master local[*]\nspark.sql.warehouse.dir file:///opt/dataforge/warehouse\nspark.extraListeners io.openlineage.spark.agent.OpenLineageSparkListener\nspark.openlineage.transport.url http://localhost:5000\nspark.openlineage.transport.type http\nspark.openlineage.namespace dataforge\n' \
       > /opt/spark/conf/spark-defaults.conf

# 数据血缘 Agent(OpenLineage 1.8.0),fat jar 自包含依赖,放入 spark jars 目录使其对
# dataforge-server/DolphinScheduler 发起的 spark 任务全局生效
COPY build/dist/openlineage-spark-1.8.0.jar /opt/spark/jars/openlineage-spark.jar

# ---------- DolphinScheduler 3.4.1 (standalone) ----------
# 发行包已预下载到本地 build/dist/(见 build.sh ensure_dist),构建不再联网下载
# 注意:3.4.1 发行包不内置任务插件(根目录 plugins/ 为空),
#       需要单独把 Spark 任务插件(shade 包)放进 plugins/task-plugins/,
#       否则创建 SPARK 任务会报: Cannot find TaskChannel for : SPARK
COPY build/dist/apache-dolphinscheduler-3.4.1-bin.tar.gz /tmp/ds.tar.gz
COPY build/dist/dolphinscheduler-task-spark-3.4.1-shade.jar \
     /opt/dolphinscheduler-spark-task-spark-3.4.1-shade.jar
RUN tar xzf /tmp/ds.tar.gz -C /opt \
 && rm -f /tmp/ds.tar.gz \
 && mv /opt/apache-dolphinscheduler-3.4.1-bin /opt/dolphinscheduler \
 # 校验解压结构,失败时立即报错便于定位
 && test -d /opt/dolphinscheduler/standalone-server \
 && test -f /opt/dolphinscheduler/standalone-server/conf/application.yaml \
 && test -f /opt/dolphinscheduler/bin/dolphinscheduler-daemon.sh \
 && chmod -R 755 /opt/dolphinscheduler \
 # 安装 Spark 任务插件(与官方 install-plugins.sh 安装到 plugins/task-plugins/ 一致)
 && mkdir -p /opt/dolphinscheduler/plugins/task-plugins \
 && mv /opt/dolphinscheduler-spark-task-spark-3.4.1-shade.jar \
      /opt/dolphinscheduler/plugins/task-plugins/dolphinscheduler-task-spark-3.4.1-shade.jar \
 && ls -l /opt/dolphinscheduler/plugins/task-plugins/
# 说明:H2 内存库 -> 文件库由运行时环境变量 SPRING_DATASOURCE_URL 覆盖(见 run-ds-standalone.sh),
# 此处不再改动 application.yaml,避免版本目录结构差异导致构建失败
RUN cat >> /opt/dolphinscheduler/standalone-server/conf/dolphinscheduler_env.sh <<'ENVEOF'
export JAVA_HOME=/opt/java/openjdk
export SPARK_HOME=/opt/spark
export DOLPHINSCHEDULER_HOME=/opt/dolphinscheduler
ENVEOF
# JDK17 下任务执行器(AbstractCommandExecutor.getProcessId)通过反射访问
# java.lang.ProcessImpl.pid,JPMS 强封装导致 InaccessibleObjectException,
# 必须对 standalone JVM 打开相关模块包。start.sh 只会拼接 jvm_args_env.sh 中
# 以 "-" 开头的行作为附加 JVM 参数,故在此追加 --add-opens(保留原有内存/GC 配置)。
# 必选:先补一个换行,否则若原文件末行无结尾换行,追加内容会拼到同
# "-Duser.timezone=..." 一行,导致这些参数全部失效。
RUN printf '\n' >> /opt/dolphinscheduler/standalone-server/bin/jvm_args_env.sh \
 && cat >> /opt/dolphinscheduler/standalone-server/bin/jvm_args_env.sh <<'EOF'
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED
--add-opens=java.base/java.time=ALL-UNNAMED
--add-opens=java.base/java.text=ALL-UNNAMED
--add-opens=java.base/java.math=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.base/java.net=ALL-UNNAMED
--add-opens=java.base/java.sql=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--add-exports=java.base/sun.nio.ch=ALL-UNNAMED
EOF

# ---------- Marquez 0.51.1(自建产物) ----------
COPY --from=marquez-builder /opt/marquez-src/api/build/libs/marquez-api-0.51.1.jar /opt/marquez/
COPY --from=marquez-builder /opt/marquez-src/marquez.dev.yml /opt/marquez/

# ---------- Marquez Web(自建静态产物 + Nginx) ----------
COPY --from=marquez-web-builder /opt/marquez-src/web/dist /opt/marquez-web
RUN rm -f /etc/nginx/sites-enabled/default
COPY build/conf/marquez-web-nginx.conf /etc/nginx/conf.d/marquez-web.conf

# ---------- dataforge-server ----------
COPY --from=app-builder /build/target/dataforge-server-1.0.0-SNAPSHOT.jar /opt/dataforge/dataforge-server.jar
COPY build/dist/postgresql-42.7.12.jar /opt/dataforge/jars/postgresql-42.7.12.jar
COPY src/main/resources/sql/init/dataforge-postgresql.sql /opt/dataforge/sql/dataforge-postgresql.sql
COPY src/main/resources/sql/testdata/dataforge-testdata-postgresql.sql /opt/dataforge/sql/testdata/dataforge-testdata-postgresql.sql
COPY src/main/resources/sql/testdata/test_db-postgresql.sql /opt/dataforge/sql/testdata/test_db-postgresql.sql
COPY build/sql/dolphinscheduler-init-data.sql /opt/dataforge/sql/dolphinscheduler-init-data.sql
COPY build/scripts/ /opt/dataforge/scripts/
COPY build/conf/supervisord.conf /etc/supervisor/conf.d/dataforge.conf

# PostgreSQL 允许外部(容器 IP/宿主端口映射)访问
RUN echo "host all all 0.0.0.0/0 scram-sha-256" >> /etc/postgresql/14/main/pg_hba.conf \
 && chmod +x /opt/dataforge/scripts/*.sh

# 清空构建期代理,避免固化到运行时镜像
ENV http_proxy="" https_proxy="" HTTP_PROXY="" HTTPS_PROXY="" \
    no_proxy="" NO_PROXY=""

# 环境变量
ENV JAVA_HOME=/opt/java/openjdk \
    SPARK_HOME=/opt/spark \
    DOLPHINSCHEDULER_HOME=/opt/dolphinscheduler \
    MARQUEZ_CONFIG=/opt/marquez/marquez.dev.yml \
    PATH=/opt/java/openjdk/bin:/opt/spark/bin:/opt/dolphinscheduler/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

EXPOSE 8082 5432 12345 5000 5001 3000

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf"]