-- ============================================================
-- dataforge - MariaDB 10.6+ 建表与初始化脚本
-- 说明：MariaDB 与 MySQL 方言高度兼容，本脚本与 dataforge-mysql.sql
--       结构一致；字符集/排序规则统一使用 utf8mb4_unicode_ci。
-- ============================================================
SET NAMES utf8mb4;

-- ==================================================================
-- 资产目录模块（asset_*）：对标 DataWorks 数据资产目录
--   分类不靠人工维护：表名规范 {分层}_{业务域}_{实体描述}_{刷新周期} 可直接
--   解析出分层与业务域，解析失败即「未分类」并自动成为治理项；
--   asset_category 只存人工覆盖与显示名。
-- ==================================================================

-- 资产分类表(分层/业务域，支持人工覆盖)
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
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_category_code` (`category_code`),
  KEY `idx_asset_category_kind` (`category_kind`),
  KEY `idx_asset_category_parent` (`parent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资产分类表(分层/业务域，支持人工覆盖)';

-- 资产收藏表
CREATE TABLE IF NOT EXISTS `asset_bookmark` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `asset_type` varchar(16) NOT NULL COMMENT '资产类型(table/column/indicator)',
  `asset_id` bigint NOT NULL COMMENT '资产ID',
  `table_metadata_id` bigint DEFAULT NULL COMMENT '表元数据ID(便于按表聚合)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_bookmark` (`user_id`,`asset_type`,`asset_id`),
  KEY `idx_asset_bookmark_user` (`user_id`),
  KEY `idx_asset_bookmark_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资产收藏表';

-- 资产访问埋点表(仅追加)：高频写入的轻量表，刻意与 operation_audit_log 分离，
-- 避免浏览行为淹没真正的操作留痕
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
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资产访问埋点表(仅追加，按日汇总)';


-- ==================================================================
-- 资产目录模块（第二批 / 第三批）：标签、使用说明、使用热度
--   使用热度走独立的轻量表按日汇总(T+1)，不回写 operation_audit_log；
--   使用说明复用同一张 asset_doc + 版本历史，避免重复建设 markdown 与版本能力。
-- ==================================================================

-- 资产使用说明表(markdown，版本历史在 asset_doc_history)
CREATE TABLE IF NOT EXISTS `asset_doc` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_metadata_id` bigint NOT NULL COMMENT '表元数据ID',
  `content` text DEFAULT NULL COMMENT '使用说明(markdown)',
  `version` int NOT NULL DEFAULT 1 COMMENT '当前版本号',
  `update_by` varchar(100) DEFAULT NULL COMMENT '最后修改人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_doc_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产使用说明表(markdown，版本历史在 asset_doc_history)';

-- 资产使用说明版本历史表
CREATE TABLE IF NOT EXISTS `asset_doc_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_metadata_id` bigint NOT NULL COMMENT '表元数据ID',
  `version` int NOT NULL COMMENT '版本号',
  `content` text DEFAULT NULL COMMENT '该版本的使用说明(markdown)',
  `update_by` varchar(100) DEFAULT NULL COMMENT '修改人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_asset_doc_history_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产使用说明版本历史表';

-- 资产标签定义表
CREATE TABLE IF NOT EXISTS `asset_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tag_code` varchar(64) NOT NULL COMMENT '标签编码',
  `tag_name` varchar(100) NOT NULL COMMENT '标签名称',
  `color` varchar(32) DEFAULT NULL COMMENT '标签颜色(前端色值)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_tag_code` (`tag_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产标签定义表';

-- 资产标签关联表(支持表/字段/指标)
CREATE TABLE IF NOT EXISTS `asset_tag_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tag_id` bigint NOT NULL COMMENT '标签ID',
  `asset_type` varchar(16) NOT NULL COMMENT '资产类型(table/column/indicator)',
  `asset_id` bigint NOT NULL COMMENT '资产ID',
  `table_metadata_id` bigint DEFAULT NULL COMMENT '表元数据ID(便于按表聚合)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_tag_rel` (`tag_id`,`asset_type`,`asset_id`),
  KEY `idx_asset_tag_rel_asset` (`asset_type`,`asset_id`),
  KEY `idx_asset_tag_rel_table` (`table_metadata_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产标签关联表(支持表/字段/指标)';

-- 资产使用热度日汇总表(T+1，由 asset_access_log 汇总)
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
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_usage_daily` (`asset_type`,`asset_id`,`stat_date`),
  KEY `idx_asset_usage_table` (`table_metadata_id`),
  KEY `idx_asset_usage_date` (`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产使用热度日汇总表(T+1，由 asset_access_log 汇总)';

-- 资产目录日快照表(T+1，一行=一张表×一个统计日)
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
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_catalog_daily` (`stat_date`,`table_metadata_id`),
  KEY `idx_asset_catalog_daily_layer` (`stat_date`,`layer_code`),
  KEY `idx_asset_catalog_daily_domain` (`stat_date`,`domain_code`),
  KEY `idx_asset_catalog_daily_ds` (`stat_date`,`datasource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='资产目录日快照表(T+1，一行=一张表×一个统计日)';

-- 调度任务运行日汇总表(T+1，由 DolphinScheduler 采集)
CREATE TABLE IF NOT EXISTS `scheduler_task_run_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日(任务实际开始日)',
  `project_code` varchar(100) NOT NULL DEFAULT '' COMMENT '调度项目编码',
  `project_name` varchar(200) DEFAULT NULL COMMENT '调度项目名',
  `workflow_code` bigint DEFAULT NULL COMMENT '工作流编码',
  `workflow_name` varchar(200) NOT NULL DEFAULT '' COMMENT '工作流名',
  `node_name` varchar(200) NOT NULL DEFAULT '' COMMENT '节点(任务)名',
  `task_type` varchar(40) NOT NULL DEFAULT '' COMMENT '任务类型(SPARK/HIVE/FLINK等)',
  `task_run_count` int NOT NULL DEFAULT 0 COMMENT '任务实例数(窗口内执行次数)',
  `delayed_count` int NOT NULL DEFAULT 0 COMMENT '延迟实例数(实际开始晚于计划开始)',
  `success_count` int NOT NULL DEFAULT 0 COMMENT '成功实例数',
  `failed_count` int NOT NULL DEFAULT 0 COMMENT '失败实例数',
  `total_duration_seconds` bigint NOT NULL DEFAULT 0 COMMENT '累计运行时长(秒)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduler_task_run_daily` (`stat_date`,`project_code`,`workflow_name`,`node_name`,`task_type`),
  KEY `idx_scheduler_task_run_date` (`stat_date`),
  KEY `idx_scheduler_task_run_project` (`stat_date`,`project_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='调度任务运行日汇总表(T+1，由 DolphinScheduler 采集)';

-- 表字段元数据表
CREATE TABLE IF NOT EXISTS `column_metadata` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_metadata_id` bigint NOT NULL COMMENT '表元数据ID',
  `storage_engine` varchar(20) DEFAULT NULL COMMENT '存储引擎类型',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名称',
  `table_name` varchar(200) DEFAULT NULL COMMENT '表名称',
  `column_name` varchar(200) NOT NULL COMMENT '字段名',
  `column_type` varchar(100) NOT NULL COMMENT '字段类型',
  `column_comment` varchar(1000) DEFAULT NULL COMMENT '字段注释/业务说明',
  `is_partition` tinyint NOT NULL DEFAULT '0' COMMENT '是否分区字段',
  `is_primary_key` tinyint NOT NULL DEFAULT '0' COMMENT '是否主键',
  `ordinal_position` int NOT NULL DEFAULT '0' COMMENT '字段顺序',
  `nullable` tinyint NOT NULL DEFAULT '1' COMMENT '是否可空',
  `default_value` varchar(500) DEFAULT NULL COMMENT '默认值',
  `business_desc` varchar(1000) DEFAULT NULL COMMENT '业务描述(与 column_comment 的技术注释区分，供资产目录展示)',
  `security_level` varchar(16) DEFAULT NULL COMMENT '字段安全等级',
  `hot_score` tinyint NOT NULL DEFAULT '0' COMMENT '字段热度星级(0~5，由使用记录日汇总回写)',
  `sync_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_column_storage` (`storage_engine`,`database_name`,`table_name`,`column_name`),
  KEY `idx_table_metadata_id` (`table_metadata_id`),
  KEY `idx_column_name` (`column_name`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='表字段元数据表';

-- 数据质量校验结果表
CREATE TABLE IF NOT EXISTS `data_quality_check_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `datasource_name` varchar(100) NOT NULL COMMENT '数据源名称',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名',
  `table_name` varchar(200) NOT NULL COMMENT '表名',
  `check_item` varchar(200) NOT NULL COMMENT '检查项(如:空值率/唯一性/数据范围/主键缺失)',
  `check_time` datetime NOT NULL COMMENT '检查时间',
  `check_record_count` bigint NOT NULL DEFAULT '0' COMMENT '检查记录数',
  `fail_record_count` bigint NOT NULL DEFAULT '0' COMMENT '未通过记录数',
  `fail_detail` text COMMENT '未通过明细(JSON)',
  `rule_type` varchar(64) DEFAULT NULL COMMENT '规则类型(7类之一)，历史数据为空表示未分类',
  `rule_name` varchar(200) DEFAULT NULL COMMENT '规则名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dqc_datasource_id` (`datasource_id`),
  KEY `idx_dqc_table` (`database_name`,`table_name`),
  KEY `idx_dqc_check_time` (`check_time`),
  KEY `idx_dqc_rule_type` (`rule_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量校验结果表';

-- 数据源配置表
CREATE TABLE IF NOT EXISTS `datasource_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_name` varchar(100) NOT NULL COMMENT '数据源名称',
  `datasource_type` varchar(50) NOT NULL COMMENT '类型',
  `connection_type` varchar(20) DEFAULT 'jdbc' COMMENT '连接方式',
  `storage_engine` varchar(20) DEFAULT NULL COMMENT '存储引擎类型',
  `url` varchar(500) NOT NULL COMMENT '连接URL',
  `database_name` varchar(500) DEFAULT NULL COMMENT '数据库名称(JSON数组)',
  `username` varchar(100) DEFAULT NULL COMMENT '用户名',
  `password` varchar(255) DEFAULT NULL COMMENT '密码(AES加密存储)',
  `extra_params` text COMMENT '额外参数(JSON格式)',
  `connection_status` tinyint NOT NULL DEFAULT '0' COMMENT '连接状态',
  `last_test_time` datetime DEFAULT NULL COMMENT '最后测试时间',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_datasource_type` (`datasource_type`),
  KEY `idx_datasource_storage_engine` (`storage_engine`),
  KEY `idx_connection_type` (`connection_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源配置表';

-- DDL执行日志表
CREATE TABLE IF NOT EXISTS `ddl_execution_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名',
  `sql_content` text NOT NULL COMMENT 'SQL内容',
  `sql_type` varchar(20) NOT NULL COMMENT 'SQL类型',
  `object_type` varchar(20) DEFAULT 'TABLE' COMMENT '对象类型',
  `object_name` varchar(200) DEFAULT NULL COMMENT '对象名称',
  `status` varchar(20) NOT NULL DEFAULT 'submitted' COMMENT '状态',
  `affected_rows` int DEFAULT NULL COMMENT '影响行数',
  `error_message` text COMMENT '错误信息',
  `execution_time_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `metadata_updated` tinyint NOT NULL DEFAULT '0' COMMENT '是否已更新元数据',
  `execute_by` bigint DEFAULT NULL COMMENT '执行人',
  `execute_by_name` varchar(50) DEFAULT NULL COMMENT '执行人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_datasource_id` (`datasource_id`),
  KEY `idx_sql_type` (`sql_type`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DDL执行日志表';

-- JAR包依赖配置表
CREATE TABLE IF NOT EXISTS `dev_jar_dependency` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `jar_name` varchar(200) NOT NULL COMMENT 'JAR包名称',
  `group_id` varchar(200) DEFAULT NULL COMMENT 'Maven Group ID',
  `artifact_id` varchar(200) DEFAULT NULL COMMENT 'Maven Artifact ID',
  `version` varchar(50) DEFAULT NULL COMMENT '版本号',
  `scope` varchar(20) NOT NULL DEFAULT 'compile' COMMENT '作用域',
  `source` varchar(20) NOT NULL DEFAULT 'system' COMMENT '来源',
  `jar_path` varchar(500) DEFAULT NULL COMMENT 'JAR包路径',
  `repository_url` varchar(500) DEFAULT NULL COMMENT 'Maven仓库地址',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小(字节)',
  `checksum` varchar(64) DEFAULT NULL COMMENT '文件校验和',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `category` varchar(100) DEFAULT NULL COMMENT '功能分类',
  `program_type` varchar(20) NOT NULL DEFAULT 'flink' COMMENT '程序类型',
  `auto_choose` tinyint NOT NULL DEFAULT '0' COMMENT '是否自动选择',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_source` (`source`),
  KEY `idx_scope` (`scope`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='JAR包依赖配置表';

-- 程序运行参数表
CREATE TABLE IF NOT EXISTS `dev_program_params` (
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `file_path` varchar(500) NOT NULL COMMENT '文件路径',
  `engine` varchar(50) DEFAULT NULL COMMENT '执行引擎类型',
  `datasources` text COMMENT '数据源列表(JSON数组)',
  `jar_dependency_catalogs` text COMMENT 'JAR依赖目录列表(JSON数组)',
  `run_params` text COMMENT '运行参数(JSON对象)',
  `compute_resources` text COMMENT '计算资源配置(JSON对象)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`project_id`,`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='程序运行参数表';

-- 开发项目管理表
CREATE TABLE IF NOT EXISTS `dev_project` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `project_name` varchar(200) NOT NULL COMMENT '项目名称',
  `project_code` varchar(100) NOT NULL COMMENT '项目编码(英文唯一标识)',
  `description` text COMMENT '项目描述',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人ID',
  `owner_name` varchar(50) DEFAULT NULL COMMENT '负责人姓名',
  `default_datasource_id` bigint DEFAULT NULL COMMENT '默认数据源ID',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `script_count` int NOT NULL DEFAULT '0' COMMENT '脚本数量(冗余字段)',
  `scheduled_count` int NOT NULL DEFAULT '0' COMMENT '已调度任务数(冗余字段)',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_code` (`project_code`),
  KEY `idx_owner_id` (`owner_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='开发项目管理表';

-- 脚本执行历史表
CREATE TABLE IF NOT EXISTS `dev_script_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `script_id` bigint NOT NULL COMMENT '脚本ID',
  `execution_id` varchar(64) NOT NULL COMMENT '执行ID(全局唯一)',
  `script_type` varchar(50) NOT NULL COMMENT '脚本类型',
  `execute_mode` varchar(20) NOT NULL DEFAULT 'debug' COMMENT '执行模式',
  `engine_id` bigint DEFAULT NULL COMMENT '执行引擎ID',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `status` varchar(20) NOT NULL DEFAULT 'submitted' COMMENT '状态',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `input_params` longtext COMMENT '输入参数(JSON)',
  `output_log` longtext COMMENT '输出日志',
  `error_message` text COMMENT '错误信息',
  `affected_rows` int DEFAULT NULL COMMENT '影响行数',
  `result_data` longtext COMMENT '结果数据(JSON)',
  `submit_by` bigint DEFAULT NULL COMMENT '提交人ID',
  `submit_by_name` varchar(50) DEFAULT NULL COMMENT '提交人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_script_id` (`script_id`),
  KEY `idx_execution_id` (`execution_id`),
  KEY `idx_status` (`status`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_execute_mode` (`execute_mode`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='脚本执行历史表';

-- ==================================================================
-- 数据质量模块（dq_*）：对标 DataWorks 数据质量
--   契约早已存在 —— 智能体提示词里已约定 7 类规则类型（主键唯一性/非空/值域/
--   行数波动/行数环比/上下游对账/口径一致性）与 DQC 质量层，但平台侧原先只有
--   结果表 data_quality_check_result（「检查项」是自由文本），规则定义、阈值与
--   运行记录无处存放。本模块补齐「规则 + 运行」两段。
--   ⚠ 规则类型是契约：新增/改名必须同时改前端 types/quality.ts 与提示词，禁止自造同义词。
-- ==================================================================

-- 规则模板表：内置模板即 7 类规则类型(template_code 与类型编码同名)；
-- 自定义模板仍需归属 7 类之一(差异只在 SQL 模板，不在类型)，否则覆盖率统计失去口径
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
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dq_template_code` (`template_code`),
  KEY `idx_dq_template_rule_type` (`rule_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量规则模板表';

-- 监控规则表：模板的具体应用，含校验对象、阈值与处置方式。
--   dispose_mode=BLOCK 即「阻断下游」，落地方式是让质检任务自身跑失败，
--   DolphinScheduler 的下游节点便不会触发（不依赖任何「反向置为失败」接口）
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
  `params` text COMMENT '自定义模板占位符取值(JSON 对象)；内置模板按规则类型使用固定参数名',
  `owner_name` varchar(100) DEFAULT NULL COMMENT '规则负责人',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dq_rule_table` (`database_name`,`table_name`),
  KEY `idx_dq_rule_type` (`rule_type`),
  KEY `idx_dq_rule_template` (`template_id`),
  KEY `idx_dq_rule_datasource` (`datasource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量监控规则表';

-- 规则级校验结果表（结果留痕）：对应提示词「校验输出须可定位：规则名/校验对象/阈值/实际值/是否通过」
--   每条结果自包含（批次、计数、实际执行的 SQL 都在），不依赖任何外部实例即可追溯；
--   它是「行数波动 / 行数环比」试跑基线与资产目录质量摘要的唯一数据源。
--   ⚠ 历史曾有的 run_id 列已随运行实例表（dq_run_instance）一并移除。
CREATE TABLE IF NOT EXISTS `dq_rule_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `batch_no` varchar(64) DEFAULT NULL COMMENT '业务批次(如 20260917)',
  `rule_id` bigint DEFAULT NULL COMMENT '规则ID',
  `rule_name` varchar(200) NOT NULL COMMENT '规则名(结果自包含，规则被删后仍可追溯)',
  `rule_type` varchar(64) NOT NULL COMMENT '规则类型(7类之一)',
  `check_object` varchar(500) DEFAULT NULL COMMENT '校验对象(表.字段 或 上下游两表)',
  `threshold` varchar(255) DEFAULT NULL COMMENT '阈值',
  `actual_value` varchar(500) DEFAULT NULL COMMENT '实际值',
  `metric_value` varchar(64) DEFAULT NULL COMMENT '原始度量值(行数类=当期行数 跨表比对=主表值,对照值)；仅存百分比无法回推历史基线',
  `status` varchar(16) NOT NULL DEFAULT 'PASS' COMMENT '校验状态(PASS=通过 WARN=橙色异常 ERROR=红色异常 FAILED=校验失败)',
  `severity` varchar(16) DEFAULT NULL COMMENT '严重级别',
  `check_record_count` bigint NOT NULL DEFAULT '0' COMMENT '检查记录数(回传侧口径)',
  `fail_record_count` bigint NOT NULL DEFAULT '0' COMMENT '未通过记录数(回传侧口径)',
  `exec_sql` text COMMENT '实际执行的校验SQL',
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '错误信息',
  `cost_ms` bigint DEFAULT NULL COMMENT '耗时(毫秒)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dq_result_rule` (`rule_id`),
  KEY `idx_dq_result_status` (`status`),
  KEY `idx_dq_result_batch` (`batch_no`),
  KEY `idx_dq_result_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量规则级校验结果表';


-- ==================================================================
-- 数据质量模块：质检任务 / 运行实例 / 告警订阅三张链路已下线（2026-10-09）
--   原先的 dq_run_instance、dq_alert_log、dq_alert_subscription、
--   dq_task、dq_task_rule_rel 五张表连同其前后端代码一并移除；
--   规则级结果保留在 dq_rule_result 中作为「结果留痕」。
-- ==================================================================

-- 指标定义主表
CREATE TABLE IF NOT EXISTS `indicators` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `project_code` varchar(200) NOT NULL COMMENT '所属项目编码',
  `topic` varchar(200) NOT NULL COMMENT '所属主题域',
  `name` varchar(200) NOT NULL COMMENT '指标名称',
  `code` varchar(100) NOT NULL COMMENT '指标编码(唯一标识)',
  `business_definition` text COMMENT '业务定义、计算方法',
  `calc_expr` varchar(1024) NOT NULL COMMENT '计算表达式',
  `default_filter` text COMMENT '筛选条件',
  `alert_config` longtext COMMENT '告警配置(JSON)',
  `business_owner` varchar(1024) DEFAULT NULL COMMENT '业务负责人',
  `tech_owner` varchar(1024) DEFAULT NULL COMMENT '技术负责人',
  `refresh_interval` int DEFAULT NULL COMMENT '数据更新间隔',
  `refresh_unit` varchar(10) DEFAULT NULL COMMENT '数据更新时间单位',
  `refresh_desc` varchar(10) DEFAULT NULL COMMENT '数据更新时间描述',
  `visibility` enum('public','internal','restricted') DEFAULT 'internal' COMMENT '可见性',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标定义主表';

-- 指标相关字段
CREATE TABLE IF NOT EXISTS `indicators_fields` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `indicators_id` int unsigned NOT NULL COMMENT '关联指标ID',
  `datasource_name` varchar(200) NOT NULL COMMENT '所属数据源',
  `db_name` varchar(200) NOT NULL COMMENT '所属库名',
  `tbl_name` varchar(200) NOT NULL COMMENT '所属表名',
  `field_name` varchar(200) NOT NULL COMMENT '字段名',
  `field_type` varchar(200) NOT NULL COMMENT '字段类型',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标相关字段';

-- 知识文档表
CREATE TABLE IF NOT EXISTS `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(200) NOT NULL COMMENT '文档标题',
  `doc_type` varchar(30) NOT NULL COMMENT '文档类型',
  `category` varchar(100) DEFAULT NULL COMMENT '分类',
  `content` longtext COMMENT '文档内容',
  `author_name` varchar(50) DEFAULT NULL COMMENT '作者姓名',
  `author_id` bigint DEFAULT NULL COMMENT '作者ID',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
  `sync_to_rag` tinyint NOT NULL DEFAULT '0' COMMENT '是否同步至RAG',
  `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量',
  `tags` varchar(500) DEFAULT NULL COMMENT '标签',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_doc_type` (`doc_type`),
  KEY `idx_status` (`status`),
  KEY `idx_author_id` (`author_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档表';

-- 审计日志表（按日 RANGE 分区，p_max 兜底）
CREATE TABLE IF NOT EXISTS `operation_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '操作用户ID',
  `username` varchar(50) DEFAULT NULL COMMENT '操作用户名',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `tenant_name` varchar(100) DEFAULT NULL COMMENT '租户名称',
  `module` varchar(50) NOT NULL COMMENT '模块名称',
  `operation` varchar(100) NOT NULL COMMENT '操作描述',
  `operation_type` varchar(20) DEFAULT NULL COMMENT '操作类型',
  `target_type` varchar(50) DEFAULT NULL COMMENT '目标对象类型',
  `target_id` bigint DEFAULT NULL COMMENT '目标对象ID',
  `target_name` varchar(200) DEFAULT NULL COMMENT '目标对象名称',
  `request_method` varchar(10) DEFAULT NULL COMMENT '请求方法',
  `request_url` varchar(500) DEFAULT NULL COMMENT '请求URL',
  `request_params` text COMMENT '请求参数(脱敏)',
  `response_body` text COMMENT '响应内容(截断)',
  `response_status` int DEFAULT NULL COMMENT '响应状态码',
  `error_message` text COMMENT '错误信息',
  `exception_class` varchar(200) DEFAULT NULL COMMENT '异常类名',
  `ip_address` varchar(50) DEFAULT NULL COMMENT '客户端IP',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `duration_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `is_sensitive` tinyint(1) DEFAULT '0' COMMENT '是否敏感操作',
  `is_ai_request` tinyint DEFAULT '0' COMMENT '是否AI发起的请求',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`,`operation_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_module` (`module`),
  KEY `idx_operation_type` (`operation_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表'
PARTITION BY RANGE (to_days(`operation_time`))
(PARTITION p_max VALUES LESS THAN MAXVALUE ENGINE = InnoDB);

-- 存储配置表
CREATE TABLE IF NOT EXISTS `storage_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `storage_name` varchar(100) NOT NULL COMMENT '存储名称',
  `storage_type` varchar(20) NOT NULL COMMENT '类型',
  `endpoint` varchar(255) DEFAULT NULL COMMENT '访问端点',
  `bucket_name` varchar(100) DEFAULT NULL COMMENT '桶名称',
  `access_key` varchar(255) DEFAULT NULL COMMENT 'Access Key',
  `secret_key` varchar(255) DEFAULT NULL COMMENT 'Secret Key',
  `root_path` varchar(255) NOT NULL DEFAULT '/' COMMENT '根路径',
  `local_mount_path` varchar(500) DEFAULT NULL COMMENT '本地挂载路径',
  `extra_params` text COMMENT '额外配置(JSON)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存储配置表';

-- 文件路径权限表
CREATE TABLE IF NOT EXISTS `sys_file_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `storage_config_id` bigint NOT NULL COMMENT '存储配置ID',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称',
  `file_path` varchar(1000) NOT NULL COMMENT '文件/目录路径',
  `permission_type` tinyint NOT NULL DEFAULT '1' COMMENT '权限类型',
  `recursive` tinyint NOT NULL DEFAULT '0' COMMENT '是否递归子目录',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_storage_config_id` (`storage_config_id`),
  KEY `idx_file_path` (`file_path`(255))
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件路径权限表';

-- 菜单/页面权限表
CREATE TABLE IF NOT EXISTS `sys_menu_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父级ID(0表示顶级)',
  `menu_name` varchar(100) NOT NULL COMMENT '菜单名称',
  `menu_code` varchar(100) NOT NULL COMMENT '菜单编码(唯一标识)',
  `path` varchar(255) DEFAULT NULL COMMENT '路由路径',
  `component` varchar(255) DEFAULT NULL COMMENT '组件路径',
  `icon` varchar(100) DEFAULT NULL COMMENT '图标',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `visible` tinyint NOT NULL DEFAULT '1' COMMENT '是否可见',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_code` (`menu_code`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单/页面权限表';

-- 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_name` varchar(50) NOT NULL COMMENT '角色名称',
  `role_code` varchar(50) NOT NULL COMMENT '角色编码(唯一标识)',
  `role_type` varchar(30) NOT NULL DEFAULT 'VIEWER' COMMENT '角色类型',
  `description` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- 角色-文件权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_file_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `file_permission_id` bigint NOT NULL COMMENT '文件权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_file` (`role_id`,`file_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-文件权限关联表';

-- 角色-菜单权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_menu_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_permission_id` bigint NOT NULL COMMENT '菜单权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-菜单权限关联表';

-- 角色项目权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_project_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `permission_type` int NOT NULL COMMENT '权限类型：1-可读 2-可编辑 3-可删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_project` (`role_id`,`project_id`),
  KEY `idx_role_id` (`role_id`),
  KEY `idx_project_id` (`project_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色项目权限关联表';

-- 角色-库表权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_table_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `table_permission_id` bigint NOT NULL COMMENT '库表权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_table` (`role_id`,`table_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-库表权限关联表';

-- 库表CRUD权限表
CREATE TABLE IF NOT EXISTS `sys_table_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称',
  `datasource_name` varchar(255) DEFAULT NULL COMMENT '数据源名称',
  `database_name` varchar(255) DEFAULT NULL COMMENT '数据库名称',
  `table_names` text COMMENT '表名列表(*表示全部)',
  `permission_scope` tinyint NOT NULL DEFAULT '1' COMMENT '权限范围',
  `can_select` tinyint NOT NULL DEFAULT '1' COMMENT '查询权限',
  `can_insert` tinyint NOT NULL DEFAULT '0' COMMENT '插入权限',
  `can_update` tinyint NOT NULL DEFAULT '0' COMMENT '更新权限',
  `can_delete` tinyint NOT NULL DEFAULT '0' COMMENT '删除权限',
  `can_export` tinyint NOT NULL DEFAULT '0' COMMENT '导出权限',
  `row_filter_sql` text COMMENT '行级过滤SQL',
  `data_masking_rule` varchar(500) DEFAULT NULL COMMENT '数据脱敏规则(JSON)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_permission_scope` (`permission_scope`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库表CRUD权限表';

-- 库表权限-列级白名单（指定列）
CREATE TABLE IF NOT EXISTS `sys_table_permission_column` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_permission_id` bigint NOT NULL COMMENT '表权限ID',
  `table_name` varchar(255) NOT NULL COMMENT '表名',
  `column_name` varchar(255) NOT NULL COMMENT '允许可见的列名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_tpc_perm` (`table_permission_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库表权限-指定列白名单';

-- 库表权限-行级过滤（指定行）
CREATE TABLE IF NOT EXISTS `sys_table_permission_row` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `table_permission_id` bigint NOT NULL COMMENT '表权限ID',
  `table_name` varchar(255) NOT NULL COMMENT '表名',
  `row_filter_sql` text COMMENT '行过滤SQL',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_tpr_perm` (`table_permission_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库表权限-行级过滤SQL';

-- 租户表
CREATE TABLE IF NOT EXISTS `sys_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_name` varchar(100) NOT NULL COMMENT '租户名称',
  `tenant_code` varchar(50) NOT NULL COMMENT '租户编码',
  `contact_name` varchar(50) DEFAULT NULL COMMENT '联系人姓名',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `contact_email` varchar(100) DEFAULT NULL COMMENT '联系邮箱',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` int DEFAULT '1' COMMENT '状态',
  `owner_id` bigint DEFAULT NULL COMMENT '所有者用户ID',
  `password` varchar(200) DEFAULT NULL COMMENT '租户密码(AES加密)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户表';

-- 租户角色关联表
CREATE TABLE IF NOT EXISTS `sys_tenant_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_role` (`tenant_id`,`role_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户角色关联表';

-- 租户用户关联表
CREATE TABLE IF NOT EXISTS `sys_tenant_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_id` (`tenant_id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户用户关联表';

-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(255) NOT NULL COMMENT '密码(MD5加密存储)',
  `real_name` varchar(50) DEFAULT NULL COMMENT '真实姓名',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态',
  `is_admin` tinyint(1) DEFAULT '0' COMMENT '是否超级管理员',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 库表元数据表
CREATE TABLE IF NOT EXISTS `table_metadata` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `storage_engine` varchar(20) DEFAULT NULL COMMENT '存储引擎类型',
  `catalog_name` varchar(100) DEFAULT NULL COMMENT '目录名',
  `database_name` varchar(100) NOT NULL COMMENT '数据库名',
  `table_name` varchar(200) NOT NULL COMMENT '表名',
  `table_type` varchar(50) DEFAULT NULL COMMENT '表类型',
  `table_comment` varchar(1000) DEFAULT NULL COMMENT '表注释/业务描述',
  `table_create_time` datetime DEFAULT NULL COMMENT '建表时间',
  `last_modify_time` datetime DEFAULT NULL COMMENT '最后修改时间',
  `partition_columns` longtext COMMENT '分区字段(JSON数组)',
  `storage_format` varchar(200) DEFAULT NULL COMMENT '存储格式',
  `compression` varchar(50) DEFAULT NULL COMMENT '压缩算法',
  `location` varchar(500) DEFAULT NULL COMMENT '存储位置',
  `row_count` bigint DEFAULT NULL COMMENT '行数估算',
  `size_bytes` bigint DEFAULT NULL COMMENT '大小(字节)',
  `hot_days` int NOT NULL DEFAULT '90' COMMENT '热数据保留天数',
  `cold_days` int NOT NULL DEFAULT '180' COMMENT '冷数据保留天数',
  `keep_days` int NOT NULL DEFAULT '720' COMMENT '数据总保留天数',
  `sync_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '元数据同步时间',
  `table_fingerprint` varchar(64) DEFAULT NULL COMMENT '表结构指纹',
  `columns_fingerprint` varchar(64) DEFAULT NULL COMMENT '字段结构指纹',
  `business_owner` varchar(255) DEFAULT NULL COMMENT '业务负责人(语义对齐 indicators.business_owner；本表按负责人筛选，需可建索引，故宽度取 255)',
  `tech_owner` varchar(255) DEFAULT NULL COMMENT '技术负责人(同上)',
  `topic` varchar(200) DEFAULT NULL COMMENT '所属主题域(对齐 indicators.topic)',
  `visibility` enum('public','internal','restricted') DEFAULT 'internal' COMMENT '可见性(对齐 indicators.visibility)',
  `category_code` varchar(64) DEFAULT NULL COMMENT '人工覆盖的分类编码；为空表示采用表名规范解析结果',
  `asset_level` varchar(16) DEFAULT NULL COMMENT '资产等级(L1~L5)',
  `browse_cnt_30d` int NOT NULL DEFAULT '0' COMMENT '近30日浏览数(由 asset_access_log 日汇总回写)',
  `read_cnt_30d` int NOT NULL DEFAULT '0' COMMENT '近30日读取数(由 asset_access_log 日汇总回写)',
  `bookmark_cnt` int NOT NULL DEFAULT '0' COMMENT '收藏数(由 asset_bookmark 回写)',
  `last_access_time` datetime DEFAULT NULL COMMENT '最近访问时间(埋点回写，用于热度排序)',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table_storage` (`storage_engine`,`database_name`,`table_name`),
  KEY `idx_database_name` (`database_name`),
  KEY `idx_table_name` (`table_name`),
  KEY `idx_table_metadata_owner` (`business_owner`),
  KEY `idx_table_metadata_category` (`category_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库表元数据表';

-- ------------------------------------------------------------------
-- ------------------------------------------------------------------
-- 2. 基础初始化数据（仅 admin 用户与 SUPER_ADMIN 角色）
--    测试数据（test 用户/角色/菜单/租户/数据源/授权）见 testdata/
-- ------------------------------------------------------------------

INSERT INTO `sys_user` (`id`,`username`,`password`,`real_name`,`email`,`phone`,`status`,`is_admin`,`last_login_time`,`create_time`,`update_time`) VALUES
(1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员','admin@example.com','13800000000',1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05');

INSERT INTO `sys_role` (`id`,`role_name`,`role_code`,`role_type`,`description`,`status`,`create_time`,`update_time`) VALUES
(1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03');

-- ------------------------------------------------------------------
-- 平台级参考数据（非测试数据，任何部署都需要，故放在本脚本而非 testdata）
--   1) 数据质量 7 类内置规则模板：与智能体提示词、前端 types/quality.ts 同源，
--      template_code 即规则类型编码；三处必须同时演进，禁止自造同义词。
--   2) 资产目录数仓分层：与提示词「分层规范」一致（ODS/DWD/DWS/ADS/DIM/DQC/TMP）。
--      业务域不预置，由表名规范 {分层}_{业务域}_{实体描述}_{刷新周期} 解析产生，
--      解析失败的资产自动进入「未分类」并成为治理项。
-- ------------------------------------------------------------------

INSERT INTO `dq_rule_template` (`template_code`,`template_name`,`rule_type`,`template_kind`,`remark`,`enabled`) VALUES
('PRIMARY_KEY_UNIQUE', '主键唯一性', 'PRIMARY_KEY_UNIQUE', 'builtin', '主键/唯一键重复检测', 1),
('NOT_NULL', '非空', 'NOT_NULL', 'builtin', '关键字段空值与空串检测', 1),
('VALUE_RANGE', '值域', 'VALUE_RANGE', 'builtin', '枚举字典取值与数值区间合法性', 1),
('ROW_COUNT_FLUCTUATION', '行数波动', 'ROW_COUNT_FLUCTUATION', 'builtin', '当日行数相对历史基线的涨跌幅', 1),
('ROW_COUNT_MOM', '行数环比', 'ROW_COUNT_MOM', 'builtin', '当期行数与上期行数的对比', 1),
('UPSTREAM_DOWNSTREAM_RECON', '上下游对账', 'UPSTREAM_DOWNSTREAM_RECON', 'builtin', '上下游同口径记录数/金额一致性', 1),
('METRIC_CONSISTENCY', '口径一致性', 'METRIC_CONSISTENCY', 'builtin', '同一指标在不同表、不同层之间的计算结果一致性', 1);

INSERT INTO `asset_category` (`category_code`,`category_name`,`parent_code`,`category_kind`,`sort_order`) VALUES
('ods', 'ODS 贴源层', NULL, 'layer', 1),
('dwd', 'DWD 明细层', NULL, 'layer', 2),
('dws', 'DWS 汇总层', NULL, 'layer', 3),
('ads', 'ADS 应用层', NULL, 'layer', 4),
('dim', 'DIM 维度层', NULL, 'layer', 5),
('dqc', 'DQC 质量层', NULL, 'layer', 6),
('tmp', 'TMP 临时层', NULL, 'layer', 7);

-- 默认资产标签（平台级参考数据；标签本身仍由人工施加到具体资产上）
INSERT INTO `asset_tag`
  (`tag_code`,`tag_name`,`color`,`remark`,`enabled`)
VALUES
  ('CORE','核心资产','#c62828','对业务有直接支撑作用的核心表',1),
  ('HOT','高频使用','#1565c0','近30日读取热度高',1),
  ('PENDING_GOVERN','待治理','#f9a825','命名不合规、未标注或无负责人等',1),
  ('SENSITIVE','敏感数据','#6a1b9a','含个人信息或需脱敏的字段',1);
