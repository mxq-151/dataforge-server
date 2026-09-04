#!/usr/bin/env bash
# ============================================================
# dataforge All-in-One 单平台镜像构建脚本(含 jar 增量)
#
# 只面向单平台(默认本机架构),产物 --load 到本地 Docker,适合日常迭代。
#
#   * 默认仅增量更新 dataforge-server 的 jar(宿主机 mvn 重编译 ->
#     基于已有基础镜像覆盖新 jar),秒级完成。
#   * FULL=1 时全量重建基础镜像(Marquez + Spark + DolphinScheduler
#     + PostgreSQL + dataforge-server),首次或基础变更时执行。
#
# 用法:
#   ./build.sh                                                        # 单平台增量更新 jar(最快)
#   FULL=1 ./build.sh                                                 # 单平台全量重建基础镜像
#   PLATFORMS="linux/arm64" ./build.sh                                # 指定某单平台
#   PROXY="" ./build.sh                                               # 全量构建直连模式(网络不受限时)
#   SKIP_TESTS=false ./build.sh                                       # mvn 打包时执行单测
#
# 全平台(多架构, --push 到仓库)构建请用 ./build-all.sh;
# 本篇只做单平台 --load 到本地。
# ============================================================
set -euo pipefail

# 单平台默认: 不预设 REGISTRY,平台未指定时由公共逻辑按本机架构取值。
: "${DEFAULT_PLATFORMS:=}"
: "${DEFAULT_REGISTRY:=}"

# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/build-lib.sh"