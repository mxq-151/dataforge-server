-- MySQL dump 10.13  Distrib 9.6.0, for macos15.7 (arm64)
--
-- Host: localhost    Database: dataforge
-- ------------------------------------------------------
-- Server version	9.6.0

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '675db888-1844-11f1-8b84-2043f1eed45b:1-1913641';

--
-- Table structure for table `column_metadata`
--

DROP TABLE IF EXISTS `column_metadata`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `column_metadata` (
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
) ENGINE=InnoDB AUTO_INCREMENT=40 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表字段元数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `column_metadata`
--

LOCK TABLES `column_metadata` WRITE;
/*!40000 ALTER TABLE `column_metadata` DISABLE KEYS */;
/*!40000 ALTER TABLE `column_metadata` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `datasource_config`
--

DROP TABLE IF EXISTS `datasource_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `datasource_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_name` varchar(100) NOT NULL COMMENT '数据源名称',
  `datasource_type` varchar(50) NOT NULL COMMENT '类型(kyuubi/doris/clickhouse/es/starrock/mysql/hdfs/s3/juicefs)',
  `connection_type` varchar(20) DEFAULT 'jdbc' COMMENT '连接方式(jdbc/http/arrow)',
  `storage_engine` varchar(20) DEFAULT NULL COMMENT '存储引擎类型(HIVE/DORIS/STARROCKS/HBASE/MYSQL/ES/CLICKHOUSE/HUDI/ICEBERG)',
  `url` varchar(500) NOT NULL COMMENT '连接URL',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名称',
  `username` varchar(100) DEFAULT NULL COMMENT '用户名',
  `password` varchar(255) DEFAULT NULL COMMENT '密码(加密存储)',
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
) ENGINE=InnoDB AUTO_INCREMENT=856 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `datasource_config`
--

LOCK TABLES `datasource_config` WRITE;
/*!40000 ALTER TABLE `datasource_config` DISABLE KEYS */;
INSERT INTO `datasource_config` VALUES (1,'test_datasource','mysql','jdbc','MYSQL','jdbc:mysql://127.0.0.1:3306/test_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','[\"test_db\"]','test_tenant','3UcRXfVuO9KPvpqKUXMswQ==',NULL,1,'2026-07-13 15:17:00','系统初始化-连接test_db的MySQL数据源',3,'2026-07-13 15:17:00','2026-07-13 15:28:14',0);
/*!40000 ALTER TABLE `datasource_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ddl_execution_log`
--

DROP TABLE IF EXISTS `ddl_execution_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ddl_execution_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `database_name` varchar(100) DEFAULT NULL COMMENT '数据库名',
  `sql_content` text NOT NULL COMMENT 'SQL内容',
  `sql_type` varchar(20) NOT NULL COMMENT 'SQL类型(CREATE/ALTER/DROP/TRUNCATE)',
  `object_type` varchar(20) DEFAULT 'TABLE' COMMENT '对象类型(TABLE/VIEW/INDEX/DATABASE)',
  `object_name` varchar(200) DEFAULT NULL COMMENT '对象名称',
  `status` varchar(20) NOT NULL DEFAULT 'submitted' COMMENT '状态(submitted/running/success/failed)',
  `affected_rows` int DEFAULT NULL COMMENT '影响行数',
  `error_message` text COMMENT '错误信息',
  `execution_time_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `metadata_updated` tinyint NOT NULL DEFAULT '0' COMMENT '是否已更新元数据(0-否 1-是)',
  `execute_by` bigint DEFAULT NULL COMMENT '执行人',
  `execute_by_name` varchar(50) DEFAULT NULL COMMENT '执行人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_datasource_id` (`datasource_id`),
  KEY `idx_sql_type` (`sql_type`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DDL执行日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ddl_execution_log`
--

LOCK TABLES `ddl_execution_log` WRITE;
/*!40000 ALTER TABLE `ddl_execution_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `ddl_execution_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `dev_jar_dependency`
--

DROP TABLE IF EXISTS `dev_jar_dependency`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dev_jar_dependency` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `jar_name` varchar(200) NOT NULL COMMENT 'JAR包名称',
  `group_id` varchar(200) DEFAULT NULL COMMENT 'Maven Group ID',
  `artifact_id` varchar(200) DEFAULT NULL COMMENT 'Maven Artifact ID',
  `version` varchar(50) DEFAULT NULL COMMENT '版本号',
  `scope` varchar(20) NOT NULL DEFAULT 'compile' COMMENT '作用域(compile-编译 runtime-运行时 provided-提供 test-测试)',
  `source` varchar(20) NOT NULL DEFAULT 'system' COMMENT '来源(system-系统内置 custom-自定义 maven-maven仓库 local-本地路径)',
  `jar_path` varchar(500) DEFAULT NULL COMMENT 'JAR包路径(local来源时使用)',
  `repository_url` varchar(500) DEFAULT NULL COMMENT 'Maven仓库地址(maven来源时使用)',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小(字节)',
  `checksum` varchar(64) DEFAULT NULL COMMENT '文件校验和(MD5/SHA256)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-正常)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `category` varchar(100) DEFAULT NULL COMMENT '功能分类(用户自定义)',
  `program_type` varchar(20) NOT NULL DEFAULT 'flink' COMMENT '程序类型(flink-flink任务 spark-spark任务)',
  `auto_choose` tinyint NOT NULL DEFAULT '0' COMMENT '是否自动选择(0-否 1-是)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_source` (`source`),
  KEY `idx_scope` (`scope`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='JAR包依赖配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `dev_jar_dependency`
--

LOCK TABLES `dev_jar_dependency` WRITE;
/*!40000 ALTER TABLE `dev_jar_dependency` DISABLE KEYS */;
/*!40000 ALTER TABLE `dev_jar_dependency` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `dev_program_params`
--

DROP TABLE IF EXISTS `dev_program_params`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dev_program_params` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID(自增)',
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `file_path` varchar(500) NOT NULL COMMENT '文件路径(相对于项目根目录)',
  `engine` varchar(50) DEFAULT NULL COMMENT '执行引擎类型(spark/flink/kyuubi/hive)',
  `datasources` text COMMENT '数据源列表(JSON数组)',
  `jar_dependency_catalogs` text COMMENT 'JAR依赖目录列表(JSON数组)',
  `run_params` text COMMENT '运行参数(JSON对象)',
  `compute_resources` text COMMENT '计算资源配置(JSON对象)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除(0-未删除 1-已删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_file` (`project_id`,`file_path`),
  KEY `idx_project_id` (`project_id`)
) ENGINE=InnoDB AUTO_INCREMENT=166 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='程序运行参数表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `dev_program_params`
--

LOCK TABLES `dev_program_params` WRITE;
/*!40000 ALTER TABLE `dev_program_params` DISABLE KEYS */;
/*!40000 ALTER TABLE `dev_program_params` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `dev_project`
--

DROP TABLE IF EXISTS `dev_project`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dev_project` (
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
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除(0-未删除 1-已删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_code` (`project_code`),
  KEY `idx_owner_id` (`owner_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=857 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开发项目管理表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `dev_project`
--

LOCK TABLES `dev_project` WRITE;
/*!40000 ALTER TABLE `dev_project` DISABLE KEYS */;
INSERT INTO `dev_project` VALUES (1,'测试项目','test_project','系统初始化测试项目',3,'test',1,1,0,0,NULL,'2026-07-13 15:17:00',NULL,'2026-07-13 15:17:00',0);
/*!40000 ALTER TABLE `dev_project` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `dev_script_execution`
--

DROP TABLE IF EXISTS `dev_script_execution`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dev_script_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `script_id` bigint NOT NULL COMMENT '脚本ID',
  `execution_id` varchar(64) NOT NULL COMMENT '执行ID(全局唯一)',
  `script_type` varchar(50) NOT NULL COMMENT '脚本类型',
  `execute_mode` varchar(20) NOT NULL DEFAULT 'debug' COMMENT '执行模式(debug-调试 run-正式运行 schedule-调度触发)',
  `engine_id` bigint DEFAULT NULL COMMENT '执行引擎ID',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `status` varchar(20) NOT NULL DEFAULT 'submitted' COMMENT '状态(submitted-已提交 running-运行中 success-成功 failed-失败 cancelled-取消 timeout-超时)',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `input_params` json DEFAULT NULL COMMENT '输入参数',
  `output_log` longtext COMMENT '输出日志',
  `error_message` text COMMENT '错误信息',
  `affected_rows` int DEFAULT NULL COMMENT '影响行数(SQL)',
  `result_data` json DEFAULT NULL COMMENT '结果数据(查询类SQL)',
  `submit_by` bigint DEFAULT NULL COMMENT '提交人ID',
  `submit_by_name` varchar(50) DEFAULT NULL COMMENT '提交人姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_script_id` (`script_id`),
  KEY `idx_execution_id` (`execution_id`),
  KEY `idx_status` (`status`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_execute_mode` (`execute_mode`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='脚本执行历史表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `dev_script_execution`
--

LOCK TABLES `dev_script_execution` WRITE;
/*!40000 ALTER TABLE `dev_script_execution` DISABLE KEYS */;
/*!40000 ALTER TABLE `dev_script_execution` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `indicators`
--

DROP TABLE IF EXISTS `indicators`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `indicators` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `project_code` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属项目编码',
  `topic` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属主题域',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标名称',
  `code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标编码(唯一标识)',
  `business_definition` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '业务定义、计算方法',
  `calc_expr` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '计算表达式',
  `default_filter` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '筛选条件',
  `alert_config` json DEFAULT NULL COMMENT '告警配置，含阈值、规则、归因提示',
  `business_owner` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务负责人',
  `tech_owner` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技术负责人',
  `refresh_interval` int DEFAULT NULL COMMENT '数据更新间隔',
  `refresh_unit` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '数据更新时间单位',
  `refresh_desc` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '数据更新时间描述',
  `visibility` enum('public','internal','restricted') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'internal' COMMENT '可见性',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标定义主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `indicators`
--

LOCK TABLES `indicators` WRITE;
/*!40000 ALTER TABLE `indicators` DISABLE KEYS */;
/*!40000 ALTER TABLE `indicators` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `indicators_fields`
--

DROP TABLE IF EXISTS `indicators_fields`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `indicators_fields` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `indicators_id` int unsigned NOT NULL COMMENT '关联指标ID',
  `datasource_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属数据源',
  `db_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属库名',
  `tbl_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属表名',
  `field_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '字段名',
  `field_type` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '字段类型',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标相关字段';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `indicators_fields`
--

LOCK TABLES `indicators_fields` WRITE;
/*!40000 ALTER TABLE `indicators_fields` DISABLE KEYS */;
/*!40000 ALTER TABLE `indicators_fields` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge_document`
--

DROP TABLE IF EXISTS `knowledge_document`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(200) NOT NULL COMMENT '文档标题',
  `doc_type` varchar(30) NOT NULL COMMENT '文档类型(business_term/process_doc/deploy_doc/lineage_doc/other)',
  `category` varchar(100) DEFAULT NULL COMMENT '分类',
  `content` longtext COMMENT '文档内容',
  `author_name` varchar(50) DEFAULT NULL COMMENT '作者姓名',
  `author_id` bigint DEFAULT NULL COMMENT '作者ID',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态(0-草稿 1-发布)',
  `sync_to_rag` tinyint NOT NULL DEFAULT '0' COMMENT '是否同步至RAG(0-未同步 1-已同步)',
  `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量',
  `tags` varchar(500) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除(0-未删除 1-已删除)',
  PRIMARY KEY (`id`),
  KEY `idx_doc_type` (`doc_type`),
  KEY `idx_status` (`status`),
  KEY `idx_author_id` (`author_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1141 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识文档表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge_document`
--

LOCK TABLES `knowledge_document` WRITE;
/*!40000 ALTER TABLE `knowledge_document` DISABLE KEYS */;
/*!40000 ALTER TABLE `knowledge_document` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `operation_audit_log`
--

DROP TABLE IF EXISTS `operation_audit_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '操作用户ID',
  `username` varchar(50) DEFAULT NULL COMMENT '操作用户名',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `tenant_name` varchar(100) DEFAULT NULL COMMENT '租户名称',
  `module` varchar(50) NOT NULL COMMENT '模块名称',
  `operation` varchar(100) NOT NULL COMMENT '操作描述',
  `operation_type` varchar(20) DEFAULT NULL COMMENT '操作类型(CREATE/UPDATE/DELETE/LOGIN/EXPORT/QUERY/EXECUTE)',
  `target_type` varchar(50) DEFAULT NULL COMMENT '目标对象类型(User/Role/Tenant/Datasource/Table/File)',
  `target_id` bigint DEFAULT NULL COMMENT '目标对象ID',
  `target_name` varchar(200) DEFAULT NULL COMMENT '目标对象名称',
  `request_method` varchar(10) DEFAULT NULL COMMENT '请求方法(GET/POST/PUT/DELETE)',
  `request_url` varchar(500) DEFAULT NULL COMMENT '请求URL',
  `request_params` text COMMENT '请求参数(JSON格式,敏感数据脱敏)',
  `response_body` text COMMENT '响应内容(仅记录关键信息,截断过长内容)',
  `response_status` int DEFAULT NULL COMMENT 'HTTP响应状态码',
  `error_message` text COMMENT '错误信息',
  `exception_class` varchar(200) DEFAULT NULL COMMENT '异常类名',
  `ip_address` varchar(50) DEFAULT NULL COMMENT '客户端IP地址',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `duration_ms` int DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `is_sensitive` tinyint(1) DEFAULT '0' COMMENT '是否敏感操作(0=否,1=是)',
  `is_ai_request` tinyint DEFAULT '0' COMMENT '是否为AI发起的请求(0否 1是)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`,`operation_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_module` (`module`),
  KEY `idx_operation_type` (`operation_type`)
) ENGINE=InnoDB AUTO_INCREMENT=4804 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
/*!50100 PARTITION BY RANGE (to_days(`operation_time`))
(PARTITION p_max VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `operation_audit_log`
--

LOCK TABLES `operation_audit_log` WRITE;
/*!40000 ALTER TABLE `operation_audit_log` DISABLE KEYS */;
INSERT INTO `operation_audit_log` VALUES (4760,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"admin\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTiIsImlzQWRtaW4iOnRydWUsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImlhdCI6MTc4MzkyNzQ1NywiZXhwIjoxNzg0MDEzODU3fQ.bBijr5j47Ci5qn9rH0fYzOZtQokf9a1IsuFcVwKX3kC3PU7QT6sGnSP9X1r0D6MF\",\"userId\":1,\"username\":\"admin\",\"realName\":\"超级管理员\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\"],\"tenantId\":null,\"tenantName\":null}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:24:18',39,0,0,'2026-07-13 15:24:18'),(4761,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTixST0xFX09QU19FTkdJTkVFUixST0xFX0RBVEFfREVWX0VOR0lORUVSLFJPTEVfQlVTSU5FU1NfUkVRVUVTVEVSLFJPTEVfREFUQV9BTkFMWVNULFJPTEVfREFUQV9BUkNISVRFQ1QiLCJ0ZW5hbnRJZCI6MSwiaXNBZG1pbiI6ZmFsc2UsInVzZXJJZCI6MywidXNlcm5hbWUiOiJ0ZXN0IiwiaWF0IjoxNzgzOTI3NDcwLCJleHAiOjE3ODQwMTM4NzB9.hDUSesebz__A_TM8bnI6-jQC0alrG_gqS2UE6OYjkDXlUP8tCe7m1c3wnIUw2Upv\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\",\"ROLE_OPS_ENGINEER\",\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ANALYST\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:24:31',35,0,0,'2026-07-13 15:24:31'),(4762,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTixST0xFX09QU19FTkdJTkVFUixST0xFX0RBVEFfREVWX0VOR0lORUVSLFJPTEVfQlVTSU5FU1NfUkVRVUVTVEVSLFJPTEVfREFUQV9BTkFMWVNULFJPTEVfREFUQV9BUkNISVRFQ1QiLCJ0ZW5hbnRJZCI6MSwiaXNBZG1pbiI6ZmFsc2UsInVzZXJJZCI6MywidXNlcm5hbWUiOiJ0ZXN0IiwiaWF0IjoxNzgzOTI3ODQyLCJleHAiOjE3ODQwMTQyNDJ9.uXCt9G3256jwwxienvhjR0QkrjdIFy4zAJ3EYd2pgJgw4cCGB3HqpKsPrm8ZLgwn\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\",\"ROLE_OPS_ENGINEER\",\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ANALYST\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:30:43',45,0,0,'2026-07-13 15:30:43'),(4763,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"1234567\"}}',NULL,500,'密码错误',NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:33:08',4,0,0,'2026-07-13 15:33:08'),(4764,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9PUFNfRU5HSU5FRVIsUk9MRV9EQVRBX0RFVl9FTkdJTkVFUixST0xFX0JVU0lORVNTX1JFUVVFU1RFUixST0xFX0RBVEFfQU5BTFlTVCxST0xFX0RBVEFfQVJDSElURUNUIiwidGVuYW50SWQiOjEsImlzQWRtaW4iOmZhbHNlLCJ1c2VySWQiOjMsInVzZXJuYW1lIjoidGVzdCIsImlhdCI6MTc4MzkyNzk5NCwiZXhwIjoxNzg0MDE0Mzk0fQ.7R2TVCyfREicu7rPbVCPKOVoOm95h92Wyn_rknTBXc8NutpRs9EgNQfh6AK2tQot\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_OPS_ENGINEER\",\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ANALYST\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:33:14',27,0,0,'2026-07-13 15:33:14'),(4765,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"admin\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTiIsImlzQWRtaW4iOnRydWUsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImlhdCI6MTc4MzkyODAxNywiZXhwIjoxNzg0MDE0NDE3fQ.ojiZ4afEImyO9Phbk8xgL-huHfFvnx8aDyLEEGmEIqfSvvVg7RL-YX2OF_RCEFOa\",\"userId\":1,\"username\":\"admin\",\"realName\":\"超级管理员\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\"],\"tenantId\":null,\"tenantName\":null}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:33:38',23,0,0,'2026-07-13 15:33:38'),(4766,1,'admin',NULL,NULL,'权限管理','绑定菜单角色','CREATE','MenuRole',NULL,NULL,'POST','/api/system/menu-role/bind','{\"dto\":[{\"menuId\":100,\"roleId\":3}]}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:16',9,0,0,'2026-07-13 15:39:16'),(4767,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:27',7,0,0,'2026-07-13 15:39:27'),(4768,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":2}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:30',12,0,0,'2026-07-13 15:39:30'),(4769,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":3}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:34',6,0,0,'2026-07-13 15:39:34'),(4770,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":4}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:36',11,0,0,'2026-07-13 15:39:36'),(4771,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":5}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:39',8,0,0,'2026-07-13 15:39:39'),(4772,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":6}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:42',8,0,0,'2026-07-13 15:39:42'),(4773,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":7}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:39:45',9,0,0,'2026-07-13 15:39:45'),(4774,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9PUFNfRU5HSU5FRVIsUk9MRV9EQVRBX0RFVl9FTkdJTkVFUixST0xFX0JVU0lORVNTX1JFUVVFU1RFUixST0xFX0RBVEFfQU5BTFlTVCxST0xFX0RBVEFfQVJDSElURUNUIiwidGVuYW50SWQiOjEsImlzQWRtaW4iOmZhbHNlLCJ1c2VySWQiOjMsInVzZXJuYW1lIjoidGVzdCIsImlhdCI6MTc4MzkyODQyMSwiZXhwIjoxNzg0MDE0ODIxfQ.O1ruSKkrgCgrs9L__GvC5B5P2EjjGOpS8O8wc6PBaXZAOlYMkn6msxyaxPmwUU35\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_OPS_ENGINEER\",\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ANALYST\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:40:21',32,0,0,'2026-07-13 15:40:21'),(4775,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"admin\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTiIsImlzQWRtaW4iOnRydWUsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImlhdCI6MTc4MzkyODQ1OSwiZXhwIjoxNzg0MDE0ODU5fQ.QaeVq_Z-cuJBDU-qYR9tw4dz2cavLlozsuEGpPoIIHFEkfSjqAeaa1jEJQDhht9o\",\"userId\":1,\"username\":\"admin\",\"realName\":\"超级管理员\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\"],\"tenantId\":null,\"tenantName\":null}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:40:59',26,0,0,'2026-07-13 15:40:59'),(4776,1,'admin',NULL,NULL,'用户管理','更新用户','UPDATE','User',NULL,NULL,'PUT','/api/system/user','{\"dto\":{\"id\":3,\"username\":null,\"password\":null,\"realName\":null,\"email\":null,\"phone\":null,\"avatar\":null,\"status\":null,\"roleIds\":[3,4,6]}}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:41:32',7,0,0,'2026-07-13 15:41:32'),(4777,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":8}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:41:52',12,0,0,'2026-07-13 15:41:52'),(4778,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":9}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:41:58',14,0,0,'2026-07-13 15:41:58'),(4779,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":10}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:42:00',7,0,0,'2026-07-13 15:42:00'),(4780,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":11}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:42:03',6,0,0,'2026-07-13 15:42:03'),(4781,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"admin\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTiIsImlzQWRtaW4iOnRydWUsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImlhdCI6MTc4MzkyODgxMywiZXhwIjoxNzg0MDE1MjEzfQ.eqrs8ibrJmFOzZUOQ6zv2vA8sBGPZgyBITKKQPKjMAWup97QKZZmQPQ7ZVFLD2NB\",\"userId\":1,\"username\":\"admin\",\"realName\":\"超级管理员\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\"],\"tenantId\":null,\"tenantName\":null}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:46:54',22,0,0,'2026-07-13 15:46:54'),(4782,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9EQVRBX0RFVl9FTkdJTkVFUixST0xFX0JVU0lORVNTX1JFUVVFU1RFUixST0xFX0RBVEFfQVJDSElURUNUIiwidGVuYW50SWQiOjEsImlzQWRtaW4iOmZhbHNlLCJ1c2VySWQiOjMsInVzZXJuYW1lIjoidGVzdCIsImlhdCI6MTc4MzkyODg0MSwiZXhwIjoxNzg0MDE1MjQxfQ.EAoFscfc0_Vg7jWFXVBSEsA8qWNyEbk_ZUPdWfYgmpVnw_Hlz3w5S2MEH-K2hoGi\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:47:22',21,0,0,'2026-07-13 15:47:22'),(4783,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"admin\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTiIsImlzQWRtaW4iOnRydWUsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImlhdCI6MTc4MzkyODg3NSwiZXhwIjoxNzg0MDE1Mjc1fQ.qAMp7Pz5eEZc38H7N8-w0y52RqX35dfMDsSfYhL6jB5w4SUlflR1TKhPbMLoSmPJ\",\"userId\":1,\"username\":\"admin\",\"realName\":\"超级管理员\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\"],\"tenantId\":null,\"tenantName\":null}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:47:56',22,0,0,'2026-07-13 15:47:56'),(4784,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9EQVRBX0RFVl9FTkdJTkVFUixST0xFX0JVU0lORVNTX1JFUVVFU1RFUixST0xFX0RBVEFfQVJDSElURUNUIiwidGVuYW50SWQiOjEsImlzQWRtaW4iOmZhbHNlLCJ1c2VySWQiOjMsInVzZXJuYW1lIjoidGVzdCIsImlhdCI6MTc4MzkyODkwMiwiZXhwIjoxNzg0MDE1MzAyfQ.0mZwurVywYkGD9bhvybxbdqYdMFehdvB5iGTwb8DmFK98tsQ30USu0IqvASHGk8f\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:48:23',25,0,0,'2026-07-13 15:48:23'),(4785,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"admin\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9TVVBFUl9BRE1JTiIsImlzQWRtaW4iOnRydWUsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImlhdCI6MTc4MzkyOTIxNiwiZXhwIjoxNzg0MDE1NjE2fQ.RtEceUXWJR44aUVBlTRnFOlOomE2IWRjMqOmMJHL9pquJ3iBSzEaIudpT9uCQGJm\",\"userId\":1,\"username\":\"admin\",\"realName\":\"超级管理员\",\"avatar\":null,\"roles\":[\"ROLE_SUPER_ADMIN\"],\"tenantId\":null,\"tenantName\":null}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:53:37',18,0,0,'2026-07-13 15:53:37'),(4786,1,'admin',NULL,NULL,'角色管理','删除角色','DELETE','Role',NULL,NULL,'DELETE','/api/system/role/1','{\"id\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:04',13,0,0,'2026-07-13 15:54:04'),(4787,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":24}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:23',7,0,0,'2026-07-13 15:54:23'),(4788,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":23}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:25',6,0,0,'2026-07-13 15:54:25'),(4789,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":22}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:27',8,0,0,'2026-07-13 15:54:27'),(4790,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":21}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:29',2,0,0,'2026-07-13 15:54:29'),(4791,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":20}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:32',8,0,0,'2026-07-13 15:54:32'),(4792,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":19}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:35',12,0,0,'2026-07-13 15:54:35'),(4793,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":18}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:37',3,0,0,'2026-07-13 15:54:37'),(4794,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":17}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:40',5,0,0,'2026-07-13 15:54:40'),(4795,1,'admin',NULL,NULL,'权限管理','删除菜单角色绑定','DELETE','MenuRole',NULL,NULL,'DELETE','/api/system/menu-role/delete','{\"id\":16}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:54:42',8,0,0,'2026-07-13 15:54:42'),(4796,1,'admin',NULL,NULL,'权限管理','更新表权限','UPDATE','TablePermission',NULL,NULL,'PUT','/api/system/permission/table','{\"dto\":{\"id\":1,\"permissionName\":\"test_db读写权限\",\"datasourceName\":\"test_datasource\",\"databaseName\":\"test_db\",\"tableNames\":[\"*\"],\"permissionScope\":1,\"canSelect\":1,\"canInsert\":1,\"canUpdate\":1,\"canDelete\":1,\"canExport\":1,\"rowFilterSql\":null,\"dataMaskingRule\":null,\"description\":\"test用户对test_db的全部表读写权限\",\"roleIds\":[1,3,6]}}','{\"id\":1,\"tableMetadataIds\":null,\"datasourceName\":\"test_datasource\",\"databaseName\":\"test_db\",\"tableNames\":[\"*\"],\"permissionName\":\"test_db读写权限\",\"permissionScope\":1,\"canSelect\":1,\"canInsert\":1,\"canUpdate\":1,\"canDelete\":1,\"canExport\":1,\"rowFilterSql\":null,\"dataMaskingRule\":null,\"description\":\"test用户对test_db的全部表读写权限\",\"createTime\":null,\"updateTime\":\"2026-07-13T15:55:04.188418\",\"roleIds\":[1,3,6],\"roleNames\":[\"未知\",\"数据开发工程师\",\"数据架构师\"]}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:55:04',8,0,0,'2026-07-13 15:55:04'),(4797,1,'admin',NULL,NULL,'权限管理','移除项目权限','DELETE','ProjectPermission',NULL,NULL,'DELETE','/api/system/project-permission/6/1','{\"roleId\":6,\"projectId\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:58:34',4,0,0,'2026-07-13 15:58:34'),(4798,1,'admin',NULL,NULL,'权限管理','移除项目权限','DELETE','ProjectPermission',NULL,NULL,'DELETE','/api/system/project-permission/2/1','{\"roleId\":2,\"projectId\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:58:40',6,0,0,'2026-07-13 15:58:40'),(4799,1,'admin',NULL,NULL,'权限管理','移除项目权限','DELETE','ProjectPermission',NULL,NULL,'DELETE','/api/system/project-permission/3/1','{\"roleId\":3,\"projectId\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:58:43',7,0,0,'2026-07-13 15:58:43'),(4800,1,'admin',NULL,NULL,'权限管理','移除项目权限','DELETE','ProjectPermission',NULL,NULL,'DELETE','/api/system/project-permission/4/1','{\"roleId\":4,\"projectId\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:58:45',9,0,0,'2026-07-13 15:58:45'),(4801,1,'admin',NULL,NULL,'权限管理','移除项目权限','DELETE','ProjectPermission',NULL,NULL,'DELETE','/api/system/project-permission/5/1','{\"roleId\":5,\"projectId\":1}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:58:47',7,0,0,'2026-07-13 15:58:47'),(4802,1,'admin',NULL,NULL,'权限管理','批量分配项目权限','CREATE','ProjectPermission',NULL,NULL,'POST','/api/system/project-permission/batch','{\"dto\":{\"roleId\":3,\"items\":[{\"projectId\":1,\"permissionType\":2}]}}',NULL,200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:59:02',6,0,0,'2026-07-13 15:59:02'),(4803,NULL,'anonymousUser',NULL,NULL,'认证','用户登录','CREATE','User',NULL,NULL,'POST','/api/system/auth/login','{\"loginDTO\":{\"username\":\"test\",\"password\":\"123456\"}}','{\"token\":\"eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoiUk9MRV9EQVRBX0RFVl9FTkdJTkVFUixST0xFX0JVU0lORVNTX1JFUVVFU1RFUixST0xFX0RBVEFfQVJDSElURUNUIiwidGVuYW50SWQiOjEsImlzQWRtaW4iOmZhbHNlLCJ1c2VySWQiOjMsInVzZXJuYW1lIjoidGVzdCIsImlhdCI6MTc4MzkyOTU2MSwiZXhwIjoxNzg0MDE1OTYxfQ.G862B0Cq6x3VqYxnzyoQpQ_0cpw36Qk-HI_TUJ7Lr2gaMiu6UUNwZ65spT2gtS1m\",\"userId\":3,\"username\":\"test\",\"realName\":\"测试用户\",\"avatar\":null,\"roles\":[\"ROLE_DATA_DEV_ENGINEER\",\"ROLE_BUSINESS_REQUESTER\",\"ROLE_DATA_ARCHITECT\"],\"tenantId\":1,\"tenantName\":\"测试租户\"}',200,NULL,NULL,'127.0.0.1','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)','2026-07-13 15:59:21',28,0,0,'2026-07-13 15:59:21');
/*!40000 ALTER TABLE `operation_audit_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `storage_config`
--

DROP TABLE IF EXISTS `storage_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `storage_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `storage_name` varchar(100) NOT NULL COMMENT '存储名称',
  `storage_type` varchar(20) NOT NULL COMMENT '类型(hdfs/s3/juicefs)',
  `endpoint` varchar(255) DEFAULT NULL COMMENT '访问端点',
  `bucket_name` varchar(100) DEFAULT NULL COMMENT '桶名称(S3使用)',
  `access_key` varchar(255) DEFAULT NULL COMMENT 'Access Key(S3使用)',
  `secret_key` varchar(255) DEFAULT NULL COMMENT 'Secret Key(S3使用)',
  `root_path` varchar(255) NOT NULL DEFAULT '/' COMMENT '根路径',
  `local_mount_path` varchar(500) DEFAULT NULL COMMENT '本地挂载路径(JuiceFS使用)',
  `extra_params` text COMMENT '额外配置(JSON)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-正常)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=412 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `storage_config`
--

LOCK TABLES `storage_config` WRITE;
/*!40000 ALTER TABLE `storage_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `storage_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_file_permission`
--

DROP TABLE IF EXISTS `sys_file_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_file_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `storage_config_id` bigint NOT NULL COMMENT '存储配置ID',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称(如:生产数据目录)',
  `file_path` varchar(1000) NOT NULL COMMENT '文件/目录路径(支持通配符)',
  `permission_type` tinyint NOT NULL DEFAULT '1' COMMENT '权限类型(1-可读 2-读写 3-完全控制)',
  `recursive` tinyint NOT NULL DEFAULT '0' COMMENT '是否递归子目录(0-否 1-是)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_storage_config_id` (`storage_config_id`),
  KEY `idx_file_path` (`file_path`(255))
) ENGINE=InnoDB AUTO_INCREMENT=307 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件路径权限表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_file_permission`
--

LOCK TABLES `sys_file_permission` WRITE;
/*!40000 ALTER TABLE `sys_file_permission` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_file_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_menu_permission`
--

DROP TABLE IF EXISTS `sys_menu_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父级ID(0表示顶级)',
  `menu_name` varchar(100) NOT NULL COMMENT '菜单名称',
  `menu_code` varchar(100) NOT NULL COMMENT '菜单编码(唯一标识)',
  `path` varchar(255) DEFAULT NULL COMMENT '路由路径',
  `component` varchar(255) DEFAULT NULL COMMENT '组件路径',
  `icon` varchar(100) DEFAULT NULL COMMENT '图标',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `visible` tinyint NOT NULL DEFAULT '1' COMMENT '是否可见(0-隐藏 1-显示)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-启用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_code` (`menu_code`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=235 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单/页面权限表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_menu_permission`
--

LOCK TABLES `sys_menu_permission` WRITE;
/*!40000 ALTER TABLE `sys_menu_permission` DISABLE KEYS */;
INSERT INTO `sys_menu_permission` VALUES (1,0,'开发助手','dev_assist','/dev_assist',NULL,'0',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(2,0,'数据探索','data_explore','/data_explore',NULL,'',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(3,0,'知识库','knowledge_base','/knowledge_base',NULL,'',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(4,0,'文件浏览','file_browser','/file_browser',NULL,'',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(5,0,'任务调度','schedule_management','/schedule_management',NULL,'',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(6,0,'系统管理','system_management','/system_management',NULL,'',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(7,0,'设置','settings','/settings',NULL,'',3,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),(100,0,'指标管理','indicator_management','/indicator_management',NULL,'',3,1,1,'2026-07-13 15:17:00','2026-07-13 15:47:41',0);
/*!40000 ALTER TABLE `sys_menu_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role`
--

DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_name` varchar(50) NOT NULL COMMENT '角色名称',
  `role_code` varchar(50) NOT NULL COMMENT '角色编码(唯一标识)',
  `role_type` varchar(30) NOT NULL DEFAULT 'VIEWER' COMMENT '角色类型(SUPER_ADMIN/OPS/DEVELOPER/ANALYST/VIEWER)',
  `description` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0-禁用 1-启用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1480 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role`
--

LOCK TABLES `sys_role` WRITE;
/*!40000 ALTER TABLE `sys_role` DISABLE KEYS */;
INSERT INTO `sys_role` VALUES (1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03',1),(2,'运维工程师','OPS_ENGINEER','OPS_ENGINEER','负责系统运维和基础设施管理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),(3,'数据开发工程师','DATA_DEV_ENGINEER','DATA_DEV_ENGINEER','负责数据开发、ETL和数据处理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),(4,'业务需求方','BUSINESS_REQUESTER','BUSINESS_REQUESTER','提出业务需求并查看数据结果',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),(5,'数据分析师','DATA_ANALYST','DATA_ANALYST','负责数据分析和探索',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),(6,'数据架构师','DATA_ARCHITECT','DATA_ARCHITECT','负责数据架构设计和治理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0);
/*!40000 ALTER TABLE `sys_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_file_permission`
--

DROP TABLE IF EXISTS `sys_role_file_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_file_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `file_permission_id` bigint NOT NULL COMMENT '文件权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_file` (`role_id`,`file_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=148 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-文件权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_file_permission`
--

LOCK TABLES `sys_role_file_permission` WRITE;
/*!40000 ALTER TABLE `sys_role_file_permission` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_role_file_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_menu_permission`
--

DROP TABLE IF EXISTS `sys_role_menu_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_permission_id` bigint NOT NULL COMMENT '菜单权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=261 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-菜单权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_menu_permission`
--

LOCK TABLES `sys_role_menu_permission` WRITE;
/*!40000 ALTER TABLE `sys_role_menu_permission` DISABLE KEYS */;
INSERT INTO `sys_role_menu_permission` VALUES (12,3,1,'2026-07-13 15:17:00'),(13,3,2,'2026-07-13 15:17:00'),(14,3,3,'2026-07-13 15:17:00'),(15,3,100,'2026-07-13 15:17:00');
/*!40000 ALTER TABLE `sys_role_menu_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_project_permission`
--

DROP TABLE IF EXISTS `sys_role_project_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_project_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `permission_type` int NOT NULL COMMENT '权限类型：1-可读，2-可编辑，3-可删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_project` (`role_id`,`project_id`),
  KEY `idx_role_id` (`role_id`),
  KEY `idx_project_id` (`project_id`)
) ENGINE=InnoDB AUTO_INCREMENT=46 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色项目权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_project_permission`
--

LOCK TABLES `sys_role_project_permission` WRITE;
/*!40000 ALTER TABLE `sys_role_project_permission` DISABLE KEYS */;
INSERT INTO `sys_role_project_permission` VALUES (1,1,1,3,'2026-07-13 15:17:00'),(45,3,1,2,'2026-07-13 15:59:02');
/*!40000 ALTER TABLE `sys_role_project_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_table_permission`
--

DROP TABLE IF EXISTS `sys_role_table_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_table_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `table_permission_id` bigint NOT NULL COMMENT '库表权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_table` (`role_id`,`table_permission_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=381 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-库表权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_table_permission`
--

LOCK TABLES `sys_role_table_permission` WRITE;
/*!40000 ALTER TABLE `sys_role_table_permission` DISABLE KEYS */;
INSERT INTO `sys_role_table_permission` VALUES (379,3,1,'2026-07-13 15:55:04'),(380,6,1,'2026-07-13 15:55:04');
/*!40000 ALTER TABLE `sys_role_table_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_table_permission`
--

DROP TABLE IF EXISTS `sys_table_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_table_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称',
  `datasource_name` varchar(255) DEFAULT NULL COMMENT '数据源名称',
  `database_name` varchar(255) DEFAULT NULL COMMENT '数据库名称',
  `table_names` text COMMENT '表名列表(逗号分隔,*表示全部)',
  `permission_scope` tinyint NOT NULL DEFAULT '1' COMMENT '权限范围(1-整表 2-指定字段 3-指定行 4-字段+行级组合)',
  `can_select` tinyint NOT NULL DEFAULT '1' COMMENT '查询权限(0-否 1-是)',
  `can_insert` tinyint NOT NULL DEFAULT '0' COMMENT '插入权限(0-否 1-是)',
  `can_update` tinyint NOT NULL DEFAULT '0' COMMENT '更新权限(0-否 1-是)',
  `can_delete` tinyint NOT NULL DEFAULT '0' COMMENT '删除权限(0-否 1-是)',
  `can_export` tinyint NOT NULL DEFAULT '0' COMMENT '导出权限(0-否 1-是)',
  `row_filter_sql` text COMMENT '行级过滤SQL条件(WHERE子句)',
  `data_masking_rule` varchar(500) DEFAULT NULL COMMENT '数据脱敏规则(JSON格式)',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_permission_scope` (`permission_scope`)
) ENGINE=InnoDB AUTO_INCREMENT=391 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库表CRUD权限表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_table_permission`
--

LOCK TABLES `sys_table_permission` WRITE;
/*!40000 ALTER TABLE `sys_table_permission` DISABLE KEYS */;
INSERT INTO `sys_table_permission` VALUES (1,'test_db读写权限','test_datasource','test_db','*',1,1,1,1,1,1,NULL,NULL,'test用户对test_db的全部表读写权限','2026-07-13 15:17:00','2026-07-13 15:55:04',0);
/*!40000 ALTER TABLE `sys_table_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_tenant`
--

DROP TABLE IF EXISTS `sys_tenant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_name` varchar(100) NOT NULL COMMENT '租户名称',
  `tenant_code` varchar(50) NOT NULL COMMENT '租户编码',
  `contact_name` varchar(50) DEFAULT NULL COMMENT '联系人姓名',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `contact_email` varchar(100) DEFAULT NULL COMMENT '联系邮箱',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` int DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `owner_id` bigint DEFAULT NULL COMMENT '所有者用户ID',
  `password` varchar(200) DEFAULT NULL COMMENT '租户密码',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` int DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1127 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_tenant`
--

LOCK TABLES `sys_tenant` WRITE;
/*!40000 ALTER TABLE `sys_tenant` DISABLE KEYS */;
INSERT INTO `sys_tenant` VALUES (1,'测试租户','test_tenant','测试联系人','13800138000','test@example.com','系统初始化测试租户',1,3,'3UcRXfVuO9KPvpqKUXMswQ==','2026-07-13 15:17:00','2026-07-13 15:17:00',0);
/*!40000 ALTER TABLE `sys_tenant` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_tenant_role`
--

DROP TABLE IF EXISTS `sys_tenant_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_tenant_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_role` (`tenant_id`,`role_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=693 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_tenant_role`
--

LOCK TABLES `sys_tenant_role` WRITE;
/*!40000 ALTER TABLE `sys_tenant_role` DISABLE KEYS */;
INSERT INTO `sys_tenant_role` VALUES (690,1,3,'2026-07-13 15:41:32'),(691,1,4,'2026-07-13 15:41:32'),(692,1,6,'2026-07-13 15:41:32');
/*!40000 ALTER TABLE `sys_tenant_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_tenant_user`
--

DROP TABLE IF EXISTS `sys_tenant_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_tenant_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_id` (`tenant_id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=768 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户用户关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_tenant_user`
--

LOCK TABLES `sys_tenant_user` WRITE;
/*!40000 ALTER TABLE `sys_tenant_user` DISABLE KEYS */;
INSERT INTO `sys_tenant_user` VALUES (1,1,3,'2026-07-13 15:17:00');
/*!40000 ALTER TABLE `sys_tenant_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
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
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除(0-未删除 1-已删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2668 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;
INSERT INTO `sys_user` VALUES (1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员',NULL,NULL,NULL,1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05',0),(3,'test','357f3724ff339b2a1602c390d59498fd','测试用户','test@example.com','13800138000',NULL,1,0,'2026-07-13 15:59:21','2026-07-13 15:17:00','2026-07-13 15:17:00',0);
/*!40000 ALTER TABLE `sys_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `table_metadata`
--

DROP TABLE IF EXISTS `table_metadata`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_metadata` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `storage_engine` varchar(20) DEFAULT NULL COMMENT '存储引擎类型',
  `catalog_name` varchar(100) DEFAULT NULL COMMENT '目录名',
  `database_name` varchar(100) NOT NULL COMMENT '数据库名',
  `table_name` varchar(200) NOT NULL COMMENT '表名',
  `table_type` varchar(50) DEFAULT NULL COMMENT '表类型(MANAGED_TABLE/EXTERNAL_TABLE/VIEW/SYSTEM_TABLE)',
  `table_comment` varchar(1000) DEFAULT NULL COMMENT '表注释/业务描述',
  `table_create_time` datetime DEFAULT NULL COMMENT '建表时间(从引擎获取)',
  `last_modify_time` datetime DEFAULT NULL COMMENT '最后修改时间',
  `partition_columns` json DEFAULT NULL COMMENT '分区字段(JSON数组)',
  `storage_format` varchar(200) DEFAULT NULL COMMENT '存储格式(InputFormat类名/引擎名)',
  `compression` varchar(50) DEFAULT NULL COMMENT '压缩算法(SNAPPY/GZIP/ZSTD/LZ4/LZO/BZIP2/DEFLATE/ZLIB/NO/UNKNOWN)',
  `location` varchar(500) DEFAULT NULL COMMENT '存储位置(HDFS路径/数据目录等)',
  `row_count` bigint DEFAULT NULL COMMENT '行数估算',
  `size_bytes` bigint DEFAULT NULL COMMENT '大小(字节)',
  `hot_days` int NOT NULL DEFAULT '90' COMMENT '热数据保留天数',
  `cold_days` int NOT NULL DEFAULT '180' COMMENT '冷数据保留天数',
  `keep_days` int NOT NULL DEFAULT '720' COMMENT '数据总保留天数',
  `sync_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '元数据同步时间',
  `table_fingerprint` varchar(64) DEFAULT NULL COMMENT '表结构指纹(表级元数据MD5)，未变更时跳过表更新',
  `columns_fingerprint` varchar(64) DEFAULT NULL COMMENT '字段结构指纹(字段级元数据MD5)，未变更时跳过字段重建',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table_storage` (`storage_engine`,`database_name`,`table_name`),
  KEY `idx_database_name` (`database_name`),
  KEY `idx_table_name` (`table_name`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库表元数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `table_metadata`
--

LOCK TABLES `table_metadata` WRITE;
/*!40000 ALTER TABLE `table_metadata` DISABLE KEYS */;
/*!40000 ALTER TABLE `table_metadata` ENABLE KEYS */;
UNLOCK TABLES;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-07-13 16:02:31


-- 3.1 创建数据库 test_db
CREATE DATABASE IF NOT EXISTS `test_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 3.2 创建 MySQL 用户 test_tenant (密码: 123456)
CREATE USER IF NOT EXISTS 'test_tenant'@'%' IDENTIFIED BY '123456';

-- 3.3 授权 test_tenant 用户对 test_db 的读写权限
GRANT SELECT, INSERT,DROP, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `test_db`.* TO 'test_tenant'@'%';
FLUSH PRIVILEGES;