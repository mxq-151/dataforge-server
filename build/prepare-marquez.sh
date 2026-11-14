#!/usr/bin/env bash
# ============================================================
# Marquez 0.51.1 宿主机预编译脚本(API shadow jar + Web 静态产物)
#
# 背景: 原 Dockerfile 的 marquez-builder(gradle:8.7-jdk17) 与 marquez-web-builder(node:18)
#       两个阶段在**镜像构建期**编译 Marquez, 导致构建慢、绑定容器内 JDK/Gradle/Node 版本、
#       缓存不可控、失败整层重来。现改为: 本脚本在宿主机编译, 产物统一落
#         build/dist/marquez/        -> marquez-api-0.51.1.jar + marquez.dev.yml
#         build/dist/marquez-web/    -> web/dist 静态资源(index.html / js / ...)
#       Dockerfile 只做 COPY, 不含任何 gradle / npm 调用。
#
# 用法:
#   build/prepare-marquez.sh            # 增量(产物与源码标记一致时跳过)
#   build/prepare-marquez.sh --force    # 强制重编
#   build/prepare-marquez.sh --check    # 只校验已有产物, 不编译(供 build.sh 增量模式调用)
#
# 宿主机前置:
#   JDK 17+       JAVA_HOME 指向 JDK17, 或 macOS 上 /usr/libexec/java_home -v 17 可定位
#   Gradle 8.x    PATH 里的 gradle, 或 GRADLE_BIN=/path/to/gradle, 或缓存的发行版
#                 (缺失时按提示 `brew install gradle` 或下载 gradle-8.7-bin.zip 解压到
#                  ${DATAFORGE_BUILD_CACHE}/gradle-8.7)
#   Node 16+ npm  PATH 里的 node / npm(缺失时 `brew install node` 或 nvm install --lts)
#
# 可复用缓存(固定位置, 不在仓库内, 避免 build/dist 的 macOS TCC 写入限制):
#   ${DATAFORGE_BUILD_CACHE:-$HOME/.cache/dataforge-build}/
#     gradle/          GRADLE_USER_HOME(依赖 + wrapper 发行版, 可离线复用)
#     npm/             npm _cacache
#     work/marquez-<版本>/  解包后的源码与构建中间产物(跨次复用, 支持增量)
#
# 相关环境变量:
#   DATAFORGE_BUILD_CACHE  缓存根目录(默认 ~/.cache/dataforge-build)
#   MARQUEZ_VERSION        默认 0.51.1
#   MARQUEZ_SRC_TGZ        源码包(默认 build/dist/marquez-<版本>.tar.gz)
#   GRADLE_BIN             指定 gradle 可执行文件
#   GRADLE_MIRROR          aliyun(默认) / off, 见 conf/gradle-init-mirror.gradle
#   GRADLE_REFRESH=1       禁用 --offline 优先, 强制联网刷新依赖
#   NPM_REGISTRY           默认 https://registry.npmmirror.com
#   NPM_REFRESH=1          忽略 node_modules 缓存标记, 重新 npm ci
#   MARQUEZ_BUILD_WEB=0    只编 API(排障用; 正常构建不要关, 否则 /opt/marquez-web 为空)
#   MARQUEZ_BUILD_API=0    只编 Web(排障用)
#
# 退出码: 0 成功/已是最新; 非 0 失败(缺工具链、编译失败、产物校验不通过)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CONF_MIRROR="$SCRIPT_DIR/conf/gradle-init-mirror.gradle"

MARQUEZ_VERSION="${MARQUEZ_VERSION:-0.51.1}"
SRC_TGZ="${MARQUEZ_SRC_TGZ:-$DIST_DIR/marquez-$MARQUEZ_VERSION.tar.gz}"
OUT_API="$DIST_DIR/marquez"          # build/dist/marquez/
OUT_WEB="$DIST_DIR/marquez-web"      # build/dist/marquez-web/
API_JAR_NAME="marquez-api-$MARQUEZ_VERSION.jar"
CONFIG_NAME="marquez.dev.yml"

CACHE_ROOT="${DATAFORGE_BUILD_CACHE:-$HOME/.cache/dataforge-build}"
GRADLE_USER_HOME_DIR="$CACHE_ROOT/gradle"
NPM_CACHE_DIR="$CACHE_ROOT/npm"
WORK_DIR="$CACHE_ROOT/work/marquez-$MARQUEZ_VERSION"
SRC_DIR="$WORK_DIR/src"

MARQUEZ_BUILD_WEB="${MARQUEZ_BUILD_WEB:-1}"
MARQUEZ_BUILD_API="${MARQUEZ_BUILD_API:-1}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
GRADLE_MIRROR="${GRADLE_MIRROR:-aliyun}"

# 产物最小体积门槛(明显偏小说明 shadowJar/webpack 半途而废)
MIN_API_JAR_BYTES=$((20 * 1024 * 1024))
MIN_WEB_BYTES=$((1024 * 1024))

FORCE=0
CHECK_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "!! 未知参数: $arg (可用: --force / --check)"; exit 2 ;;
  esac
done

log()  { echo "==> $*"; }
warn() { echo "!! $*"; }
die()  { echo "!! $*" >&2; exit 1; }

# ---------- 产物校验 ----------
# 只做"必要文件存在 + 关键 jar 内容 + web 产物规模 + manifest sha256"四类检查,
# 任一项不通过都算失败(调用方据此中止构建, 不把残缺产物 COPY 进镜像)。
manifest_path() { echo "$1/manifest.sha256"; }

sha256_of() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1; }

verify_artifacts() {
  local verbose="${1:-1}"
  local fail=0

  if [ "$MARQUEZ_BUILD_API" = "1" ]; then
    local jar="$OUT_API/$API_JAR_NAME" cfg="$OUT_API/$CONFIG_NAME"
    [ -f "$jar" ] || { warn "缺少 API jar: $jar"; fail=1; }
    [ -f "$cfg" ] || { warn "缺少配置: $cfg"; fail=1; }
    if [ -f "$jar" ]; then
      local sz; sz=$(wc -c < "$jar" | tr -d ' ')
      [ "$sz" -ge "$MIN_API_JAR_BYTES" ] || { warn "API jar 体积异常: $sz 字节 (< $MIN_API_JAR_BYTES), 疑似编译中断"; fail=1; }
      # 关键类与 Main-Class: 确保是 shadowJar 而不是普通 jar(普通 jar 缺依赖, 起不来)。
      # ⚠ 不要用 `unzip -l | grep -q`: 脚本开了 pipefail, grep -q 提前退出会让 unzip
      #   收到 SIGPIPE(141) 使整条管道判为失败, 误报"缺类"。这里先取完整清单再匹配。
      if command -v unzip >/dev/null 2>&1; then
        local listing mf_txt
        listing="$(unzip -Z1 "$jar" 2>/dev/null || true)"
        case "$listing" in
          *"marquez/MarquezApp.class"*) : ;;
          *) warn "API jar 内缺 marquez/MarquezApp.class"; fail=1 ;;
        esac
        mf_txt="$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null || true)"
        case "$mf_txt" in
          *"Main-Class: marquez.MarquezApp"*) : ;;
          *) warn "API jar MANIFEST 缺 Main-Class: marquez.MarquezApp"; fail=1 ;;
        esac
      else
        warn "未找到 unzip, 跳过 jar 内容检查(仅做体积/校验和)"
      fi
      local mf; mf="$(manifest_path "$OUT_API")"
      if [ -f "$mf" ]; then
        local want got
        want=$(grep "  $API_JAR_NAME\$" "$mf" | cut -d' ' -f1 || true)
        got="$(sha256_of "$jar")"
        [ -n "$want" ] && [ "$want" = "$got" ] || { warn "API jar sha256 与 manifest 不一致(want=$want got=$got)"; fail=1; }
      else
        warn "缺少 manifest: $mf"; fail=1
      fi
    fi
  fi

  if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then
    local idx="$OUT_WEB/index.html"
    [ -f "$idx" ] || { warn "缺少 Web 入口: $idx"; fail=1; }
    if [ -f "$idx" ]; then
      local njs bytes
      njs=$(find "$OUT_WEB" -name '*.js' -type f 2>/dev/null | wc -l | tr -d ' ')
      bytes=$(du -sk "$OUT_WEB" 2>/dev/null | cut -f1)
      bytes=$((bytes * 1024))
      [ "$njs" -ge 1 ] || { warn "Web 产物无 js 文件"; fail=1; }
      [ "$bytes" -ge "$MIN_WEB_BYTES" ] || { warn "Web 产物体积异常: $bytes 字节 (< $MIN_WEB_BYTES)"; fail=1; }
    fi
    local mf; mf="$(manifest_path "$OUT_WEB")"
    if [ -f "$mf" ] && [ -f "$idx" ]; then
      local want got
      want=$(grep "  index.html\$" "$mf" | cut -d' ' -f1 || true)
      got="$(sha256_of "$idx")"
      [ -n "$want" ] && [ "$want" = "$got" ] || { warn "Web index.html sha256 与 manifest 不一致"; fail=1; }
    elif [ ! -f "$mf" ]; then
      warn "缺少 manifest: $mf"; fail=1
    fi
  fi

  if [ "$fail" -ne 0 ]; then
    [ "$verbose" = "1" ] && warn "Marquez 产物校验失败: 请执行 build/prepare-marquez.sh --force 重编"
    return 1
  fi
  return 0
}

# ---------- 工具链 ----------
resolve_java() {
  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    local found=""
    if [ -x /usr/libexec/java_home ]; then found="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; fi
    if [ -z "$found" ]; then
      local j
      for j in /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /opt/openjdk-17; do
        [ -x "$j/bin/java" ] && { found="$j"; break; }
      done
    fi
    [ -n "$found" ] && { JAVA_HOME="$found"; export JAVA_HOME; }
  fi
  [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] || die \
    "未找到 JDK 17+: 请设置 JAVA_HOME(如 export JAVA_HOME=\$(/usr/libexec/java_home -v 17))或安装 'brew install openjdk@17'"
  local major
  major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [ -n "$major" ] || die "无法识别 java 版本: $JAVA_HOME/bin/java -version"
  [ "$major" -ge 17 ] || die "Marquez 需要 JDK 17+, 当前 JAVA_HOME=$JAVA_HOME (major=$major)"
}

resolve_gradle() {
  if [ -n "${GRADLE_BIN:-}" ]; then
    [ -x "$GRADLE_BIN" ] || die "GRADLE_BIN 不可执行: $GRADLE_BIN"
    echo "$GRADLE_BIN"; return 0
  fi
  if command -v gradle >/dev/null 2>&1; then command -v gradle; return 0; fi
  local g
  for g in "$CACHE_ROOT/gradle-8.7/bin/gradle" "$CACHE_ROOT"/gradle-8.*/bin/gradle; do
    [ -x "$g" ] && { echo "$g"; return 0; }
  done
  # 复用本机 gradle wrapper 已下载的发行版(8.7/8.9 均可构建 Marquez 0.51.1)
  for g in "$HOME"/.gradle/wrapper/dists/gradle-8.*/*/gradle-8.*/bin/gradle; do
    [ -x "$g" ] && { echo "$g"; return 0; }
  done
  die "未找到 gradle(需要 8.x): 三选一 ——
     1) brew install gradle
     2) 下载 https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip 解压到 $CACHE_ROOT/gradle-8.7
     3) export GRADLE_BIN=/path/to/gradle"
}

resolve_node() {
  command -v node >/dev/null 2>&1 || die "未找到 node: 请安装 Node 16+ ('brew install node' 或 'nvm install --lts')"
  command -v npm  >/dev/null 2>&1 || die "未找到 npm: 请安装 Node 16+ ('brew install node' 或 'nvm install --lts')"
  local major; major="$(node -v | sed 's/^v\([0-9]*\).*/\1/')"
  [ "${major:-0}" -ge 16 ] || die "Node 版本过低(v$major), 需要 16+"
}

# ---------- 发布(TCC 友好) ----------
# macOS 上 build/dist 里旧文件可能带 com.apple.macl, 原地覆盖偶发 Permission denied;
# 统一先写 build/dist/.<name>.tmp.<pid>, 再原子 mv 替换; 已存在目录则用 rsync 覆盖内容。
publish_dir() {
  local src="$1" dst="$2"
  mkdir -p "$(dirname "$dst")"
  if [ -d "$dst" ]; then
    rsync -a --delete "$src"/ "$dst"/
  else
    local tmp="$DIST_DIR/.$(basename "$dst").tmp.$$"
    rm -rf "$tmp"
    rsync -a "$src"/ "$tmp"/
    mv "$tmp" "$dst"
  fi
}

# ---------- 标记 ----------
STAMP_API="$OUT_API/.build-stamp"
STAMP_WEB="$OUT_WEB/.build-stamp"

compute_stamp() {
  {
    echo "marquez_version=$MARQUEZ_VERSION"
    echo "src_sha256=$(sha256_of "$SRC_TGZ")"
    if [ "$MARQUEZ_BUILD_API" = "1" ]; then
      echo "gradle=$(basename "$(dirname "$(dirname "$GRADLE_BIN_RESOLVED")")")"
      echo "java=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | tr -d '\n')"
    fi
    if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then
      echo "node=$(node -v)"
      echo "npm=$(npm -v)"
    fi
  } | sort
}

stamp_matches() {
  local file="$1" want="$2"
  [ -f "$file" ] || return 1
  [ "$(cat "$file")" = "$want" ]
}

if [ "$CHECK_ONLY" = "1" ]; then
  if ! verify_artifacts 1; then
    exit 1
  fi
  echo "==> Marquez 产物校验通过: $OUT_API / $OUT_WEB"
  exit 0
fi

[ -f "$SRC_TGZ" ] || die "缺少 Marquez 源码包: $SRC_TGZ
     构建脚本在 FULL=1 时会自动下载(见 build-lib.sh ensure_dist), 也可手动:
     curl -fL -o '$SRC_TGZ' https://github.com/MarquezProject/marquez/archive/refs/tags/$MARQUEZ_VERSION.tar.gz"

resolve_java
if [ "$MARQUEZ_BUILD_API" = "1" ]; then
  GRADLE_BIN_RESOLVED="$(resolve_gradle)"
  log "工具链: java=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"
  log "工具链: gradle=$GRADLE_BIN_RESOLVED ($("$GRADLE_BIN_RESOLVED" --version 2>/dev/null | sed -n 's/^Gradle //p' | head -1))"
else
  GRADLE_BIN_RESOLVED=""
fi
if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then resolve_node; fi

mkdir -p "$CACHE_ROOT" "$GRADLE_USER_HOME_DIR" "$NPM_CACHE_DIR" "$WORK_DIR"

STAMP_VALUE="$(compute_stamp)"
STAMPS_OK=1
if [ "$MARQUEZ_BUILD_API" = "1" ]; then stamp_matches "$STAMP_API" "$STAMP_VALUE" || STAMPS_OK=0; fi
if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then stamp_matches "$STAMP_WEB" "$STAMP_VALUE" || STAMPS_OK=0; fi
if [ "$FORCE" = "0" ] && [ "$STAMPS_OK" = "1" ] && verify_artifacts 0; then
  log "Marquez $MARQUEZ_VERSION 产物已是最新(源码/工具链标记一致), 跳过编译"
  log "    产物: build/dist/marquez/ ($(du -sh "$OUT_API" | cut -f1)) / build/dist/marquez-web/ ($(du -sh "$OUT_WEB" | cut -f1))"
  log "    强制重编: build/prepare-marquez.sh --force"
  exit 0
fi

# ---------- 1) 解包源码(源码包 sha 变化时重解) ----------
SRC_SHA="$(sha256_of "$SRC_TGZ")"
if [ ! -f "$SRC_DIR/.src-sha256" ] || [ "$(cat "$SRC_DIR/.src-sha256")" != "$SRC_SHA" ]; then
  log "解包 Marquez 源码: $SRC_TGZ -> $SRC_DIR"
  rm -rf "$SRC_DIR"
  mkdir -p "$SRC_DIR"
  tar xzf "$SRC_TGZ" -C "$SRC_DIR" --strip-components=1
  echo "$SRC_SHA" > "$SRC_DIR/.src-sha256"
else
  log "源码已解包且 sha256 未变, 复用: $SRC_DIR"
fi

# ---------- 2) API shadowJar(离线优先) ----------
build_api() {
  local gradle="$GRADLE_BIN_RESOLVED"
  local common=(--no-daemon -q
    --init-script "$CONF_MIRROR"
    -Dorg.gradle.internal.http.connectionTimeout=60000
    -Dorg.gradle.internal.http.socketTimeout=180000
    :api:shadowJar -x test)
  export GRADLE_USER_HOME="$GRADLE_USER_HOME_DIR"
  export GRADLE_MIRROR
  # 注意: macOS 自带 bash 3.2 在 set -u 下展开空数组会报 unbound variable,
  # 所以离线开关用普通字符串而不是数组。
  local offline_flag=""
  if [ "${GRADLE_REFRESH:-0}" != "1" ] && [ -d "$GRADLE_USER_HOME_DIR/caches/modules-2" ]; then
    offline_flag="--offline"
  fi
  log "gradle :api:shadowJar (GRADLE_USER_HOME=$GRADLE_USER_HOME, ${offline_flag:-online})"
  # shellcheck disable=SC2086
  if ! ( cd "$SRC_DIR" && "$gradle" "${common[@]}" $offline_flag ); then
    if [ -n "$offline_flag" ]; then
      warn "离线模式失败(本地 gradle 缓存缺依赖), 回退联网解析一次"
      ( cd "$SRC_DIR" && "$gradle" "${common[@]}" ) || die "Marquez API 编译失败(gradle :api:shadowJar)"
    else
      die "Marquez API 编译失败(gradle :api:shadowJar); 网络/镜像排查见 conf/gradle-init-mirror.gradle 顶部说明"
    fi
  fi
  local out="$SRC_DIR/api/build/libs/$API_JAR_NAME"
  [ -f "$out" ] || die "gradle 未产出预期 jar: $out"
  log "API 产物: $out ($(du -h "$out" | cut -f1))"
}

# ---------- 3) Web(npm ci + webpack) ----------
build_web() {
  local web="$SRC_DIR/web"
  [ -f "$web/package.json" ] || die "缺少 web/package.json: $web"
  # webpack.prod.js 补丁(幂等): 关 sourcemap / 关 minimize / 限堆, 防止构建机内存受限时 OOM。
  #   原 Dockerfile 用 node -e 内联改写, 这里保持一致, 但重复执行不会叠加插入。
  node - "$web/webpack.prod.js" <<'JSEOF'
const fs = require('fs');
const f = process.argv[2];
let s = fs.readFileSync(f, 'utf8');
const orig = s;
s = s.replace(/devtool: 'source-map'/, 'devtool: false');
if (!s.includes('minimize: false')) {
  s = s.replace('const webpackProd = {', 'const webpackProd = {\n  optimization: { minimize: false },');
}
if (s !== orig) { fs.writeFileSync(f, s); console.log('    patched webpack.prod.js'); }
JSEOF

  local lock="$web/package-lock.json"
  [ -f "$lock" ] || die "缺少 web/package-lock.json, 无法 npm ci"
  local lock_sha stamp="$web/.npm-ci-stamp" want got
  lock_sha="$(sha256_of "$lock")"
  want="lock=$lock_sha node=$(node -v)"
  got="$(cat "$stamp" 2>/dev/null || true)"
  if [ "${NPM_REFRESH:-0}" != "1" ] && [ -d "$web/node_modules" ] && [ "$want" = "$got" ]; then
    log "node_modules 与 package-lock 一致, 跳过 npm ci"
  else
    log "npm ci (registry=$NPM_REGISTRY, cache=$NPM_CACHE_DIR)"
    ( cd "$web" && npm ci --no-audit --no-fund --cache "$NPM_CACHE_DIR" --registry "$NPM_REGISTRY" ) \
      || die "npm ci 失败; 可试 NPM_REGISTRY=https://registry.npmjs.org 或 NPM_REFRESH=1"
    echo "$want" > "$stamp"
  fi

  log "npm run build (webpack prod)"
  ( cd "$web" && NODE_OPTIONS=--max-old-space-size=2048 npm run build ) \
    || die "marquez-web 构建失败(webpack); 内存不足可加大 --max-old-space-size"
  [ -f "$web/dist/index.html" ] || die "webpack 未产出 web/dist/index.html"
}

if [ "$MARQUEZ_BUILD_API" = "1" ]; then
  t0=$(date +%s); build_api; log "API 编译耗时 $(( $(date +%s) - t0 ))s"
fi
if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then
  t0=$(date +%s); build_web; log "Web 编译耗时 $(( $(date +%s) - t0 ))s"
fi

# ---------- 4) 发布到 build/dist/ 并写 manifest ----------
API_STAGE="$WORK_DIR/out-api"
WEB_STAGE="$WORK_DIR/out-web"
rm -rf "$API_STAGE" "$WEB_STAGE"

if [ "$MARQUEZ_BUILD_API" = "1" ]; then
  mkdir -p "$API_STAGE"
  cp -f "$SRC_DIR/api/build/libs/$API_JAR_NAME" "$API_STAGE/$API_JAR_NAME"
  cp -f "$SRC_DIR/$CONFIG_NAME" "$API_STAGE/$CONFIG_NAME"
  ( cd "$API_STAGE" && shasum -a 256 "$API_JAR_NAME" > manifest.sha256 \
    && shasum -a 256 "$CONFIG_NAME" >> manifest.sha256 )
  publish_dir "$API_STAGE" "$OUT_API"
  printf '%s\n' "$STAMP_VALUE" > "$OUT_API/.build-stamp.new"
  mv -f "$OUT_API/.build-stamp.new" "$OUT_API/.build-stamp"
fi

if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then
  mkdir -p "$WEB_STAGE"
  rsync -a "$SRC_DIR/web/dist"/ "$WEB_STAGE"/
  ( cd "$WEB_STAGE" && shasum -a 256 index.html > manifest.sha256 )
  publish_dir "$WEB_STAGE" "$OUT_WEB"
  printf '%s\n' "$STAMP_VALUE" > "$OUT_WEB/.build-stamp.new"
  mv -f "$OUT_WEB/.build-stamp.new" "$OUT_WEB/.build-stamp"
fi

# ---------- 5) 收尾校验(失败即终止, 不产出不可用镜像) ----------
verify_artifacts 1 || die "构建完成但产物校验未通过, 已中止(不会进入 docker build)"

log "Marquez $MARQUEZ_VERSION 预编译完成:"
log "    build/dist/marquez/      $(du -sh "$OUT_API" 2>/dev/null | cut -f1)  ($(ls -1 "$OUT_API" | tr '\n' ' '))"
if [ "$MARQUEZ_BUILD_WEB" = "1" ]; then
  log "    build/dist/marquez-web/  $(du -sh "$OUT_WEB" 2>/dev/null | cut -f1)  ($(find "$OUT_WEB" -type f | wc -l | tr -d ' ') 个文件)"
fi
