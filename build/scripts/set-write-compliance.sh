#!/bin/bash
# ============================================================
# Spark 写入合规监听器(spark-write-compliance)开关 —— 容器内使用
#
# 改写 /opt/spark/conf/spark-defaults.conf 中由本脚本管理的配置块,
# 对容器内此后提交的所有 spark-submit / spark-sql 任务生效
# (DolphinScheduler SPARK 任务、dataforge-server 代码执行提交的任务都在内)。
# 已运行的 Spark 进程不受影响(SparkConf 在启动时读取)。
#
# 用法:
#   set-write-compliance.sh [status|enforce [用户名]|strict|relaxed|on|off]
#
#   status          查看当前生效的配置块(默认动作)
#   enforce [用户]  真实拦截(推荐档):
#                     fail.on.violation=true  -> 规则命中/校验不通过即让任务失败
#                     allow.on.unknown=false  -> 解析不出写入目标也阻断(不留静默放行口子)
#                     permission.check.user   -> 指定判定身份；省略则回落 Spark 运行用户
#   strict          enforce 的同义档：不指定用户，一律按 Spark 运行用户(sparkUser)判定。
#                   ⚠ sparkUser 必须是「已登记的平台用户」(sys_user.username)，
#                     否则权限服务会以「无法确定校验用户」400 拒绝 -> 任务全量失败。
#                     (容器内 sparkUser 通常是 OS 用户 test_tenant，它是租户编码、
#                      不是平台用户名，因此本档需先登记同名 sys_user 才能用。)
#   relaxed         真实拦截，但解析不出写入目标时只告警放行(allow.on.unknown=true)。
#                   观察期用：先看「存在 [N] 个无法判定的写入目标」告警，确认无误伤再切 enforce。
#   on              启用但不拦：以超管 admin 校验，链路真实打通但判定必然通过。
#                   仅用于演示/排障，不要用于生产。
#   off             停用: write.compliance.enabled=false
#                   (jar 与 spark.sql.extensions 仍装载，只是不再做任何校验)
#
# 环境变量:
#   SPARK_HOME  Spark 安装目录, 默认 /opt/spark
#   SERVER_URL  权限服务地址, 默认 http://localhost:8082/api (容器内 dataforge-server)
# ============================================================
set -euo pipefail

SPARK_HOME="${SPARK_HOME:-/opt/spark}"
CONF_FILE="${SPARK_HOME}/conf/spark-defaults.conf"
SERVER_URL="${SERVER_URL:-http://localhost:8082/api}"

BEGIN_PREFIX="# >>> write-compliance"
END_PREFIX="# <<< write-compliance"

MODE="${1:-status}"
USERNAME="${2:-}"

die() { echo "!! $*" >&2; exit 1; }

[ -f "$CONF_FILE" ] || die "找不到 Spark 配置文件: $CONF_FILE"

# ---------- 生成配置块 ----------
# 参数1: 档位 off|on|relaxed|enforce
# 参数2: 校验用户名(enforce/relaxed 可用), 为空表示回落 sparkUser
generate_block() {
  local mode="$1"
  local username="$2"
  local enabled failon allow_unknown

  case "$mode" in
    off)     enabled="false"; failon="true";  allow_unknown="false" ;;
    on)      enabled="true";  failon="true";  allow_unknown="true"  ;;
    relaxed) enabled="true";  failon="true";  allow_unknown="true"  ;;
    enforce) enabled="true";  failon="true";  allow_unknown="false" ;;
    *)       die "未知档位: $mode" ;;
  esac

  cat <<EOF
${BEGIN_PREFIX} (managed by /opt/dataforge/scripts/set-write-compliance.sh) >>>
# 数据写入合规监听器 spark-write-compliance。
# jar: /opt/spark/jars/spark-write-compliance-core-*.jar
#      /opt/spark/jars/spark-write-compliance-spark-3.5-*.jar
#
# spark.sql.extensions             -> 分析期注入 CheckRule，命中禁止规则/越权时在写入发生前抛异常阻断任务
# spark.sql.queryExecutionListeners -> 执行结束后解析执行计划，判定并上报（审计/告警）
#
# 注意: 配置键统一带 spark. 前缀。Spark 3.5 起 SparkSubmitArguments 会丢弃
# spark-defaults.conf / --conf 中所有非 spark. 前缀的配置项。
#
# 本块由 /opt/dataforge/scripts/set-write-compliance.sh 生成，手工改动会在下次切换档位时被覆盖。

spark.sql.extensions com.writecompliance.adapter.WriteComplianceSparkSessionExtension
spark.sql.queryExecutionListeners com.writecompliance.adapter.WriteComplianceQueryExecutionListener

spark.write.compliance.enabled ${enabled}
spark.write.compliance.fail.on.violation ${failon}
spark.write.compliance.allow.on.unknown ${allow_unknown}
spark.write.compliance.log.plans false

# 禁止规则: 正则(忽略大小写匹配路径 / db.table), 命中即阻断, 索引从 0 开始。
# 默认不启用任何规则(留空表示只做权限校验); 需要时取消下面注释并按需增删。
#spark.write.compliance.rule.0 ^/tmp/
#spark.write.compliance.rule.0.name no-tmp-dir
#spark.write.compliance.rule.1 ^raw_
#spark.write.compliance.rule.1.name no-raw-layer
EOF

  if [ "$enabled" = "true" ]; then
    cat <<EOF

# 数据资源权限校验: 分析期把访问到的库表/文件路径上报 dataforge-server 判定。
# 判定不通过 / 用户未登记 / 权限服务不可达 一律阻断(fail-close，不做静默放行)。
spark.write.compliance.permission.check.enabled true
# dataforge-server 容器内地址: 端口 8082 + context-path /api
spark.write.compliance.permission.check.url ${SERVER_URL}
spark.write.compliance.permission.check.default.database default
EOF
    if [ "$mode" = "on" ]; then
      cat <<EOF
# ⚠ admin = 超管，判定直接放行 —— 链路真实打通但不会拦下任务，仅用于演示/排障。
spark.write.compliance.permission.check.user admin
EOF
    elif [ -n "$username" ]; then
      cat <<EOF
# 以哪个平台用户身份判定(sys_user.username)。真实拦截生效中:
# 该用户无权访问的资源、以及服务不可达，都会让任务在写入发生前失败。
spark.write.compliance.permission.check.user ${username}
EOF
    else
      cat <<EOF
# permission.check.user 留空: 按 Spark 运行用户(sparkUser)判定。
# 前置条件: sparkUser 必须是已登记的平台用户(sys_user.username)，否则一律阻断。
EOF
    fi
    cat <<EOF
# 共享密钥，须与 dataforge-server 的 permission.check.secret 一致
spark.write.compliance.permission.check.token dataforge-permission-check-2026-shared-secret
spark.write.compliance.permission.check.connect.timeout.ms 3000
spark.write.compliance.permission.check.read.timeout.ms 5000
EOF
  fi

  echo "${END_PREFIX} <<<"
}

# ---------- 用新块替换旧块(块不存在则追加) ----------
rewrite_block() {
  local new_block="$1"
  local block_file tmp_file
  block_file="$(mktemp)"
  tmp_file="$(mktemp)"
  printf '%s\n' "$new_block" > "$block_file"

  awk -v begin="$BEGIN_PREFIX" -v end="$END_PREFIX" -v bf="$block_file" '
    index($0, begin) == 1 {
      while ((getline line < bf) > 0) print line
      close(bf)
      replaced = 1
      skipping = 1
      next
    }
    skipping && index($0, end) == 1 { skipping = 0; next }
    skipping { next }
    { print }
    END {
      if (!replaced) {
        print ""
        while ((getline line < bf) > 0) print line
        close(bf)
      }
    }
  ' "$CONF_FILE" > "$tmp_file"

  cat "$tmp_file" > "$CONF_FILE"
  rm -f "$block_file" "$tmp_file"
}

case "$MODE" in
  status)
    echo "== Spark 配置文件: $CONF_FILE"
    awk -v begin="$BEGIN_PREFIX" -v end="$END_PREFIX" '
      index($0, begin) == 1 { printing = 1 }
      printing { print }
      printing && index($0, end) == 1 { printing = 0 }
    ' "$CONF_FILE"
    echo
    echo "== 当前 enabled:"
    grep -E "^spark\.write\.compliance\.enabled" "$CONF_FILE" || echo "   (未配置 -> 默认 false, 不启用)"
    ;;
  on|off|relaxed|enforce|strict)
    block_mode="$MODE"
    block_user=""
    if [ "$MODE" = "strict" ]; then
      block_mode="enforce"
    elif [ "$MODE" = "enforce" ] || [ "$MODE" = "relaxed" ]; then
      block_user="$USERNAME"
    fi

    if [ "$block_mode" = "enforce" ] && [ -z "$block_user" ]; then
      echo "!! 提示: 未指定校验用户名 -> 按 Spark 运行用户(sparkUser)判定。"
      echo "!!       该用户必须是已登记的平台用户(sys_user.username)，否则所有任务都会被拦下。"
    fi

    block="$(generate_block "$block_mode" "$block_user")"
    rewrite_block "$block"
    echo "== 已切换为 [$MODE]，配置文件已更新: $CONF_FILE"
    echo
    printf '%s\n' "$block"
    echo
    echo "-- 对之后新提交的 Spark 任务生效；查看当前状态: $0 status"
    ;;
  *)
    die "用法: $0 [status|enforce [用户名]|strict|relaxed|on|off]"
    ;;
esac
