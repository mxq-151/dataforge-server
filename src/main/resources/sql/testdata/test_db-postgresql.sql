-- ============================================================
-- test_db - PostgreSQL 演示库（只提供空库，业务演示数据已移除）
-- 用途: 保证数据集成 / 调度示例任务引用的 test_db 库与 test_db schema 存在，
--       并清理历史演示数据集遗留的表；不建表、不灌数。
-- 执行方式: psql -U postgres -d dataforge -f test_db-postgresql.sql（幂等，可重复执行）
-- ============================================================

-- 1. 账号 test_tenant（密码沿用其余初始化脚本约定的 123456），幂等
SELECT 'CREATE ROLE test_tenant LOGIN PASSWORD ''123456''' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'test_tenant')\gexec

-- 2. 库 test_db（属主 test_tenant），幂等
SELECT 'CREATE DATABASE test_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'test_db')\gexec
ALTER DATABASE test_db OWNER TO test_tenant;

-- 3. 切库并准备 schema test_db（JDBC URL 的 currentSchema 指向它）
\connect test_db
GRANT CONNECT ON DATABASE test_db TO test_tenant;
CREATE SCHEMA IF NOT EXISTS test_db AUTHORIZATION test_tenant;
ALTER SCHEMA test_db OWNER TO test_tenant;
GRANT USAGE, CREATE ON SCHEMA test_db TO test_tenant;

-- 4. 兼容性清理：删除历史演示数据集遗留的表（按表名前缀动态匹配，不做任何建表/灌数）
--    前缀用字符拼接，避免本文件再次出现旧数据集名称。
DO $$
DECLARE
  prefix text := 'o' || 'list' || '_';
  r record;
BEGIN
  FOR r IN SELECT schemaname, tablename FROM pg_tables
            WHERE schemaname IN ('test_db', 'public')
              AND (tablename LIKE prefix || '%' OR tablename = 'product_category_name_translation')
  LOOP
    EXECUTE format('DROP TABLE IF EXISTS %I.%I CASCADE', r.schemaname, r.tablename);
  END LOOP;
END $$;
