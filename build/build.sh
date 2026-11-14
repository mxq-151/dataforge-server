#!/usr/bin/env bash
# ============================================================
# dataforge All-in-One 单平台镜像构建脚本(含 jar 增量)
#
# 只面向单平台(默认本机架构),产物 --load 到本地 Docker,适合日常迭代。
#
#   * dataforge-server 一律在**宿主机**编译(mvn package -> build/dist/dataforge-server.jar),
#     镜像内不再跑 Maven,因此两种模式都要求本机有可用的 mvn 与 JDK 17+。
#   * Marquez(API + Web)同样在**宿主机**预编译(build/prepare-marquez.sh, 需要 JDK 17+ /
#     Gradle 8.x / Node 16+),产物落 build/dist/marquez{,-web}/,Dockerfile 只 COPY。
#     FULL=1 时产物未变会跳过编译, FORCE=1 强制重编。
#   * 默认仅增量更新 jar(宿主机重编译 -> 基于已有基础镜像覆盖新 jar),秒级完成。
#   * FULL=1 时全量重建基础镜像(Spark + DolphinScheduler + PostgreSQL + dataforge-server
#     + 宿主机预编译的 Marquez),首次或基础变更时执行。
#
# 用法:
#   ./build.sh                                                        # 单平台增量更新 jar(最快)
#   FULL=1 ./build.sh                                                 # 单平台全量重建基础镜像
#   FULL=1 FORCE=1 ./build.sh                                         # 全量重建 + 强制重编 Marquez
#   PLATFORMS="linux/arm64" ./build.sh                                # 指定某单平台
#   PROXY="" ./build.sh                                               # 全量构建直连模式(网络不受限时)
#   SKIP_TESTS=false ./build.sh                                       # mvn 打包时执行单测
#   HADOOP_VERSION=3.3.6 HBASE_VERSION=2.4.17 ./build.sh              # 动态编译指定组件版本
#                                                                     # (支持 HADOOP/HBASE/JUICEFS/
#                                                                     #  PARQUET/ORC_VERSION,未设
#                                                                     #  置的组件用 pom 默认版本)
#   SPARK_LISTENER_DIR=/path/to/spark-listener ./build.sh             # 指定写入合规监听器源码目录
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