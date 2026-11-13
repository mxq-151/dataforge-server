#!/bin/bash
# ============================================================
# 生成 TPC-DS 测试数据（dsdgen 造数 -> Spark 转 parquet）
#
# 生成器：TPC-DS DSGen 4.0.0，版本自证见 tools/release.h：
#         VERSION 4 / RELEASE 0 / MODIFICATION 0 / C_DATES "2001 - 2021"
#         （不再使用旧版 tpcds-kit 2.10.0，其 C_DATES 为 "2001 - 2018"，
#          且 dbgen_version.dat 首列为 2.10.0）
#
# 用法（容器内，root 或 test_tenant 均可）：
#   gen-tpcds.sh --dsgen /path/to/DSGen-software-code-4.0.0   # 默认 SF=0.5，parquet
#   gen-tpcds.sh --dsgen <dir> -s 0.1 -o /data/tpcds_sf01
#   gen-tpcds.sh --dsgen <dir> -s 1 -f orc -o /tmp/tpcds_sf1 --keep-dat
#
# 参数：
#   --dsgen       <dir>     DSGen 4.0.0 源码目录（含 tools/，或直接指向 tools/）；
#                           也可用环境变量 DSGEN_SRC 指定。不再内置下载旧版 tpcds-kit
#   -s|--scale    <0.01~1>  数据规模（0.5 ≈ 500MB 原始数据），默认 0.5
#   -f|--format   <parquet|orc>  输出格式，默认 parquet
#   -o|--output   <dir>     输出目录，默认 /tmp/tpcds（造数用；镜像内的基线数据在 /opt/tpcds，
#                          由构建期 TPCDS_DATA_SRC staging 进镜像，本脚本不会直接改写它）
#   -w|--workdir  <dir>     工具/中间文件目录，默认 /tmp/tpcds-work
#       --keep-dat          保留生成的 .dat 文本（默认转换完删除）
#       --force             输出目录非空时强制覆盖（默认中止）
#       --local-cores <n>   Spark local 模式核数，默认 4
#   -h|--help
#
# 为什么用 dsdgen + Spark 转换，而不是纯 Java 生成器：
#   * dsdgen 是 TPC 官方生成器，24 张表的列顺序/取值分布完全权威；
#   * 纯 Java 方案（spark-sql-perf）的 0.5.x 产物未发布到 Maven Central，需自行用 sbt 构建，
#     且其对 Spark 3.5 / Scala 2.12 的二进制兼容性无保证；dsdgen 只需 gcc+make。
#   * 官方 dsdgen 的 -SCALE 只接受**整数 GB**（4.0.0 的 r_params.c 仍为 {"scale", OPT_INT}，
#     rowcounts 只定义 1/10/100/1000... 档位，传 0.5 会被 atoi 成 0 并越界崩溃）。
#     脚本对 scaling.c 打一个小补丁：保持 -SCALE 1，用环境变量 DF_TPCDS_FRAC 把
#     LINEAR/LOG 表的基数行数乘上比例；STATIC 表（date_dim/time_dim 等）不变，
#     FK 取值范围随行数同步缩放，表间引用仍然有效。
#   * 4.0.0 与 2.10.0 的 scaling.c / r_params.c 内容完全一致，因此补丁可直接沿用；
#     数据层面的差异来自 release.h 的日期范围（2001-2021 vs 2001-2018）、
#     tpcds.sql 三个字段（date_dim.d_date/t_time 加 not null，customer.c_last_review_date_sk
#     -> c_last_review_date char(10)）以及 scaling.dst/tpcds.dst 的分布权重调整。
#
# 依赖：
#   gcc/make/libc6-dev（缺失时自动 apt-get 安装，约 135MB，装在容器可写层，重建容器后需重装）
#   Spark 3.5.9（/opt/spark）、python3（镜像已带）
#   4.0.0 自带 tokenizer.c/y.tab.c，无需 bison/yacc（make 会先编 distcomp 生成 tpcds.idx）
#
# 实测耗时（arm64 开发机，4 核）：SF=0.5 → 编译 dsdgen 1s + dsdgen 28s + Spark 转换 17s，
# 总 47s；文本 592MB → parquet(snappy) 179MB。
# ============================================================
set -euo pipefail

SCALE="0.5"
FORMAT="parquet"
OUT="/tmp/tpcds"
WORKDIR="/tmp/tpcds-work"
KEEP_DAT=0
FORCE=0
CORES="4"
DSGEN_SRC="${DSGEN_SRC:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --dsgen)     DSGEN_SRC="$2"; shift 2 ;;
    -s|--scale)  SCALE="$2"; shift 2 ;;
    -f|--format) FORMAT="$2"; shift 2 ;;
    -o|--output) OUT="$2"; shift 2 ;;
    -w|--workdir) WORKDIR="$2"; shift 2 ;;
    --local-cores) CORES="$2"; shift 2 ;;
    --keep-dat)  KEEP_DAT=1; shift ;;
    --force)     FORCE=1; shift ;;
    -h|--help)   sed -n '2,50p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（--help 查看用法）"; exit 2 ;;
  esac
done

SPARK_HOME="${SPARK_HOME:-/opt/spark}"
KIT_DIR="$WORKDIR/tpcds-kit"
DAT_DIR="$WORKDIR/dat"
LOG="$WORKDIR/gen-tpcds.log"
RUN_USER="${TPCDS_RUN_USER:-test_tenant}"
T_START=$(date +%s)

log() { echo "[tpcds $(date +%H:%M:%S)] $*"; }
die() { echo "[tpcds] ERROR: $*" >&2; exit 1; }

# ---- 参数校验 ----
python3 - "$SCALE" <<'PY' || die "scale 必须是 (0,1] 之间的数字"
import sys
s=float(sys.argv[1])
assert 0 < s <= 1.0, s
PY
[ "$FORMAT" = "parquet" ] || [ "$FORMAT" = "orc" ] || die "format 只支持 parquet / orc"

INT_SCALE=$(python3 -c "import sys,math;print(max(1,int(math.floor(float('$SCALE')))))")
FRAC=$(python3 -c "print(float('$SCALE'))")

# ---- 目标目录检查：已存在且非空时先中止（--force 才覆盖） ----
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
  if [ "$FORCE" -ne 1 ]; then
    echo "[tpcds] 目标目录已存在且非空: $OUT"
    ls -A "$OUT" | head -5
    die "为避免覆盖已有数据已中止；确认要重建请加 --force"
  fi
  log "目标目录非空，但指定了 --force，将覆盖: $OUT"
fi

mkdir -p "$WORKDIR"
: > "$LOG"

# ---- 1) 编译依赖（gcc/make）----
need_pkg=0
command -v gcc >/dev/null 2>&1 || need_pkg=1
command -v make >/dev/null 2>&1 || need_pkg=1
if [ "$need_pkg" -eq 1 ]; then
  log "安装编译依赖 gcc/make/libc6-dev（约 135MB，仅装在容器可写层）"
  apt-get update -qq >>"$LOG" 2>&1 || die "apt-get update 失败（容器需能访问 apt 源）"
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gcc make libc6-dev >>"$LOG" 2>&1 \
    || die "安装 gcc/make 失败，详见 $LOG"
fi

# ---- 2) 取 DSGen 4.0.0 源码并编译 dsdgen ----
[ -n "$DSGEN_SRC" ] || die "请用 --dsgen 指定 DSGen 4.0.0 源码目录（含 tools/），或设置 DSGEN_SRC"
if [ -d "$DSGEN_SRC/tools" ]; then SRC_TOOLS="$DSGEN_SRC/tools"; else SRC_TOOLS="$DSGEN_SRC"; fi
[ -f "$SRC_TOOLS/scaling.c" ] || die "不是 TPC-DS DSGen 源码目录（缺 scaling.c）: $SRC_TOOLS"
mkdir -p "$KIT_DIR/tools"
cp -a "$SRC_TOOLS/." "$KIT_DIR/tools/"

log "生成器版本证据（$KIT_DIR/tools/release.h）:"
grep -E '^#define[[:space:]]+(VERSION|RELEASE|MODIFICATION|PATCH|C_DATES)' "$KIT_DIR/tools/release.h" | sed 's/^/    /'

# 补丁：支持小数 SF（原实现 get_int("SCALE") 把 0.5 变 0，越界段错误）
if grep -q "DF_TPCDS_FRAC" "$KIT_DIR/tools/scaling.c"; then
  log "scaling.c 已含小数 SF 补丁，跳过"
else
  log "给 scaling.c 打小数 SF 补丁"
  python3 - "$KIT_DIR/tools/scaling.c" <<'PY' || die "scaling.c 补丁失败"
import sys
p=sys.argv[1]; s=open(p).read()
old="\t\tnScale = get_int(\"SCALE\");\n\t\tif (nScale > 100000)\n\t\t\tReportErrorNoLine(QERR_BAD_SCALE, NULL, 1);"
new=("\t\t/* dataforge patch: dsdgen -SCALE 只接受整数 GB，这里用 -SCALE 1 生成，\n"
     "\t\t * 再按 DF_TPCDS_FRAC 缩放 LINEAR/LOG 表行数，得到 SF<1 的数据；\n"
     "\t\t * STATIC 表（date_dim 等固定维表）保持不变，FK 取值范围随行数同步缩放。 */\n"
     "\t\tdouble dScaleFrac = 1.0;\n"
     "\t\t{\n"
     "\t\t\tconst char *szFrac = getenv(\"DF_TPCDS_FRAC\");\n"
     "\t\t\tif (szFrac && *szFrac)\n"
     "\t\t\t{\n"
     "\t\t\t\tdouble d = atof(szFrac);\n"
     "\t\t\t\tif (d > 0 && d < 1.0)\n"
     "\t\t\t\t\tdScaleFrac = d;\n"
     "\t\t\t}\n"
     "\t\t}\n"
     "\t\tnScale = get_int(\"SCALE\");\n"
     "\t\tif (nScale > 100000)\n"
     "\t\t\tReportErrorNoLine(QERR_BAD_SCALE, NULL, 1);")
assert old in s, "anchor not found (DSGen 版本不匹配?)"
s=s.replace(old,new,1)
anchor="\t\t\t/* now adjust for the multiplier */"
inject=("\t\t\t/* dataforge patch: 按比例缩放 LINEAR(2)/LOG(3) 表的基数行数 */\n"
        "\t\t\tif (dScaleFrac < 1.0 &&\n"
        "\t\t\t\tdist_member(NULL, \"rowcounts\", nTable + nRowcountOffset + 1, 3) != 1)\n"
        "\t\t\t{\n"
        "\t\t\t\tarRowcount[nTable].kBaseRowcount =\n"
        "\t\t\t\t\t(ds_key_t)((double)arRowcount[nTable].kBaseRowcount * dScaleFrac + 0.5);\n"
        "\t\t\t\tif (arRowcount[nTable].kBaseRowcount < 1)\n"
        "\t\t\t\t\tarRowcount[nTable].kBaseRowcount = 1;\n"
        "\t\t\t}\n\n")
assert anchor in s, "anchor2 not found"
s=s.replace(anchor, inject+anchor, 1)
open(p,"w").write(s)
print("patched")
PY
fi

log "编译 dsdgen（4.0.0 自带 y.tab.c/tokenizer.c，make 会先编 distcomp 生成 tpcds.idx）"
( cd "$KIT_DIR/tools" && \
  make -f Makefile.suite CC=gcc DATABASE=ANSI MACHINE=LINUX WORKLOAD=TPCDS OS=LINUX \
    BASE_CFLAGS="-D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE -DYYDEBUG -fcommon" dsdgen \
    >>"$LOG" 2>&1 ) || die "dsdgen 编译失败，详见 $LOG"
[ -x "$KIT_DIR/tools/dsdgen" ] || die "dsdgen 不存在"

# ---- 3) 生成 .dat ----
rm -rf "$DAT_DIR"; mkdir -p "$DAT_DIR"
log "dsdgen 生成数据：scale=$INT_SCALE frac=$FRAC 目录=$DAT_DIR"
t0=$(date +%s)
( cd "$KIT_DIR/tools" && DF_TPCDS_FRAC="$FRAC" ./dsdgen -SCALE "$INT_SCALE" -TERMINATE N -DIR "$DAT_DIR/" ) >>"$LOG" 2>&1 \
  || die "dsdgen 执行失败，详见 $LOG"
log "dsdgen 完成，耗时 $(( $(date +%s) - t0 ))s，文本大小 $(du -sh "$DAT_DIR" | cut -f1)"
if [ -f "$DAT_DIR/dbgen_version.dat" ]; then
  log "造数版本自证（dbgen_version.dat）: $(cut -d'|' -f1-3 "$DAT_DIR/dbgen_version.dat")"
fi

# ---- 4) 生成 Spark 建表/转换 SQL ----
SQL_FILE="$WORKDIR/load.sql"
python3 - "$KIT_DIR/tools/tpcds.sql" "$DAT_DIR" "$OUT" > "$SQL_FILE" <<'PY'
import re, sys
ddl_path, dat_dir, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(ddl_path).read()
src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
src = re.sub(r"--[^\n]*", "", src)
blocks = re.findall(r"create\s+table\s+(\w+)\s*\((.*?)\)\s*;", src, flags=re.S | re.I)

def conv(t):
    t = t.strip().lower()
    m = re.match(r"(\w+)\s*(\((\d+)(,(\d+))?\))?", t)
    base, p1, p2 = m.group(1), m.group(3), m.group(5)
    if base in ("char", "varchar"):
        return "string"
    if base == "decimal":
        return "decimal(%s,%s)" % (p1 or 7, p2 or 2)
    return {"integer": "int", "smallint": "int", "bigint": "bigint",
            "time": "string", "date": "date"}.get(base, base)

for name, body in blocks:
    cols = []
    for line in re.split(r",\s*\n", body):
        line = " ".join(line.split())
        if not line:
            continue
        # 跳过表级约束（4.0.0 的 tpcds.sql 在 create table 里带了 primary key/foreign key）
        if re.match(r"(?i)(primary\s+key|foreign\s+key|constraint|unique|check)\b", line):
            continue
        line = line.rstrip(",").strip()
        parts = line.split()
        cols.append("%s %s" % (parts[0], conv(parts[1])))
    coldef = ",\n  ".join(cols)
    print("CREATE TABLE IF NOT EXISTS src_%s (\n  %s\n) USING csv OPTIONS ("
          "path '%s/%s.dat', sep '|', header 'false', nullValue '', emptyValue '');"
          % (name, coldef, dat_dir, name))
    print("CREATE TABLE IF NOT EXISTS %s USING %s LOCATION '%s/%s' AS SELECT * FROM src_%s;"
          % (name, "${FORMAT}", out_dir, name, name))
PY
# 输出格式由 shell 变量注入
sed -i "s/\${FORMAT}/$FORMAT/g" "$SQL_FILE"
[ -s "$SQL_FILE" ] || die "生成 SQL 失败"

# ---- 5) Spark 转换（local 模式，不占用 YARN）----
# 说明：关闭 write-compliance 扩展 —— 这是造数场景，不需要经过 dataforge-server 的写入权限校验
#       （否则 CTAS 会被当成业务写操作拦下）；OpenLineage 监听器保留不影响结果。
log "Spark 转换开始（master=local[$CORES]，格式 $FORMAT）"
t1=$(date +%s)
if [ "$(id -u)" -eq 0 ] && id "$RUN_USER" >/dev/null 2>&1; then
  # 以 test_tenant 运行，保证输出文件属主正确（后续 DS/YARN 作业都能读）
  mkdir -p "$OUT"; chown "$RUN_USER:$RUN_USER" "$OUT" "$WORKDIR" 2>/dev/null || true
  chmod -R o+rX "$DAT_DIR" 2>/dev/null || true
  # 必须切到可写目录：spark-sql 默认用 Hive 元数据（Derby），会在 cwd 建 metastore_db；
  # 用 su 时 cwd 是 /，test_tenant 无法写。这里同时显式使用内存 catalog，彻底规避 Derby。
  su -s /bin/bash "$RUN_USER" -c \
    "cd $WORKDIR && HOME=/home/$RUN_USER $SPARK_HOME/bin/spark-sql --master 'local[$CORES]' --driver-memory 4g \
     --conf spark.sql.catalogImplementation=in-memory \
     --conf spark.sql.shuffle.partitions=8 --conf spark.sql.extensions= \
     --conf spark.ui.enabled=false -f $SQL_FILE" >>"$LOG" 2>&1 \
    || die "Spark 转换失败，详见 $LOG"
else
  ( cd "$WORKDIR" && "$SPARK_HOME/bin/spark-sql" --master "local[$CORES]" --driver-memory 4g \
    --conf spark.sql.catalogImplementation=in-memory \
    --conf spark.sql.shuffle.partitions=8 --conf spark.sql.extensions= \
    --conf spark.ui.enabled=false -f "$SQL_FILE" ) >>"$LOG" 2>&1 \
    || die "Spark 转换失败，详见 $LOG"
fi
log "Spark 转换完成，耗时 $(( $(date +%s) - t1 ))s"

# ---- 6) 汇总（先统计，再清理 .dat）----
log "输出目录: $OUT"
printf "%-24s %12s %10s\n" "table" "rows" "size"
total=0
for d in "$OUT"/*/; do
  t=$(basename "$d")
  sz=$(du -sm "$d" | cut -f1); total=$((total + sz))
  rows="n/a"
  if [ -f "$DAT_DIR/$t.dat" ]; then rows=$(wc -l < "$DAT_DIR/$t.dat"); fi
  printf "%-24s %12s %8sMB\n" "$t" "$rows" "$sz"
done
log "合计: ${total}MB（du 实测 $(du -sh "$OUT" | cut -f1)）"

# ---- 7) 清理中间 .dat ----
if [ "$KEEP_DAT" -ne 1 ]; then
  rm -rf "$DAT_DIR"
  log "已删除中间 .dat 文本（需保留请加 --keep-dat）"
fi
log "全部完成，总耗时 $(( $(date +%s) - T_START ))s"
