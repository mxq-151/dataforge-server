#!/bin/bash
# ============================================================
# dataforge 容器入口(ENTRYPOINT)
#
# 职责：把**镜像内**的 sql/、scripts/ 与 dataforge-server.jar 同步到 /opt/dataforge，
#       然后执行 CMD(supervisord)。
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
# jar 同步策略（v2）
#   以前 jar 刻意不同步，只能靠 build/hot-update-dataforge-server.sh 手动热替换；
#   但这样"重建镜像 + 持久化卷"场景下，卷里几个月前的旧 jar 会遮住镜像新 jar，
#   表现为接口 404（实测：integration 接口全部 404，jar 里 0 个 integration 类）。
#   现在启动时按 **大小 + SHA256** 对比 /opt/dataforge-image/dataforge-server.jar
#   与 /opt/dataforge/dataforge-server.jar：
#     - 一致          -> 跳过，仅打一行日志（幂等，不重复拷贝）
#     - 不一致/缺失   -> 先把卷内旧 jar 备份为 dataforge-server.jar.bak.<时间戳>，
#                        再以临时文件 + mv 原子替换；失败时回滚并打印 ERROR
#     - 无卷模式（两者同设备）-> 直接跳过，不产生额外 IO
#   备份保留份数由 DATAFORGE_JAR_BACKUP_KEEP 控制（默认 3，只删更早的）。
#
# 环境变量
#   DATAFORGE_JAR_SYNC=auto|force|off   默认 auto
#     auto  仅在 jar 不一致时替换（推荐）
#     force 无条件用镜像 jar 覆盖（仍会备份，便于排障）
#     off   关闭同步，完全沿用卷内现有 jar（保留 hot-update 手动控制能力）
#   DATAFORGE_JAR_BACKUP_KEEP=<n>       默认 3，保留最近 n 份 .bak
#
# 注意：hot-update-dataforge-server.sh 会同时更新 /opt/dataforge 与 /opt/dataforge-image
#       两个路径，使两边一致；否则下次重启会被本脚本按"镜像为准"回退。
# ============================================================
set -u

IMAGE_DIR=/opt/dataforge-image
DATA_DIR=/opt/dataforge

JAR_SYNC_MODE="${DATAFORGE_JAR_SYNC:-auto}"
JAR_BACKUP_KEEP="${DATAFORGE_JAR_BACKUP_KEEP:-3}"

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

# 输出 "<字节数>:<sha256>"；sha256sum 不可用时退化为 "<字节数>:nosha"。
file_signature() {
  local file="$1"
  local size
  size=$(stat -c %s "$file" 2>/dev/null) || return 1
  if command -v sha256sum >/dev/null 2>&1; then
    local hash
    hash=$(sha256sum "$file" 2>/dev/null | cut -d' ' -f1)
    [ -n "$hash" ] || return 1
    printf '%s:%s' "$size" "$hash"
  else
    printf '%s:nosha' "$size"
  fi
}

# 只保留最近 $1 份 dataforge-server.jar.bak.*，避免备份无限堆积。
prune_jar_backups() {
  local keep="$1"
  local dst="$DATA_DIR/dataforge-server.jar"
  case "$keep" in
    ''|*[!0-9]*) keep=3 ;;
  esac
  local index=0
  local file
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    index=$((index + 1))
    if [ "$index" -gt "$keep" ]; then
      rm -f "$file" && echo "[entrypoint] 清理旧 jar 备份: $(basename "$file")"
    fi
  done <<EOF
$(ls -1t "$dst".bak.* 2>/dev/null || true)
EOF
}

sync_server_jar() {
  local src="$IMAGE_DIR/dataforge-server.jar"
  local dst="$DATA_DIR/dataforge-server.jar"

  case "$JAR_SYNC_MODE" in
    off)
      echo "[entrypoint] jar 同步已关闭(DATAFORGE_JAR_SYNC=off)，沿用卷内现有 jar"
      return 0
      ;;
    auto|force) ;;
    *)
      echo "[entrypoint] WARN: 未知 DATAFORGE_JAR_SYNC=${JAR_SYNC_MODE}，按 auto 处理" >&2
      JAR_SYNC_MODE=auto
      ;;
  esac

  if [ ! -f "$src" ]; then
    echo "[entrypoint] WARN: 镜像内未找到 ${src}，跳过 jar 同步(沿用卷内 jar)" >&2
    return 0
  fi

  # 无卷模式：/opt/dataforge 与 /opt/dataforge-image 同属镜像层(同 st_dev)，
  # 不存在"旧卷遮新镜像"的问题，直接跳过，避免每次启动多读 ~800MB。
  if [ "$JAR_SYNC_MODE" = auto ] && [ -f "$dst" ] \
     && [ "$(stat -c %d "$src" 2>/dev/null)" = "$(stat -c %d "$dst" 2>/dev/null)" ]; then
    echo "[entrypoint] 无卷模式(与镜像同设备)，跳过 jar 同步"
    return 0
  fi

  if [ "$JAR_SYNC_MODE" = auto ] && [ -f "$dst" ]; then
    local src_sig dst_sig
    src_sig=$(file_signature "$src") || { echo "[entrypoint] WARN: 计算镜像 jar 摘要失败，跳过同步(沿用卷内 jar)" >&2; return 0; }
    dst_sig=$(file_signature "$dst") || { echo "[entrypoint] WARN: 计算卷内 jar 摘要失败，跳过同步(沿用卷内 jar)" >&2; return 0; }
    if [ "$src_sig" = "$dst_sig" ]; then
      echo "[entrypoint] jar 已一致(${src_sig})，跳过同步"
      return 0
    fi
    echo "[entrypoint] jar 不一致: 镜像=${src_sig} 卷内=${dst_sig}，准备替换"
  fi

  local tmp="${dst}.tmp.$$"
  if ! cp -f "$src" "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    echo "[entrypoint] ERROR: 拷贝镜像 jar 失败(${src} -> ${tmp})，沿用现有 jar 继续启动" >&2
    return 0
  fi

  local bak=""
  if [ -f "$dst" ]; then
    bak="${dst}.bak.$(date +%Y%m%d%H%M%S)"
    if ! mv -f "$dst" "$bak" 2>/dev/null; then
      rm -f "$tmp"
      echo "[entrypoint] ERROR: 备份旧 jar 失败(${dst} -> ${bak})，未做替换" >&2
      return 0
    fi
  fi

  if ! mv -f "$tmp" "$dst" 2>/dev/null; then
    rm -f "$tmp"
    if [ -n "$bak" ] && [ -f "$bak" ]; then
      mv -f "$bak" "$dst" 2>/dev/null && echo "[entrypoint] ERROR: 替换 jar 失败，已回滚到旧 jar" >&2
    else
      echo "[entrypoint] ERROR: 写入 ${dst} 失败，镜像内 jar 未能落盘" >&2
    fi
    return 0
  fi

  local new_sig
  new_sig=$(file_signature "$dst" 2>/dev/null || echo "unknown")
  echo "[entrypoint] jar 已更新为镜像版本(${new_sig})${bak:+，旧版备份: $(basename "$bak")}"
  prune_jar_backups "$JAR_BACKUP_KEEP"
}

sync_one sql
sync_one scripts
chmod +x "$DATA_DIR"/scripts/*.sh 2>/dev/null || true

sync_server_jar

# 基础镜像(eclipse-temurin)自带 /__cacert_entrypoint.sh(导入自定义 CA 证书后 exec CMD)。
# 我们接管 ENTRYPOINT 后必须把它接回链路，否则会丢掉证书导入这一步。
if [ -x /__cacert_entrypoint.sh ]; then
  exec /__cacert_entrypoint.sh "$@"
fi
exec "$@"
