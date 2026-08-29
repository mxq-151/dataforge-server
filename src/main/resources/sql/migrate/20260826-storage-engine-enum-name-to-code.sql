-- ---------------------------------------------------------------------------
-- 数据迁移：StorageEngineType / EngineType 合并进 DatasourceType 后的存量数据修复
--
-- 背景
--   合并前 table_metadata.storage_engine / column_metadata.storage_engine 的类型是
--   StorageEngineType，该枚举既没有 @EnumValue 也没有 @JsonValue，MyBatis 使用默认的
--   EnumTypeHandler 落库【枚举名】，即 'MYSQL' / 'HIVE' / 'CLICKHOUSE' 这类大写值。
--
--   合并后字段类型改为 DatasourceType，其 code 字段上带 @EnumValue，MyBatis-Plus 改用
--   MybatisEnumTypeHandler 落库【小写 code】，即 'mysql' / 'hive' / 'clickhouse'。
--
-- 影响
--   未经迁移的历史行在读取时会得到 null（不报错，但按引擎过滤的查询会全部漏掉这些行），
--   GET /metadata/table/columns 等接口返回的 storage_engine 也会变成 null。
--
--   回归测试：StorageEnginePersistenceRegressionTest#legacyUppercaseRowsLostBeforeMigration
--
-- 执行方式
--   只需执行一次，可重复执行（LOWER 幂等）。建议执行前先备份：
--     mysqldump dataforge table_metadata column_metadata > storage_engine_backup.sql
-- ---------------------------------------------------------------------------

UPDATE table_metadata  SET storage_engine = LOWER(storage_engine) WHERE storage_engine IS NOT NULL;
UPDATE column_metadata SET storage_engine = LOWER(storage_engine) WHERE storage_engine IS NOT NULL;

-- 校验：以下查询应返回 0 行
-- SELECT 'table_metadata'  AS tbl, id, storage_engine FROM table_metadata
--   WHERE storage_engine IS NOT NULL AND storage_engine <> LOWER(storage_engine)
-- UNION ALL
-- SELECT 'column_metadata' AS tbl, id, storage_engine FROM column_metadata
--   WHERE storage_engine IS NOT NULL AND storage_engine <> LOWER(storage_engine);
