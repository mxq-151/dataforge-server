# ============================================================
# dataforge All-in-One 镜像
# 包含: dataforge-server + JDK17 + PostgreSQL14 + Spark3.5.9(local)
#       + Hadoop 3.3.6 最小 YARN(RM+NM 伪分布式, 无 HDFS, Spark on YARN)
#       + Spark History Server(18080, 事件日志落 df-data 卷)
#       + DolphinScheduler3.4.1(standalone)
#       + Marquez0.51.1(源码自建 api + web)
#       + Spark 写入合规监听器 spark-write-compliance(core + spark-3.5, 全局装载)
#
# dataforge-server 的 jar 不在镜像内编译: 由 build/build.sh 在宿主机用本机 mvn 打包后
# 落到 build/dist/dataforge-server.jar, 本 Dockerfile 只负责 COPY 进来。
# 这样免去容器内预热 ~/.m2、构建上下文不必再送 src/pom、编译错误直接可见、可离线构建。
#
# Marquez 0.51.1(API shadow jar + Web 静态产物)同样不在镜像内编译: 由
# build/prepare-marquez.sh 在宿主机用本机 JDK17 + Gradle + Node 构建, 产物落
# build/dist/marquez/ 与 build/dist/marquez-web/, 本文件只做 COPY。
# 因此镜像构建阶段不再有 gradle / npm / gcc / make 任何编译动作, 也没有 gradle:8.7-jdk17
# 与 node:18-bullseye 两个 builder 镜像(约 2.5GB)参与。
#
# 直接 docker build 前需自行准备(建议统一用 build/build.sh 或 build/build-all.sh 驱动):
#   build/dist/dataforge-server.jar      宿主机 mvn package 产物
#   build/dist/spark-listeners/*.jar     宿主机构建的写入合规监听器
#   build/dist/marquez/                  宿主机构建的 Marquez API jar + marquez.dev.yml
#   build/dist/marquez-web/              宿主机构建的 Marquez Web 静态产物(web/dist)
#   build/dist/<其余发行包>               见 build-lib.sh ensure_dist
#
# 构建(需要代理时):
#   docker build --network=host \
#     --build-arg BUILD_HTTP_PROXY=http://127.0.0.1:7890 \
#     --build-arg BUILD_HTTPS_PROXY=http://127.0.0.1:7890 \
#     -t dataforge-allinone:1.0 .
# ============================================================

# ============================================================
# 单阶段运行时镜像: 所有编译(server jar / 监听器 / Marquez API+Web)都在宿主机完成,
# 本文件只解包发行包 + COPY 产物, 不含任何编译动作。
# ============================================================

# ---------- 运行时镜像 ----------
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
# UID/GID 默认 501:20(与 macOS 宿主首用户一致):容器内 TPC-DS 数据与 warehouse 均归 test_tenant,
# 仍挂宿主机目录做调试时也不会出现属主错位;其它机器可覆盖:
#   --build-arg TEST_TENANT_UID=$(id -u) --build-arg TEST_TENANT_GID=$(id -g)
# 注：容器基础镜像里 gid 20 已被 dialout 占用，用 `groupadd -o` 允许重复 GID。
ARG TEST_TENANT_UID=501
ARG TEST_TENANT_GID=20
RUN groupadd -o -g ${TEST_TENANT_GID} test_tenant \
 && useradd -rm -u ${TEST_TENANT_UID} -g ${TEST_TENANT_GID} -s /bin/bash -d /home/test_tenant test_tenant \
 && echo "test_tenant ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/test_tenant \
 && chmod 440 /etc/sudoers.d/test_tenant \
 # Spark 任务输出写到 warehouse,须对任务用户(test_tenant)可写
 && chown -R test_tenant:test_tenant /opt/dataforge/warehouse

# ---------- TPC-DS 4.0.0 基准数据（镜像内数据层，不依赖宿主机 bind mount） ----------
# 数据来源: build/build-lib.sh 的 stage_tpcds_data() 把宿主机 TPCDS_DATA_SRC(默认
#   /Users/mxq/Desktop/tpcds)下的目录结构与 parquet 同步到 build/dist/tpcds-data,
#   Dockerfile 只做 COPY。构建可重复, 不用 docker cp; 宿主机杂物(.DS_Store 等)不会进镜像。
# 路径 /opt/tpcds: 不被 df-data 命名卷遮蔽, 容器重建后始终能拿到镜像内的初始数据。
# 该层只读使用: entrypoint 首次启动时把它播种到可写 warehouse(/opt/dataforge/warehouse/tpcds),
# 之后 test_db 内新建的表/数据写在 df-data 卷上, 镜像层永远不会被改写。
COPY --chown=test_tenant:test_tenant build/dist/tpcds-data /opt/tpcds
RUN mkdir -p /opt/tpcds/test_db.db && chown test_tenant:test_tenant /opt/tpcds/test_db.db

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

# spark-env.sh：把 HADOOP_CONF_DIR/YARN_CONF_DIR 指到 /opt/hadoop/etc/hadoop，
# 使 `spark-submit --master yarn` 能读到 RM 地址（否则报 Cannot initialize Cluster）。
COPY build/conf/spark-env.sh /opt/spark/conf/spark-env.sh
RUN chmod +x /opt/spark/conf/spark-env.sh

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

# ---------- Hadoop 3.3.6 最小可用 YARN（伪分布式 RM + NM，不含 HDFS） ----------
# 发行包已预下载到本地 build/dist/（见 build.sh ensure_dist），构建不再联网下载。
# install-hadoop.sh 负责裁剪：1.3G -> ~210M，只留 YARN 运行必需模块（详见脚本头部说明）。
# 版本选择：Spark 3.5.9 的 bin-hadoop3 发行包基于 hadoop 3.3.x，YARN 侧对齐 3.3.6 最稳。
#
# ⚠ 发行包用 **bind mount** 而不是 COPY：730M 的 tar.gz 若 COPY 进镜像会永久留一层
#   （即使后续 RUN 里 rm 掉，层里仍在，镜像体积照样 +730M）。bind mount 只读挂载构建上下文，
#   不进任何层，装完即释放。
RUN --mount=type=bind,source=build/dist/hadoop-3.3.6.tar.gz,target=/tmp/hadoop.tgz \
    --mount=type=bind,source=build/scripts/install-hadoop.sh,target=/tmp/install-hadoop.sh \
    bash /tmp/install-hadoop.sh /tmp/hadoop.tgz /opt/hadoop

# YARN 配置：三个 XML 是**模板**（含 __YARN_*__ 占位符），运行期由 run-yarn.sh 按环境变量
# 重新渲染（资源配额可配）；这里先用默认值渲染一份，保证镜像内直接调 yarn CLI 也能用。
COPY build/conf/hadoop/ /opt/hadoop/etc/hadoop/
RUN set -eux; \
    cd /opt/hadoop/etc/hadoop; \
    for f in core-site.xml mapred-site.xml yarn-site.xml; do \
      sed -e 's|__YARN_DATA_DIR__|/var/lib/dataforge-yarn|g' \
          -e 's|__YARN_LOG_DIR__|/opt/dataforge/yarn/logs|g' \
          -e 's|__YARN_NM_MEMORY_MB__|4096|g' \
          -e 's|__YARN_NM_VCORES__|2|g' "$f.tmpl" > "$f"; \
    done; \
    chmod +x hadoop-env.sh; ls -l core-site.xml yarn-site.xml mapred-site.xml

# YARN 运行目录：守护进程以 test_tenant 身份运行（与 DolphinScheduler 租户一致），
# 必须可写。nm-local/nm-logs 属临时目录；yarn/logs（聚合日志）与 spark-events 落在
# df-data 卷上，容器重建后仍可读（卷在首次创建时用镜像内容播种，entrypoint 每次启动再校正属主）。
RUN mkdir -p /var/lib/dataforge-yarn/nm-local /var/lib/dataforge-yarn/nm-logs \
             /var/lib/dataforge-yarn/tmp /var/lib/dataforge-yarn/pids \
             /var/log/dataforge/yarn \
             /opt/dataforge/yarn/logs /opt/dataforge/yarn/spark-staging \
             /opt/dataforge/spark-events \
 && chown -R test_tenant:test_tenant /var/lib/dataforge-yarn /var/log/dataforge/yarn \
 && chown -R test_tenant:test_tenant /opt/dataforge/yarn /opt/dataforge/spark-events

# ---------- Hive 2.3.9（Metastore 服务端 + beeline 客户端） ----------
# 版本策略：Spark 3.5.9 内置 Hive 客户端即 2.3.9，Kyuubi 的 Spark 引擎复用同一客户端，
# beeline 也来自 /opt/hive（2.3.9），保证客户端/服务端严格同版本（不出现 2.x 连 3.x）。
# 发行包已预下载到 build/dist/（见 build-lib.sh ensure_dist），bind mount 安装避免 tar 进层。
RUN --mount=type=bind,source=build/dist/apache-hive-2.3.9-bin.tar.gz,target=/tmp/hive.tgz \
    bash -c 'set -eux; \
      tar xzf /tmp/hive.tgz -C /opt; \
      mv /opt/apache-hive-2.3.9-bin /opt/hive; \
      rm -rf /opt/hive/examples /opt/hive/hcatalog /opt/hive/jdbc; \
      mkdir -p /opt/hive/logs'
# Metastore 元数据存 PostgreSQL（库 metastore，账号 hive/hive），warehouse 指向宿主 bind mount
COPY build/conf/hive/hive-site.xml /opt/hive/conf/hive-site.xml
COPY build/dist/postgresql-42.7.12.jar /opt/hive/lib/postgresql-42.7.12.jar
RUN mkdir -p /opt/dataforge/hive/logs \
 && chown -R test_tenant:test_tenant /opt/hive/logs /opt/dataforge/hive

# ---------- Apache Kyuubi 1.9.1（Spark SQL 即席查询，Thrift/JDBC 10009） ----------
# 1.9.1 官方构建对应 Spark 3.5.1（同一 3.5.x 补丁线，兼容 3.5.9），Hive 客户端随 Spark 为 2.3.9。
RUN --mount=type=bind,source=build/dist/apache-kyuubi-1.9.1-bin.tgz,target=/tmp/kyuubi.tgz \
    bash -c 'set -eux; \
      tar xzf /tmp/kyuubi.tgz -C /opt; \
      mv /opt/apache-kyuubi-1.9.1-bin /opt/kyuubi; \
      mkdir -p /opt/kyuubi/logs /opt/kyuubi/work /opt/kyuubi/embedded_zookeeper'
COPY build/conf/kyuubi/kyuubi-defaults.conf /opt/kyuubi/conf/kyuubi-defaults.conf
RUN mkdir -p /opt/dataforge/kyuubi/logs /opt/dataforge/kyuubi/work \
 && chown -R test_tenant:test_tenant /opt/dataforge/kyuubi /opt/kyuubi/embedded_zookeeper /opt/kyuubi/logs /opt/kyuubi/work

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

# ---------- DolphinScheduler Worker 任务并行度 ----------
# DS 3.4.1 中 worker 的并行度由 worker.physical-task-config.task-executor-thread-size 决定
# (物理任务执行线程池大小,同时作为上报给 master 的可用 slot 数),发行包默认 100。
# 单容器内 Spark 任务较重,按容器资源压到 2,避免同时拉起过多任务把内存/CPU 打满。
RUN sed -i 's|^\([[:space:]]*task-executor-thread-size:\).*$|\1 2|' \
        /opt/dolphinscheduler/standalone-server/conf/application.yaml \
 && grep -n -B2 'task-executor-thread-size' \
        /opt/dolphinscheduler/standalone-server/conf/application.yaml

# ---------- Marquez 0.51.1 / Marquez Web：宿主机预编译产物，镜像内只 COPY ----------
# 原实现是两个 builder 阶段(FROM gradle:8.7-jdk17 / node:18-bullseye)在镜像构建期
# gradle :api:shadowJar + npm ci + webpack, 慢且依赖容器内工具链。现改由
# build/prepare-marquez.sh 在宿主机完成, 产物固定落:
#   build/dist/marquez/       marquez-api-0.51.1.jar + marquez.dev.yml(+ manifest.sha256)
#   build/dist/marquez-web/   web/dist 静态资源(index.html / js / css / ...)
# 目录与运行行为完全不变: /opt/marquez 下仍是 jar + 配置, /opt/marquez-web 仍是 nginx 站点根。
# 校验(文件齐全 + sha256 + jar 内 Main-Class)由 prepare-marquez.sh 在 COPY 之前完成,
# 不通过会直接中止构建, 不会把半成品打进镜像。

# ---------- Marquez 0.51.1(自建产物) ----------
COPY build/dist/marquez/marquez-api-0.51.1.jar /opt/marquez/
COPY build/dist/marquez/marquez.dev.yml /opt/marquez/

# ---------- Marquez Web(自建静态产物 + Nginx) ----------
COPY build/dist/marquez-web/ /opt/marquez-web/
RUN rm -f /etc/nginx/sites-enabled/default
COPY build/conf/marquez-web-nginx.conf /etc/nginx/conf.d/marquez-web.conf

# ---------- dataforge-server ----------
# jar 由宿主机 mvn 打好后落在 build/dist/(见 build/build.sh build_server_jar),此处只做拷贝
COPY build/dist/dataforge-server.jar /opt/dataforge/dataforge-server.jar
# 同时放一份到不被命名卷挂载的 /opt/dataforge-image：entrypoint 启动时按"大小+SHA256"
# 校验并同步到 /opt/dataforge，避免持久化卷里的旧 jar 遮住镜像新 jar（表现为接口 404）。
COPY build/dist/dataforge-server.jar /opt/dataforge-image/dataforge-server.jar
COPY build/dist/postgresql-42.7.12.jar /opt/dataforge/jars/postgresql-42.7.12.jar

# ---------- 初始化 SQL 与容器脚本：放镜像专属目录，由 entrypoint 启动时同步 ----------
# 不能直接 COPY 到 /opt/dataforge/{sql,scripts}：该目录常被命名卷 df-data 挂载，
# 卷只在**首次创建**时用镜像内容播种，之后重建镜像都不会刷新卷里这些文件，
# 于是"重建镜像 + 重启容器"依然跑着旧 SQL / 旧脚本 —— 实测踩过：镜像层里的
# dataforge-postgresql.sql 已是新版，容器里读到的还是 9 月的旧版，建表缺列，
# 报 column "xxx" does not exist，且极易被误判成"镜像没重建"。
# /opt/dataforge-image 不被任何卷挂载，entrypoint 每次启动都用它覆盖 /opt/dataforge，
# 保证镜像始终是权威版本（见 build/scripts/docker-entrypoint.sh）。
COPY src/main/resources/sql/ddl/dataforge-postgresql.sql /opt/dataforge-image/sql/ddl/dataforge-postgresql.sql
COPY src/main/resources/sql/init/dataforge-postgresql.sql /opt/dataforge-image/sql/init/dataforge-postgresql.sql
COPY build/sql/dolphinscheduler-init-data.sql /opt/dataforge-image/sql/dolphinscheduler-init-data.sql
# TPC-DS 建表 DDL（test_db 库，25 张外部表，LOCATION=/opt/dataforge/warehouse/tpcds/*）；由 init-hive-tables.sh 执行
COPY build/sql/tpcds-ddl.sql /opt/dataforge-image/sql/tpcds-ddl.sql
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
    HADOOP_HOME=/opt/hadoop \
    HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop \
    YARN_CONF_DIR=/opt/hadoop/etc/hadoop \
    HIVE_HOME=/opt/hive \
    HIVE_CONF_DIR=/opt/hive/conf \
    KYUUBI_HOME=/opt/kyuubi \
    DOLPHINSCHEDULER_HOME=/opt/dolphinscheduler \
    MARQUEZ_CONFIG=/opt/marquez/marquez.dev.yml \
    PATH=/opt/java/openjdk/bin:/opt/spark/bin:/opt/hadoop/bin:/opt/hadoop/sbin:/opt/hive/bin:/opt/kyuubi/bin:/opt/dolphinscheduler/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# 8088 RM UI/REST、8042 NM REST、18080 Spark History Server
# 9083 Hive Metastore Thrift、10009 Kyuubi Thrift/JDBC、10099 Kyuubi REST API
# （8030/8031/8032/8033 与 8041/8043 为 RM/NM 内部 RPC，仅容器内可达，不对外映射）
EXPOSE 8082 5432 12345 5000 5001 3000 8088 8042 18080 9083 10009 10099

# entrypoint 先把镜像里的 sql/ 与 scripts/ 同步进可能被命名卷遮蔽的 /opt/dataforge，
# 再exec CMD 启动 supervisord（详见 build/scripts/docker-entrypoint.sh 顶部说明）
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf"]