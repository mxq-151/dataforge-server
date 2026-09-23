-- ---------------------------------------------------------------------------
-- 数据迁移：「数据质量」与「资产目录」两个模块（第二批 / 第三批）—— MySQL / MariaDB 版
--
-- 与 PostgreSQL 版内容等价，差异仅在方言：
--   TIMESTAMP/datetime、SMALLINT/tinyint、TEXT/text；
--   MySQL 8 的 ALTER TABLE 没有 ADD COLUMN IF NOT EXISTS，用存储过程守卫。
--
-- 执行方式（MySQL / MariaDB）
--   mysql -h 127.0.0.1 -P 3306 -u root -p dataforge \
--     < 20260917-dq-and-asset-catalog-batch2-mysql.sql
--   可重复执行；脚本末尾会清理自己创建的临时存储过程。
--
--   注意：init/dataforge-mysql.sql 已同步包含本脚本的全部建表与新增列，
--   全新部署无需执行本脚本；本脚本只用于已存在库的增量升级。
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

-- ===========================================================================
-- 一、新增表（CREATE TABLE IF NOT EXISTS 本身幂等）
-- ===========================================================================

CREATE TABLE IF NOT EXISTS `asset_doc` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_metadata_id` bigint NOT NULL COMMENT '表元数据ID',
  `content` text DEFAULT NULL COMMENT '使用说明(markdown)',
  `version` int NOT NULL DEFAULT 1 COMMENT '当前版本号',
  `update_by` varchar(100) DEFAULT NULL COMMENT '最后修改人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_doc_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产使用说明表(markdown，版本历史在 asset_doc_history)';
CREATE TABLE IF NOT EXISTS `asset_doc_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_metadata_id` bigint NOT NULL COMMENT '表元数据ID',
  `version` int NOT NULL COMMENT '版本号',
  `content` text DEFAULT NULL COMMENT '该版本的使用说明(markdown)',
  `update_by` varchar(100) DEFAULT NULL COMMENT '修改人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_asset_doc_history_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产使用说明版本历史表';
CREATE TABLE IF NOT EXISTS `asset_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tag_code` varchar(64) NOT NULL COMMENT '标签编码',
  `tag_name` varchar(100) NOT NULL COMMENT '标签名称',
  `color` varchar(32) DEFAULT NULL COMMENT '标签颜色(前端色值)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_tag_code` (`tag_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产标签定义表';
CREATE TABLE IF NOT EXISTS `asset_tag_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tag_id` bigint NOT NULL COMMENT '标签ID',
  `asset_type` varchar(16) NOT NULL COMMENT '资产类型(table/column/indicator)',
  `asset_id` bigint NOT NULL COMMENT '资产ID',
  `table_metadata_id` bigint DEFAULT NULL COMMENT '表元数据ID(便于按表聚合)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_tag_rel` (`tag_id`,`asset_type`,`asset_id`),
  KEY `idx_asset_tag_rel_asset` (`asset_type`,`asset_id`),
  KEY `idx_asset_tag_rel_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产标签关联表(支持表/字段/指标)';
CREATE TABLE IF NOT EXISTS `asset_usage_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `asset_type` varchar(16) NOT NULL DEFAULT 'table' COMMENT '资产类型(table/column/indicator)',
  `asset_id` bigint NOT NULL COMMENT '资产ID',
  `table_metadata_id` bigint DEFAULT NULL COMMENT '表元数据ID(便于按表聚合)',
  `stat_date` date NOT NULL COMMENT '统计日',
  `browse_count` int NOT NULL DEFAULT 0 COMMENT '浏览数',
  `read_count` int NOT NULL DEFAULT 0 COMMENT '读取数',
  `bookmark_count` int NOT NULL DEFAULT 0 COMMENT '收藏数',
  `relate_count` int NOT NULL DEFAULT 0 COMMENT '被关联数(引用该资产的规则/指标等)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_usage_daily` (`asset_type`,`asset_id`,`stat_date`),
  KEY `idx_asset_usage_table` (`table_metadata_id`),
  KEY `idx_asset_usage_date` (`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产使用热度日汇总表(T+1，由 asset_access_log 汇总)';
CREATE TABLE IF NOT EXISTS `dq_alert_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `run_id` bigint DEFAULT NULL COMMENT '运行实例ID',
  `task_id` bigint DEFAULT NULL COMMENT '质检任务ID',
  `rule_id` bigint DEFAULT NULL COMMENT '规则ID',
  `rule_name` varchar(200) DEFAULT NULL COMMENT '规则名(结果自包含，规则被删后仍可追溯)',
  `channel` varchar(32) NOT NULL COMMENT '告警渠道(site=站内 webhook=回调 email=邮件)',
  `receiver` varchar(200) DEFAULT NULL COMMENT '接收人',
  `status` varchar(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '发送状态(SUCCESS/FAILED)',
  `fail_reason` varchar(500) DEFAULT NULL COMMENT '失败原因',
  `send_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_dq_alert_log_run` (`run_id`),
  KEY `idx_dq_alert_log_task` (`task_id`),
  KEY `idx_dq_alert_log_time` (`send_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量告警发送记录表';
CREATE TABLE IF NOT EXISTS `dq_alert_subscription` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` bigint NOT NULL COMMENT '质检任务ID',
  `notify_on` varchar(64) NOT NULL DEFAULT 'ERROR,FAILED' COMMENT '触发告警的结果状态集合(逗号分隔)',
  `channels` varchar(255) DEFAULT NULL COMMENT '渠道集合(逗号分隔)',
  `receivers` varchar(500) DEFAULT NULL COMMENT '接收人集合(逗号分隔)',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_dq_alert_sub_task` (`task_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量告警订阅表(按任务订阅，命中指定结果状态时告警)';
CREATE TABLE IF NOT EXISTS `dq_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_name` varchar(200) NOT NULL COMMENT '任务名称',
  `owner_name` varchar(100) DEFAULT NULL COMMENT '任务负责人',
  `description` varchar(500) DEFAULT NULL COMMENT '任务说明',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `datasource_name` varchar(100) DEFAULT NULL COMMENT '数据源名称',
  `database_name` varchar(100) DEFAULT NULL COMMENT '库名',
  `table_name` varchar(200) DEFAULT NULL COMMENT '表名',
  `trigger_type` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT '触发方式(SCHEDULE=调度触发 MANUAL=手动触发)',
  `project_code` varchar(200) DEFAULT NULL COMMENT '调度项目编码',
  `workflow_code` bigint DEFAULT NULL COMMENT '调度工作流编码',
  `workflow_name` varchar(200) DEFAULT NULL COMMENT '调度工作流名称',
  `node_name` varchar(200) DEFAULT NULL COMMENT '调度节点名',
  `dispose_policy` varchar(8) NOT NULL DEFAULT 'ALERT' COMMENT '任务级处置策略(BLOCK=阻断下游 ALERT=告警)',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_dq_task_table` (`database_name`,`table_name`),
  KEY `idx_dq_task_trigger` (`trigger_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量质检任务表(规则集的执行计划)';
CREATE TABLE IF NOT EXISTS `dq_task_rule_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` bigint NOT NULL COMMENT '质检任务ID',
  `rule_id` bigint NOT NULL COMMENT '监控规则ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dq_task_rule` (`task_id`,`rule_id`),
  KEY `idx_dq_task_rule_rule` (`rule_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量质检任务与规则的关联表';


-- ===========================================================================
-- 二、既有表新增列
--   MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用存储过程查 information_schema 守卫。
-- ===========================================================================

DROP PROCEDURE IF EXISTS df_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE df_add_column_if_missing(
  IN p_table VARCHAR(64),
  IN p_column VARCHAR(64),
  IN p_definition VARCHAR(1000),
  IN p_comment VARCHAR(500)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
  ) THEN
    IF p_comment IS NULL OR p_comment = '' THEN
      SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    ELSE
      SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ',
                        p_definition, ' COMMENT ', QUOTE(p_comment));
    END IF;
    PREPARE df_stmt FROM @ddl;
    EXECUTE df_stmt;
    DEALLOCATE PREPARE df_stmt;
  END IF;
END$$
DELIMITER ;

CALL df_add_column_if_missing('table_metadata', 'browse_cnt_30d',
  'int NOT NULL DEFAULT 0',
  '近30日浏览数(由 asset_access_log 日汇总回写)');
CALL df_add_column_if_missing('table_metadata', 'read_cnt_30d',
  'int NOT NULL DEFAULT 0',
  '近30日读取数(由 asset_access_log 日汇总回写)');
CALL df_add_column_if_missing('table_metadata', 'bookmark_cnt',
  'int NOT NULL DEFAULT 0',
  '收藏数(由 asset_bookmark 回写)');
CALL df_add_column_if_missing('table_metadata', 'last_access_time',
  'datetime DEFAULT NULL',
  '最近访问时间(埋点回写，用于热度排序)');
CALL df_add_column_if_missing('dq_rule_result', 'metric_value',
  'varchar(64) DEFAULT NULL',
  '原始度量值(行数类=当期行数 跨表比对=主表值,对照值)；仅存百分比无法回推历史基线');
CALL df_add_column_if_missing('dq_rule', 'params',
  'text DEFAULT NULL',
  '自定义模板占位符取值(JSON 对象)；内置模板按规则类型使用固定参数名');
CALL df_add_column_if_missing('column_metadata', 'hot_score',
  'tinyint NOT NULL DEFAULT 0',
  '字段热度星级(0~5，由使用记录日汇总回写)');


DROP PROCEDURE IF EXISTS df_add_column_if_missing;

-- ===========================================================================
-- 三、平台级参考数据：默认资产标签（与 init 脚本一致，INSERT IGNORE 幂等）
-- ===========================================================================

INSERT IGNORE INTO `asset_tag`
  (`tag_code`,`tag_name`,`color`,`remark`,`enabled`)
VALUES
  ('CORE','核心资产','#c62828','对业务有直接支撑作用的核心表',1),
  ('HOT','高频使用','#1565c0','近30日读取热度高',1),
  ('PENDING_GOVERN','待治理','#f9a825','命名不合规、未标注或无负责人等',1),
  ('SENSITIVE','敏感数据','#6a1b9a','含个人信息或需脱敏的字段',1);
