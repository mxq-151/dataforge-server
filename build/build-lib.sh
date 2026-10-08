#!/usr/bin/env bash
# ============================================================
# dataforge All-in-One 镜像构建公共逻辑
# 供 build.sh(单平台) / build-all.sh(全平台) 两个入口 source,本文件不单独执行。
#
# 入口脚本约定(在 source 之前设置):
#   DEFAULT_PLATFORMS  未显式指定 PLATFORMS 时使用的默认平台列表;
#                      留空则按本机构架取单平台值。
#   DEFAULT_REGISTRY   未显式指定 REGISTRY 时使用的默认仓库前缀(一般留空)。
#
# 通用可用环境变量(两种入口一致):
#   FULL       1=全量重建基础镜像,0=仅更新 dataforge-server jar(默认)
#   PLATFORMS  构建平台列表,逗号分隔多个(单入口默认本机,全入口默认 amd64+arm64)
#   REGISTRY   多平台 push 的仓库前缀,如 docker.io/me
#   IMAGE_NAME / IMAGE_TAG / IMAGE_VER  镜像命名
#   PROXY      全量构建的宿主机代理地址;不设置 / off / none 走直连
#   SKIP_TESTS  mvn 打包是否跳过单测(默认 true)
#   JAVA_HOME  宿主机编译 dataforge-server 用的 JDK(需 17+);未设置时尝试用 macOS
#              `/usr/libexec/java_home -v 17` 自动定位
#
# dataforge-server 编译已移到 Docker 之外: 两种模式都在宿主机用本机 mvn `mvn package`,
# 产物固定落 build/dist/dataforge-server.jar,Dockerfile 只做 COPY(不再有 Maven 构建阶段)。
# 因此**两种模式都需要宿主机具备可用的 mvn 与 JDK 17+**(不再有"容器内编译"的退路)。
#
# Spark 写入合规监听器(spark-write-compliance)相关(两种模式都会构建并打进镜像 /opt/spark/jars/):
#   SPARK_LISTENER_DIR      监听器源码目录(默认 <dataforge-docker 同级>/spark-listener)
#   SPARK_LISTENER_MODULES  maven 模块列表(默认 core,spark-3.5 —— 对齐容器内 Spark 3.5.9)
#   SKIP_SPARK_LISTENER     1=跳过构建, 沿用 build/dist/spark-listeners/ 中已有 jar
#
# 大数据组件版本动态覆盖(可选;未设置的组件沿用 pom.xml 默认版本):
#   HADOOP_VERSION / HBASE_VERSION / JUICEFS_VERSION / PARQUET_VERSION / ORC_VERSION
#   示例: HADOOP_VERSION=3.3.6 HBASE_VERSION=2.4.17 ./build.sh
#   (两种模式都作为 -D 参数透传给宿主机的 mvn)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"  # 本目录 build/(Dockerfile / dist / conf / scripts / sql)
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"                  # 项目根 dataforge-server/(构建上下文: pom / src 在其下)
BUILD_DIR="$SCRIPT_DIR"
DIST_DIR="$BUILD_DIR/dist"

# Spark 写入合规监听器(spark-write-compliance)源码目录, 默认与 dataforge-docker 同级:
#   <父目录>/spark-listener
# 可用 SPARK_LISTENER_DIR 覆盖; SKIP_SPARK_LISTENER=1 可跳过构建(沿用 build/dist/spark-listeners 中已有 jar)。
SPARK_LISTENER_DIR="${SPARK_LISTENER_DIR:-$(cd "$SERVER_DIR/../.." && pwd)/spark-listener}"
SPARK_LISTENER_JARS_DIR="$DIST_DIR/spark-listeners"
# 只构建容器内 Spark 版本需要的模块: core(纯 Java 引擎) + spark-3.5 适配层
SPARK_LISTENER_MODULES="${SPARK_LISTENER_MODULES:-core,spark-3.5}"

# 大数据组件版本覆盖映射: 环境变量名 -> maven 属性名
VERSION_PROP_MAP=(
  "HADOOP_VERSION:hadoop.version"
  "HBASE_VERSION:hbase.version"
  "JUICEFS_VERSION:juicefs.version"
  "PARQUET_VERSION:parquet.version"
  "ORC_VERSION:orc.version"
)

# 输出 mvn 版本覆盖参数串(如 " -Dhadoop.version=3.3.6 ...";无覆盖时为空)
version_mvn_args() {
  local args=""
  local mapping env_name prop val
  for mapping in "${VERSION_PROP_MAP[@]}"; do
    env_name="${mapping%%:*}"; prop="${mapping##*:}"
    val="${!env_name:-}"
    [ -n "$val" ] && args="$args -D$prop=$val"
  done
  echo "$args"
  return 0
}

# 输出已设置版本的环境变量名列表(如 "HADOOP_VERSION ORC_VERSION")
version_env_names() {
  local names="" mapping env_name
  for mapping in "${VERSION_PROP_MAP[@]}"; do
    env_name="${mapping%%:*}"
    [ -n "${!env_name:-}" ] && names="$names $env_name"
  done
  echo "$names"
  return 0
}

IMAGE_NAME="${IMAGE_NAME:-dataforge-allinone}"
IMAGE_TAG="${IMAGE_TAG:-dataforge-allinone:1.0}"
IMAGE_VER="${IMAGE_VER:-1.0}"
REGISTRY="${REGISTRY:-${DEFAULT_REGISTRY:-}}"
# 仅全量构建使用;不设置 / 置空 / 置 `off` 均走直连(本机无法连通代理时必须直连)
case "${PROXY:-no_proxy}" in
  no_proxy|off|OFF|none) PROXY="" ;;   # 不设置 / 空 / off => 直连
  *) : ;;
esac
FULL="${FULL:-0}"                              # 1=全量重建,0=仅更新 jar(默认)
SKIP_TESTS="${SKIP_TESTS:-true}"
JAR_NAME="${JAR_NAME:-dataforge-server-1.0.0-SNAPSHOT.jar}"
HOST_JAR="$SERVER_DIR/target/$JAR_NAME"
# 宿主机编译产物统一落此处, Dockerfile 直接 COPY build/dist/dataforge-server.jar
SERVER_JAR_IN_DIST="$DIST_DIR/dataforge-server.jar"

# ---------- 宿主机工具链定位 ----------
# dataforge-server 的编译在 Docker 之外完成,两种模式都依赖宿主机的 mvn 与 JDK 17+。
ensure_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    local d
    for d in /opt/homebrew/bin /usr/local/bin "$HOME/.sdkman/candidates/maven/current/bin"; do
      if [ -x "$d/mvn" ]; then PATH="$d:$PATH"; export PATH; break; fi
    done
  fi
  command -v mvn >/dev/null 2>&1 || {
    echo "!! 未找到 mvn: dataforge-server 在宿主机编译,请安装 Maven 并确保其可执行文件在 PATH 中"
    exit 1
  }

  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    local found=""
    if [ -x /usr/libexec/java_home ]; then
      found="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
    if [ -z "$found" ]; then
      local j
      for j in /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do
        if [ -x "$j/bin/java" ]; then found="$j"; break; fi
      done
    fi
    if [ -n "$found" ]; then
      JAVA_HOME="$found"; export JAVA_HOME
      echo "==> 未显式设置 JAVA_HOME, 自动使用 $JAVA_HOME"
    fi
  fi

  local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/java}"
  [ -n "$java_bin" ] || java_bin="java"
  "$java_bin" -version >/dev/null 2>&1 || {
    echo "!! 找不到可用的 java(JAVA_HOME=${JAVA_HOME:-<未设置>}),请设置 JAVA_HOME 指向 JDK 17+"
    exit 1
  }
}

command -v docker >/dev/null 2>&1 || { echo "!! 未找到 docker"; exit 1; }
ensure_maven

# ---------- 平台解析与 push/load 模式 ----------
HOST_ARCH="$(docker info --format '{{.Architecture}}' 2>/dev/null || uname -m)"
case "$HOST_ARCH" in
  x86_64|amd64)            HOST_PLATFORM="linux/amd64" ;;
  aarch64|arm64)           HOST_PLATFORM="linux/arm64" ;;
  armv7l|arm)              HOST_PLATFORM="linux/arm/v7" ;;
  *)                       HOST_PLATFORM="linux/amd64" ;;
esac
PLATFORMS="${PLATFORMS:-${DEFAULT_PLATFORMS:-}}"
if [ -z "$PLATFORMS" ]; then
  PLATFORMS="$HOST_PLATFORM"          # 单平台默认本机架构
  MULTI_ARCH=0
  FULL_TAG="$IMAGE_TAG"
else
  IFS=',' read -ra _plats <<< "${PLATFORMS// /}"
  if [ "${#_plats[@]}" -gt 1 ]; then  # 逗号分隔 => 多平台, 须 --push 到仓库
    MULTI_ARCH=1
    [ -n "$REGISTRY" ] || { echo "!! 多平台构建必须设置 REGISTRY 才能 --push 到仓库"; exit 1; }
    FULL_TAG="${REGISTRY%/}/$IMAGE_NAME:$IMAGE_VER"
  else
    MULTI_ARCH=0
    FULL_TAG="$IMAGE_TAG"
  fi
fi

# 基础镜像: 多平台 push 时从仓库拉取,单平台时用本地镜像
BASE_IMAGE="$IMAGE_TAG"
[ "$MULTI_ARCH" = "1" ] && BASE_IMAGE="$FULL_TAG"

load_push() {
  [ "$MULTI_ARCH" = "1" ] && echo "--push" || echo "--load"
}

# ---------- Spark 写入合规监听器构建 ----------
# 从 SPARK_LISTENER_DIR 构建 core + spark-3.5 适配层, 产物落到 build/dist/spark-listeners/,
# 由 Dockerfile 放进镜像的 /opt/spark/jars/, 使容器内 Spark 全局装载写入合规监听器
# (spark.sql.extensions + spark.sql.queryExecutionListeners + spark.write.compliance.*)。
ensure_spark_listener() {
  if [ "${SKIP_SPARK_LISTENER:-0}" = "1" ]; then
    echo "==> 跳过监听器构建(SKIP_SPARK_LISTENER=1), 沿用 $SPARK_LISTENER_JARS_DIR"
    if [ -z "$(ls -A "$SPARK_LISTENER_JARS_DIR" 2>/dev/null)" ]; then
      echo "!! $SPARK_LISTENER_JARS_DIR 为空, 不能跳过构建(否则镜像内没有监听器 jar)"; exit 1
    fi
    return 0
  fi

  local src="$SPARK_LISTENER_DIR"
  if [ ! -f "$src/pom.xml" ]; then
    echo "!! 未找到 spark-listener 工程: $src (缺少 pom.xml)"
    echo "   用 SPARK_LISTENER_DIR=/path/to/spark-listener 指定源码目录"
    exit 1
  fi

  echo "==> 构建 Spark 写入合规监听器: $src (modules=$SPARK_LISTENER_MODULES)"
  ( cd "$src" && mvn -q -DskipTests -pl "$SPARK_LISTENER_MODULES" -am install )

  mkdir -p "$SPARK_LISTENER_JARS_DIR"
  local module built base ver
  for module in ${SPARK_LISTENER_MODULES//,/ }; do
    built="$(ls -1 "$src/$module/target/$module-"*.jar 2>/dev/null | grep -v -- '-sources' | head -1)"
    if [ -z "$built" ]; then
      echo "!! 未找到构建产物: $src/$module/target/$module-*.jar"; exit 1
    fi
    base="$(basename "$built")"; base="${base%.jar}"; ver="${base##*-}"
    cp -f "$built" "$SPARK_LISTENER_JARS_DIR/spark-write-compliance-$module-$ver.jar"
  done
  ls -l "$SPARK_LISTENER_JARS_DIR"
}

# ---------- 宿主机编译 dataforge-server ----------
# 编译已移出 Docker: 用宿主机 mvn `package`,产物拷到 build/dist/dataforge-server.jar,
# Dockerfile 只做 COPY。两种构建模式都走这里。
build_server_jar() {
  echo "==> 宿主机 mvn 编译 dataforge-server (skipTests=$SKIP_TESTS)"
  local version_args; version_args="$(version_mvn_args)"
  if [ -n "$version_args" ]; then
    echo "==> 组件版本覆盖:$(version_env_names)"
  fi
  if [ "$SKIP_TESTS" = "true" ]; then
    (cd "$SERVER_DIR" && mvn -q -DskipTests $version_args package)
  else
    (cd "$SERVER_DIR" && mvn -q $version_args package)
  fi
  [ -f "$HOST_JAR" ] || { echo "!! 打包产物不存在: $HOST_JAR"; exit 1; }
  mkdir -p "$DIST_DIR"
  cp -f "$HOST_JAR" "$SERVER_JAR_IN_DIST"
  echo "    产物: build/dist/dataforge-server.jar ($(wc -c < "$SERVER_JAR_IN_DIST" | tr -d ' ') 字节)"
}

echo "==> 平台: $PLATFORMS (host=$HOST_PLATFORM) | 目标: $FULL_TAG | 模式: $([ "$MULTI_ARCH" = 1 ] && echo "多平台 push" || echo "单平台 load")"

# ---------- 1) jar 增量模式(默认) ----------
build_jar_only() {
  if [ "$MULTI_ARCH" = "0" ] && ! docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
    echo "!! 基础镜像 $BASE_IMAGE 不存在,请先执行: FULL=1 ./build.sh"; exit 1
  fi

  echo "==> 1/4 构建 Spark 写入合规监听器 jar"
  ensure_spark_listener

  echo "==> 2/4 宿主机编译 dataforge-server jar"
  build_server_jar

  # 搭建独立的精简构建上下文(仅 jar + Dockerfile),避免 dataforge-server/.dockerignore
  # 中 `**/target/` 排除 jar,也不把 target 其余文件送入上下文。
  # 同时带上监听器 jar / Spark 默认配置 / 容器脚本: 三者都能随增量构建刷新,
  # 无需 FULL=1 重建基础镜像(基础镜像里已有同名文件,这里是覆盖)。
  local staging; staging="$(mktemp -d)"
  cp "$SERVER_JAR_IN_DIST" "$staging/dataforge-server.jar"
  cp "$BUILD_DIR/conf/spark-defaults.conf" "$staging/spark-defaults.conf"
  cp -R "$SPARK_LISTENER_JARS_DIR" "$staging/spark-listeners"
  cp -R "$BUILD_DIR/scripts" "$staging/scripts"
  cat > "$staging/Dockerfile" <<'DOCKEREOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY dataforge-server.jar /opt/dataforge/dataforge-server.jar
COPY spark-defaults.conf /opt/spark/conf/spark-defaults.conf
COPY spark-listeners/ /opt/spark/jars/
COPY scripts/ /opt/dataforge/scripts/
RUN chmod +x /opt/dataforge/scripts/*.sh
DOCKEREOF

  echo "==> 3/4 基于 $BASE_IMAGE 覆盖新 jar 构建 $FULL_TAG"
  docker buildx build --platform "$PLATFORMS" \
    $(load_push) \
    --provenance=false --sbom=false \
    --build-arg "BASE_IMAGE=$BASE_IMAGE" \
    -t "$FULL_TAG" -f "$staging/Dockerfile" "$staging"

  rm -rf "$staging"
  echo "==> 4/4 完成: $FULL_TAG(已更新 dataforge-server jar / 监听器 jar / Spark 默认配置 / 容器脚本)"
}

# ---------- 2) 全量重建(基础镜像) ----------
ensure_dist() {
  mkdir -p "$DIST_DIR"
  fetch_one() {
    local name="${1:?}" url="${2:?}"
    if [ -f "$DIST_DIR/$name" ]; then echo "    已存在: build/dist/$name"; return 0; fi
    echo "==> 下载发行包: $name"
    if [ -n "$PROXY" ]; then curl -fsSL --proxy "$PROXY" -o "$DIST_DIR/$name" "$url"
    else curl -fsSL -o "$DIST_DIR/$name" "$url"; fi
  }
  fetch_one spark-3.5.9-bin-hadoop3.tgz \
            https://archive.apache.org/dist/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz
  fetch_one apache-dolphinscheduler-3.4.1-bin.tar.gz \
            https://archive.apache.org/dist/dolphinscheduler/3.4.1/apache-dolphinscheduler-3.4.1-bin.tar.gz
  fetch_one marquez-0.51.1.tar.gz \
            https://github.com/MarquezProject/marquez/archive/refs/tags/0.51.1.tar.gz
  # DolphinScheduler 3.4.1 发行包不内置任务插件(plugins/为空)!
  # 必须单独补下 Spark 任务插件(shade 包),放入 plugins/task-plugins/,
  # 否则创建 SPARK 任务会报: Cannot find TaskChannel for : SPARK
  fetch_one dolphinscheduler-task-spark-3.4.1-shade.jar \
            https://repo1.maven.org/maven2/org/apache/dolphinscheduler/dolphinscheduler-task-spark/3.4.1/dolphinscheduler-task-spark-3.4.1-shade.jar
}

build_full() {
  echo "==> 检查/补下本地发行包($DIST_DIR/)"
  ensure_dist

  echo "==> 构建 Spark 写入合规监听器"
  ensure_spark_listener

  echo "==> 宿主机编译 dataforge-server"
  build_server_jar

  # docker-container 构建器容器内通过 host.docker.internal 访问宿主代理
  HOST_PROXY="http://host.docker.internal:7890"
  # Dockerfile: build/Dockerfile; 构建上下文 = 项目根 $SERVER_DIR
  # (需要 build/{conf,scripts,sql,dist} 与 src/main/resources/sql/ 下的初始化脚本;
  #  dataforge-server 的 pom.xml / src 已不参与镜像内编译,由宿主机 mvn 负责)
  local args=(-t "$FULL_TAG" --platform "$PLATFORMS" $(load_push) \
    --provenance=false --sbom=false -f "$BUILD_DIR/Dockerfile")

  if [ -n "$PROXY" ]; then
    echo "==> 宿代理: $PROXY -> buildkit 内地址: $HOST_PROXY(请确认代理已开启)"
    args+=(--build-arg "BUILD_HTTP_PROXY=$HOST_PROXY" --build-arg "BUILD_HTTPS_PROXY=$HOST_PROXY")
  else
    echo "==> 直连模式(不使用代理)"
  fi

  local builder=""
  if [ -n "$PROXY" ] && [ "$MULTI_ARCH" = "0" ]; then
    echo "==> 准备 buildx 构建器(proxied)"
    if ! docker buildx inspect proxied >/dev/null 2>&1; then
      docker buildx create --name proxied --driver docker-container \
        --driver-opt env.http_proxy="$HOST_PROXY" --driver-opt env.https_proxy="$HOST_PROXY" \
        --driver-opt env.HTTP_PROXY="$HOST_PROXY" --driver-opt env.HTTPS_PROXY="$HOST_PROXY"
    fi
    builder="--builder proxied"
  fi

  echo "==> 全量构建 $FULL_TAG(镜像内仅剩 Marquez 源码构建, 耗时较长)"
  docker buildx build $builder "${args[@]}" "$SERVER_DIR"
  echo "==> 全量构建完成: $FULL_TAG"
}

# ---------- 统一执行 ----------
echo "==> 检查 buildx"
docker buildx version >/dev/null 2>&1 || { echo "!! 未启用 buildx,请安装 Docker Buildx"; exit 1; }

if [ "$FULL" = "1" ]; then
  build_full
else
  build_jar_only
fi

echo
echo "下一步: ./startup/macos/start.sh (默认无卷) 或 ./startup/macos/start-persist.sh (持久化); ./startup/macos/stop.sh 停止; ./startup/macos/clear-data.sh 清旧卷数据"
echo "日志:   docker logs -f dataforge-allinone"
echo "热替换(不改镜像): $SCRIPT_DIR/hot-update-dataforge-server.sh"