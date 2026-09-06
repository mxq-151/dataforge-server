-- ---------------------------------------------------------------------------
-- 数据迁移：新增库表权限-行级过滤表 sys_table_permission_row，迁移历史 row_filter_sql
--
-- 背景
--   此前 row_filter_sql 是挂在整条表权限上的一个字段，整条权限命中的多张表（或 *)
--   会共用同一过滤条件，导致过滤条件被错误施加到非目标表（例如订单过滤条件套到商品表，
--   把所有行过滤成 0 行）。本次改为按 (表权限, 表) 存储各自的行过滤条件。
--
-- 影响
--   仅新增一张表；对老数据做尽力迁移。
--   迁移规则：仅当某条权限的 table_names 恰好只有一张表时，把该条权限的 row_filter_sql
--   迁入该表对应的行过滤记录；若 table_names 含多张表或为 *，语义有歧义，不自动迁移
--   （需人工在界面上按表重新配置）。迁移后旧 sys_table_permission.row_filter_sql 保留但不参与计算。
--
-- 新部署无需执行（init/dataforge-*.sql 已包含建表）。
-- 执行方式（PostgreSQL）：docker 内或宿主机执行一次即可，幂等。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sys_table_permission_row (
  id BIGSERIAL,
  table_permission_id BIGINT NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  row_filter_sql TEXT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_table_permission_row_perm ON sys_table_permission_row (table_permission_id);
COMMENT ON TABLE sys_table_permission_row IS '库表权限-行级过滤SQL';

-- 尽力迁移：单表权限的历史 row_filter_sql 迁入新的按表行过滤表
INSERT INTO sys_table_permission_row(table_permission_id, table_name, row_filter_sql, create_time)
SELECT id,
       TRIM(BOTH FROM table_name),
       row_filter_sql,
       NOW()
FROM (
    SELECT tp.id,
           tp.row_filter_sql,
           unnest(string_to_array(tp.table_names, ',')) AS table_name
    FROM sys_table_permission tp
    WHERE tp.row_filter_sql IS NOT NULL
      AND btrim(tp.row_filter_sql) <> ''
      AND tp.table_names IS NOT NULL
      AND tp.table_names NOT LIKE '%*%'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM sys_table_permission_row r
    WHERE r.table_permission_id = t.id AND r.table_name = TRIM(BOTH FROM t.table_name)
);