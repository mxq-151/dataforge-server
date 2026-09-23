-- ---------------------------------------------------------------------------
-- 数据迁移：新增「数据质量」与「资产目录」两个模块（第一批）—— MySQL / MariaDB 版
--
-- 背景
--   对标阿里云 DataWorks 后确认本项目缺少两条能力线：
--     1) 数据质量：平台侧只有结果表 data_quality_check_result（「检查项」为自由文本），
--        没有规则定义、执行计划与运行记录，而智能体提示词里早已约定 7 类规则类型
--        与 DQC 质量层，形成「契约完备、落点缺失」的断裂；
--     2) 资产目录：元数据、血缘、权限、指标都已有，但缺少以「资产」为主语的浏览与
--        检索入口，负责人、分类、标签、收藏与使用热度这些治理属性也无处存放。
--
-- 影响
--   - 新增 7 张表：dq_rule_template / dq_rule / dq_run_instance / dq_rule_result /
--     asset_category / asset_bookmark / asset_access_log；
--   - 改造 3 张表（仅新增列，均可空或带默认值）：table_metadata、
--     column_metadata、data_quality_check_result；
--   - 新增 2 条一级菜单（data_quality / asset_catalog）与角色授权；
--   - 写入平台级参考数据：7 类内置规则模板 + 7 个数仓分层分类；
--   - 不修改、不删除任何既有数据；老接口 /quality/check-result/* 继续可用。
--
--   说明：dq_run_instance 原计划属第二批，因「手动触发校验 + 运行记录」在第一批就要
--   交付，没有运行实例则规则级结果无处挂载，故提前到本批。
--
-- 与本脚本的关系
--   PostgreSQL 版（无 -mysql 后缀）用于 docker 部署库；本文件用于 MySQL / MariaDB
--   环境（含本地开发库，application.yml 默认连的就是本机 MySQL）。
--   两端 schema 完全等价，差异仅在方言：TEXT/CLOB、TIMESTAMP/datetime、
--   SMALLINT/tinyint，以及 PG 用 CHECK 约束表达的可见性取值在 MySQL 侧用 enum 表达。
--
-- 执行方式（MySQL / MariaDB）
--   mysql -h 127.0.0.1 -P 3306 -u root -p dataforge \
--     < 20260917-dq-and-asset-catalog-batch1-mysql.sql
--   可重复执行（CREATE TABLE IF NOT EXISTS / 存储过程守卫 / INSERT IGNORE）。
--   脚本末尾会清理自己创建的临时存储过程。
--
--   注意：init/dataforge-mysql.sql 已同步包含本脚本的全部建表、新增列与参考数据，
--   全新部署无需执行本脚本；本脚本只用于已上线/已存在库的增量升级。
--
-- 校验
--   SHOW TABLES LIKE 'dq_%';      应 4 张
--   SHOW TABLES LIKE 'asset_%';   应 3 张
--   SHOW COLUMNS FROM table_metadata;  应含 business_owner/tech_owner/topic/
--                                      visibility/category_code/asset_level
--   SHOW COLUMNS FROM column_metadata; 应含 business_desc/security_level
--   SHOW COLUMNS FROM data_quality_check_result; 应含 rule_type/rule_name
--   SELECT rule_type FROM dq_rule_template;  应 7 行
--   SELECT menu_code FROM sys_menu_permission WHERE menu_code IN
--     ('data_quality','asset_catalog');  应 2 行
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

-- ===========================================================================
-- 一、新增表
--   MySQL 8 的 ALTER TABLE 没有 ADD COLUMN IF NOT EXISTS，但 CREATE TABLE
--   IF NOT EXISTS 是幂等的，所以新增表直接执行即可。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS `asset_category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `category_code` varchar(64) NOT NULL COMMENT '分类编码',
  `category_name` varchar(100) NOT NULL COMMENT '分类名称',
  `parent_code` varchar(64) DEFAULT NULL COMMENT '父级分类编码',
  `category_kind` varchar(16) NOT NULL COMMENT '分类维度(layer=数仓分层 domain=业务域)',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序号',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_category_code` (`category_code`),
  KEY `idx_asset_category_kind` (`category_kind`),
  KEY `idx_asset_category_parent` (`parent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产分类表(分层/业务域，支持人工覆盖)';
CREATE TABLE IF NOT EXISTS `asset_bookmark` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `asset_type` varchar(16) NOT NULL COMMENT '资产类型(table/column/indicator)',
  `asset_id` bigint NOT NULL COMMENT '资产ID',
  `table_metadata_id` bigint DEFAULT NULL COMMENT '表元数据ID(便于按表聚合)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_bookmark` (`user_id`,`asset_type`,`asset_id`),
  KEY `idx_asset_bookmark_user` (`user_id`),
  KEY `idx_asset_bookmark_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产收藏表';
CREATE TABLE IF NOT EXISTS `asset_access_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `user_name` varchar(100) DEFAULT NULL COMMENT '用户名',
  `asset_type` varchar(16) NOT NULL COMMENT '资产类型(table/column/indicator)',
  `asset_id` bigint NOT NULL COMMENT '资产ID',
  `table_metadata_id` bigint DEFAULT NULL COMMENT '表元数据ID(便于按表聚合)',
  `access_type` varchar(16) NOT NULL COMMENT '访问类型(browse=浏览 read=读取 bookmark=收藏 query=带查询)',
  `keyword` varchar(200) DEFAULT NULL COMMENT '检索关键词',
  `access_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
  PRIMARY KEY (`id`),
  KEY `idx_asset_access_asset` (`asset_type`,`asset_id`),
  KEY `idx_asset_access_time` (`access_time`),
  KEY `idx_asset_access_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产访问埋点表(仅追加，按日汇总)';
CREATE TABLE IF NOT EXISTS `dq_rule_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `template_code` varchar(64) NOT NULL COMMENT '模板编码(内置模板与规则类型编码同名)',
  `template_name` varchar(200) NOT NULL COMMENT '模板名称',
  `rule_type` varchar(64) NOT NULL COMMENT '规则类型(7类之一:PRIMARY_KEY_UNIQUE/NOT_NULL/VALUE_RANGE/ROW_COUNT_FLUCTUATION/ROW_COUNT_MOM/UPSTREAM_DOWNSTREAM_RECON/METRIC_CONSISTENCY)',
  `template_kind` varchar(16) NOT NULL DEFAULT 'custom' COMMENT '模板类别(builtin=内置 custom=自定义)',
  `sql_template` text COMMENT '校验SQL模板',
  `field_types` varchar(255) DEFAULT NULL COMMENT '适用字段类型',
  `param_schema` text COMMENT '参数结构(JSON)',
  `remark` varchar(500) DEFAULT NULL COMMENT '说明',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dq_template_code` (`template_code`),
  KEY `idx_dq_template_rule_type` (`rule_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量规则模板表';
CREATE TABLE IF NOT EXISTS `dq_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `rule_name` varchar(200) NOT NULL COMMENT '规则名',
  `rule_type` varchar(64) NOT NULL COMMENT '规则类型(7类之一)',
  `template_id` bigint DEFAULT NULL COMMENT '规则模板ID',
  `template_code` varchar(64) DEFAULT NULL COMMENT '规则模板编码(冗余，模板被删后仍可追溯)',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `datasource_name` varchar(100) DEFAULT NULL COMMENT '数据源名称',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名',
  `table_name` varchar(200) NOT NULL COMMENT '表名',
  `column_name` varchar(200) DEFAULT NULL COMMENT '字段名',
  `partition_expr` varchar(255) DEFAULT NULL COMMENT '分区表达式',
  `ref_datasource_id` bigint DEFAULT NULL COMMENT '参照数据源ID(上下游对账/口径一致性用)',
  `ref_database_name` varchar(100) DEFAULT NULL COMMENT '参照库名',
  `ref_table_name` varchar(200) DEFAULT NULL COMMENT '参照表名',
  `ref_column_name` varchar(200) DEFAULT NULL COMMENT '参照字段名',
  `compare_mode` varchar(8) NOT NULL DEFAULT 'EQ' COMMENT '比较方式(EQ/NE/GT/GTE/LT/LTE)',
  `expected_value` varchar(255) DEFAULT NULL COMMENT '期望值',
  `warn_threshold` varchar(255) DEFAULT NULL COMMENT '橙色(警告)阈值，空表示不设',
  `error_threshold` varchar(255) DEFAULT NULL COMMENT '红色(异常)阈值，空表示不设',
  `dispose_mode` varchar(8) NOT NULL DEFAULT 'ALERT' COMMENT '处置方式(BLOCK=阻断下游 ALERT=告警)',
  `owner_name` varchar(100) DEFAULT NULL COMMENT '规则负责人',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_dq_rule_table` (`database_name`,`table_name`),
  KEY `idx_dq_rule_type` (`rule_type`),
  KEY `idx_dq_rule_template` (`template_id`),
  KEY `idx_dq_rule_datasource` (`datasource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量监控规则表';
CREATE TABLE IF NOT EXISTS `dq_run_instance` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` bigint DEFAULT NULL COMMENT '质检任务ID',
  `task_name` varchar(200) DEFAULT NULL COMMENT '任务名(冗余)',
  `batch_no` varchar(64) NOT NULL COMMENT '批次号',
  `trigger_type` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT '触发方式(SCHEDULE=调度触发 MANUAL=手动触发)',
  `schedule_instance_id` bigint DEFAULT NULL COMMENT '调度触发时对应的调度实例号，用于跳转调度实例详情',
  `target_datasource_id` bigint DEFAULT NULL COMMENT '目标数据源ID',
  `target_database_name` varchar(100) DEFAULT NULL COMMENT '目标库名',
  `target_table_name` varchar(200) DEFAULT NULL COMMENT '目标表名',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT '运行状态(RUNNING/SUCCESS/FAILED)',
  `check_record_count` bigint NOT NULL DEFAULT '0' COMMENT '检查记录数',
  `fail_record_count` bigint NOT NULL DEFAULT '0' COMMENT '未通过记录数',
  `pass_count` int NOT NULL DEFAULT '0' COMMENT '通过规则数',
  `warn_count` int NOT NULL DEFAULT '0' COMMENT '橙色异常规则数',
  `error_count` int NOT NULL DEFAULT '0' COMMENT '红色异常规则数',
  `failed_count` int NOT NULL DEFAULT '0' COMMENT 'SQL 未跑通的规则数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_dq_run_task` (`task_id`),
  KEY `idx_dq_run_batch` (`batch_no`),
  KEY `idx_dq_run_table` (`target_database_name`,`target_table_name`),
  KEY `idx_dq_run_status` (`status`),
  KEY `idx_dq_run_start_time` (`start_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量运行实例表';
CREATE TABLE IF NOT EXISTS `dq_rule_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `run_id` bigint NOT NULL COMMENT '运行实例ID',
  `rule_id` bigint DEFAULT NULL COMMENT '规则ID',
  `rule_name` varchar(200) NOT NULL COMMENT '规则名(结果自包含，规则被删后仍可追溯)',
  `rule_type` varchar(64) NOT NULL COMMENT '规则类型(7类之一)',
  `check_object` varchar(500) DEFAULT NULL COMMENT '校验对象(表.字段 或 上下游两表)',
  `threshold` varchar(255) DEFAULT NULL COMMENT '阈值',
  `actual_value` varchar(500) DEFAULT NULL COMMENT '实际值',
  `status` varchar(16) NOT NULL DEFAULT 'PASS' COMMENT '校验状态(PASS=通过 WARN=橙色异常 ERROR=红色异常 FAILED=校验失败)',
  `severity` varchar(16) DEFAULT NULL COMMENT '严重级别',
  `exec_sql` text COMMENT '实际执行的校验SQL',
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '错误信息',
  `cost_ms` bigint DEFAULT NULL COMMENT '耗时(毫秒)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_dq_result_run` (`run_id`),
  KEY `idx_dq_result_rule` (`rule_id`),
  KEY `idx_dq_result_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据质量规则级校验结果表';


-- ===========================================================================
-- 二、既有表新增列 / 新增索引
--   MySQL 8 没有 ADD COLUMN IF NOT EXISTS 与 CREATE INDEX IF NOT EXISTS，
--   用存储过程查 information_schema 守卫，保证脚本可重复执行。
--   （MariaDB 10.6+ 原生支持 IF NOT EXISTS，本写法在两侧都安全。）
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

DROP PROCEDURE IF EXISTS df_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE df_add_index_if_missing(
  IN p_table VARCHAR(64),
  IN p_index VARCHAR(64),
  IN p_columns VARCHAR(500)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
    PREPARE df_stmt FROM @ddl;
    EXECUTE df_stmt;
    DEALLOCATE PREPARE df_stmt;
  END IF;
END$$
DELIMITER ;

-- 三、既有表新增列
CALL df_add_column_if_missing('table_metadata', 'business_owner',
  'varchar(255) DEFAULT NULL',
  '业务负责人(语义对齐 indicators.business_owner；本表按负责人筛选，需可建索引，故宽度取 255)');
CALL df_add_column_if_missing('table_metadata', 'tech_owner',
  'varchar(255) DEFAULT NULL',
  '技术负责人(同上)');
CALL df_add_column_if_missing('table_metadata', 'topic',
  'varchar(200) DEFAULT NULL',
  '所属主题域(对齐 indicators.topic)');
CALL df_add_column_if_missing('table_metadata', 'visibility',
  'enum(''public'',''internal'',''restricted'') DEFAULT ''internal''',
  '可见性(对齐 indicators.visibility)');
CALL df_add_column_if_missing('table_metadata', 'category_code',
  'varchar(64) DEFAULT NULL',
  '人工覆盖的分类编码；为空表示采用表名规范解析结果');
CALL df_add_column_if_missing('table_metadata', 'asset_level',
  'varchar(16) DEFAULT NULL',
  '资产等级(L1~L5)');
CALL df_add_column_if_missing('column_metadata', 'business_desc',
  'varchar(1000) DEFAULT NULL',
  '业务描述(与 column_comment 的技术注释区分，供资产目录展示)');
CALL df_add_column_if_missing('column_metadata', 'security_level',
  'varchar(16) DEFAULT NULL',
  '字段安全等级');
CALL df_add_column_if_missing('data_quality_check_result', 'rule_type',
  'varchar(64) DEFAULT NULL',
  '规则类型(7类之一)，历史数据为空表示未分类');
CALL df_add_column_if_missing('data_quality_check_result', 'rule_name',
  'varchar(200) DEFAULT NULL',
  '规则名');

-- 既有表新增索引
CALL df_add_index_if_missing('table_metadata', 'idx_table_metadata_owner', '`business_owner`');
CALL df_add_index_if_missing('table_metadata', 'idx_table_metadata_category', '`category_code`');
CALL df_add_index_if_missing('data_quality_check_result', 'idx_dqc_rule_type', '`rule_type`');


DROP PROCEDURE IF EXISTS df_add_column_if_missing;
DROP PROCEDURE IF EXISTS df_add_index_if_missing;

-- ===========================================================================
-- 四、菜单与角色授权
--   说明：init/dataforge-mysql.sql 只建菜单表结构、不含菜单数据，菜单原先仅存在于
--   testdata 脚本中。因此已存在库必须由本脚本补菜单，否则新入口无权限码可取。
--
--   升级兼容（重要）：
--     前端的菜单可见性有一条兜底 —— 当用户的 menuTags 覆盖「全部一级菜单码」时直接
--     视为可见全部，否则退化为按码过滤。一级菜单码集合由前端映射表演进，本次由 9 个
--     增至 11 个。补授规则可保证「存量角色可见菜单数不减少」：
--       * asset_catalog 授予全部未删除角色 → 任何角色至少新增 1 个可见入口；
--       * data_quality 仅授予治理角色（架构师定规则、开发实现、运维处置告警与阻断）。
--     SUPER_ADMIN 不需要显式授权（前端按 isSuperAdmin 直接取全部一级菜单码），
--     这里一并插入以便服务端菜单权限接口返回一致。
-- ===========================================================================

INSERT IGNORE INTO `sys_menu_permission`
  (`parent_id`,`menu_name`,`menu_code`,`path`,`icon`,`sort_order`,`visible`,`status`)
VALUES (0,'数据质量','data_quality','/data_quality','',4,1,1);

INSERT IGNORE INTO `sys_menu_permission`
  (`parent_id`,`menu_name`,`menu_code`,`path`,`icon`,`sort_order`,`visible`,`status`)
VALUES (0,'资产目录','asset_catalog','/asset_catalog','',5,1,1);

-- 资产目录：对全部角色开放（业务需求方与数据分析师最需要找数认数，
-- 只给治理角色会直接违背「业务方提需求」的产品定位）
INSERT IGNORE INTO `sys_role_menu_permission` (`role_id`,`menu_permission_id`)
SELECT r.`id`, m.`id`
FROM `sys_role` r
CROSS JOIN `sys_menu_permission` m
WHERE m.`menu_code` = 'asset_catalog'
  AND r.`deleted` = 0
  AND m.`deleted` = 0;

-- 数据质量：限治理角色
INSERT IGNORE INTO `sys_role_menu_permission` (`role_id`,`menu_permission_id`)
SELECT r.`id`, m.`id`
FROM `sys_role` r
CROSS JOIN `sys_menu_permission` m
WHERE m.`menu_code` = 'data_quality'
  AND r.`deleted` = 0
  AND m.`deleted` = 0
  AND r.`role_code` IN ('SUPER_ADMIN','DATA_ARCHITECT','DATA_DEV_ENGINEER','OPS_ENGINEER');

-- ===========================================================================
-- 五、平台级参考数据（与 init 脚本一致，INSERT IGNORE 保证幂等）
--   7 类内置规则模板：与智能体提示词、前端 types/quality.ts 同源，
--   template_code 即规则类型编码；三处必须同时演进，禁止自造同义词。
--   7 个数仓分层：与提示词「分层规范」一致（ODS/DWD/DWS/ADS/DIM/DQC/TMP）。
-- ===========================================================================

INSERT IGNORE INTO `dq_rule_template` (`template_code`,`template_name`,`rule_type`,`template_kind`,`remark`,`enabled`) VALUES
('PRIMARY_KEY_UNIQUE', '主键唯一性', 'PRIMARY_KEY_UNIQUE', 'builtin', '主键/唯一键重复检测', 1),
('NOT_NULL', '非空', 'NOT_NULL', 'builtin', '关键字段空值与空串检测', 1),
('VALUE_RANGE', '值域', 'VALUE_RANGE', 'builtin', '枚举字典取值与数值区间合法性', 1),
('ROW_COUNT_FLUCTUATION', '行数波动', 'ROW_COUNT_FLUCTUATION', 'builtin', '当日行数相对历史基线的涨跌幅', 1),
('ROW_COUNT_MOM', '行数环比', 'ROW_COUNT_MOM', 'builtin', '当期行数与上期行数的对比', 1),
('UPSTREAM_DOWNSTREAM_RECON', '上下游对账', 'UPSTREAM_DOWNSTREAM_RECON', 'builtin', '上下游同口径记录数/金额一致性', 1),
('METRIC_CONSISTENCY', '口径一致性', 'METRIC_CONSISTENCY', 'builtin', '同一指标在不同表、不同层之间的计算结果一致性', 1);
INSERT IGNORE INTO `asset_category` (`category_code`,`category_name`,`parent_code`,`category_kind`,`sort_order`) VALUES
('ods', 'ODS 贴源层', NULL, 'layer', 1),
('dwd', 'DWD 明细层', NULL, 'layer', 2),
('dws', 'DWS 汇总层', NULL, 'layer', 3),
('ads', 'ADS 应用层', NULL, 'layer', 4),
('dim', 'DIM 维度层', NULL, 'layer', 5),
('dqc', 'DQC 质量层', NULL, 'layer', 6),
('tmp', 'TMP 临时层', NULL, 'layer', 7);
