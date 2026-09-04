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
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"  # 本目录 build/(Dockerfile / dist / conf / scripts / sql)
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"                  # 项目根 dataforge-server/(构建上下文: pom / src 在其下)
BUILD_DIR="$SCRIPT_DIR"
DIST_DIR="$BUILD_DIR/dist"

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

command -v docker >/dev/null 2>&1 || { echo "!! 未找到 docker"; exit 1; }
command -v mvn   >/dev/null 2>&1 || { echo "!! 未找到 mvn, jar 增量模式需要宿主机 Maven(或改用 FULL=1 由容器内编译)"; exit 1; }

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

echo "==> 平台: $PLATFORMS (host=$HOST_PLATFORM) | 目标: $FULL_TAG | 模式: $([ "$MULTI_ARCH" = 1 ] && echo "多平台 push" || echo "单平台 load")"

# ---------- 1) jar 增量模式(默认) ----------
build_jar_only() {
  if [ "$MULTI_ARCH" = "0" ] && ! docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
    echo "!! 基础镜像 $BASE_IMAGE 不存在,请先执行: FULL=1 ./build.sh"; exit 1
  fi

  echo "==> 1/3 宿主机 mvn 编译 dataforge-server (skipTests=$SKIP_TESTS)"
  if [ "$SKIP_TESTS" = "true" ]; then
    (cd "$SERVER_DIR" && mvn -q -DskipTests package)
  else
    (cd "$SERVER_DIR" && mvn -q package)
  fi
  [ -f "$HOST_JAR" ] || { echo "!! 打包产物不存在: $HOST_JAR"; exit 1; }

  # 搭建独立的精简构建上下文(仅 jar + Dockerfile),避免 dataforge-server/.dockerignore
  # 中 `**/target/` 排除 jar,也不把 target 其余文件送入上下文。
  local staging; staging="$(mktemp -d)"
  cp "$HOST_JAR" "$staging/dataforge-server.jar"
  cat > "$staging/Dockerfile" <<'DOCKEREOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY dataforge-server.jar /opt/dataforge/dataforge-server.jar
DOCKEREOF

  echo "==> 2/3 基于 $BASE_IMAGE 覆盖新 jar 构建 $FULL_TAG"
  docker buildx build --platform "$PLATFORMS" \
    $(load_push) \
    --build-arg "BASE_IMAGE=$BASE_IMAGE" \
    -t "$FULL_TAG" -f "$staging/Dockerfile" "$staging"

  rm -rf "$staging"
  echo "==> 3/3 完成: $FULL_TAG(已仅更新 dataforge-server jar)"
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

  # docker-container 构建器容器内通过 host.docker.internal 访问宿主代理
  HOST_PROXY="http://host.docker.internal:7890"
  # Dockerfile: build/Dockerfile; 构建上下文 = 项目根 $SERVER_DIR(需 src / pom 供 app-builder 阶段)
  local args=(-t "$FULL_TAG" --platform "$PLATFORMS" $(load_push) -f "$BUILD_DIR/Dockerfile")

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

  echo "==> 全量构建 $FULL_TAG(编译 dataforge-server / Marquez 依赖耗时较长)"
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