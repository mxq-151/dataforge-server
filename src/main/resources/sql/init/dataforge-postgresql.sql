-- ============================================================
-- dataforge - PostgreSQL 14+ 建表与初始化脚本
-- 约定：
--   * 自增主键使用 BIGSERIAL（允许显式 id 插入）
--   * tinyint -> smallint, longtext/text -> text, json -> jsonb
--   * enum -> varchar + CHECK 约束
--   * 主键 id 与 Java Long 对齐，统一 BIGSERIAL
--   * operation_audit_log 在本脚本中按普通表建立（PG 原生分区不支持
--     MySQL 的 p_max 兜底+日分区重叠语义），审计清理走 DELETE 兜底
-- ============================================================
BEGIN;

-- 表字段元数据表
CREATE TABLE IF NOT EXISTS column_metadata (
  id BIGSERIAL,
  table_metadata_id BIGINT NOT NULL,
  storage_engine VARCHAR(20),
  database_name VARCHAR(100),
  table_name VARCHAR(200),
  column_name VARCHAR(200) NOT NULL,
  column_type VARCHAR(100) NOT NULL,
  column_comment VARCHAR(1000),
  is_partition SMALLINT NOT NULL DEFAULT 0,
  is_primary_key SMALLINT NOT NULL DEFAULT 0,
  ordinal_position INTEGER NOT NULL DEFAULT 0,
  nullable SMALLINT NOT NULL DEFAULT 1,
  default_value VARCHAR(500),
  sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_column_storage UNIQUE (storage_engine, database_name, table_name, column_name)
);
CREATE INDEX IF NOT EXISTS idx_column_metadata_table_metadata_id ON column_metadata (table_metadata_id);
CREATE INDEX IF NOT EXISTS idx_column_metadata_column_name ON column_metadata (column_name);
COMMENT ON TABLE column_metadata IS '表字段元数据表';

-- 数据源配置表
CREATE TABLE IF NOT EXISTS datasource_config (
  id BIGSERIAL,
  datasource_name VARCHAR(100) NOT NULL,
  datasource_type VARCHAR(50) NOT NULL,
  connection_type VARCHAR(20) DEFAULT 'jdbc',
  storage_engine VARCHAR(20),
  url VARCHAR(500) NOT NULL,
  database_name VARCHAR(500),
  username VARCHAR(100),
  password VARCHAR(255),
  extra_params TEXT,
  connection_status SMALLINT NOT NULL DEFAULT 0,
  last_test_time TIMESTAMP,
  description VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
COMMENT ON COLUMN datasource_config.password IS '密码(AES加密存储)';
CREATE INDEX IF NOT EXISTS idx_datasource_config_type ON datasource_config (datasource_type);
CREATE INDEX IF NOT EXISTS idx_datasource_config_engine ON datasource_config (storage_engine);
CREATE INDEX IF NOT EXISTS idx_datasource_config_conn_type ON datasource_config (connection_type);
COMMENT ON TABLE datasource_config IS '数据源配置表';

-- DDL执行日志表
CREATE TABLE IF NOT EXISTS ddl_execution_log (
  id BIGSERIAL,
  datasource_id BIGINT NOT NULL,
  database_name VARCHAR(100),
  sql_content TEXT NOT NULL,
  sql_type VARCHAR(20) NOT NULL,
  object_type VARCHAR(20) DEFAULT 'TABLE',
  object_name VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'submitted',
  affected_rows INTEGER,
  error_message TEXT,
  execution_time_ms INTEGER,
  metadata_updated SMALLINT NOT NULL DEFAULT 0,
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
  id BIGSERIAL,
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
  status SMALLINT NOT NULL DEFAULT 1,
  description VARCHAR(500),
  category VARCHAR(100),
  program_type VARCHAR(20) NOT NULL DEFAULT 'flink',
  auto_choose SMALLINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
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
  datasources TEXT,
  jar_dependency_catalogs TEXT,
  run_params TEXT,
  compute_resources TEXT,
  remark VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, file_path)
);
COMMENT ON TABLE dev_program_params IS '程序运行参数表';

-- 开发项目管理表
CREATE TABLE IF NOT EXISTS dev_project (
  id BIGSERIAL,
  project_name VARCHAR(200) NOT NULL,
  project_code VARCHAR(100) NOT NULL,
  description TEXT,
  owner_id BIGINT,
  owner_name VARCHAR(50),
  default_datasource_id BIGINT,
  status SMALLINT NOT NULL DEFAULT 1,
  script_count INTEGER NOT NULL DEFAULT 0,
  scheduled_count INTEGER NOT NULL DEFAULT 0,
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_project_code UNIQUE (project_code)
);
CREATE INDEX IF NOT EXISTS idx_dev_project_owner_id ON dev_project (owner_id);
CREATE INDEX IF NOT EXISTS idx_dev_project_status ON dev_project (status);
COMMENT ON TABLE dev_project IS '开发项目管理表';

-- 脚本执行历史表
CREATE TABLE IF NOT EXISTS dev_script_execution (
  id BIGSERIAL,
  script_id BIGINT NOT NULL,
  execution_id VARCHAR(64) NOT NULL,
  script_type VARCHAR(50) NOT NULL,
  execute_mode VARCHAR(20) NOT NULL DEFAULT 'debug',
  engine_id BIGINT,
  datasource_id BIGINT,
  status VARCHAR(20) NOT NULL DEFAULT 'submitted',
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  duration_ms INTEGER,
  input_params JSONB,
  output_log TEXT,
  error_message TEXT,
  affected_rows INTEGER,
  result_data JSONB,
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

-- 指标定义主表
CREATE TABLE IF NOT EXISTS indicators (
  id BIGSERIAL,
  project_code VARCHAR(200) NOT NULL,
  topic VARCHAR(200) NOT NULL,
  name VARCHAR(200) NOT NULL,
  code VARCHAR(1024) NOT NULL,
  business_definition TEXT,
  calc_expr VARCHAR(1024) NOT NULL,
  default_filter TEXT,
  alert_config JSONB,
  business_owner VARCHAR(1024),
  tech_owner VARCHAR(1024),
  refresh_interval INTEGER,
  refresh_unit VARCHAR(10),
  refresh_desc VARCHAR(10),
  visibility VARCHAR(20) DEFAULT 'internal',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_code UNIQUE (code),
  CONSTRAINT chk_indicators_visibility CHECK (visibility IN ('public','internal','restricted'))
);
COMMENT ON TABLE indicators IS '指标定义主表';

-- 指标相关字段
CREATE TABLE IF NOT EXISTS indicators_fields (
  id BIGSERIAL,
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
  id BIGSERIAL,
  title VARCHAR(200) NOT NULL,
  doc_type VARCHAR(30) NOT NULL,
  category VARCHAR(100),
  content TEXT,
  author_name VARCHAR(50),
  author_id BIGINT,
  status SMALLINT NOT NULL DEFAULT 0,
  sync_to_rag SMALLINT NOT NULL DEFAULT 0,
  view_count INTEGER NOT NULL DEFAULT 0,
  tags VARCHAR(500),
  description VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_doc_type ON knowledge_document (doc_type);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_status ON knowledge_document (status);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_author_id ON knowledge_document (author_id);
COMMENT ON TABLE knowledge_document IS '知识文档表';

-- 审计日志表（PG 下按普通表建立，审计清理走 DELETE 兜底）
CREATE TABLE IF NOT EXISTS operation_audit_log (
  id BIGSERIAL,
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
  request_params TEXT,
  response_body TEXT,
  response_status INTEGER,
  error_message TEXT,
  exception_class VARCHAR(200),
  ip_address VARCHAR(50),
  user_agent VARCHAR(500),
  operation_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  duration_ms INTEGER,
  is_sensitive SMALLINT DEFAULT 0,
  is_ai_request SMALLINT DEFAULT 0,
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
  id BIGSERIAL,
  storage_name VARCHAR(100) NOT NULL,
  storage_type VARCHAR(20) NOT NULL,
  endpoint VARCHAR(255),
  bucket_name VARCHAR(100),
  access_key VARCHAR(255),
  secret_key VARCHAR(255),
  root_path VARCHAR(255) NOT NULL DEFAULT '/',
  local_mount_path VARCHAR(500),
  extra_params TEXT,
  status SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
COMMENT ON TABLE storage_config IS '存储配置表';

-- 文件路径权限表
CREATE TABLE IF NOT EXISTS sys_file_permission (
  id BIGSERIAL,
  storage_config_id BIGINT NOT NULL,
  permission_name VARCHAR(100) NOT NULL,
  file_path VARCHAR(1000) NOT NULL,
  permission_type SMALLINT NOT NULL DEFAULT 1,
  recursive SMALLINT NOT NULL DEFAULT 0,
  description VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_file_permission_storage_config_id ON sys_file_permission (storage_config_id);
CREATE INDEX IF NOT EXISTS idx_sys_file_permission_file_path ON sys_file_permission (file_path);
COMMENT ON TABLE sys_file_permission IS '文件路径权限表';

-- 菜单/页面权限表
CREATE TABLE IF NOT EXISTS sys_menu_permission (
  id BIGSERIAL,
  parent_id BIGINT NOT NULL DEFAULT 0,
  menu_name VARCHAR(100) NOT NULL,
  menu_code VARCHAR(100) NOT NULL,
  path VARCHAR(255),
  component VARCHAR(255),
  icon VARCHAR(100),
  sort_order INTEGER NOT NULL DEFAULT 0,
  visible SMALLINT NOT NULL DEFAULT 1,
  status SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_menu_code UNIQUE (menu_code)
);
CREATE INDEX IF NOT EXISTS idx_sys_menu_permission_parent_id ON sys_menu_permission (parent_id);
COMMENT ON TABLE sys_menu_permission IS '菜单/页面权限表';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
  id BIGSERIAL,
  role_name VARCHAR(50) NOT NULL,
  role_code VARCHAR(50) NOT NULL,
  role_type VARCHAR(30) NOT NULL DEFAULT 'VIEWER',
  description VARCHAR(255),
  status SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_code UNIQUE (role_code)
);
COMMENT ON TABLE sys_role IS '角色表';

-- 角色-文件权限关联表
CREATE TABLE IF NOT EXISTS sys_role_file_permission (
  id BIGSERIAL,
  role_id BIGINT NOT NULL,
  file_permission_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_file UNIQUE (role_id, file_permission_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_role_file_permission_role_id ON sys_role_file_permission (role_id);
COMMENT ON TABLE sys_role_file_permission IS '角色-文件权限关联表';

-- 角色-菜单权限关联表
CREATE TABLE IF NOT EXISTS sys_role_menu_permission (
  id BIGSERIAL,
  role_id BIGINT NOT NULL,
  menu_permission_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_menu UNIQUE (role_id, menu_permission_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_role_menu_permission_role_id ON sys_role_menu_permission (role_id);
COMMENT ON TABLE sys_role_menu_permission IS '角色-菜单权限关联表';

-- 角色项目权限关联表
CREATE TABLE IF NOT EXISTS sys_role_project_permission (
  id BIGSERIAL,
  role_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  permission_type INTEGER NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_project UNIQUE (role_id, project_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_role_project_permission_role_id ON sys_role_project_permission (role_id);
CREATE INDEX IF NOT EXISTS idx_sys_role_project_permission_project_id ON sys_role_project_permission (project_id);
COMMENT ON TABLE sys_role_project_permission IS '角色项目权限关联表';

-- 角色-库表权限关联表
CREATE TABLE IF NOT EXISTS sys_role_table_permission (
  id BIGSERIAL,
  role_id BIGINT NOT NULL,
  table_permission_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_role_table UNIQUE (role_id, table_permission_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_role_table_permission_role_id ON sys_role_table_permission (role_id);
COMMENT ON TABLE sys_role_table_permission IS '角色-库表权限关联表';

-- 库表CRUD权限表
CREATE TABLE IF NOT EXISTS sys_table_permission (
  id BIGSERIAL,
  permission_name VARCHAR(100) NOT NULL,
  datasource_name VARCHAR(255),
  database_name VARCHAR(255),
  table_names TEXT,
  permission_scope SMALLINT NOT NULL DEFAULT 1,
  can_select SMALLINT NOT NULL DEFAULT 1,
  can_insert SMALLINT NOT NULL DEFAULT 0,
  can_update SMALLINT NOT NULL DEFAULT 0,
  can_delete SMALLINT NOT NULL DEFAULT 0,
  can_export SMALLINT NOT NULL DEFAULT 0,
  row_filter_sql TEXT,
  data_masking_rule VARCHAR(500),
  description VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_table_permission_scope ON sys_table_permission (permission_scope);
COMMENT ON TABLE sys_table_permission IS '库表CRUD权限表';

-- 租户表
CREATE TABLE IF NOT EXISTS sys_tenant (
  id BIGSERIAL,
  tenant_name VARCHAR(100) NOT NULL,
  tenant_code VARCHAR(50) NOT NULL,
  contact_name VARCHAR(50),
  contact_phone VARCHAR(20),
  contact_email VARCHAR(100),
  description VARCHAR(500),
  status INTEGER DEFAULT 1,
  owner_id BIGINT,
  password VARCHAR(200),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted INTEGER DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_tenant_code UNIQUE (tenant_code)
);
COMMENT ON COLUMN sys_tenant.password IS '租户密码(AES加密)';
COMMENT ON TABLE sys_tenant IS '租户表';

-- 租户角色关联表
CREATE TABLE IF NOT EXISTS sys_tenant_role (
  id BIGSERIAL,
  tenant_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_tenant_role UNIQUE (tenant_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_role_tenant_id ON sys_tenant_role (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_role_role_id ON sys_tenant_role (role_id);
COMMENT ON TABLE sys_tenant_role IS '租户角色关联表';

-- 租户用户关联表
CREATE TABLE IF NOT EXISTS sys_tenant_user (
  id BIGSERIAL,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT uk_tenant_id UNIQUE (tenant_id),
  CONSTRAINT uk_user_id UNIQUE (user_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_user_tenant_id ON sys_tenant_user (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sys_tenant_user_user_id ON sys_tenant_user (user_id);
COMMENT ON TABLE sys_tenant_user IS '租户用户关联表';

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGSERIAL,
  username VARCHAR(50) NOT NULL,
  password VARCHAR(255) NOT NULL,
  real_name VARCHAR(50),
  email VARCHAR(100),
  phone VARCHAR(20),
  avatar VARCHAR(255),
  status SMALLINT NOT NULL DEFAULT 1,
  is_admin SMALLINT DEFAULT 0,
  last_login_time TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_username UNIQUE (username)
);
COMMENT ON COLUMN sys_user.password IS '密码(MD5加密存储)';
COMMENT ON TABLE sys_user IS '用户表';

-- 库表元数据表
CREATE TABLE IF NOT EXISTS table_metadata (
  id BIGSERIAL,
  datasource_id BIGINT NOT NULL,
  storage_engine VARCHAR(20),
  catalog_name VARCHAR(100),
  database_name VARCHAR(100) NOT NULL,
  table_name VARCHAR(200) NOT NULL,
  table_type VARCHAR(50),
  table_comment VARCHAR(1000),
  table_create_time TIMESTAMP,
  last_modify_time TIMESTAMP,
  partition_columns JSONB,
  storage_format VARCHAR(200),
  compression VARCHAR(50),
  location VARCHAR(500),
  row_count BIGINT,
  size_bytes BIGINT,
  hot_days INTEGER NOT NULL DEFAULT 90,
  cold_days INTEGER NOT NULL DEFAULT 180,
  keep_days INTEGER NOT NULL DEFAULT 720,
  sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  table_fingerprint VARCHAR(64),
  columns_fingerprint VARCHAR(64),
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_table_storage UNIQUE (storage_engine, database_name, table_name)
);
CREATE INDEX IF NOT EXISTS idx_table_metadata_database_name ON table_metadata (database_name);
CREATE INDEX IF NOT EXISTS idx_table_metadata_table_name ON table_metadata (table_name);
COMMENT ON TABLE table_metadata IS '库表元数据表';

-- ------------------------------------------------------------------
-- ------------------------------------------------------------------
-- 基础初始化数据（仅 admin 用户与 SUPER_ADMIN 角色）
--    测试数据（test 用户/角色/菜单/租户/数据源/授权/演示业务库）见 testdata/
-- ------------------------------------------------------------------

INSERT INTO sys_user (id, username, password, real_name, email, phone, status, is_admin, last_login_time, create_time, update_time, deleted) VALUES
(1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员','admin@example.com','13800000000',1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05',0);

INSERT INTO sys_role (id, role_name, role_code, role_type, description, status, create_time, update_time, deleted) VALUES
(1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03',0);

-- 同步各自增序列到当前最大 id
SELECT setval(pg_get_serial_sequence('sys_user','id'), (SELECT COALESCE(MAX(id),1) FROM sys_user));
SELECT setval(pg_get_serial_sequence('sys_role','id'), (SELECT COALESCE(MAX(id),1) FROM sys_role));

COMMIT;
