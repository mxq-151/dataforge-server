#!/bin/bash
# ============================================================
# 安装**最小可用** Hadoop（只留 YARN 运行必需模块）到 $DEST
#
# 用法: install-hadoop.sh <hadoop-3.x.tar.gz> [DEST=/opt/hadoop]
#
# 为什么裁剪：hadoop-3.3.6 发行包解压后 1.3G，其中
#   share/hadoop/tools/lib/aws-java-sdk-bundle  296M  S3A 客户端，本容器用不到
#   share/hadoop/client                          83M  hadoop-client-api/runtime 打包版（Spark 自带）
#   lib/native                                  168M  x86_64 静态库/so，arm64 宿主无法加载
#   share/hadoop/yarn/*catalog-webapp*.war       73M  应用目录 WebApp
#   share/hadoop/{hdfs 服务端, csi, sources, tests}  ~100M
# 裁剪后约 210M，YARN（RM+NM）+ mapreduce_shuffle 客户端仍是完整可用的。
#
# 保留：bin/ sbin/ etc/ share/hadoop/{common,yarn,mapreduce,hdfs-client}
# 说明：timelineservice 目录虽然本镜像关闭了 Timeline Service v2，但 RM 的 WebApp 在
#       启动时会硬引用 TimelineCollectorManager，删掉会 ClassNotFoundException 起不来，故保留（15M）。
# 说明：hdfs 只保留 hadoop-hdfs-client 与 hdfs/lib —— 本容器不跑 HDFS（fs.defaultFS=file:///），
#       但 YARN 若干类在类加载期会碰到 hdfs-client 的类，整目录删掉有 NoClassDefFoundError 风险。
# ============================================================
set -euo pipefail

TARBALL="${1:?用法: install-hadoop.sh <hadoop-*.tar.gz> [DEST]}"
DEST="${2:-/opt/hadoop}"

[ -f "$TARBALL" ] || { echo "!! 未找到发行包: $TARBALL"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "==> 解压 $TARBALL"
tar xzf "$TARBALL" -C "$WORK" --strip-components=1

cd "$WORK"

echo "==> 裁剪非 YARN 必需内容"
# S3A/Azure/GCS 客户端、client uber jar、x86_64 原生库、头文件、文档
rm -rf share/hadoop/tools \
       share/hadoop/client \
       lib/native \
       include \
       share/doc
# 源码/tests 包
rm -rf share/hadoop/*/sources
rm -f  share/hadoop/*/*-tests.jar share/hadoop/*/*-test-sources.jar
# CSI 卷插件（本镜像不用）
rm -rf share/hadoop/yarn/csi
rm -f  share/hadoop/yarn/hadoop-yarn-applications-catalog-webapp-*.war
# HDFS 服务端：不跑 NameNode/DataNode
rm -f  share/hadoop/hdfs/hadoop-hdfs-3.3.6.jar \
       share/hadoop/hdfs/hadoop-hdfs-httpfs-*.jar \
       share/hadoop/hdfs/hadoop-hdfs-nfs-*.jar \
       share/hadoop/hdfs/hadoop-hdfs-rbf-*.jar \
       share/hadoop/hdfs/hadoop-hdfs-native-client-*.jar

# 自检：YARN 运行必需的文件必须在
for f in bin/yarn sbin/yarn-daemon.sh \
         share/hadoop/common/hadoop-common-*.jar \
         share/hadoop/yarn/hadoop-yarn-server-resourcemanager-*.jar \
         share/hadoop/yarn/hadoop-yarn-server-nodemanager-*.jar \
         share/hadoop/yarn/hadoop-yarn-server-common-*.jar \
         share/hadoop/yarn/hadoop-yarn-common-*.jar \
         share/hadoop/mapreduce/hadoop-mapreduce-client-shuffle-*.jar; do
  # shellcheck disable=SC2086
  ls $f >/dev/null 2>&1 || { echo "!! 裁剪后缺少必需文件: $f"; exit 1; }
done

echo "==> 安装到 $DEST"
mkdir -p "$DEST"
cp -a "$WORK"/. "$DEST"/
# mktemp -d 建的临时目录是 0700，cp -a 会把它的权限带到 $DEST；
# 而 YARN 守护进程以 test_tenant 运行，必须能遍历 $DEST，否则报 Permission denied。
chmod 755 "$DEST"
find "$DEST" -type d -exec chmod 755 {} + 2>/dev/null || true
chmod +x "$DEST"/bin/* "$DEST"/sbin/* 2>/dev/null || true

echo "==> 完成: $(du -sh "$DEST" | cut -f1)  $DEST"
