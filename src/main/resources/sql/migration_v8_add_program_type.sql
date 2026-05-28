-- =====================================================
-- Migration V8: 添加 program_type 和 auto_choose 字段到 dev_jar_dependency 表
-- 日期: 2026-05-25
-- 说明: 支持按程序类型(flink/spark)分类管理JAR依赖，并支持自动选择功能
-- =====================================================

-- 添加 program_type 字段：程序类型 (flink/spark)
ALTER TABLE dev_jar_dependency
    ADD COLUMN program_type VARCHAR(20) NOT NULL DEFAULT 'flink'
        COMMENT '程序类型(flink-flink任务 spark-spark任务)'
        AFTER category;

-- 添加 auto_choose 字段：是否自动选择 (0-否 1-是)
ALTER TABLE dev_jar_dependency
    ADD COLUMN auto_choose TINYINT NOT NULL DEFAULT 0
        COMMENT '是否自动选择(0-否 1-是)'
        AFTER program_type;

-- 为 program_type 字段创建索引，优化查询性能
ALTER TABLE dev_jar_dependency
    ADD INDEX idx_program_type (program_type);

-- 更新现有数据：将所有现有记录的 program_type 设置为 flink（默认值已在 DDL 中设置）
-- 如果需要根据其他条件更新，可以执行以下示例：
-- UPDATE dev_jar_dependency SET program_type = 'spark' WHERE description LIKE '%spark%';

-- 验证字段是否添加成功
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM INFORMATION_SCHEMA.COLUMNS
-- WHERE TABLE_NAME = 'dev_jar_dependency'
-- AND COLUMN_NAME IN ('program_type', 'auto_choose');
