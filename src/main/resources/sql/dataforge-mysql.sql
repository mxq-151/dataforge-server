-- ============================================================
-- dataforge - MySQL 8.0+ 建表与初始化脚本
-- 适用数据库: MySQL 8.0+（含 operation_audit_log 按日 RANGE 分区）
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------------
-- 1. 建表（按字母序）
-- ------------------------------------------------------------------

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
  `sync_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_column_storage` (`storage_engine`,`database_name`,`table_name`,`column_name`),
  KEY `idx_table_metadata_id` (`table_metadata_id`),
  KEY `idx_column_name` (`column_name`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='表字段元数据表';

-- 数据源配置表
CREATE TABLE IF NOT EXISTS `datasource_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_name` varchar(100) NOT NULL COMMENT '数据源名称',
  `datasource_type` varchar(50) NOT NULL COMMENT '类型(kyuubi/doris/clickhouse/es/starrock/mysql/hdfs/s3/juicefs)',
  `connection_type` varchar(20) DEFAULT 'jdbc' COMMENT '连接方式(jdbc/http/arrow)',
  `storage_engine` varchar(20) DEFAULT NULL COMMENT '存储引擎类型',
  `url` varchar(500) NOT NULL COMMENT '连接URL',
  `database_name` varchar(500) DEFAULT NULL COMMENT '数据库名称(JSON数组)',
  `username` varchar(100) DEFAULT NULL COMMENT '用户名',
  `password` varchar(255) DEFAULT NULL COMMENT '密码(AES加密存储)',
  `extra_params` text COMMENT '额外参数(JSON格式)',
  `connection_status` tinyint NOT NULL DEFAULT '0' COMMENT '连接状态(0-未测试 1-正常 2-异常)',
  `last_test_time` datetime DEFAULT NULL COMMENT '最后测试时间',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_datasource_type` (`datasource_type`),
  KEY `idx_datasource_storage_engine` (`storage_engine`),
  KEY `idx_connection_type` (`connection_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='数据源配置表';

-- DDL执行日志表
CREATE TABLE IF NOT EXISTS `ddl_execution_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名',
  `sql_content` text NOT NULL COMMENT 'SQL内容',
  `sql_type` varchar(20) NOT NULL COMMENT 'SQL类型(CREATE/ALTER/DROP/TRUNCATE)',
  `object_type` varchar(20) DEFAULT 'TABLE' COMMENT '对象类型',
  `object_name` varchar(200) DEFAULT NULL COMMENT '对象名称',
  `status` varchar(20) NOT NULL DEFAULT 'submitted' COMMENT '状态(submitted/running/success/failed)',
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
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='DDL执行日志表';

-- JAR包依赖配置表
CREATE TABLE IF NOT EXISTS `dev_jar_dependency` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `jar_name` varchar(200) NOT NULL COMMENT 'JAR包名称',
  `group_id` varchar(200) DEFAULT NULL COMMENT 'Maven Group ID',
  `artifact_id` varchar(200) DEFAULT NULL COMMENT 'Maven Artifact ID',
  `version` varchar(50) DEFAULT NULL COMMENT '版本号',
  `scope` varchar(20) NOT NULL DEFAULT 'compile' COMMENT '作用域',
  `source` varchar(20) NOT NULL DEFAULT 'system' COMMENT '来源(system/custom/maven/local)',
  `jar_path` varchar(500) DEFAULT NULL COMMENT 'JAR包路径',
  `repository_url` varchar(500) DEFAULT NULL COMMENT 'Maven仓库地址',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小(字节)',
  `checksum` varchar(64) DEFAULT NULL COMMENT '文件校验和',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-正常)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `category` varchar(100) DEFAULT NULL COMMENT '功能分类',
  `program_type` varchar(20) NOT NULL DEFAULT 'flink' COMMENT '程序类型(flink/spark)',
  `auto_choose` tinyint NOT NULL DEFAULT '0' COMMENT '是否自动选择(0-否 1-是)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_source` (`source`),
  KEY `idx_scope` (`scope`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='JAR包依赖配置表';

-- 程序运行参数表
CREATE TABLE IF NOT EXISTS `dev_program_params` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID(自增)',
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
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_file` (`project_id`,`file_path`),
  KEY `idx_project_id` (`project_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='程序运行参数表';

-- 开发项目管理表
CREATE TABLE IF NOT EXISTS `dev_project` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `project_name` varchar(200) NOT NULL COMMENT '项目名称',
  `project_code` varchar(100) NOT NULL COMMENT '项目编码(英文唯一标识)',
  `description` text COMMENT '项目描述',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人ID',
  `owner_name` varchar(50) DEFAULT NULL COMMENT '负责人姓名',
  `default_datasource_id` bigint DEFAULT NULL COMMENT '默认数据源ID',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-归档 1-正常)',
  `script_count` int NOT NULL DEFAULT '0' COMMENT '脚本数量(冗余字段)',
  `scheduled_count` int NOT NULL DEFAULT '0' COMMENT '已调度任务数(冗余字段)',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_code` (`project_code`),
  KEY `idx_owner_id` (`owner_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='开发项目管理表';

-- 脚本执行历史表
CREATE TABLE IF NOT EXISTS `dev_script_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `script_id` bigint NOT NULL COMMENT '脚本ID',
  `execution_id` varchar(64) NOT NULL COMMENT '执行ID(全局唯一)',
  `script_type` varchar(50) NOT NULL COMMENT '脚本类型',
  `execute_mode` varchar(20) NOT NULL DEFAULT 'debug' COMMENT '执行模式(debug/run/schedule)',
  `engine_id` bigint DEFAULT NULL COMMENT '执行引擎ID',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `status` varchar(20) NOT NULL DEFAULT 'submitted' COMMENT '状态(submitted/running/success/failed/cancelled/timeout)',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `input_params` json DEFAULT NULL COMMENT '输入参数',
  `output_log` longtext COMMENT '输出日志',
  `error_message` text COMMENT '错误信息',
  `affected_rows` int DEFAULT NULL COMMENT '影响行数',
  `result_data` json DEFAULT NULL COMMENT '结果数据',
  `submit_by` bigint DEFAULT NULL COMMENT '提交人ID',
  `submit_by_name` varchar(50) DEFAULT NULL COMMENT '提交人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_script_id` (`script_id`),
  KEY `idx_execution_id` (`execution_id`),
  KEY `idx_status` (`status`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_execute_mode` (`execute_mode`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='脚本执行历史表';

-- 指标定义主表
CREATE TABLE IF NOT EXISTS `indicators` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `project_code` varchar(200) NOT NULL COMMENT '所属项目编码',
  `topic` varchar(200) NOT NULL COMMENT '所属主题域',
  `name` varchar(200) NOT NULL COMMENT '指标名称',
  `code` varchar(100) NOT NULL COMMENT '指标编码(唯一标识)',
  `business_definition` text COMMENT '业务定义、计算方法',
  `calc_expr` varchar(100) NOT NULL COMMENT '计算表达式',
  `default_filter` text COMMENT '筛选条件',
  `alert_config` json DEFAULT NULL COMMENT '告警配置',
  `business_owner` varchar(100) DEFAULT NULL COMMENT '业务负责人',
  `tech_owner` varchar(100) DEFAULT NULL COMMENT '技术负责人',
  `refresh_interval` int DEFAULT NULL COMMENT '数据更新间隔',
  `refresh_unit` varchar(10) DEFAULT NULL COMMENT '数据更新时间单位',
  `refresh_desc` varchar(10) DEFAULT NULL COMMENT '数据更新时间描述',
  `visibility` enum('public','internal','restricted') DEFAULT 'internal' COMMENT '可见性',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB COMMENT='指标定义主表';

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
) ENGINE=InnoDB COMMENT='指标相关字段';

-- 知识文档表
CREATE TABLE IF NOT EXISTS `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(200) NOT NULL COMMENT '文档标题',
  `doc_type` varchar(30) NOT NULL COMMENT '文档类型',
  `category` varchar(100) DEFAULT NULL COMMENT '分类',
  `content` longtext COMMENT '文档内容',
  `author_name` varchar(50) DEFAULT NULL COMMENT '作者姓名',
  `author_id` bigint DEFAULT NULL COMMENT '作者ID',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态(0-草稿 1-发布)',
  `sync_to_rag` tinyint NOT NULL DEFAULT '0' COMMENT '是否同步至RAG',
  `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量',
  `tags` varchar(500) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_doc_type` (`doc_type`),
  KEY `idx_status` (`status`),
  KEY `idx_author_id` (`author_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='知识文档表';

-- 审计日志表（按日 RANGE 分区，p_max 兜底；日分区由 AuditLogPartitionTask 维护）
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
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='审计日志表'
PARTITION BY RANGE (to_days(`operation_time`))
(PARTITION p_max VALUES LESS THAN MAXVALUE ENGINE = InnoDB);

-- 存储配置表
CREATE TABLE IF NOT EXISTS `storage_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `storage_name` varchar(100) NOT NULL COMMENT '存储名称',
  `storage_type` varchar(20) NOT NULL COMMENT '类型(hdfs/s3/juicefs)',
  `endpoint` varchar(255) DEFAULT NULL COMMENT '访问端点',
  `bucket_name` varchar(100) DEFAULT NULL COMMENT '桶名称',
  `access_key` varchar(255) DEFAULT NULL COMMENT 'Access Key',
  `secret_key` varchar(255) DEFAULT NULL COMMENT 'Secret Key',
  `root_path` varchar(255) NOT NULL DEFAULT '/' COMMENT '根路径',
  `local_mount_path` varchar(500) DEFAULT NULL COMMENT '本地挂载路径',
  `extra_params` text COMMENT '额外配置(JSON)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-正常)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='存储配置表';

-- 文件路径权限表
CREATE TABLE IF NOT EXISTS `sys_file_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `storage_config_id` bigint NOT NULL COMMENT '存储配置ID',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称',
  `file_path` varchar(1000) NOT NULL COMMENT '文件/目录路径',
  `permission_type` tinyint NOT NULL DEFAULT '1' COMMENT '权限类型(1-读 2-读写 3-完全控制)',
  `recursive` tinyint NOT NULL DEFAULT '0' COMMENT '是否递归子目录',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_storage_config_id` (`storage_config_id`),
  KEY `idx_file_path` (`file_path`(255))
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='文件路径权限表';

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
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-启用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_code` (`menu_code`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='菜单/页面权限表';

-- 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_name` varchar(50) NOT NULL COMMENT '角色名称',
  `role_code` varchar(50) NOT NULL COMMENT '角色编码(唯一标识)',
  `role_type` varchar(30) NOT NULL DEFAULT 'VIEWER' COMMENT '角色类型',
  `description` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-启用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='角色表';

-- 角色-文件权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_file_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `file_permission_id` bigint NOT NULL COMMENT '文件权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_file` (`role_id`,`file_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='角色-文件权限关联表';

-- 角色-菜单权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_menu_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_permission_id` bigint NOT NULL COMMENT '菜单权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='角色-菜单权限关联表';

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
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='角色项目权限关联表';

-- 角色-库表权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_table_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `table_permission_id` bigint NOT NULL COMMENT '库表权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_table` (`role_id`,`table_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='角色-库表权限关联表';

-- 库表CRUD权限表
CREATE TABLE IF NOT EXISTS `sys_table_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称',
  `datasource_name` varchar(255) DEFAULT NULL COMMENT '数据源名称',
  `database_name` varchar(255) DEFAULT NULL COMMENT '数据库名称',
  `table_names` text COMMENT '表名列表(*表示全部)',
  `permission_scope` tinyint NOT NULL DEFAULT '1' COMMENT '权限范围(1-整表 2-指定字段 3-指定行 4-组合)',
  `can_select` tinyint NOT NULL DEFAULT '1' COMMENT '查询权限',
  `can_insert` tinyint NOT NULL DEFAULT '0' COMMENT '插入权限',
  `can_update` tinyint NOT NULL DEFAULT '0' COMMENT '更新权限',
  `can_delete` tinyint NOT NULL DEFAULT '0' COMMENT '删除权限',
  `can_export` tinyint NOT NULL DEFAULT '0' COMMENT '导出权限',
  `row_filter_sql` text COMMENT '行级过滤SQL(WHERE)',
  `data_masking_rule` varchar(500) DEFAULT NULL COMMENT '数据脱敏规则(JSON)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_permission_scope` (`permission_scope`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='库表CRUD权限表';

-- 租户表
CREATE TABLE IF NOT EXISTS `sys_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_name` varchar(100) NOT NULL COMMENT '租户名称',
  `tenant_code` varchar(50) NOT NULL COMMENT '租户编码',
  `contact_name` varchar(50) DEFAULT NULL COMMENT '联系人姓名',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `contact_email` varchar(100) DEFAULT NULL COMMENT '联系邮箱',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` int DEFAULT '1' COMMENT '状态：0-禁用 1-启用',
  `owner_id` bigint DEFAULT NULL COMMENT '所有者用户ID',
  `password` varchar(200) DEFAULT NULL COMMENT '租户密码(AES加密)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` int DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='租户表';

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
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='租户角色关联表';

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
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='租户用户关联表';

-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(255) NOT NULL COMMENT '密码(MD5加密存储)',
  `real_name` varchar(50) DEFAULT NULL COMMENT '真实姓名',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-启用)',
  `is_admin` tinyint(1) DEFAULT '0' COMMENT '是否超级管理员',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='用户表';

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
  `partition_columns` json DEFAULT NULL COMMENT '分区字段(JSON数组)',
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
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table_storage` (`storage_engine`,`database_name`,`table_name`),
  KEY `idx_database_name` (`database_name`),
  KEY `idx_table_name` (`table_name`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='库表元数据表';

-- ------------------------------------------------------------------
-- 2. 初始化数据
-- ------------------------------------------------------------------

INSERT INTO `sys_user` (`id`,`username`,`password`,`real_name`,`email`,`phone`,`status`,`is_admin`,`last_login_time`,`create_time`,`update_time`,`deleted`) VALUES
(1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员','admin@example.com','13800000000',1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05',0),
(3,'test','357f3724ff339b2a1602c390d59498fd','测试用户','test@example.com','13800138000',1,0,'2026-07-13 15:59:21','2026-07-13 15:17:00','2026-07-13 15:17:00',0);

INSERT INTO `sys_role` (`id`,`role_name`,`role_code`,`role_type`,`description`,`status`,`create_time`,`update_time`,`deleted`) VALUES
(1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03',0),
(2,'运维工程师','OPS_ENGINEER','OPS_ENGINEER','负责系统运维和基础设施管理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(3,'数据开发工程师','DATA_DEV_ENGINEER','DATA_DEV_ENGINEER','负责数据开发、ETL和数据处理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(4,'业务需求方','BUSINESS_REQUESTER','BUSINESS_REQUESTER','提出业务需求并查看数据结果',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(5,'数据分析师','DATA_ANALYST','DATA_ANALYST','负责数据分析和探索',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(6,'数据架构师','DATA_ARCHITECT','DATA_ARCHITECT','负责数据架构设计和治理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0);

INSERT INTO `sys_menu_permission` (`id`,`parent_id`,`menu_name`,`menu_code`,`path`,`icon`,`sort_order`,`visible`,`status`,`create_time`,`update_time`,`deleted`) VALUES
(1,0,'开发助手','dev_assist','/dev_assist','0',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(2,0,'数据探索','data_explore','/data_explore','',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(3,0,'知识库','knowledge_base','/knowledge_base','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(4,0,'文件浏览','file_browser','/file_browser','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(5,0,'任务调度','schedule_management','/schedule_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(6,0,'系统管理','system_management','/system_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(7,0,'设置','settings','/settings','',3,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(100,0,'指标管理','indicator_management','/indicator_management','',3,1,1,'2026-07-13 15:17:00','2026-07-13 15:47:41',0);

INSERT INTO `sys_role_menu_permission` (`id`,`role_id`,`menu_permission_id`,`create_time`) VALUES
(12,3,1,'2026-07-13 15:17:00'),(13,3,2,'2026-07-13 15:17:00'),(14,3,3,'2026-07-13 15:17:00'),(15,3,100,'2026-07-13 15:17:00');

INSERT INTO `sys_role_project_permission` (`id`,`role_id`,`project_id`,`permission_type`,`create_time`) VALUES
(1,1,1,3,'2026-07-13 15:17:00'),(45,3,1,2,'2026-07-13 15:59:02');

INSERT INTO `sys_role_table_permission` (`id`,`role_id`,`table_permission_id`,`create_time`) VALUES
(379,3,1,'2026-07-13 15:55:04'),(380,6,1,'2026-07-13 15:55:04');

INSERT INTO `sys_table_permission` (`id`,`permission_name`,`datasource_name`,`database_name`,`table_names`,`permission_scope`,`can_select`,`can_insert`,`can_update`,`can_delete`,`can_export`,`description`,`create_time`,`update_time`,`deleted`) VALUES
(1,'test_db读写权限','test_datasource','test_db','*',1,1,1,1,1,1,'test用户对test_db的全部表读写权限','2026-07-13 15:17:00','2026-07-13 15:55:04',0);

INSERT INTO `sys_tenant` (`id`,`tenant_name`,`tenant_code`,`contact_name`,`contact_phone`,`contact_email`,`description`,`status`,`owner_id`,`password`,`create_time`,`update_time`,`deleted`) VALUES
(1,'测试租户','test_tenant','测试联系人','13800138000','test@example.com','系统初始化测试租户',1,3,'3UcRXfVuO9KPvpqKUXMswQ==','2026-07-13 15:17:00','2026-07-13 15:17:00',0);

INSERT INTO `sys_tenant_role` (`id`,`tenant_id`,`role_id`,`create_time`) VALUES
(690,1,3,'2026-07-13 15:41:32'),(691,1,4,'2026-07-13 15:41:32'),(692,1,6,'2026-07-13 15:41:32');

INSERT INTO `sys_tenant_user` (`id`,`tenant_id`,`user_id`,`create_time`) VALUES
(1,1,3,'2026-07-13 15:17:00');

INSERT INTO `datasource_config` (`id`,`datasource_name`,`datasource_type`,`connection_type`,`storage_engine`,`url`,`database_name`,`username`,`password`,`connection_status`,`last_test_time`,`description`,`create_by`,`create_time`,`update_time`,`deleted`) VALUES
(1,'test_datasource','mysql','jdbc','MYSQL','jdbc:mysql://127.0.0.1:3306/test_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','["test_db"]','test_tenant','3UcRXfVuO9KPvpqKUXMswQ==',1,'2026-07-13 15:17:00','系统初始化-连接test_db的MySQL数据源',3,'2026-07-13 15:17:00','2026-07-13 15:28:14',0);

INSERT INTO `dev_project` (`id`,`project_name`,`project_code`,`description`,`owner_id`,`owner_name`,`default_datasource_id`,`status`,`script_count`,`scheduled_count`,`create_by`,`create_time`,`update_by`,`update_time`,`deleted`) VALUES
(1,'测试项目','test_project','系统初始化测试项目',3,'test',1,1,0,0,3,'2026-07-13 15:17:00',NULL,'2026-07-13 15:17:00',0);

-- ------------------------------------------------------------------
-- 3. 演示用业务库与账号（可选）
-- ------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `test_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'test_tenant'@'%' IDENTIFIED BY '123456';
GRANT ALL PRIVILEGES ON `test_db`.* TO 'test_tenant'@'%';
FLUSH PRIVILEGES;