-- ---------------------------------------------------------------------------
-- 数据迁移：dev_program_params 主键由自增 id 改为 (project_id, file_path) 联合主键
--
-- 背景
--   原表主键为自增 id，(project_id, file_path) 仅为唯一约束 uk_project_file。
--   ProgramParamsService 已改为数据库原生 SQL upsert（ON DUPLICATE KEY UPDATE /
--   ON CONFLICT / MERGE），业务唯一键即 (project_id, file_path)，自增 id 失去意义，
--   因此将主键改为联合主键并移除 id 列。
--
-- 影响
--   表结构变化后，依赖 dev_program_params.id 的代码需同步调整（该表无外键引用，
--   无其他表引用其 id）。
--
-- 执行方式（MySQL）
--   只执行一次。建议执行前先备份：
--     mysqldump dataforge dev_program_params > dev_program_params_backup.sql
--   迁移完成后可用下方校验 SQL 确认：
--     SHOW CREATE TABLE dev_program_params;   -- PRIMARY KEY 应为 (project_id, file_path)
--
-- 注意
--   以下语句按顺序执行：
--   1) 先去掉 id 的 AUTO_INCREMENT（MySQL 要求自增列必须是 key，不先去掉无法 DROP PRIMARY KEY）
--   2) 再移除主键与索引
--   3) 删 id 列
--   4) 最后建联合主键
-- ---------------------------------------------------------------------------

ALTER TABLE dev_program_params MODIFY COLUMN id bigint NOT NULL;
ALTER TABLE dev_program_params DROP PRIMARY KEY;
ALTER TABLE dev_program_params DROP INDEX uk_project_file;
ALTER TABLE dev_program_params DROP INDEX idx_project_id;
ALTER TABLE dev_program_params DROP COLUMN id;
ALTER TABLE dev_program_params ADD PRIMARY KEY (project_id, file_path);

-- 校验：应能看到联合主键
-- SHOW CREATE TABLE dev_program_params;
