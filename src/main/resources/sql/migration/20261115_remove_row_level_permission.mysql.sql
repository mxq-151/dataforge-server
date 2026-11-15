-- 迁移脚本：移除行级权限（2026-11-15）
-- 适用：MySQL 8.x；MariaDB 可直接使用本脚本。
-- 影响范围：
--   1) 删除 sys_table_permission_row 表（行过滤规则存量数据）
--   2) 删除 sys_table_permission.row_filter_sql 列（历史行过滤配置）
-- 本脚本为不可逆的数据删除，默认不自动执行，由运维在产品确认后手动执行。
--
-- 执行前置：
--   a. 确认客户端已停止读写 rowFilterSql 字段；
--   b. 先备份以下两份数据（建议保留至少一个发布周期）：
--        CREATE TABLE bak_sys_table_permission_row_20261115
--            AS SELECT * FROM sys_table_permission_row;
--        CREATE TABLE bak_sys_table_permission_row_filter_20261115
--            AS SELECT id, row_filter_sql FROM sys_table_permission;
--   c. 备份表确认可读后再执行本脚本。

DROP TABLE IF EXISTS sys_table_permission_row;

ALTER TABLE sys_table_permission DROP COLUMN row_filter_sql;
