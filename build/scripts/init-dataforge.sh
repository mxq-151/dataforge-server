#!/bin/bash
# ============================================================
# dataforge All-in-One 首次初始化:
#   1. 等待 PostgreSQL 就绪
#   2. 创建 dataforge / marquez 库与账号(幂等)
#   3. 导入 dataforge PostgreSQL schema(init/dataforge-postgresql.sql, 建表+admin)
#   4. 导入测试数据(testdata/*.sql: 测试用户/角色/数据源/授权 + PG 演示库 test_db)
#   5. 初始化 DolphinScheduler 的 PostgreSQL 库(建库 + 官方 schema + 补充数据)
# ============================================================
set -u

PG_BIN=/usr/lib/postgresql/14/bin
PG_DATA=/var/lib/postgresql/14/main
MARK=/var/lib/postgresql/.dataforge-init-done

# 等待 postgres(supervisord 并发启动 postgres program)
wait_for_pg() {
  for i in $(seq 1 90); do
    if su postgres -c "$PG_BIN/pg_isready -h 127.0.0.1 -p 5432" >/dev/null 2>&1; then
      echo "[init] postgres is ready after ${i} checks"
      return 0
    fi
    sleep 2
  done
  echo "[init] ERROR: postgres not ready in time"
  exit 1
}

echo "[init] ===== dataforge init start ====="
wait_for_pg

if [ -f "$MARK" ]; then
  echo "[init] already initialized, skip (rm $MARK to re-init)"
  exit 0
fi

# 1. postgres 超级用户密码(供外部 JDBC / 应用使用)
# 注意:必须走 unix socket(local peer 认证,postgres 系统用户免密),
# 不能加 -h 127.0.0.1(TCP 会触发 pg_hba scram 认证,无 TTY 下阻塞卡死)
su postgres -c "$PG_BIN/psql -c \"ALTER USER postgres PASSWORD '123456';\"" >/dev/null 2>&1 || true

# 2. dataforge 库
if ! su postgres -c "$PG_BIN/psql -tAc \"SELECT 1 FROM pg_database WHERE datname='dataforge'\"" | grep -q 1; then
  echo "[init] create database: dataforge"
  su postgres -c "$PG_BIN/createdb dataforge"
else
  echo "[init] database dataforge already exists"
fi

# 3. marquez 库与用户
if ! su postgres -c "$PG_BIN/psql -tAc \"SELECT 1 FROM pg_user WHERE usename='marquez'\"" | grep -q 1; then
  echo "[init] create user: marquez"
  su postgres -c "$PG_BIN/psql -c \"CREATE USER marquez WITH PASSWORD 'marquez';\"" >/dev/null
fi
if ! su postgres -c "$PG_BIN/psql -tAc \"SELECT 1 FROM pg_database WHERE datname='marquez'\"" | grep -q 1; then
  echo "[init] create database: marquez"
  su postgres -c "$PG_BIN/createdb -O marquez marquez"
fi
su postgres -c "$PG_BIN/psql -d marquez -c 'GRANT ALL PRIVILEGES ON SCHEMA public TO marquez;'" >/dev/null 2>&1 || true

# 4. 同步 dataforge schema(建表 + admin 用户)
#    本脚本自身幂等,故**每次启动都执行**,不再用「sys_user 已存在就跳过」的守卫:
#      * CREATE TABLE / INDEX IF NOT EXISTS  -> 补齐缺失的表与索引
#      * INSERT ... ON CONFLICT DO NOTHING   -> 补齐平台级种子数据
#      * 文件末尾「既有库自愈段」(DO 块 + 列/约束存在性判断) -> 补齐已有表缺的列与约束
#    旧守卫是老库升级新镜像后报 column "xxx" does not exist 的根源:
#    只要库里已有 sys_user,新增的列就永远不会被补上。
echo "[init] sync dataforge schema (init/dataforge-postgresql.sql)"
SCHEMA_OUT=$(su postgres -c "$PG_BIN/psql -d dataforge -v ON_ERROR_STOP=0 -f /opt/dataforge/sql/dataforge-postgresql.sql" 2>&1)
SCHEMA_ERRORS=$(printf '%s\n' "$SCHEMA_OUT" | grep -c 'ERROR:' || true)
if [ "${SCHEMA_ERRORS:-0}" -gt 0 ]; then
  echo "[init] WARN: schema 同步有 ${SCHEMA_ERRORS} 条 ERROR(幂等脚本,通常不影响启动,但请核查):"
  printf '%s\n' "$SCHEMA_OUT" | grep 'ERROR:' | head -20
else
  echo "[init] dataforge schema synced (0 error)"
fi

# 5. 导入测试数据(脚本幂等,可重复执行)
#    dataforge-testdata-postgresql.sql : 测试用户/业务角色/菜单/租户/数据源/授权(导入 dataforge 库)
#    test_db-postgresql.sql            : PG 演示业务库 test_db(自动建库/建账号/切库)
echo "[init] import dataforge test data (testdata/*.sql)"
if ! su postgres -c "$PG_BIN/psql -d dataforge -tAc \"SELECT 1 FROM sys_user WHERE username='test'\"" | grep -q 1; then
  su postgres -c "$PG_BIN/psql -d dataforge -v ON_ERROR_STOP=0 < /opt/dataforge/sql/testdata/dataforge-testdata-postgresql.sql" >/dev/null 2>&1 || true
  su postgres -c "$PG_BIN/psql -d dataforge -v ON_ERROR_STOP=0 < /opt/dataforge/sql/testdata/test_db-postgresql.sql" >/dev/null 2>&1 || true
else
  echo "[init] dataforge test data already imported"
fi

# 6. 初始化 DolphinScheduler 的 PostgreSQL 库(独立于 dataforge 库)
#    schema 用官方 PG 脚本(含 QRTZ + t_ds_* 全部表 + admin/default 默认数据);
#    随后导入补充数据(测试用户/租户/环境/项目/令牌, dolphinscheduler-init-data.sql)
if ! su postgres -c "$PG_BIN/psql -tAc \"SELECT 1 FROM pg_database WHERE datname='dolphinscheduler'\"" | grep -q 1; then
  echo "[init] create database: dolphinscheduler"
  su postgres -c "$PG_BIN/createdb dolphinscheduler"
else
  echo "[init] database dolphinscheduler already exists"
fi
DS_SCHEMA=/opt/dolphinscheduler/standalone-server/conf/sql/dolphinscheduler_postgresql.sql
if ! su postgres -c "$PG_BIN/psql -d dolphinscheduler -tAc \"SELECT 1 FROM information_schema.tables WHERE table_name='t_ds_user'\"" | grep -q 1; then
  echo "[init] import dolphinscheduler schema (dolphinscheduler_postgresql.sql)"
  su postgres -c "$PG_BIN/psql -d dolphinscheduler -v ON_ERROR_STOP=0 < $DS_SCHEMA" >/dev/null 2>&1 || true
  echo "[init] import dolphinscheduler init data (dolphinscheduler-init-data.sql)"
  su postgres -c "$PG_BIN/psql -d dolphinscheduler -v ON_ERROR_STOP=0 < /opt/dataforge/sql/dolphinscheduler-init-data.sql" >/dev/null 2>&1 || true
else
  echo "[init] dolphinscheduler schema already imported"
fi

# 7. 标记完成
touch "$MARK"
echo "[init] ===== dataforge init done ====="
exit 0