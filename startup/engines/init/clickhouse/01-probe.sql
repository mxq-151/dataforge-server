-- ClickHouse 首次初始化（由镜像的 /docker-entrypoint-initdb.d 在数据目录为空时执行）
-- 账号/密码不写在这里：使用 CLICKHOUSE_USER / CLICKHOUSE_PASSWORD 环境变量注入。
CREATE DATABASE IF NOT EXISTS dwd;

-- 连通性/查询验证用的最小表
CREATE TABLE IF NOT EXISTS dwd.e2e_probe
(
    id   UInt32,
    name String
)
ENGINE = MergeTree
ORDER BY id;

INSERT INTO dwd.e2e_probe VALUES (1, 'dataforge');
