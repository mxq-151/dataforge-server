#!/usr/bin/env bash
# ============================================================
# dataforge All-in-One 镜像构建脚本(调整版)
#
# 相比原 dataforge-docker/build.sh 的改进:
#   * 默认只增量更新 dataforge-server 的 jar:宿主机 mvn 重编译 -> 基于已有
#     基础镜像 Dockerfile 覆盖新 jar 生成新镜像,秒级完成,适合日常迭代。
#   * 支持多平台多架构构建:通过 buildx --platform 构建;
#     单平台 --load 载入本地,多平台(PLATFORMS 含逗号) --push 到 REGISTRY。
#   * FULL=1 时才全量重建基础镜像(Marquez gradle/web + Spark +
#     DolphinScheduler + PostgreSQL + dataforge-server),首次或基础变更时执行。
#
# 用法:
#   ./build.sh                                                     # 默认:仅更新 jar(最快)
#   FULL=1 ./build.sh                                              # 全量重建基础镜像
#   FULL=1 BUILD_BASE_IMAGE=... ./build.sh
#   PLATFORMS="linux/amd64,linux/arm64" REGISTRY=myrepo ./build.sh # 多平台构建并 push
#   PLATFORMS="linux/arm64" ./build.sh                             # 指定单平台构建
#   FULL=1 PLATFORMS="linux/amd64,linux/arm64" REGISTRY=myrepo ./build.sh
#   PROXY="" ./build.sh                                            # 直连(全量构建网络不受限时)
#   SKIP_TESTS=false ./build.sh                                    # mvn 打包时执行单测
#
# 多平台须知:
#   * 构建多架构须本机已注册 qemu(binfmt):
#       docker run --privileged --rm tonistiigi/binfmt --install all
#   * 多平台镜像无法 --load 到本地 Docker,必须给出 REGISTRY 并 --push;
#     后续 docker compose 需改用 pull 的镜像。
#   * 首次多平台使用 jar 增量模式前,请先用 FULL=1 + REGISTRY push 好基础镜像。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"     # dataforge-docker(基础 Dockerfile / dist / scripts / conf)

IMAGE_NAME="${IMAGE_NAME:-dataforge-allinone}"
IMAGE_TAG="${IMAGE_TAG:-dataforge-allinone:1.0}"
IMAGE_VER="${IMAGE_VER:-1.0}"
REGISTRY="${REGISTRY:-}"                       # 多平台 push 的仓库前缀,如 docker.io/me
PROXY="${PROXY:-http://127.0.0.1:7890}"        # 仅 FULL=1 全量构建使用
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
PLATFORMS="${PLATFORMS:-$HOST_PLATFORM}"

IFS=',' read -ra _plats <<< "${PLATFORMS// /}"
if [ "${#_plats[@]}" -gt 1 ]; then
  MULTI_ARCH=1
  [ -n "$REGISTRY" ] || { echo "!! 多平台构建必须设置 REGISTRY 才能 --push 到仓库"; exit 1; }
  FULL_TAG="${REGISTRY%/}/$IMAGE_NAME:$IMAGE_VER"
else
  MULTI_ARCH=0
  FULL_TAG="$IMAGE_TAG"
fi

# 基础镜像:多平台 push 时从仓库拉取,单平台时用本地镜像
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

  # 搭建独立的精简构建上下文(仅 jar + Dockerfile),避免父目录 .dockerignore
  # 中 `**/target/` 排除 jar,也不把 dataforge-server/target 其余文件送入上下文。
  local staging; staging="$(mktemp -d)"
  trap 'rm -rf "$staging"' EXIT
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

  echo "==> 3/3 完成: $FULL_TAG(已仅更新 dataforge-server jar)"
}

# ---------- 2) 全量重建(基础镜像) ----------
ensure_dist() {
  mkdir -p "$DOCKER_DIR/dist"
  fetch_one() {
    local name="${1:?}" url="${2:?}"
    if [ -f "$DOCKER_DIR/dist/$name" ]; then echo "    已存在: dist/$name"; return 0; fi
    echo "==> 下载发行包: $name"
    if [ -n "$PROXY" ]; then curl -fsSL --proxy "$PROXY" -o "$DOCKER_DIR/dist/$name" "$url"
    else curl -fsSL -o "$DOCKER_DIR/dist/$name" "$url"; fi
  }
  fetch_one spark-3.5.9-bin-hadoop3.tgz \
            https://archive.apache.org/dist/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz
  fetch_one apache-dolphinscheduler-3.4.1-bin.tar.gz \
            https://archive.apache.org/dist/dolphinscheduler/3.4.1/apache-dolphinscheduler-3.4.1-bin.tar.gz
  fetch_one marquez-0.51.1.tar.gz \
            https://github.com/MarquezProject/marquez/archive/refs/tags/0.51.1.tar.gz
}

build_full() {
  echo "==> 检查/补下本地发行包($DOCKER_DIR/dist/)"
  ensure_dist

  # docker-container 构建器容器内通过 host.docker.internal 访问宿主代理
  HOST_PROXY="http://host.docker.internal:7890"
  local args=(-t "$FULL_TAG" --platform "$PLATFORMS" $(load_push) -f "$DOCKER_DIR/Dockerfile")

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
  docker buildx build $builder "${args[@]}" "$DOCKER_DIR"
  echo "==> 全量构建完成: $FULL_TAG"
}

# ---------- 统一入口 ----------
echo "==> 检查 buildx"
docker buildx version >/dev/null 2>&1 || { echo "!! 未启用 buildx,请安装 Docker Buildx"; exit 1; }

if [ "$FULL" = "1" ]; then
  build_full
else
  build_jar_only
fi

echo
echo "下一步: docker compose up -d (-f $DOCKER_DIR/docker-compose.yml)"
echo "日志:   docker logs -f dataforge-allinone"
echo "热替换(不改镜像): $SERVER_DIR/hot-update-dataforge-server.sh"