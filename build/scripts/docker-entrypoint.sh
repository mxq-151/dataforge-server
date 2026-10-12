#!/bin/bash
# ============================================================
# dataforge 容器入口(ENTRYPOINT)
#
# 职责：把**镜像内**的 sql/ 与 scripts/ 同步到 /opt/dataforge，然后执行 CMD(supervisord)。
#
# 为什么必须存在
#   /opt/dataforge 常被命名卷 df-data 挂载，而 Docker 只在卷**首次创建**时用镜像内容
#   播种，之后无论重建多少次镜像都不会刷新卷里这些文件。于是"重建镜像 + 重启容器"
#   依然跑着旧 SQL / 旧脚本 —— 实测踩过：镜像层里 dataforge-postgresql.sql 已是新版，
#   容器里读到的却是 9 月的旧版，建表缺列，线上报 column "xxx" does not exist，
#   而根因排查时会被误判成"镜像没重建"。
#   镜像内的 /opt/dataforge-image 不被任何卷挂载，这里每次启动都以它为准覆盖卷内副本，
#   使 **镜像永远是权威版本**，不必再依赖"重建镜像要连卷一起删"这种人工约定。
#
# 不同步 dataforge-server.jar
#   该文件由 build/hot-update-dataforge-server.sh 热替换，若每次启动都用镜像版本覆盖，
#   热更新会在下次重启时被回退。
# ============================================================
set -u

IMAGE_DIR=/opt/dataforge-image
DATA_DIR=/opt/dataforge

sync_one() {
  # 注意：不能写成 `local name="$1" src=.../$name` —— bash 的 local 会先把这些变量
  # 一起置为未定义再求值右侧，配合 set -u 会直接报 `name: unbound variable` 并退出。
  local name="$1"
  local src="$IMAGE_DIR/$name"
  local dst="$DATA_DIR/$name"
  [ -d "$src" ] || return 0
  mkdir -p "$dst"
  if ! cp -a "$src/." "$dst/" 2>/dev/null; then
    echo "[entrypoint] WARN: 同步 $name 失败(镜像版本 ${src} -> ${dst})" >&2
  fi
}

sync_one sql
sync_one scripts
chmod +x "$DATA_DIR"/scripts/*.sh 2>/dev/null || true

# 基础镜像(eclipse-temurin)自带 /__cacert_entrypoint.sh(导入自定义 CA 证书后 exec CMD)。
# 我们接管 ENTRYPOINT 后必须把它接回链路，否则会丢掉证书导入这一步。
if [ -x /__cacert_entrypoint.sh ]; then
  exec /__cacert_entrypoint.sh "$@"
fi
exec "$@"
