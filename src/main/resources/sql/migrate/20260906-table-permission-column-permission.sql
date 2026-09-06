-- ---------------------------------------------------------------------------
-- 数据迁移：新增库表权限-列级白名单表 sys_table_permission_column
--
-- 背景
--   表权限(system/permission/table)此前仅支持表级 CRUD、行过滤SQL(row_filter_sql)
--   与列脱敏(data_masking_rule)，缺少「指定列」(列白名单)能力。列表接口
--   /system/permission/table/list 因此返回不了指定列权限信息。
--   本次新增列级白名单子表，用于按 (表权限, 表, 列) 存储允许可见的列；
--   某表在某条权限下无列记录时表示该表所有列可见。
--
-- 影响
--   仅新增一张表，不影响已有数据与已有接口。
--   新部署无需执行本脚本（init/dataforge-*.sql 已包含该建表语句）。
--
-- 执行方式（PostgreSQL）
--   在 dataforge 库执行一次即可，幂等（CREATE TABLE IF NOT EXISTS）。
--   docker 部署可进入容器执行：
--     docker exec -it <容器名> su postgres -c \
--       "psql -d dataforge < /opt/dataforge/sql/20260906-table-permission-column-permission.sql"
--   或宿主机：
--     PGPASSWORD=123456 psql -h 127.0.0.1 -p 5432 -U postgres -d dataforge \
--       -f 20260906-table-permission-column-permission.sql
--
-- 校验：迁移完成后执行 \d sys_table_permission_column 应能看到该表。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sys_table_permission_column (
  id BIGSERIAL,
  table_permission_id BIGINT NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  column_name VARCHAR(255) NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_table_permission_column_perm ON sys_table_permission_column (table_permission_id);
COMMENT ON TABLE sys_table_permission_column IS '库表权限-指定列白名单';