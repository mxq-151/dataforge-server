#!/usr/bin/env bash
# ============================================================
# dataforge All-in-One 全平台(多架构)镜像构建脚本
#
# 同时构建 linux/amd64 与 linux/arm64,产物 --push 到 REGISTRY。
# 多架构镜像无法 --load 到本地 Docker,必须提供仓库前缀。
#
#   * 默认增量更新 dataforge-server 的 jar; FULL=1 全量重建基础镜像。
#   * 首次多平台增量前,请先用 FULL=1 + REGISTRY 打好并推送基础镜像。
#
# 用法:
#   REGISTRY=myrepo ./build-all.sh                                     # amd64+arm64 增量,push
#   REGISTRY=myrepo FULL=1 ./build-all.sh                              # amd64+arm64 全量重建,push
#   REGISTRY=myrepo PLATFORMS="linux/amd64,linux/arm64" ./build-all.sh  # 自定义平台列表(逗号分隔)
#   REGISTRY=myrepo SKIP_TESTS=false ./build-all.sh                    # mvn 打包时执行单测
#   REGISTRY=myrepo HADOOP_VERSION=3.3.6 ./build-all.sh                # 动态编译指定组件版本
#                                                                      # (HADOOP/HBASE/JUICEFS/PARQUET/
#                                                                      #  ORC_VERSION,未设则用 pom 默认)
#
# 前置(多架构交叉编译须宿主机注册 binfmt):
#   docker run --privileged --rm tonistiigi/binfmt --install all
#
# 单平台(本机架构, --load 到本地)请用 ./build.sh。
# ============================================================
set -euo pipefail

# 全平台默认同时构建常用两大架构; 可在命令行用 PLATFORMS 覆盖。
: "${DEFAULT_PLATFORMS:=linux/amd64,linux/arm64}"
: "${DEFAULT_REGISTRY:=}"

# shellcheck disable=SC1091
source "$(dirname "${BASH_SOURCE[0]}")/build-lib.sh"