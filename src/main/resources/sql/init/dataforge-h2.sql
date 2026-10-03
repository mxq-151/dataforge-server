-- ============================================================
-- dataforge - H2 2.x 建表与初始化脚本
-- 约定：
--   * 自增主键：BIGINT AUTO_INCREMENT（默认模式与 MySQL 兼容模式均可用）
--   * text/longtext -> CLOB, json -> JSON, datetime -> TIMESTAMP
--   * tinyint -> TINYINT, enum -> VARCHAR + CHECK
--   * H2 不支持表分区，operation_audit_log 按普通表建立
-- ============================================================

-- ==================================================================
-- 资产目录模块（asset_*）：对标 DataWorks 数据资产目录
--   分类不靠人工维护：表名规范 {分层}_{业务域}_{实体描述}_{刷新周期} 可直接
--   解析出分层与业务域，解析失败即「未分类」并自动成为治理项；
--   asset_category 只存人工覆盖与显示名。
-- ==================================================================

-- 资产分类表(分层/业务域，支持人工覆盖)
CREATE TABLE IF NOT EXISTS asset_category (
  id BIGINT AUTO_INCREMENT,
  category_code VARCHAR(64) NOT NULL,
  category_name VARCHAR(100) NOT NULL,
  parent_code VARCHAR(64),
  category_kind VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  remark VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_category_code ON asset_category (category_code);
CREATE INDEX IF NOT EXISTS idx_asset_category_kind ON asset_category (category_kind);
CREATE INDEX IF NOT EXISTS idx_asset_category_parent ON asset_category (parent_code);
COMMENT ON TABLE asset_category IS '资产分类表(分层/业务域，支持人工覆盖)';
COMMENT ON COLUMN asset_category.category_kind IS '分类维度(layer=数仓分层 domain=业务域)';

-- 资产收藏表
CREATE TABLE IF NOT EXISTS asset_bookmark (
  id BIGINT AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  asset_type VARCHAR(16) NOT NULL,
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_bookmark ON asset_bookmark (user_id, asset_type, asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_bookmark_user ON asset_bookmark (user_id);
CREATE INDEX IF NOT EXISTS idx_asset_bookmark_table ON asset_bookmark (table_metadata_id);
COMMENT ON TABLE asset_bookmark IS '资产收藏表';
COMMENT ON COLUMN asset_bookmark.asset_type IS '资产类型(table/column/indicator)';

-- 资产访问埋点表(仅追加)：高频写入的轻量表，刻意与 operation_audit_log 分离，
-- 避免浏览行为淹没真正的操作留痕
CREATE TABLE IF NOT EXISTS asset_access_log (
  id BIGINT AUTO_INCREMENT,
  user_id BIGINT,
  user_name VARCHAR(100),
  asset_type VARCHAR(16) NOT NULL,
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  access_type VARCHAR(16) NOT NULL,
  keyword VARCHAR(200),
  access_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_asset_access_asset ON asset_access_log (asset_type, asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_access_time ON asset_access_log (access_time);
CREATE INDEX IF NOT EXISTS idx_asset_access_user ON asset_access_log (user_id);
COMMENT ON TABLE asset_access_log IS '资产访问埋点表(仅追加，按日汇总)';
COMMENT ON COLUMN asset_access_log.access_type IS '访问类型(browse=浏览 read=读取 bookmark=收藏 query=带查询)';


-- ==================================================================
-- 资产目录模块（第二批 / 第三批）：标签、使用说明、使用热度
--   使用热度走独立的轻量表按日汇总(T+1)，不回写 operation_audit_log；
--   使用说明复用同一张 asset_doc + 版本历史，避免重复建设 markdown 与版本能力。
-- ==================================================================

-- 资产使用说明表(markdown，版本历史在 asset_doc_history)
CREATE TABLE IF NOT EXISTS asset_doc (
  id BIGINT AUTO_INCREMENT NOT NULL,
  table_metadata_id BIGINT NOT NULL,
  content CLOB,
  version INT NOT NULL DEFAULT 1,
  update_by VARCHAR(100),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_doc_table ON asset_doc (table_metadata_id);
COMMENT ON TABLE asset_doc IS '资产使用说明表(markdown，版本历史在 asset_doc_history)';
COMMENT ON COLUMN asset_doc.update_by IS '最后修改人';

-- 资产使用说明版本历史表
CREATE TABLE IF NOT EXISTS asset_doc_history (
  id BIGINT AUTO_INCREMENT NOT NULL,
  table_metadata_id BIGINT NOT NULL,
  version INT NOT NULL,
  content CLOB,
  update_by VARCHAR(100),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_asset_doc_history_table ON asset_doc_history (table_metadata_id);
COMMENT ON TABLE asset_doc_history IS '资产使用说明版本历史表';

-- 资产标签定义表
CREATE TABLE IF NOT EXISTS asset_tag (
  id BIGINT AUTO_INCREMENT NOT NULL,
  tag_code VARCHAR(64) NOT NULL,
  tag_name VARCHAR(100) NOT NULL,
  color VARCHAR(32),
  remark VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_tag_code ON asset_tag (tag_code);
COMMENT ON TABLE asset_tag IS '资产标签定义表';
COMMENT ON COLUMN asset_tag.color IS '标签颜色(前端色值，如 #1565c0)';

-- 资产标签关联表(支持表/字段/指标)
CREATE TABLE IF NOT EXISTS asset_tag_rel (
  id BIGINT AUTO_INCREMENT NOT NULL,
  tag_id BIGINT NOT NULL,
  asset_type VARCHAR(16) NOT NULL,
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_tag_rel ON asset_tag_rel (tag_id, asset_type, asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_tag_rel_asset ON asset_tag_rel (asset_type, asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_tag_rel_table ON asset_tag_rel (table_metadata_id);
COMMENT ON TABLE asset_tag_rel IS '资产标签关联表(支持表/字段/指标)';

-- 资产使用热度日汇总表(T+1，由 asset_access_log 汇总)
CREATE TABLE IF NOT EXISTS asset_usage_daily (
  id BIGINT AUTO_INCREMENT NOT NULL,
  asset_type VARCHAR(16) NOT NULL DEFAULT 'table',
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  stat_date DATE NOT NULL,
  browse_count INT NOT NULL DEFAULT 0,
  read_count INT NOT NULL DEFAULT 0,
  bookmark_count INT NOT NULL DEFAULT 0,
  relate_count INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_usage_daily ON asset_usage_daily (asset_type, asset_id, stat_date);
CREATE INDEX IF NOT EXISTS idx_asset_usage_table ON asset_usage_daily (table_metadata_id);
CREATE INDEX IF NOT EXISTS idx_asset_usage_date ON asset_usage_daily (stat_date);
COMMENT ON TABLE asset_usage_daily IS '资产使用热度日汇总表(T+1，由 asset_access_log 汇总)';
COMMENT ON COLUMN asset_usage_daily.stat_date IS '统计日(按日汇总，T+1 生成)';
COMMENT ON COLUMN asset_usage_daily.relate_count IS '被关联数(引用该资产的规则/指标等)';

-- 资产目录日快照表(T+1，一行=一张表×一个统计日)
CREATE TABLE IF NOT EXISTS asset_catalog_daily (
  id BIGINT AUTO_INCREMENT NOT NULL,
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
  convention_parsed TINYINT NOT NULL DEFAULT 0,
  refresh_cycle VARCHAR(8),
  category_code VARCHAR(64),
  business_owner VARCHAR(255),
  tech_owner VARCHAR(255),
  topic VARCHAR(255),
  visibility VARCHAR(20),
  asset_level VARCHAR(8),
  row_count BIGINT,
  size_bytes BIGINT,
  column_count INT NOT NULL DEFAULT 0,
  tag_count INT NOT NULL DEFAULT 0,
  indicator_count INT NOT NULL DEFAULT 0,
  quality_rule_count INT NOT NULL DEFAULT 0,
  has_doc TINYINT NOT NULL DEFAULT 0,
  health_issue_count INT NOT NULL DEFAULT 0,
  health_issue_codes VARCHAR(500),
  browse_count_30d INT NOT NULL DEFAULT 0,
  read_count_30d INT NOT NULL DEFAULT 0,
  bookmark_count INT NOT NULL DEFAULT 0,
  last_access_time TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_catalog_daily ON asset_catalog_daily (stat_date, table_metadata_id);
CREATE INDEX IF NOT EXISTS idx_asset_catalog_daily_layer ON asset_catalog_daily (stat_date, layer_code);
CREATE INDEX IF NOT EXISTS idx_asset_catalog_daily_domain ON asset_catalog_daily (stat_date, domain_code);
CREATE INDEX IF NOT EXISTS idx_asset_catalog_daily_ds ON asset_catalog_daily (stat_date, datasource_id);
COMMENT ON TABLE asset_catalog_daily IS '资产目录日快照表(T+1，一行=一张表×一个统计日)';
COMMENT ON COLUMN asset_catalog_daily.stat_date IS '统计日';
COMMENT ON COLUMN asset_catalog_daily.layer_code IS '命名规范解析出的分层编码(未解析为NULL)';
COMMENT ON COLUMN asset_catalog_daily.domain_code IS '命名规范解析出的业务域编码(未解析为NULL)';
COMMENT ON COLUMN asset_catalog_daily.health_issue_codes IS '治理项编码集合(逗号分隔)';

-- 调度任务运行日汇总表(T+1，由 DolphinScheduler 采集)
CREATE TABLE IF NOT EXISTS scheduler_task_run_daily (
  id BIGINT AUTO_INCREMENT NOT NULL,
  stat_date DATE NOT NULL,
  project_code VARCHAR(100) NOT NULL DEFAULT '',
  project_name VARCHAR(200),
  workflow_code BIGINT,
  workflow_name VARCHAR(200) NOT NULL DEFAULT '',
  node_name VARCHAR(200) NOT NULL DEFAULT '',
  task_type VARCHAR(40) NOT NULL DEFAULT '',
  task_run_count INT NOT NULL DEFAULT 0,
  delayed_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  total_duration_seconds BIGINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheduler_task_run_daily ON scheduler_task_run_daily (stat_date, project_code, workflow_name, node_name, task_type);
CREATE INDEX IF NOT EXISTS idx_scheduler_task_run_date ON scheduler_task_run_daily (stat_date);
CREATE INDEX IF NOT EXISTS idx_scheduler_task_run_project ON scheduler_task_run_daily (stat_date, project_code);
COMMENT ON TABLE scheduler_task_run_daily IS '调度任务运行日汇总表(T+1，由 DolphinScheduler 采集)';
COMMENT ON COLUMN scheduler_task_run_daily.stat_date IS '统计日(任务实际开始日)';
COMMENT ON COLUMN scheduler_task_run_daily.delayed_count IS '延迟实例数(实际开始晚于计划开始)';

-- 表字段元数据表
CREATE TABLE IF NOT EXISTS column_metadata (
  id BIGINT AUTO_INCREMENT,
  table_metadata_id BIGINT NOT NULL,
  storage_engine VARCHAR(20),
  database_name VARCHAR(100),
  table_name VARCHAR(200),
  column_name VARCHAR(200) NOT NULL,
  column_type VARCHAR(100) NOT NULL,
  column_comment VARCHAR(1000),
  is_partition TINYINT NOT NULL DEFAULT 0,
  is_primary_key TINYINT NOT NULL DEFAULT 0,
  ordinal_position INT NOT NULL DEFAULT 0,
  nullable TINYINT NOT NULL DEFAULT 1,
  default_value VARCHAR(500),
  business_desc VARCHAR(1000),
  security_level VARCHAR(16),
  hot_score TINYINT NOT NULL DEFAULT 0,
  sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_column_storage ON column_metadata (storage_engine, database_name, table_name, column_name);
CREATE INDEX IF NOT EXISTS idx_column_metadata_table_metadata_id ON column_metadata (table_metadata_id);
CREATE INDEX IF NOT EXISTS idx_column_metadata_column_name ON column_metadata (column_name);
COMMENT ON TABLE column_metadata IS '表字段元数据表';
COMMENT ON COLUMN column_metadata.business_desc IS '业务描述(与 column_comment 的技术注释区分，供资产目录展示)';
COMMENT ON COLUMN column_metadata.security_level IS '字段安全等级';
COMMENT ON COLUMN column_metadata.hot_score IS '字段热度星级(0~5，由使用记录日汇总回写)';

-- 数据质量校验结果表
CREATE TABLE IF NOT EXISTS data_quality_check_result (
  id BIGINT AUTO_INCREMENT,
  datasource_id BIGINT,
  datasource_name VARCHAR(100) NOT NULL,
  database_name VARCHAR(100),
  table_name VARCHAR(200) NOT NULL,
  check_item VARCHAR(200) NOT NULL,
  check_time TIMESTAMP NOT NULL,
  check_record_count BIGINT NOT NULL DEFAULT 0,
  fail_record_count BIGINT NOT NULL DEFAULT 0,
  fail_detail CLOB,
  rule_type VARCHAR(64),
  rule_name VARCHAR(200),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dqc_datasource_id ON data_quality_check_result (datasource_id);
CREATE INDEX IF NOT EXISTS idx_dqc_table ON data_quality_check_result (database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_dqc_check_time ON data_quality_check_result (check_time);
CREATE INDEX IF NOT EXISTS idx_dqc_rule_type ON data_quality_check_result (rule_type);
COMMENT ON TABLE data_quality_check_result IS '数据质量校验结果表';
COMMENT ON COLUMN data_quality_check_result.check_item IS '检查项(自由文本，历史字段；新链路请用 rule_type/rule_name)';
COMMENT ON COLUMN data_quality_check_result.rule_type IS '规则类型(7类之一)，历史数据为空表示未分类';
COMMENT ON COLUMN data_quality_check_result.rule_name IS '规则名';

-- 数据源配置表
CREATE TABLE IF NOT EXISTS datasource_config (
  id BIGINT AUTO_INCREMENT,
  datasource_name VARCHAR(100) NOT NULL,
  datasource_type VARCHAR(50) NOT NULL,
  connection_type VARCHAR(20) DEFAULT 'jdbc',
  storage_engine VARCHAR(20),
  url VARCHAR(500) NOT NULL,
  database_name VARCHAR(500),
  username VARCHAR(100),
  password VARCHAR(255),
  extra_params CLOB,
  connection_status TINYINT NOT NULL DEFAULT 0,
  last_test_time TIMESTAMP,
  description VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_datasource_config_type ON datasource_config (datasource_type);
CREATE INDEX IF NOT EXISTS idx_datasource_config_engine ON datasource_config (storage_engine);
CREATE INDEX IF NOT EXISTS idx_datasource_config_conn_type ON datasource_config (connection_type);
COMMENT ON TABLE datasource_config IS '数据源配置表';

-- DDL执行日志表
CREATE TABLE IF NOT EXISTS ddl_execution_log (
  id BIGINT AUTO_INCREMENT,
  datasource_id BIGINT NOT NULL,
  database_name VARCHAR(100),
  sql_content CLOB NOT NULL,
  sql_type VARCHAR(20) NOT NULL,
  object_type VARCHAR(20) DEFAULT 'TABLE',
  object_name VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'submitted',
  affected_rows INT,
  error_message CLOB,
  execution_time_ms INT,
  metadata_updated TINYINT NOT NULL DEFAULT 0,
  execute_by BIGINT,
  execute_by_name VARCHAR(50),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ddl_execution_log_datasource_id ON ddl_execution_log (datasource_id);
CREATE INDEX IF NOT EXISTS idx_ddl_execution_log_sql_type ON ddl_execution_log (sql_type);
CREATE INDEX IF NOT EXISTS idx_ddl_execution_log_status ON ddl_execution_log (status);
CREATE INDEX IF NOT EXISTS idx_ddl_execution_log_create_time ON ddl_execution_log (create_time);
COMMENT ON TABLE ddl_execution_log IS 'DDL执行日志表';

-- JAR包依赖配置表
CREATE TABLE IF NOT EXISTS dev_jar_dependency (
  id BIGINT AUTO_INCREMENT,
  jar_name VARCHAR(200) NOT NULL,
  group_id VARCHAR(200),
  artifact_id VARCHAR(200),
  version VARCHAR(50),
  scope VARCHAR(20) NOT NULL DEFAULT 'compile',
  source VARCHAR(20) NOT NULL DEFAULT 'system',
  jar_path VARCHAR(500),
  repository_url VARCHAR(500),
  file_size BIGINT,
  checksum VARCHAR(64),
  status TINYINT NOT NULL DEFAULT 1,
  description VARCHAR(500),
  category VARCHAR(100),
  program_type VARCHAR(20) NOT NULL DEFAULT 'flink',
  auto_choose TINYINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dev_jar_dependency_source ON dev_jar_dependency (source);
CREATE INDEX IF NOT EXISTS idx_dev_jar_dependency_scope ON dev_jar_dependency (scope);
COMMENT ON TABLE dev_jar_dependency IS 'JAR包依赖配置表';

-- 程序运行参数表
CREATE TABLE IF NOT EXISTS dev_program_params (
  project_id BIGINT NOT NULL,
  file_path VARCHAR(500) NOT NULL,
  engine VARCHAR(50),
  datasources CLOB,
  jar_dependency_catalogs CLOB,
  run_params CLOB,
  compute_resources CLOB,
  remark VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, file_path)
);
COMMENT ON TABLE dev_program_params IS '程序运行参数表';

-- 开发项目管理表
CREATE TABLE IF NOT EXISTS dev_project (
  id BIGINT AUTO_INCREMENT,
  project_name VARCHAR(200) NOT NULL,
  project_code VARCHAR(100) NOT NULL,
  description CLOB,
  owner_id BIGINT,
  owner_name VARCHAR(50),
  default_datasource_id BIGINT,
  status TINYINT NOT NULL DEFAULT 1,
  script_count INT NOT NULL DEFAULT 0,
  scheduled_count INT NOT NULL DEFAULT 0,
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_code ON dev_project (project_code);
CREATE INDEX IF NOT EXISTS idx_dev_project_owner_id ON dev_project (owner_id);
CREATE INDEX IF NOT EXISTS idx_dev_project_status ON dev_project (status);
COMMENT ON TABLE dev_project IS '开发项目管理表';

-- 脚本执行历史表
CREATE TABLE IF NOT EXISTS dev_script_execution (
  id BIGINT AUTO_INCREMENT,
  script_id BIGINT NOT NULL,
  execution_id VARCHAR(64) NOT NULL,
  script_type VARCHAR(50) NOT NULL,
  execute_mode VARCHAR(20) NOT NULL DEFAULT 'debug',
  engine_id BIGINT,
  datasource_id BIGINT,
  status VARCHAR(20) NOT NULL DEFAULT 'submitted',
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  duration_ms INT,
  input_params JSON,
  output_log CLOB,
  error_message CLOB,
  affected_rows INT,
  result_data JSON,
  submit_by BIGINT,
  submit_by_name VARCHAR(50),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dev_script_execution_script_id ON dev_script_execution (script_id);
CREATE INDEX IF NOT EXISTS idx_dev_script_execution_execution_id ON dev_script_execution (execution_id);
CREATE INDEX IF NOT EXISTS idx_dev_script_execution_status ON dev_script_execution (status);
CREATE INDEX IF NOT EXISTS idx_dev_script_execution_start_time ON dev_script_execution (start_time);
CREATE INDEX IF NOT EXISTS idx_dev_script_execution_execute_mode ON dev_script_execution (execute_mode);
COMMENT ON TABLE dev_script_execution IS '脚本执行历史表';

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
CREATE TABLE IF NOT EXISTS dq_rule_template (
  id BIGINT AUTO_INCREMENT,
  template_code VARCHAR(64) NOT NULL,
  template_name VARCHAR(200) NOT NULL,
  rule_type VARCHAR(64) NOT NULL,
  template_kind VARCHAR(16) NOT NULL DEFAULT 'custom',
  sql_template CLOB,
  field_types VARCHAR(255),
  param_schema CLOB,
  remark VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dq_template_code ON dq_rule_template (template_code);
CREATE INDEX IF NOT EXISTS idx_dq_template_rule_type ON dq_rule_template (rule_type);
COMMENT ON TABLE dq_rule_template IS '数据质量规则模板表';
COMMENT ON COLUMN dq_rule_template.rule_type IS '规则类型(7类之一:PRIMARY_KEY_UNIQUE/NOT_NULL/VALUE_RANGE/ROW_COUNT_FLUCTUATION/ROW_COUNT_MOM/UPSTREAM_DOWNSTREAM_RECON/METRIC_CONSISTENCY)';
COMMENT ON COLUMN dq_rule_template.template_kind IS '模板类别(builtin=内置 custom=自定义)';

-- 监控规则表：模板的具体应用，含校验对象、阈值与处置方式。
--   dispose_mode=BLOCK 即「阻断下游」，落地方式是让质检任务自身跑失败，
--   DolphinScheduler 的下游节点便不会触发（不依赖任何「反向置为失败」接口）
CREATE TABLE IF NOT EXISTS dq_rule (
  id BIGINT AUTO_INCREMENT,
  rule_name VARCHAR(200) NOT NULL,
  rule_type VARCHAR(64) NOT NULL,
  template_id BIGINT,
  template_code VARCHAR(64),
  datasource_id BIGINT,
  datasource_name VARCHAR(100),
  database_name VARCHAR(100),
  table_name VARCHAR(200) NOT NULL,
  column_name VARCHAR(200),
  partition_expr VARCHAR(255),
  ref_datasource_id BIGINT,
  ref_database_name VARCHAR(100),
  ref_table_name VARCHAR(200),
  ref_column_name VARCHAR(200),
  compare_mode VARCHAR(8) NOT NULL DEFAULT 'EQ',
  expected_value VARCHAR(255),
  warn_threshold VARCHAR(255),
  error_threshold VARCHAR(255),
  dispose_mode VARCHAR(8) NOT NULL DEFAULT 'ALERT',
  params CLOB,
  owner_name VARCHAR(100),
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_rule_table ON dq_rule (database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_dq_rule_type ON dq_rule (rule_type);
CREATE INDEX IF NOT EXISTS idx_dq_rule_template ON dq_rule (template_id);
CREATE INDEX IF NOT EXISTS idx_dq_rule_datasource ON dq_rule (datasource_id);
COMMENT ON TABLE dq_rule IS '数据质量监控规则表';
COMMENT ON COLUMN dq_rule.compare_mode IS '比较方式(EQ/NE/GT/GTE/LT/LTE)';
COMMENT ON COLUMN dq_rule.params IS '自定义模板占位符取值(JSON 对象)；内置模板按规则类型使用固定参数名';
COMMENT ON COLUMN dq_rule.dispose_mode IS '处置方式(BLOCK=阻断下游 ALERT=告警)，与提示词 S2 的「处置方式」同义';
COMMENT ON COLUMN dq_rule.owner_name IS '规则负责人';
COMMENT ON COLUMN dq_rule.warn_threshold IS '橙色(警告)阈值，空表示不设';
COMMENT ON COLUMN dq_rule.error_threshold IS '红色(异常)阈值，空表示不设';

-- 运行实例表：一次质检任务执行(或一次调度批次)的汇总
CREATE TABLE IF NOT EXISTS dq_run_instance (
  id BIGINT AUTO_INCREMENT,
  task_id BIGINT,
  task_name VARCHAR(200),
  batch_no VARCHAR(64) NOT NULL,
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  schedule_instance_id BIGINT,
  target_datasource_id BIGINT,
  target_database_name VARCHAR(100),
  target_table_name VARCHAR(200),
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  check_record_count BIGINT NOT NULL DEFAULT 0,
  fail_record_count BIGINT NOT NULL DEFAULT 0,
  pass_count INT NOT NULL DEFAULT 0,
  warn_count INT NOT NULL DEFAULT 0,
  error_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_run_task ON dq_run_instance (task_id);
CREATE INDEX IF NOT EXISTS idx_dq_run_batch ON dq_run_instance (batch_no);
CREATE INDEX IF NOT EXISTS idx_dq_run_table ON dq_run_instance (target_database_name, target_table_name);
CREATE INDEX IF NOT EXISTS idx_dq_run_status ON dq_run_instance (status);
CREATE INDEX IF NOT EXISTS idx_dq_run_start_time ON dq_run_instance (start_time);
COMMENT ON TABLE dq_run_instance IS '数据质量运行实例表';
COMMENT ON COLUMN dq_run_instance.trigger_type IS '触发方式(SCHEDULE=调度触发 MANUAL=手动触发)';
COMMENT ON COLUMN dq_run_instance.schedule_instance_id IS '调度触发时对应的调度实例号，用于跳转调度实例详情';
COMMENT ON COLUMN dq_run_instance.failed_count IS 'SQL 未跑通的规则数，与「校验不通过」区分';

-- 规则级校验结果表：对应提示词「校验输出须可定位：规则名/校验对象/阈值/实际值/是否通过」
CREATE TABLE IF NOT EXISTS dq_rule_result (
  id BIGINT AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  rule_id BIGINT,
  rule_name VARCHAR(200) NOT NULL,
  rule_type VARCHAR(64) NOT NULL,
  check_object VARCHAR(500),
  threshold VARCHAR(255),
  actual_value VARCHAR(500),
  metric_value VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'PASS',
  severity VARCHAR(16),
  exec_sql CLOB,
  error_msg VARCHAR(1000),
  cost_ms BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_result_run ON dq_rule_result (run_id);
CREATE INDEX IF NOT EXISTS idx_dq_result_rule ON dq_rule_result (rule_id);
CREATE INDEX IF NOT EXISTS idx_dq_result_status ON dq_rule_result (status);
COMMENT ON TABLE dq_rule_result IS '数据质量规则级校验结果表';
COMMENT ON COLUMN dq_rule_result.rule_name IS '规则名(结果自包含，规则被删后仍可追溯)';
COMMENT ON COLUMN dq_rule_result.status IS '校验状态(PASS=通过 WARN=橙色异常 ERROR=红色异常 FAILED=校验失败)';
COMMENT ON COLUMN dq_rule_result.check_object IS '校验对象(表.字段 或 上下游两表)';
COMMENT ON COLUMN dq_rule_result.metric_value IS '原始度量值(行数类=当期行数 跨表比对=主表值,对照值)；仅存百分比无法回推历史基线';


-- ==================================================================
-- 数据质量模块（第二批 / 第三批）：质检任务、任务与规则关联、告警订阅与发送记录
--   阻断下游不做「平台反向置调度节点失败」：质检任务作为 DAG 节点，
--   校验不通过即非零退出，下游自然不执行。告警第一期只落发送记录。
-- ==================================================================

-- 数据质量告警发送记录表
CREATE TABLE IF NOT EXISTS dq_alert_log (
  id BIGINT AUTO_INCREMENT NOT NULL,
  run_id BIGINT,
  task_id BIGINT,
  rule_id BIGINT,
  rule_name VARCHAR(200),
  channel VARCHAR(32) NOT NULL,
  receiver VARCHAR(200),
  status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
  fail_reason VARCHAR(500),
  send_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_alert_log_run ON dq_alert_log (run_id);
CREATE INDEX IF NOT EXISTS idx_dq_alert_log_task ON dq_alert_log (task_id);
CREATE INDEX IF NOT EXISTS idx_dq_alert_log_time ON dq_alert_log (send_time);
COMMENT ON TABLE dq_alert_log IS '数据质量告警发送记录表';
COMMENT ON COLUMN dq_alert_log.channel IS '告警渠道(site=站内 webhook=回调 email=邮件)，第一期只落记录';
COMMENT ON COLUMN dq_alert_log.status IS '发送状态(SUCCESS/FAILED)';

-- 数据质量告警订阅表(按任务订阅，命中指定结果状态时告警)
CREATE TABLE IF NOT EXISTS dq_alert_subscription (
  id BIGINT AUTO_INCREMENT NOT NULL,
  task_id BIGINT NOT NULL,
  notify_on VARCHAR(64) NOT NULL DEFAULT 'ERROR,FAILED',
  channels VARCHAR(255),
  receivers VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_alert_sub_task ON dq_alert_subscription (task_id);
COMMENT ON TABLE dq_alert_subscription IS '数据质量告警订阅表(按任务订阅，命中指定结果状态时告警)';
COMMENT ON COLUMN dq_alert_subscription.notify_on IS '触发告警的结果状态集合，逗号分隔，如 ERROR,FAILED (PASS/WARN/ERROR/FAILED)';

-- 数据质量质检任务表(规则集的执行计划)
CREATE TABLE IF NOT EXISTS dq_task (
  id BIGINT AUTO_INCREMENT NOT NULL,
  task_name VARCHAR(200) NOT NULL,
  owner_name VARCHAR(100),
  description VARCHAR(500),
  datasource_id BIGINT,
  datasource_name VARCHAR(100),
  database_name VARCHAR(100),
  table_name VARCHAR(200),
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  project_code VARCHAR(200),
  workflow_code BIGINT,
  workflow_name VARCHAR(200),
  node_name VARCHAR(200),
  dispose_policy VARCHAR(8) NOT NULL DEFAULT 'ALERT',
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_task_table ON dq_task (database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_dq_task_trigger ON dq_task (trigger_type);
COMMENT ON TABLE dq_task IS '数据质量质检任务表(规则集的执行计划)';
COMMENT ON COLUMN dq_task.dispose_policy IS '任务级默认处置策略(BLOCK=阻断下游 ALERT=告警)，规则未单独设置时生效';
COMMENT ON COLUMN dq_task.node_name IS '关联的调度节点名；阻断由该节点非零退出实现，平台不做反向置失败';

-- 数据质量质检任务与规则的关联表
CREATE TABLE IF NOT EXISTS dq_task_rule_rel (
  id BIGINT AUTO_INCREMENT NOT NULL,
  task_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dq_task_rule ON dq_task_rule_rel (task_id, rule_id);
CREATE INDEX IF NOT EXISTS idx_dq_task_rule_rule ON dq_task_rule_rel (rule_id);
COMMENT ON TABLE dq_task_rule_rel IS '数据质量质检任务与规则的关联表';

-- 指标定义主表
CREATE TABLE IF NOT EXISTS indicators (
  id BIGINT AUTO_INCREMENT,
  project_code VARCHAR(200) NOT NULL,
  topic VARCHAR(200) NOT NULL,
  name VARCHAR(200) NOT NULL,
  code VARCHAR(1024) NOT NULL,
  business_definition CLOB,
  calc_expr VARCHAR(1024) NOT NULL,
  default_filter CLOB,
  alert_config JSON,
  business_owner VARCHAR(1024),
  tech_owner VARCHAR(1024),
  refresh_interval INT,
  refresh_unit VARCHAR(10),
  refresh_desc VARCHAR(10),
  visibility VARCHAR(20) DEFAULT 'internal',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_indicators_visibility CHECK (visibility IN ('public','internal','restricted'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_code ON indicators (code);
COMMENT ON TABLE indicators IS '指标定义主表';

-- 指标相关字段
CREATE TABLE IF NOT EXISTS indicators_fields (
  id BIGINT AUTO_INCREMENT,
  indicators_id BIGINT NOT NULL,
  datasource_name VARCHAR(200) NOT NULL,
  db_name VARCHAR(200) NOT NULL,
  tbl_name VARCHAR(200) NOT NULL,
  field_name VARCHAR(200) NOT NULL,
  field_type VARCHAR(200) NOT NULL,
  PRIMARY KEY (id)
);
COMMENT ON TABLE indicators_fields IS '指标相关字段';

-- 知识文档表
CREATE TABLE IF NOT EXISTS knowledge_document (
  id BIGINT AUTO_INCREMENT,
  title VARCHAR(200) NOT NULL,
  doc_type VARCHAR(30) NOT NULL,
  category VARCHAR(100),
  content CLOB,
  author_name VARCHAR(50),
  author_id BIGINT,
  status TINYINT NOT NULL DEFAULT 0,
  sync_to_rag TINYINT NOT NULL DEFAULT 0,
  view_count INT NOT NULL DEFAULT 0,
  tags VARCHAR(500),
  description VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_doc_type ON knowledge_document (doc_type);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_status ON knowledge_document (status);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_author_id ON knowledge_document (author_id);
COMMENT ON TABLE knowledge_document IS '知识文档表';

-- 审计日志表（H2 不支持分区，普通表）
CREATE TABLE IF NOT EXISTS operation_audit_log (
  id BIGINT AUTO_INCREMENT,
  user_id BIGINT,
  username VARCHAR(50),
  tenant_id BIGINT,
  tenant_name VARCHAR(100),
  module VARCHAR(50) NOT NULL,
  operation VARCHAR(100) NOT NULL,
  operation_type VARCHAR(20),
  target_type VARCHAR(50),
  target_id BIGINT,
  target_name VARCHAR(200),
  request_method VARCHAR(10),
  request_url VARCHAR(500),
  request_params CLOB,
  response_body CLOB,
  response_status INT,
  error_message CLOB,
  exception_class VARCHAR(200),
  ip_address VARCHAR(50),
  user_agent VARCHAR(500),
  operation_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  duration_ms INT,
  is_sensitive TINYINT DEFAULT 0,
  is_ai_request TINYINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, operation_time)
);
CREATE INDEX IF NOT EXISTS idx_operation_audit_log_user_id ON operation_audit_log (user_id);
CREATE INDEX IF NOT EXISTS idx_operation_audit_log_tenant_id ON operation_audit_log (tenant_id);
CREATE INDEX IF NOT EXISTS idx_operation_audit_log_module ON operation_audit_log (module);
CREATE INDEX IF NOT EXISTS idx_operation_audit_log_operation_type ON operation_audit_log (operation_type);
COMMENT ON TABLE operation_audit_log IS '审计日志表';

-- 存储配置表
CREATE TABLE IF NOT EXISTS storage_config (
  id BIGINT AUTO_INCREMENT,
  storage_name VARCHAR(100) NOT NULL,
  storage_type VARCHAR(20) NOT NULL,
  endpoint VARCHAR(255),
  bucket_name VARCHAR(100),
  access_key VARCHAR(255),
  secret_key VARCHAR(255),
  root_path VARCHAR(255) NOT NULL DEFAULT '/',
  local_mount_path VARCHAR(500),
  extra_params CLOB,
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
COMMENT ON TABLE storage_config IS '存储配置表';

-- 文件路径权限表
CREATE TABLE IF NOT EXISTS sys_file_permission (
  id BIGINT AUTO_INCREMENT,
  storage_config_id BIGINT NOT NULL,
  permission_name VARCHAR(100) NOT NULL,
  file_path VARCHAR(1000) NOT NULL,
  permission_type TINYINT NOT NULL DEFAULT 1,
  recursive TINYINT NOT NULL DEFAULT 0,
  description VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_file_permission_storage_config_id ON sys_file_permission (storage_config_id);
COMMENT ON TABLE sys_file_permission IS '文件路径权限表';

-- 菜单/页面权限表
CREATE TABLE IF NOT EXISTS sys_menu_permission (
  id BIGINT AUTO_INCREMENT,
  parent_id BIGINT NOT NULL DEFAULT 0,
  menu_name VARCHAR(100) NOT NULL,
  menu_code VARCHAR(100) NOT NULL,
  path VARCHAR(255),
  component VARCHAR(255),
  icon VARCHAR(100),
  sort_order INT NOT NULL DEFAULT 0,
  visible TINYINT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_menu_code ON sys_menu_permission (menu_code);
CREATE INDEX IF NOT EXISTS idx_sys_menu_permission_parent_id ON sys_menu_permission (parent_id);
COMMENT ON TABLE sys_menu_permission IS '菜单/页面权限表';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT AUTO_INCREMENT,
  role_name VARCHAR(50) NOT NULL,
  role_code VARCHAR(50) NOT NULL,
  role_type VARCHAR(30) NOT NULL DEFAULT 'VIEWER',
  description VARCHAR(255),
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_code ON sys_role (role_code);
COMMENT ON TABLE sys_role IS '角色表';

-- 角色-文件权限关联表
CREATE TABLE IF NOT EXISTS sys_role_file_permission (
  id BIGINT AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  file_permission_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_file ON sys_role_file_permission (role_id, file_permission_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_file_permission_role_id ON sys_role_file_permission (role_id);
COMMENT ON TABLE sys_role_file_permission IS '角色-文件权限关联表';

-- 角色-菜单权限关联表
CREATE TABLE IF NOT EXISTS sys_role_menu_permission (
  id BIGINT AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  menu_permission_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_menu ON sys_role_menu_permission (role_id, menu_permission_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_menu_permission_role_id ON sys_role_menu_permission (role_id);
COMMENT ON TABLE sys_role_menu_permission IS '角色-菜单权限关联表';

-- 角色项目权限关联表
CREATE TABLE IF NOT EXISTS sys_role_project_permission (
  id BIGINT AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  permission_type INT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_project ON sys_role_project_permission (role_id, project_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_project_permission_role_id ON sys_role_project_permission (role_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_project_permission_project_id ON sys_role_project_permission (project_id);
COMMENT ON TABLE sys_role_project_permission IS '角色项目权限关联表';

-- 角色-库表权限关联表
CREATE TABLE IF NOT EXISTS sys_role_table_permission (
  id BIGINT AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  table_permission_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_table ON sys_role_table_permission (role_id, table_permission_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_table_permission_role_id ON sys_role_table_permission (role_id);
COMMENT ON TABLE sys_role_table_permission IS '角色-库表权限关联表';

-- 库表CRUD权限表
CREATE TABLE IF NOT EXISTS sys_table_permission (
  id BIGINT AUTO_INCREMENT,
  permission_name VARCHAR(100) NOT NULL,
  datasource_name VARCHAR(255),
  database_name VARCHAR(255),
  table_names CLOB,
  permission_scope TINYINT NOT NULL DEFAULT 1,
  can_select TINYINT NOT NULL DEFAULT 1,
  can_insert TINYINT NOT NULL DEFAULT 0,
  can_update TINYINT NOT NULL DEFAULT 0,
  can_delete TINYINT NOT NULL DEFAULT 0,
  can_export TINYINT NOT NULL DEFAULT 0,
  row_filter_sql CLOB,
  data_masking_rule VARCHAR(500),
  description VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_table_permission_scope ON sys_table_permission (permission_scope);
COMMENT ON TABLE sys_table_permission IS '库表CRUD权限表';

-- 库表权限-列级白名单（指定列）
CREATE TABLE IF NOT EXISTS sys_table_permission_column (
  id BIGINT AUTO_INCREMENT,
  table_permission_id BIGINT NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  column_name VARCHAR(255) NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_table_permission_column_perm ON sys_table_permission_column (table_permission_id);
COMMENT ON TABLE sys_table_permission_column IS '库表权限-指定列白名单';

-- 库表权限-行级过滤（指定行）
CREATE TABLE IF NOT EXISTS sys_table_permission_row (
  id BIGINT AUTO_INCREMENT,
  table_permission_id BIGINT NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  row_filter_sql CLOB,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_table_permission_row_perm ON sys_table_permission_row (table_permission_id);
COMMENT ON TABLE sys_table_permission_row IS '库表权限-行级过滤SQL';

-- 租户表
CREATE TABLE IF NOT EXISTS sys_tenant (
  id BIGINT AUTO_INCREMENT,
  tenant_name VARCHAR(100) NOT NULL,
  tenant_code VARCHAR(50) NOT NULL,
  contact_name VARCHAR(50),
  contact_phone VARCHAR(20),
  contact_email VARCHAR(100),
  description VARCHAR(500),
  status INT DEFAULT 1,
  owner_id BIGINT,
  password VARCHAR(200),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted INT DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_code ON sys_tenant (tenant_code);
COMMENT ON TABLE sys_tenant IS '租户表';

-- 租户角色关联表
CREATE TABLE IF NOT EXISTS sys_tenant_role (
  id BIGINT AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_role ON sys_tenant_role (tenant_id, role_id);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_role_tenant_id ON sys_tenant_role (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_role_role_id ON sys_tenant_role (role_id);
COMMENT ON TABLE sys_tenant_role IS '租户角色关联表';

-- 租户用户关联表
CREATE TABLE IF NOT EXISTS sys_tenant_user (
  id BIGINT AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_id ON sys_tenant_user (tenant_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_id ON sys_tenant_user (user_id);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_user_tenant_id ON sys_tenant_user (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_user_user_id ON sys_tenant_user (user_id);
COMMENT ON TABLE sys_tenant_user IS '租户用户关联表';

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  password VARCHAR(255) NOT NULL,
  real_name VARCHAR(50),
  email VARCHAR(100),
  phone VARCHAR(20),
  avatar VARCHAR(255),
  status TINYINT NOT NULL DEFAULT 1,
  is_admin TINYINT DEFAULT 0,
  last_login_time TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_username ON sys_user (username);
COMMENT ON TABLE sys_user IS '用户表';

-- 库表元数据表
CREATE TABLE IF NOT EXISTS table_metadata (
  id BIGINT AUTO_INCREMENT,
  datasource_id BIGINT NOT NULL,
  storage_engine VARCHAR(20),
  catalog_name VARCHAR(100),
  database_name VARCHAR(100) NOT NULL,
  table_name VARCHAR(200) NOT NULL,
  table_type VARCHAR(50),
  table_comment VARCHAR(1000),
  table_create_time TIMESTAMP,
  last_modify_time TIMESTAMP,
  partition_columns JSON,
  storage_format VARCHAR(200),
  compression VARCHAR(50),
  location VARCHAR(500),
  row_count BIGINT,
  size_bytes BIGINT,
  hot_days INT NOT NULL DEFAULT 90,
  cold_days INT NOT NULL DEFAULT 180,
  keep_days INT NOT NULL DEFAULT 720,
  sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  table_fingerprint VARCHAR(64),
  columns_fingerprint VARCHAR(64),
  business_owner VARCHAR(255),
  tech_owner VARCHAR(255),
  topic VARCHAR(200),
  visibility VARCHAR(20) NOT NULL DEFAULT 'internal',
  category_code VARCHAR(64),
  asset_level VARCHAR(16),
  browse_cnt_30d INT NOT NULL DEFAULT 0,
  read_cnt_30d INT NOT NULL DEFAULT 0,
  bookmark_cnt INT NOT NULL DEFAULT 0,
  last_access_time TIMESTAMP,
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT chk_table_metadata_visibility CHECK (visibility IN ('public','internal','restricted'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_storage ON table_metadata (storage_engine, database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_table_metadata_database_name ON table_metadata (database_name);
CREATE INDEX IF NOT EXISTS idx_table_metadata_table_name ON table_metadata (table_name);
CREATE INDEX IF NOT EXISTS idx_table_metadata_owner ON table_metadata (business_owner);
CREATE INDEX IF NOT EXISTS idx_table_metadata_category ON table_metadata (category_code);
COMMENT ON TABLE table_metadata IS '库表元数据表';
COMMENT ON COLUMN table_metadata.business_owner IS '业务负责人(语义对齐 indicators.business_owner；本表按负责人筛选，需可建索引，故宽度取 255)';
COMMENT ON COLUMN table_metadata.tech_owner IS '技术负责人(同上)';
COMMENT ON COLUMN table_metadata.topic IS '所属主题域(对齐 indicators.topic)';
COMMENT ON COLUMN table_metadata.visibility IS '可见性(public/internal/restricted，对齐 indicators.visibility)';
COMMENT ON COLUMN table_metadata.category_code IS '人工覆盖的分类编码；为空表示采用表名规范解析结果';
COMMENT ON COLUMN table_metadata.asset_level IS '资产等级(L1~L5)';
COMMENT ON COLUMN table_metadata.browse_cnt_30d IS '近30日浏览数(由 asset_access_log 日汇总回写)';
COMMENT ON COLUMN table_metadata.read_cnt_30d IS '近30日读取数(由 asset_access_log 日汇总回写)';
COMMENT ON COLUMN table_metadata.bookmark_cnt IS '收藏数(由 asset_bookmark 回写)';
COMMENT ON COLUMN table_metadata.last_access_time IS '最近访问时间(埋点回写，用于热度排序)';

-- ------------------------------------------------------------------
-- ------------------------------------------------------------------
-- 基础初始化数据（仅 admin 用户与 SUPER_ADMIN 角色）
--    测试数据（test 用户/角色/菜单/租户/数据源/授权）见 testdata/
-- ------------------------------------------------------------------

INSERT INTO sys_user (id, username, password, real_name, email, phone, status, is_admin, last_login_time, create_time, update_time, deleted) VALUES
(1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员','admin@example.com','13800000000',1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05',0);

INSERT INTO sys_role (id, role_name, role_code, role_type, description, status, create_time, update_time, deleted) VALUES
(1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03',0);

-- ------------------------------------------------------------------
-- 平台级参考数据（非测试数据，任何部署都需要，故放在本脚本而非 testdata）
--   1) 数据质量 7 类内置规则模板：与智能体提示词、前端 types/quality.ts 同源，
--      template_code 即规则类型编码；三处必须同时演进，禁止自造同义词。
--   2) 资产目录数仓分层：与提示词「分层规范」一致（ODS/DWD/DWS/ADS/DIM/DQC/TMP）。
--      业务域不预置，由表名规范 {分层}_{业务域}_{实体描述}_{刷新周期} 解析产生，
--      解析失败的资产自动进入「未分类」并成为治理项。
-- ------------------------------------------------------------------

INSERT INTO dq_rule_template (template_code, template_name, rule_type, template_kind, remark, enabled) VALUES
('PRIMARY_KEY_UNIQUE', '主键唯一性', 'PRIMARY_KEY_UNIQUE', 'builtin', '主键/唯一键重复检测', 1),
('NOT_NULL', '非空', 'NOT_NULL', 'builtin', '关键字段空值与空串检测', 1),
('VALUE_RANGE', '值域', 'VALUE_RANGE', 'builtin', '枚举字典取值与数值区间合法性', 1),
('ROW_COUNT_FLUCTUATION', '行数波动', 'ROW_COUNT_FLUCTUATION', 'builtin', '当日行数相对历史基线的涨跌幅', 1),
('ROW_COUNT_MOM', '行数环比', 'ROW_COUNT_MOM', 'builtin', '当期行数与上期行数的对比', 1),
('UPSTREAM_DOWNSTREAM_RECON', '上下游对账', 'UPSTREAM_DOWNSTREAM_RECON', 'builtin', '上下游同口径记录数/金额一致性', 1),
('METRIC_CONSISTENCY', '口径一致性', 'METRIC_CONSISTENCY', 'builtin', '同一指标在不同表、不同层之间的计算结果一致性', 1);

INSERT INTO asset_category (category_code, category_name, parent_code, category_kind, sort_order) VALUES
('ods', 'ODS 贴源层', NULL, 'layer', 1),
('dwd', 'DWD 明细层', NULL, 'layer', 2),
('dws', 'DWS 汇总层', NULL, 'layer', 3),
('ads', 'ADS 应用层', NULL, 'layer', 4),
('dim', 'DIM 维度层', NULL, 'layer', 5),
('dqc', 'DQC 质量层', NULL, 'layer', 6),
('tmp', 'TMP 临时层', NULL, 'layer', 7);

-- 默认资产标签（平台级参考数据；标签本身仍由人工施加到具体资产上）
INSERT INTO asset_tag (tag_code, tag_name, color, remark, enabled) VALUES
('CORE', '核心资产', '#c62828', '对业务有直接支撑作用的核心表', 1),
('HOT', '高频使用', '#1565c0', '近30日读取热度高', 1),
('PENDING_GOVERN', '待治理', '#f9a825', '命名不合规、未标注或无负责人等', 1),
('SENSITIVE', '敏感数据', '#6a1b9a', '含个人信息或需脱敏的字段', 1);

-- 同步各自增列到当前最大 id（H2 的 RESTART WITH 仅支持常量）
ALTER TABLE sys_user ALTER COLUMN id RESTART WITH 2;
ALTER TABLE sys_role ALTER COLUMN id RESTART WITH 2;
