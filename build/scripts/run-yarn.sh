#!/bin/bash
# ============================================================
# dataforge 容器内 YARN 启停脚本（伪分布式：ResourceManager + NodeManager）
#
# 由 supervisord 以 program:yarn 拉起，**前台**运行两个守护进程（不 fork），
# 因此 supervisord 的 stop/restart 能直接作用在这两个 JVM 上（见 stopasgroup/killasgroup）。
#
# 职责：
#   1. 用环境变量渲染 yarn-site.xml（/opt/hadoop/etc/hadoop/yarn-site.xml）
#   2. 准备 YARN 运行目录（卷挂载后需要重新 chown 给运行用户）
#   3. 先起 RM，等 /ws/v1/cluster/info 就绪；再起 NM，等 /ws/v1/node/info 就绪
#   4. 任一进程退出 -> 停掉另一个并以非 0 退出（交给 supervisord 重启）
#   5. 收到 SIGTERM/SIGINT -> 先 TERM 两个守护进程，等 60s，再 KILL
#
# 运行用户：默认 test_tenant（与 DolphinScheduler 租户一致）。
#   未启用 LinuxContainerExecutor（容器内无 setuid container-executor），
#   YARN 只能以 NM 进程的用户身份拉起容器，所以**提交作业也必须用 test_tenant**：
#     sudo -u test_tenant spark-submit --master yarn ...
#   以 root 提交会失败：Can't run container ... as user root。
#
# 环境变量：
#   YARN_ENABLED=true|false     默认 true；false 时本脚本打印原因后正常退出(不重启)
#   YARN_NM_MEMORY_MB=4096      可调度内存上限
#   YARN_NM_VCORES=2            可调度 vcore 上限
#   YARN_DATA_DIR=/var/lib/dataforge-yarn   NM local-dirs / tmp（临时，容器重建即丢）
#   YARN_LOG_DIR=/opt/dataforge/yarn/logs   聚合日志落盘（df-data 卷，可持久）
#   YARN_USER=test_tenant       YARN 守护进程与容器运行用户
#   YARN_READY_TIMEOUT=180      单个守护进程就绪等待上限（秒）
# ============================================================
set -u

export HADOOP_HOME="${HADOOP_HOME:-/opt/hadoop}"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}"
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export HADOOP_LOG_DIR="${HADOOP_LOG_DIR:-/var/log/dataforge/yarn}"
export HADOOP_PID_DIR="${HADOOP_PID_DIR:-/var/lib/dataforge-yarn/pids}"
export HADOOP_IDENT_STRING="${HADOOP_IDENT_STRING:-dataforge-yarn}"

YARN_USER="${YARN_USER:-test_tenant}"
YARN_DATA_DIR="${YARN_DATA_DIR:-/var/lib/dataforge-yarn}"
YARN_LOG_DIR="${YARN_LOG_DIR:-/opt/dataforge/yarn/logs}"
NM_MEMORY_MB="${YARN_NM_MEMORY_MB:-4096}"
NM_VCORES="${YARN_NM_VCORES:-2}"
READY_TIMEOUT="${YARN_READY_TIMEOUT:-180}"
RM_WEB="http://127.0.0.1:8088"
NM_WEB="http://127.0.0.1:8042"
READY_FILE=/var/run/dataforge-yarn.ready

log() { echo "[yarn $(date +%H:%M:%S)] $*"; }

# ---------- 1) 渲染 XML 配置（*.xml.tmpl -> *.xml） ----------
# 三个 XML 都用模板，占位符与取值集中在下面，便于对照；构建期也按默认值渲染一份，
# 镜像内直接调 yarn CLI 时同样可用。
render_conf() {
  local name tmpl out rc=0
  for name in core-site.xml mapred-site.xml yarn-site.xml; do
    tmpl="$HADOOP_CONF_DIR/$name.tmpl"
    out="$HADOOP_CONF_DIR/$name"
    if [ ! -f "$tmpl" ]; then
      log "WARN: 未找到模板 $tmpl，沿用现有 $name"
      continue
    fi
    if sed -e "s|__YARN_DATA_DIR__|${YARN_DATA_DIR}|g" \
           -e "s|__YARN_LOG_DIR__|${YARN_LOG_DIR}|g" \
           -e "s|__YARN_NM_MEMORY_MB__|${NM_MEMORY_MB}|g" \
           -e "s|__YARN_NM_VCORES__|${NM_VCORES}|g" \
           "$tmpl" > "$out.tmp"; then
      mv -f "$out.tmp" "$out"
    else
      rm -f "$out.tmp"; log "ERROR: 渲染 $name 失败"; rc=1
    fi
  done
  log "配置已渲染: mem=${NM_MEMORY_MB}MB vcores=${NM_VCORES} data=${YARN_DATA_DIR} log=${YARN_LOG_DIR}"
  return $rc
}

# ---------- 2) 目录准备 ----------
prepare_dirs() {
  local d
  for d in "$YARN_DATA_DIR" "$YARN_DATA_DIR/nm-local" "$YARN_DATA_DIR/nm-logs" \
           "$YARN_DATA_DIR/tmp" "$HADOOP_PID_DIR" "$HADOOP_LOG_DIR" "$YARN_LOG_DIR"; do
    mkdir -p "$d" 2>/dev/null || true
  done
  # 卷(df-data)在容器重建后属主可能仍是 root，这里统一给运行用户
  if id "$YARN_USER" >/dev/null 2>&1; then
    chown -R "$YARN_USER:$YARN_USER" "$YARN_DATA_DIR" "$HADOOP_PID_DIR" "$HADOOP_LOG_DIR" 2>/dev/null || true
    # 聚合日志目录可能很大，只改顶层，避免每次启动递归 chown 拖慢
    chown "$YARN_USER:$YARN_USER" "$YARN_LOG_DIR" 2>/dev/null || true
    chmod 775 "$YARN_LOG_DIR" 2>/dev/null || true
  fi
}

# ---------- 3) 以运行用户前台启动守护进程 ----------
# setpriv 直接 exec（不 fork），因此 $! 就是 java 进程，信号可以直达；
# 没有 setpriv 时退回 su -c。
run_as_yarn_user() {
  local cmd="$1"
  if command -v setpriv >/dev/null 2>&1; then
    local uid gid
    uid="$(id -u "$YARN_USER")"; gid="$(id -g "$YARN_USER")"
    setpriv --reuid="$uid" --regid="$gid" --init-groups --inh-caps=-all \
      env HOME="/home/$YARN_USER" USER="$YARN_USER" LOGNAME="$YARN_USER" \
      bash -c "$cmd"
  else
    su -s /bin/bash "$YARN_USER" -c "$cmd"
  fi
}

wait_healthy() {
  local name="$1" url="$2" pid="$3" waited=0
  while [ "$waited" -lt "$READY_TIMEOUT" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      log "ERROR: $name 进程已退出（等待就绪期间），见 $HADOOP_LOG_DIR"
      return 1
    fi
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      log "$name 就绪: $url"
      return 0
    fi
    sleep 2; waited=$((waited + 2))
    [ $((waited % 20)) -eq 0 ] && log "等待 $name 就绪... ${waited}s"
  done
  log "ERROR: $name 在 ${READY_TIMEOUT}s 内未就绪: $url"
  return 1
}

RM_PID=""
NM_PID=""
STOPPING=0

shutdown() {
  [ "$STOPPING" = "1" ] && return 0
  STOPPING=1
  log "收到停止信号，开始优雅停止 YARN"
  local pids="$NM_PID $RM_PID" p waited
  for p in $pids; do [ -n "$p" ] && kill -TERM "$p" 2>/dev/null || true; done
  waited=0
  while [ "$waited" -lt 60 ]; do
    local alive=0
    for p in $pids; do
      [ -n "$p" ] && kill -0 "$p" 2>/dev/null && alive=1
    done
    [ "$alive" = "0" ] && break
    sleep 1; waited=$((waited + 1))
  done
  for p in $pids; do
    [ -n "$p" ] && kill -0 "$p" 2>/dev/null && kill -KILL "$p" 2>/dev/null || true
  done
  rm -f "$READY_FILE"
  log "YARN 已停止"
  exit 0
}
trap shutdown TERM INT

# ---------- 主流程 ----------
if [ "${YARN_ENABLED:-true}" = "false" ]; then
  log "YARN_ENABLED=false，跳过 YARN 启动（yarn CLI 仍可用，但 RM/NM 不在本容器运行）"
  exit 0
fi

if [ ! -x "$HADOOP_HOME/bin/yarn" ]; then
  log "ERROR: 未找到 $HADOOP_HOME/bin/yarn —— 当前镜像没有内置 YARN（需 FULL=1 ./build.sh 构建基础层）"
  exit 0
fi

# 端口预检：8088/8042 已被占用说明容器里有遗留的 RM/NM（例如手工起过一份）。
# 不预检的话新实例绑定失败、但健康检查会打到旧实例上，出现"启动成功但资源配额还是旧的"的假象。
port_in_use() {
  (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | awk '{print $4}' | grep -qE "[:.]$1$"
}
for _p in 8088 8042; do
  if port_in_use "$_p"; then
    log "ERROR: 端口 $_p 已被占用，容器内可能有遗留的 YARN 进程；跳过本次启动（请先确认并清理）"
    exit 1
  fi
done

render_conf || exit 1
prepare_dirs

mkdir -p "$HADOOP_LOG_DIR"
log "启动 ResourceManager（用户 $YARN_USER，日志 $HADOOP_LOG_DIR）"
run_as_yarn_user "exec $HADOOP_HOME/bin/yarn resourcemanager" \
  >> "$HADOOP_LOG_DIR/resourcemanager.out" 2>&1 &
RM_PID=$!
wait_healthy ResourceManager "$RM_WEB/ws/v1/cluster/info" "$RM_PID" || { kill -TERM "$RM_PID" 2>/dev/null; exit 1; }

log "启动 NodeManager（用户 $YARN_USER）"
run_as_yarn_user "exec $HADOOP_HOME/bin/yarn nodemanager" \
  >> "$HADOOP_LOG_DIR/nodemanager.out" 2>&1 &
NM_PID=$!
wait_healthy NodeManager "$NM_WEB/ws/v1/node/info" "$NM_PID" || { shutdown; exit 1; }

# 汇总一行节点状态，便于 docker logs 直接看到资源上限
curl -fsS --max-time 5 "$RM_WEB/ws/v1/cluster/nodes" 2>/dev/null \
  | head -c 600 | tr -d '\n' | sed 's/^/[yarn] nodes: /'
echo
date -u +"%Y-%m-%dT%H:%M:%SZ" > "$READY_FILE"
log "YARN 就绪：RM $RM_WEB（UI / 8088），NM $NM_WEB"

while true; do
  if ! kill -0 "$RM_PID" 2>/dev/null; then
    log "ERROR: ResourceManager 退出，停止 NodeManager"
    NM_PID_SAVE="$NM_PID"; NM_PID=""
    kill -TERM "$NM_PID_SAVE" 2>/dev/null
    exit 1
  fi
  if ! kill -0 "$NM_PID" 2>/dev/null; then
    log "ERROR: NodeManager 退出，停止 ResourceManager"
    RM_PID_SAVE="$RM_PID"; RM_PID=""
    kill -TERM "$RM_PID_SAVE" 2>/dev/null
    exit 1
  fi
  sleep 5
done
