-- ---------------------------------------------------------------------------
-- 数据迁移：资产目录日快照表（PostgreSQL 版）
--
-- 背景
--   资产目录（目录树 / 检索 / 盘点 / 治理项）原先在每次查询时临时拼接：
--   全量扫描 table_metadata、在内存里按表名解析分层与业务域、逐表重算治理项。
--   本批引入 asset_catalog_daily，把「结构 + 治理属性 + 关联计数 + 使用热度」
--   按天固化，查询侧改为读快照。
--
--   快照为 T+1：当天新建 / 变更的表次日才会进入目录。
--
-- 影响
--   - 新增 1 张表：asset_catalog_daily；不修改、不删除任何既有数据与列。
--   - 由计划任务每日 02:30 生成（见 AssetCatalogSnapshotTask），保留 180 天。
--
-- 执行方式（PostgreSQL）
--   PGPASSWORD=123456 psql -h 127.0.0.1 -p 5432 -U postgres -d dataforge \
--     -f 20260929-asset-catalog-daily-snapshot.sql
--   可重复执行（CREATE TABLE/INDEX IF NOT EXISTS）。
--
--   注意：init/dataforge-postgresql.sql 已同步包含本表，
--   全新部署无需执行本脚本；本脚本只用于已存在库的增量升级。
--
-- 校验
--   \d asset_catalog_daily
--   SELECT COUNT(*) FROM asset_catalog_daily;   -- 首次部署为 0，次日任务后有数据
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS asset_catalog_daily (
  id BIGSERIAL NOT NULL,
  stat_date DATE NOT NULL,
  table_metadata_id BIGINT NOT NULL,
  datasource_id BIGINT,
  datasource_name VARCHAR(200),
  storage_engine VARCHAR(40),
  database_name VARCHAR(100),
  table_name VARCHAR(200) NOT NULL,
  table_comment VARCHAR(1000),
  table_type VARCHAR(50),
  layer_code VARCHAR(40),
  domain_code VARCHAR(40),
  convention_parsed SMALLINT NOT NULL DEFAULT 0,
  refresh_cycle VARCHAR(8),
  category_code VARCHAR(64),
  business_owner VARCHAR(255),
  tech_owner VARCHAR(255),
  topic VARCHAR(255),
  visibility VARCHAR(20),
  asset_level VARCHAR(8),
  row_count BIGINT,
  size_bytes BIGINT,
  column_count INTEGER NOT NULL DEFAULT 0,
  tag_count INTEGER NOT NULL DEFAULT 0,
  indicator_count INTEGER NOT NULL DEFAULT 0,
  quality_rule_count INTEGER NOT NULL DEFAULT 0,
  has_doc SMALLINT NOT NULL DEFAULT 0,
  health_issue_count INTEGER NOT NULL DEFAULT 0,
  health_issue_codes VARCHAR(500),
  browse_count_30d INTEGER NOT NULL DEFAULT 0,
  read_count_30d INTEGER NOT NULL DEFAULT 0,
  bookmark_count INTEGER NOT NULL DEFAULT 0,
  last_access_time TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_catalog_daily UNIQUE (stat_date, table_metadata_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_catalog_daily_layer ON asset_catalog_daily (stat_date, layer_code);
CREATE INDEX IF NOT EXISTS idx_asset_catalog_daily_domain ON asset_catalog_daily (stat_date, domain_code);
CREATE INDEX IF NOT EXISTS idx_asset_catalog_daily_ds ON asset_catalog_daily (stat_date, datasource_id);

COMMENT ON TABLE asset_catalog_daily IS '资产目录日快照表(T+1，一行=一张表×一个统计日)';
COMMENT ON COLUMN asset_catalog_daily.stat_date IS '统计日';
COMMENT ON COLUMN asset_catalog_daily.layer_code IS '命名规范解析出的分层编码(未解析为NULL)';
COMMENT ON COLUMN asset_catalog_daily.domain_code IS '命名规范解析出的业务域编码(未解析为NULL)';
COMMENT ON COLUMN asset_catalog_daily.health_issue_codes IS '治理项编码集合(逗号分隔)';
