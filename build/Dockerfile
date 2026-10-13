# ============================================================
# dataforge All-in-One 镜像
# 包含: dataforge-server + JDK17 + PostgreSQL14 + Spark3.5.9(local)
#       + DolphinScheduler3.4.1(standalone)
#       + Marquez0.51.1(源码自建 api + web)
#       + Spark 写入合规监听器 spark-write-compliance(core + spark-3.5, 全局装载)
#
# dataforge-server 的 jar 不在镜像内编译: 由 build/build.sh 在宿主机用本机 mvn 打包后
# 落到 build/dist/dataforge-server.jar, 本 Dockerfile 只负责 COPY 进来。
# 这样免去容器内预热 ~/.m2、构建上下文不必再送 src/pom、编译错误直接可见、可离线构建。
#
# 直接 docker build 前需自行准备(建议统一用 build/build.sh 或 build/build-all.sh 驱动):
#   build/dist/dataforge-server.jar      宿主机 mvn package 产物
#   build/dist/spark-listeners/*.jar     宿主机构建的写入合规监听器
#   build/dist/<其余发行包>               见 build-lib.sh ensure_dist
#
# 构建(需要代理时):
#   docker build --network=host \
#     --build-arg BUILD_HTTP_PROXY=http://127.0.0.1:7890 \
#     --build-arg BUILD_HTTPS_PROXY=http://127.0.0.1:7890 \
#     -t dataforge-allinone:1.0 .
# ============================================================

# ---------- Stage 1: Marquez API 0.51.1(源码构建,不依赖官方镜像) ----------
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
# 直接用 base 镜像自带的 Gradle(base=gradle:8.7-jdk17,版本与 wrapper 要求的
# gradle-8.7-bin.zip 一致),不走 ./gradlew:本机出网极慢(~0.2MB/s),wrapper 的
# networkTimeout=10000 会在下载发行包时直接用超时中断构建。
#
# 依赖仓库统一改走阿里云镜像(见 build/conf/gradle-init-mirror.gradle 顶部说明):
# 容器内直连 repo.maven.apache.org / plugins.gradle.org 会 TLS 握手失败,报
#   Could not resolve io.dropwizard:dropwizard-json-logging:2.1.12
#     > Remote host terminated the handshake
# 且 Java/Gradle **不读** http_proxy/https_proxy 环境变量,靠上面的构建期代理参数
# 兜不住这一步,只能从仓库层面改写。临时绕开可 --build-arg GRADLE_MIRROR=off。
# 超时参数顺带放大,避免慢链路下握手被服务端提前断掉。
# /root/.gradle 缓存挂载:缓存依赖与发行包,避免层失效后重下
ARG GRADLE_MIRROR=aliyun
ENV GRADLE_MIRROR=${GRADLE_MIRROR}
COPY build/conf/gradle-init-mirror.gradle /opt/gradle-init/mirror.gradle
RUN --mount=type=cache,target=/root/.gradle \
    gradle --no-daemon -q \
      --init-script /opt/gradle-init/mirror.gradle \
      -Dorg.gradle.internal.http.connectionTimeout=60000 \
      -Dorg.gradle.internal.http.socketTimeout=180000 \
      :api:shadowJar -x test

# ---------- Stage 2: Marquez Web(源码构建,不依赖官方镜像) ----------
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
# /root/.npm 缓存挂载:缓存 npm 包,避免层失效后重下 node_modules(同上)
RUN --mount=type=cache,target=/root/.npm \
    node -e 'const fs=require("fs");let s=fs.readFileSync("webpack.prod.js","utf8");s=s.replace(/devtool: '\''source-map'\''/,"devtool: false");s=s.replace("const webpackProd = {","const webpackProd = {\n  optimization: { minimize: false },");fs.writeFileSync("webpack.prod.js",s);' \
 && npm ci --no-audit --no-fund \
 && NODE_OPTIONS=--max-old-space-size=2048 npm run build

# ---------- Stage 3: 运行时镜像 ----------
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
#
# ⚠ apt 源必须走 https,且换国内镜像 —— 实测本机所在网络对**明文 http(80) 限速/阻断**:
#   同一个阿里云 ubuntu-ports,http 只有 56~141kB/s(容器内多次直接 0 字节/Connection failed),
#   https 稳定 7~9MB/s(宿主与容器各 3/3 全通)。原始的 ports.ubuntu.com(http) 也因此只有
#   ~598kB/s 并报 502 Bad Gateway,apt 直接失败退出("Unable to fetch some archives")。
#   故这里先按目标架构把 sources.list 换成 https 国内镜像,再 update/install:
#     amd64(原 archive.ubuntu.com / security.ubuntu.com) -> mirrors.aliyun.com/ubuntu
#     其余 (原 ports.ubuntu.com/ubuntu-ports,jammy 的 arm64 属此类) -> mirrors.aliyun.com/ubuntu-ports
#   基础镜像已自带 ca-certificates + apt>=1.5,https 源开箱可用,无需额外装包。
#   APT_MIRROR 传 off / 空 则不改写(回退发行版自带源,需自备可用代理);
#   也可传别的镜像域名(实测 mirrors.tuna.tsinghua.edu.cn / mirrors.ustc.edu.cn 亦可用)。
#
# apt 下载缓存:本机链路整体很慢(实测换成 https 源后 apt 聚合仍有 ~175kB/s,
# 索引 48MB 要 4.6 分钟,整套索引+包 100MB+),而构建经常在后续步骤失败后重跑。
# 故把 apt 的下载目录与索引目录挂成 cache mount:
#   - 失败重跑时已下载的 .deb 与索引会被复用(已实测:失败构建后挂载内容仍保留),
#     不必从零再拉一遍;
#   - sharing=locked 让两个平台的 apt 步骤串行,既避免并发挤这条脆弱链路,也避免互相覆盖;
#   - 因此这里**不能再写 `rm -rf /var/lib/apt/lists/*`**:它是挂载点,删掉等于清缓存;
#     挂载内容本来也不会进镜像层,不会让镜像变大。
ARG APT_MIRROR=mirrors.aliyun.com
ARG TARGETARCH
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    set -eu; \
    if [ -n "${APT_MIRROR}" ] && [ "${APT_MIRROR}" != "off" ]; then \
      if [ "${TARGETARCH}" = "amd64" ]; then _dir=ubuntu; else _dir=ubuntu-ports; fi; \
      _mirror="https://${APT_MIRROR}/${_dir}"; \
      for _f in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources; do \
        [ -f "$_f" ] || continue; \
        sed -i \
          -e "s|http://ports.ubuntu.com/ubuntu-ports|${_mirror}|g" \
          -e "s|http://archive.ubuntu.com/ubuntu|${_mirror}|g" \
          -e "s|http://security.ubuntu.com/ubuntu|${_mirror}|g" \
          -e "s|https://ports.ubuntu.com/ubuntu-ports|${_mirror}|g" \
          -e "s|https://archive.ubuntu.com/ubuntu|${_mirror}|g" \
          -e "s|https://security.ubuntu.com/ubuntu|${_mirror}|g" \
          "$_f"; \
      done; \
      echo "==> apt 源已切换到 ${_mirror} (targetarch=${TARGETARCH})"; \
      grep -hE '^(deb|URIs)' /etc/apt/sources.list 2>/dev/null | head -2; \
    else \
      echo "==> APT_MIRROR=off, 使用发行版自带 apt 源"; \
    fi; \
    apt-get -o Acquire::Retries=5 \
            -o Acquire::http::Timeout=60 \
            -o Acquire::https::Timeout=60 \
            update \
 && apt-get install -y --no-install-recommends \
        postgresql-14 postgresql-client \
        nginx supervisor sudo \
        curl ca-certificates netcat-openbsd procps vim

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
 && test -x /opt/spark/bin/spark-class

# local 模式默认配置: spark.master / warehouse / OpenLineage 数据血缘(上报容器内 Marquez)
# + 写入合规监听器(spark.sql.extensions / queryExecutionListeners / spark.write.compliance.*)。
# 单独放成仓库文件而非内联 printf，便于随增量构建(./build.sh)一起刷新，也可直接 diff 审阅。
COPY build/conf/spark-defaults.conf /opt/spark/conf/spark-defaults.conf

# 数据血缘 Agent(OpenLineage 1.8.0),fat jar 自包含依赖,放入 spark jars 目录使其对
# dataforge-server/DolphinScheduler 发起的 spark 任务全局生效
COPY build/dist/openlineage-spark-1.8.0.jar /opt/spark/jars/openlineage-spark.jar

# ---------- Spark 写入合规监听器(spark-write-compliance) ----------
# jar 由 build/build-lib.sh 的 ensure_spark_listener() 从 spark-listener 工程构建后落到
# build/dist/spark-listeners/(core 纯 Java 引擎 + spark-3.5 适配层)。
# 放进 SPARK_HOME/jars 而非仅 --jars: spark.sql.extensions / spark.sql.queryExecutionListeners
# 是 Spark 自己按类名反射装载的，类必须对驱动端类加载器可见，
# 否则 Spark 只会打一条 "Cannot use ... to configure session extensions" 告警并静默跳过。
COPY build/dist/spark-listeners/*.jar /opt/spark/jars/

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

# ---------- DolphinScheduler 时区修正 ----------
# DS 的 jvm_args_env.sh 里 `-Duser.timezone=${SPRING_JACKSON_TIME_ZONE}` 不会被展开:
# start.sh 只是把以 "-" 开头的行原样拼进 java 命令行,不做 shell 变量替换,
# 于是 JVM 收到字面量 ${SPRING_JACKSON_TIME_ZONE} 无法解析,时区退化为 GMT。
# 后果:依赖(DEPENDENT)任务按 GMT 计算业务日期,与上游调度日期相差一天(8 小时),
# 前置依赖永远等不到上游成功实例,工作流长期卡在 RUNNING。
# 构建期用具体时区替换占位符(sed 幂等),并导出同名环境变量供 DS/Jackson 运行时使用。
ARG SPRING_JACKSON_TIME_ZONE=Asia/Shanghai
ENV SPRING_JACKSON_TIME_ZONE=${SPRING_JACKSON_TIME_ZONE}
RUN sed -i "s|^-Duser.timezone=.*$|-Duser.timezone=${SPRING_JACKSON_TIME_ZONE}|" \
        /opt/dolphinscheduler/standalone-server/bin/jvm_args_env.sh \
 && grep -n 'Duser.timezone' /opt/dolphinscheduler/standalone-server/bin/jvm_args_env.sh

# ---------- Marquez 0.51.1(自建产物) ----------
COPY --from=marquez-builder /opt/marquez-src/api/build/libs/marquez-api-0.51.1.jar /opt/marquez/
COPY --from=marquez-builder /opt/marquez-src/marquez.dev.yml /opt/marquez/

# ---------- Marquez Web(自建静态产物 + Nginx) ----------
COPY --from=marquez-web-builder /opt/marquez-src/web/dist /opt/marquez-web
RUN rm -f /etc/nginx/sites-enabled/default
COPY build/conf/marquez-web-nginx.conf /etc/nginx/conf.d/marquez-web.conf

# ---------- dataforge-server ----------
# jar 由宿主机 mvn 打好后落在 build/dist/(见 build/build.sh build_server_jar),此处只做拷贝
COPY build/dist/dataforge-server.jar /opt/dataforge/dataforge-server.jar
COPY build/dist/postgresql-42.7.12.jar /opt/dataforge/jars/postgresql-42.7.12.jar

# ---------- 初始化 SQL 与容器脚本：放镜像专属目录，由 entrypoint 启动时同步 ----------
# 不能直接 COPY 到 /opt/dataforge/{sql,scripts}：该目录常被命名卷 df-data 挂载，
# 卷只在**首次创建**时用镜像内容播种，之后重建镜像都不会刷新卷里这些文件，
# 于是"重建镜像 + 重启容器"依然跑着旧 SQL / 旧脚本 —— 实测踩过：镜像层里的
# dataforge-postgresql.sql 已是新版，容器里读到的还是 9 月的旧版，建表缺列，
# 报 column "xxx" does not exist，且极易被误判成"镜像没重建"。
# /opt/dataforge-image 不被任何卷挂载，entrypoint 每次启动都用它覆盖 /opt/dataforge，
# 保证镜像始终是权威版本（见 build/scripts/docker-entrypoint.sh）。
COPY src/main/resources/sql/init/dataforge-postgresql.sql /opt/dataforge-image/sql/dataforge-postgresql.sql
COPY src/main/resources/sql/testdata/dataforge-testdata-postgresql.sql /opt/dataforge-image/sql/testdata/dataforge-testdata-postgresql.sql
COPY src/main/resources/sql/testdata/test_db-postgresql.sql /opt/dataforge-image/sql/testdata/test_db-postgresql.sql
COPY build/sql/dolphinscheduler-init-data.sql /opt/dataforge-image/sql/dolphinscheduler-init-data.sql
COPY build/scripts/ /opt/dataforge-image/scripts/
COPY build/scripts/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY build/conf/supervisord.conf /etc/supervisor/conf.d/dataforge.conf

# PostgreSQL 允许外部(容器 IP/宿主端口映射)访问
RUN echo "host all all 0.0.0.0/0 scram-sha-256" >> /etc/postgresql/14/main/pg_hba.conf \
 && chmod +x /opt/dataforge-image/scripts/*.sh /usr/local/bin/docker-entrypoint.sh

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

# entrypoint 先把镜像里的 sql/ 与 scripts/ 同步进可能被命名卷遮蔽的 /opt/dataforge，
# 再exec CMD 启动 supervisord（详见 build/scripts/docker-entrypoint.sh 顶部说明）
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf"]