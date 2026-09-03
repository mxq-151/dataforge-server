-- ---------------------------------------------------------------------------
-- 数据迁移：indicators 表 varchar(100) 列扩容至 varchar(1024)
--
-- 背景
--   indicators 表的 code / calc_expr / business_owner / tech_owner 原为 VARCHAR(100)，
--   指标计算表达式(calc_expr)等字段可能超过 100 字符，导致 upsert 时报：
--     value too long for type character varying(100)
--   因此将上述列统一扩容至 VARCHAR(1024)。
--
-- 影响
--   仅放宽列长度，不改变已有数据；uk_code(code 唯一索引)不受影响。
--   新部署无需执行本脚本（init/dataforge-postgresql.sql 已按新长度建表）。
--
-- 执行方式（PostgreSQL）
--   在 dataforge 库执行一次即可，幂等（重复执行只返回 NOTICE）。
--   docker 部署可进入容器执行：
--     docker exec -it <容器名> su postgres -c \
--       "psql -d dataforge < /opt/dataforge/sql/20260903-indicators-column-length-1024.sql"
--   或宿主机：
--     PGPASSWORD=123456 psql -h 127.0.0.1 -p 5432 -U postgres -d dataforge \
--       -f 20260903-indicators-column-length-1024.sql
--   迁移完成后可用下方校验 SQL 确认列长度。
-- ---------------------------------------------------------------------------

ALTER TABLE indicators ALTER COLUMN code           TYPE VARCHAR(1024);
ALTER TABLE indicators ALTER COLUMN calc_expr      TYPE VARCHAR(1024);
ALTER TABLE indicators ALTER COLUMN business_owner TYPE VARCHAR(1024);
ALTER TABLE indicators ALTER COLUMN tech_owner     TYPE VARCHAR(1024);

-- 校验：应全部返回 1024
-- SELECT column_name, character_maximum_length
--   FROM information_schema.columns
--  WHERE table_name = 'indicators'
--    AND column_name IN ('code','calc_expr','business_owner','tech_owner');
