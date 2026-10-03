-- ---------------------------------------------------------------------------
-- 数据迁移：资产目录日快照表（MySQL / MariaDB 版）
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
-- 执行方式（MySQL / MariaDB）
--   mysql -h 127.0.0.1 -P 3306 -u root -p dataforge \
--     < 20260929-asset-catalog-daily-snapshot-mysql.sql
--   可重复执行（CREATE TABLE IF NOT EXISTS 本身幂等）。
--
--   注意：init/dataforge-mysql.sql、init/dataforge-mariadb.sql 已同步包含本表，
--   全新部署无需执行本脚本；本脚本只用于已存在库的增量升级。
--
-- 校验
--   SHOW CREATE TABLE asset_catalog_daily;
--   SELECT COUNT(*) FROM asset_catalog_daily;   -- 首次部署为 0，次日任务后有数据
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `asset_catalog_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日',
  `table_metadata_id` bigint NOT NULL COMMENT '表元数据ID',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `datasource_name` varchar(200) DEFAULT NULL COMMENT '数据源名称',
  `storage_engine` varchar(40) DEFAULT NULL COMMENT '存储引擎编码',
  `database_name` varchar(100) DEFAULT NULL COMMENT '库名',
  `table_name` varchar(200) NOT NULL COMMENT '表名',
  `table_comment` varchar(1000) DEFAULT NULL COMMENT '表注释',
  `table_type` varchar(50) DEFAULT NULL COMMENT '表类型',
  `layer_code` varchar(40) DEFAULT NULL COMMENT '命名规范解析出的分层编码(未解析为NULL)',
  `domain_code` varchar(40) DEFAULT NULL COMMENT '命名规范解析出的业务域编码(未解析为NULL)',
  `convention_parsed` tinyint NOT NULL DEFAULT 0 COMMENT '是否符合命名规范(四要素齐备)',
  `refresh_cycle` varchar(8) DEFAULT NULL COMMENT '刷新周期后缀',
  `category_code` varchar(64) DEFAULT NULL COMMENT '人工覆盖的分类编码',
  `business_owner` varchar(255) DEFAULT NULL COMMENT '业务负责人',
  `tech_owner` varchar(255) DEFAULT NULL COMMENT '技术负责人',
  `topic` varchar(255) DEFAULT NULL COMMENT '所属主题域',
  `visibility` varchar(20) DEFAULT NULL COMMENT '可见性(public/internal/restricted)',
  `asset_level` varchar(8) DEFAULT NULL COMMENT '资产等级L1~L5',
  `row_count` bigint DEFAULT NULL COMMENT '行数',
  `size_bytes` bigint DEFAULT NULL COMMENT '存储字节数',
  `column_count` int NOT NULL DEFAULT 0 COMMENT '字段数',
  `tag_count` int NOT NULL DEFAULT 0 COMMENT '表标签数',
  `indicator_count` int NOT NULL DEFAULT 0 COMMENT '关联指标数',
  `quality_rule_count` int NOT NULL DEFAULT 0 COMMENT '已启用质量规则数',
  `has_doc` tinyint NOT NULL DEFAULT 0 COMMENT '是否有使用说明',
  `health_issue_count` int NOT NULL DEFAULT 0 COMMENT '治理项数量',
  `health_issue_codes` varchar(500) DEFAULT NULL COMMENT '治理项编码集合(逗号分隔)',
  `browse_count_30d` int NOT NULL DEFAULT 0 COMMENT '近30日浏览数',
  `read_count_30d` int NOT NULL DEFAULT 0 COMMENT '近30日读取数',
  `bookmark_count` int NOT NULL DEFAULT 0 COMMENT '收藏数',
  `last_access_time` datetime DEFAULT NULL COMMENT '最近访问时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_catalog_daily` (`stat_date`,`table_metadata_id`),
  KEY `idx_asset_catalog_daily_layer` (`stat_date`,`layer_code`),
  KEY `idx_asset_catalog_daily_domain` (`stat_date`,`domain_code`),
  KEY `idx_asset_catalog_daily_ds` (`stat_date`,`datasource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产目录日快照表(T+1，一行=一张表×一个统计日)';
