-- Migration V6: 库表权限改用名称关联（替代ID关联）
-- 原来通过 sys_permission_table 关联 table_metadata_id，元数据重建后ID变化导致权限失效
-- 现在直接在 sys_table_permission 中存储 datasource_name, database_name, table_names

-- 1. 添加新字段到 sys_table_permission
ALTER TABLE sys_table_permission ADD COLUMN datasource_name VARCHAR(255) DEFAULT NULL COMMENT '数据源名称' AFTER permission_name;
ALTER TABLE sys_table_permission ADD COLUMN database_name VARCHAR(255) DEFAULT NULL COMMENT '数据库名称' AFTER datasource_name;
ALTER TABLE sys_table_permission ADD COLUMN table_names TEXT DEFAULT NULL COMMENT '表名列表(逗号分隔,*表示全部)' AFTER database_name;

-- 2. 从旧关联表迁移数据到新字段
UPDATE sys_table_permission stp
INNER JOIN (
    SELECT pt.table_permission_id,
           GROUP_CONCAT(DISTINCT tm.table_name ORDER BY tm.table_name SEPARATOR ',') AS table_name_list,
           MAX(tm.database_name) AS db_name,
           MAX(dc.datasource_name) AS ds_name
    FROM sys_permission_table pt
    LEFT JOIN table_metadata tm ON pt.table_metadata_id = tm.id
    LEFT JOIN datasource_config dc ON tm.datasource_id = dc.id
    GROUP BY pt.table_permission_id
) migration ON stp.id = migration.table_permission_id
SET stp.table_names = migration.table_name_list,
    stp.database_name = migration.db_name,
    stp.datasource_name = migration.ds_name;

-- 3. 旧表 sys_permission_table 不再使用，可选择保留或删除
-- 如需删除，取消下面的注释:
-- DROP TABLE IF EXISTS sys_permission_table;
