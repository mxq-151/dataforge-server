-- ============================================================
-- 元数据表结构升级：增加存储引擎字段并调整唯一键
-- 版本: 2.0.0
-- 说明：支持多存储引擎场景，唯一键改为存储引擎+库名+表名/列名
-- ============================================================

-- 1. 数据源配置表增加 storage_engine 字段
ALTER TABLE datasource_config 
ADD COLUMN storage_engine VARCHAR(20) DEFAULT NULL COMMENT '存储引擎类型(HIVE/DORIS/STARROCKS/HBASE/MYSQL/ES/CLICKHOUSE/HUDI/ICEBERG)' 
AFTER datasource_type;

-- 添加索引
CREATE INDEX idx_datasource_storage_engine ON datasource_config(storage_engine);

-- 2. 表元数据表增加 storage_engine 字段
ALTER TABLE table_metadata 
ADD COLUMN storage_engine VARCHAR(20) DEFAULT NULL COMMENT '存储引擎类型' 
AFTER datasource_id;

-- 删除旧的唯一键
ALTER TABLE table_metadata DROP INDEX IF EXISTS uk_table;

-- 创建新的唯一键：storage_engine + database_name + table_name
ALTER TABLE table_metadata 
ADD UNIQUE KEY uk_table_storage (storage_engine, database_name, table_name);

-- 3. 列元数据表增加 storage_engine、database_name、table_name 字段
ALTER TABLE column_metadata 
ADD COLUMN storage_engine VARCHAR(20) DEFAULT NULL COMMENT '存储引擎类型' 
AFTER table_metadata_id,
ADD COLUMN database_name VARCHAR(100) DEFAULT NULL COMMENT '数据库名称' 
AFTER storage_engine,
ADD COLUMN table_name VARCHAR(200) DEFAULT NULL COMMENT '表名称' 
AFTER database_name;

-- 删除旧的唯一键（如果存在基于 table_metadata_id + column_name 的唯一键）
-- 注意：如果原唯一键名称不同，请根据实际情况调整
ALTER TABLE column_metadata DROP INDEX IF EXISTS uk_column;

-- 创建新的唯一键：storage_engine + database_name + table_name + column_name
ALTER TABLE column_metadata 
ADD UNIQUE KEY uk_column_storage (storage_engine, database_name, table_name, column_name);

-- 4. 更新现有数据（从数据源配置推断存储引擎类型）
-- 注意：这里只是一个示例，实际应根据业务逻辑调整
UPDATE table_metadata tm
INNER JOIN datasource_config dc ON tm.datasource_id = dc.id
SET tm.storage_engine = CASE 
    WHEN dc.datasource_type = 'hive' THEN 'HIVE'
    WHEN dc.datasource_type = 'doris' THEN 'DORIS'
    WHEN dc.datasource_type = 'starrocks' THEN 'STARROCKS'
    WHEN dc.datasource_type = 'mysql' THEN 'MYSQL'
    WHEN dc.datasource_type = 'es' THEN 'ES'
    WHEN dc.datasource_type = 'clickhouse' THEN 'CLICKHOUSE'
    ELSE dc.datasource_type
END
WHERE tm.storage_engine IS NULL;

-- 同步更新列元数据的存储引擎信息
UPDATE column_metadata cm
INNER JOIN table_metadata tm ON cm.table_metadata_id = tm.id
SET cm.storage_engine = tm.storageEngine,
    cm.databaseName = tm.databaseName,
    cm.tableName = tm.tableName
WHERE cm.storage_engine IS NULL;
