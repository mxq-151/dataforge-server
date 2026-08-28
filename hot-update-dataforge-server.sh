#!/usr/bin/env bash
# ============================================================
# dataforge-server 热更新脚本
#
# 用途: 源码变更后, 本地 mvn 重新打包 -> docker cp 进容器 -> 重启容器。
#       (相比 dataforge-docker/build.sh 全量镜像重建, 耗时短, 适合日常迭代)
#
# 用法:
#   ./hot-update-dataforge-server.sh                    # 默认容器 dataforge-allinone
#   CONTAINER=my-ds ./hot-update-dataforge-server.sh    # 指定容器
#   SKIP_TESTS=false ./hot-update-dataforge-server.sh   # 打包时执行单测
#
# 说明:
#   * 热替换只写入容器可写层, 不会固化进镜像;
#     之后重建镜像(build.sh)会覆盖, 需要重新执行本脚本
#   * 替换前自动备份容器内旧 jar 到容器 /opt/dataforge/ 下(带时间戳)
#   * 重启容器会连带重启 PostgreSQL / Marquez / DolphinScheduler 等进程
# ============================================================
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"

CONTAINER="${CONTAINER:-dataforge-allinone}"
CONTAINER_JAR_PATH="${CONTAINER_JAR_PATH:-/opt/dataforge/dataforge-server.jar}"
SKIP_TESTS="${SKIP_TESTS:-true}"
JAR_NAME="dataforge-server-1.0.0-SNAPSHOT.jar"
HOST_JAR="$SERVER_DIR/target/$JAR_NAME"
PORT="${PORT:-8082}"

command -v mvn >/dev/null 2>&1 || { echo "!! 未找到 mvn, 请先安装 Maven"; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "!! 未找到 docker"; exit 1; }

echo "==> 1/5 本地打包 dataforge-server (skipTests=$SKIP_TESTS)"
if [ "$SKIP_TESTS" = "true" ]; then
  (cd "$SERVER_DIR" && mvn -q -DskipTests package)
else
  (cd "$SERVER_DIR" && mvn -q package)
fi
[ -f "$HOST_JAR" ] || { echo "!! 打包产物不存在: $HOST_JAR"; exit 1; }

echo "==> 2/5 检查容器 $CONTAINER"
docker inspect "$CONTAINER" >/dev/null 2>&1 || { echo "!! 容器不存在: $CONTAINER"; exit 1; }

echo "==> 3/5 备份容器内旧 jar"
docker exec "$CONTAINER" sh -c "cp -f $CONTAINER_JAR_PATH ${CONTAINER_JAR_PATH}.bak.\$(date +%Y%m%d%H%M%S)" \
  || { echo "!! 备份旧 jar 失败"; exit 1; }

echo "==> 4/5 热替换 jar 并重启容器"
docker cp "$HOST_JAR" "$CONTAINER:$CONTAINER_JAR_PATH"
docker restart "$CONTAINER"

echo "==> 5/5 等待 dataforge-server 启动 (端口 $PORT)"
READY=""
for i in $(seq 1 40); do
  if docker exec "$CONTAINER" sh -c 'pgrep -f dataforge-server.jar >/dev/null' 2>/dev/null; then
    CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/system/auth/login" 2>/dev/null || true)"
    case "$CODE" in
      200|400|401|405) READY=1; echo "    dataforge-server 已就绪 (第 ${i} 次探测, HTTP $CODE)"; break ;;
    esac
  fi
  sleep 3
done
[ -n "$READY" ] || { echo "!! 等待超时, 请查看容器日志: docker logs -f $CONTAINER"; exit 1; }

echo "==> 完成"
echo "    新 jar: $JAR_NAME (容器内备份: $CONTAINER_JAR_PATH.bak.*)"
