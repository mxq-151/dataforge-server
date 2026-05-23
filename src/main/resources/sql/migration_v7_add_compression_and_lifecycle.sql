-- ============================================================
-- 元数据表结构升级：增加压缩算法、存储位置、冷热数据生命周期字段
-- 版本: 7.0.0
-- 说明：增加 compression、location、hot_days、cold_days、keep_days 字段
-- ============================================================

-- 0. 扩大 storage_format 列长度（Hive InputFormat 类名可超过50字符）
ALTER TABLE table_metadata
MODIFY COLUMN storage_format VARCHAR(200) DEFAULT NULL COMMENT '存储格式(InputFormat类名/引擎名)';

-- 1. 表元数据表增加 compression 字段
ALTER TABLE table_metadata
ADD COLUMN compression VARCHAR(50) DEFAULT NULL COMMENT '压缩算法(SNAPPY/GZIP/ZSTD/LZ4/LZO/BZIP2/DEFLATE/ZLIB/NO)'
AFTER storage_format;

-- 2. 表元数据表增加 location 字段
ALTER TABLE table_metadata
ADD COLUMN location VARCHAR(500) DEFAULT NULL COMMENT '存储位置(HDFS路径/数据目录等)'
AFTER compression;

-- 3. 表元数据表增加 table_create_time 字段（如果不存在）
ALTER TABLE table_metadata
ADD COLUMN IF NOT EXISTS table_create_time DATETIME DEFAULT NULL COMMENT '表创建时间'
AFTER table_comment;

-- 4. 表元数据表增加冷热数据生命周期字段
ALTER TABLE table_metadata
ADD COLUMN hot_days INT NOT NULL DEFAULT 90 COMMENT '热数据保留天数' AFTER size_bytes,
ADD COLUMN cold_days INT NOT NULL DEFAULT 180 COMMENT '冷数据保留天数' AFTER hot_days,
ADD COLUMN keep_days INT NOT NULL DEFAULT 720 COMMENT '数据总保留天数' AFTER cold_days;
