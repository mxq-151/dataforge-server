-- ============================================================
-- dataforge - H2 2.x 建表与初始化脚本
-- 约定：
--   * 自增主键：BIGINT AUTO_INCREMENT（默认模式与 MySQL 兼容模式均可用）
--   * text/longtext -> CLOB, json -> JSON, datetime -> TIMESTAMP
--   * tinyint -> TINYINT, enum -> VARCHAR + CHECK
--   * H2 不支持表分区，operation_audit_log 按普通表建立
-- ============================================================

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
  sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_column_storage ON column_metadata (storage_engine, database_name, table_name, column_name);
CREATE INDEX IF NOT EXISTS idx_column_metadata_table_metadata_id ON column_metadata (table_metadata_id);
CREATE INDEX IF NOT EXISTS idx_column_metadata_column_name ON column_metadata (column_name);
COMMENT ON TABLE column_metadata IS '表字段元数据表';

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
  id BIGINT AUTO_INCREMENT,
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
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_file ON dev_program_params (project_id, file_path);
CREATE INDEX IF NOT EXISTS idx_dev_program_params_project_id ON dev_program_params (project_id);
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

-- 指标定义主表
CREATE TABLE IF NOT EXISTS indicators (
  id BIGINT AUTO_INCREMENT,
  project_code VARCHAR(200) NOT NULL,
  topic VARCHAR(200) NOT NULL,
  name VARCHAR(200) NOT NULL,
  code VARCHAR(100) NOT NULL,
  business_definition CLOB,
  calc_expr VARCHAR(100) NOT NULL,
  default_filter CLOB,
  alert_config JSON,
  business_owner VARCHAR(100),
  tech_owner VARCHAR(100),
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
  create_by BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_storage ON table_metadata (storage_engine, database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_table_metadata_database_name ON table_metadata (database_name);
CREATE INDEX IF NOT EXISTS idx_table_metadata_table_name ON table_metadata (table_name);
COMMENT ON TABLE table_metadata IS '库表元数据表';

-- ------------------------------------------------------------------
-- 初始化数据
-- ------------------------------------------------------------------

INSERT INTO sys_user (id, username, password, real_name, email, phone, status, is_admin, last_login_time, create_time, update_time, deleted) VALUES
(1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员','admin@example.com','13800000000',1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05',0),
(3,'test','357f3724ff339b2a1602c390d59498fd','测试用户','test@example.com','13800138000',1,0,'2026-07-13 15:59:21','2026-07-13 15:17:00','2026-07-13 15:17:00',0);

INSERT INTO sys_role (id, role_name, role_code, role_type, description, status, create_time, update_time, deleted) VALUES
(1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03',0),
(2,'运维工程师','OPS_ENGINEER','OPS_ENGINEER','负责系统运维和基础设施管理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(3,'数据开发工程师','DATA_DEV_ENGINEER','DATA_DEV_ENGINEER','负责数据开发、ETL和数据处理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(4,'业务需求方','BUSINESS_REQUESTER','BUSINESS_REQUESTER','提出业务需求并查看数据结果',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(5,'数据分析师','DATA_ANALYST','DATA_ANALYST','负责数据分析和探索',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0),
(6,'数据架构师','DATA_ARCHITECT','DATA_ARCHITECT','负责数据架构设计和治理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00',0);

INSERT INTO sys_menu_permission (id, parent_id, menu_name, menu_code, path, icon, sort_order, visible, status, create_time, update_time, deleted) VALUES
(1,0,'开发助手','dev_assist','/dev_assist','0',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(2,0,'数据探索','data_explore','/data_explore','',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(3,0,'知识库','knowledge_base','/knowledge_base','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(4,0,'文件浏览','file_browser','/file_browser','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(5,0,'任务调度','schedule_management','/schedule_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(6,0,'系统管理','system_management','/system_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(7,0,'设置','settings','/settings','',3,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15',0),
(100,0,'指标管理','indicator_management','/indicator_management','',3,1,1,'2026-07-13 15:17:00','2026-07-13 15:47:41',0);

INSERT INTO sys_role_menu_permission (id, role_id, menu_permission_id, create_time) VALUES
(12,3,1,'2026-07-13 15:17:00'),(13,3,2,'2026-07-13 15:17:00'),(14,3,3,'2026-07-13 15:17:00'),(15,3,100,'2026-07-13 15:17:00');

INSERT INTO sys_role_project_permission (id, role_id, project_id, permission_type, create_time) VALUES
(1,1,1,3,'2026-07-13 15:17:00'),(45,3,1,2,'2026-07-13 15:59:02');

INSERT INTO sys_role_table_permission (id, role_id, table_permission_id, create_time) VALUES
(379,3,1,'2026-07-13 15:55:04'),(380,6,1,'2026-07-13 15:55:04');

INSERT INTO sys_table_permission (id, permission_name, datasource_name, database_name, table_names, permission_scope, can_select, can_insert, can_update, can_delete, can_export, description, create_time, update_time, deleted) VALUES
(1,'test_db读写权限','test_datasource','test_db','*',1,1,1,1,1,1,'test用户对test_db的全部表读写权限','2026-07-13 15:17:00','2026-07-13 15:55:04',0);

INSERT INTO sys_tenant (id, tenant_name, tenant_code, contact_name, contact_phone, contact_email, description, status, owner_id, password, create_time, update_time, deleted) VALUES
(1,'测试租户','test_tenant','测试联系人','13800138000','test@example.com','系统初始化测试租户',1,3,'3UcRXfVuO9KPvpqKUXMswQ==','2026-07-13 15:17:00','2026-07-13 15:17:00',0);

INSERT INTO sys_tenant_role (id, tenant_id, role_id, create_time) VALUES
(690,1,3,'2026-07-13 15:41:32'),(691,1,4,'2026-07-13 15:41:32'),(692,1,6,'2026-07-13 15:41:32');

INSERT INTO sys_tenant_user (id, tenant_id, user_id, create_time) VALUES
(1,1,3,'2026-07-13 15:17:00');

INSERT INTO datasource_config (id, datasource_name, datasource_type, connection_type, storage_engine, url, database_name, username, password, connection_status, last_test_time, description, create_by, create_time, update_time, deleted) VALUES
(1,'test_datasource','mysql','jdbc','MYSQL','jdbc:mysql://127.0.0.1:3306/test_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','["test_db"]','test_tenant','3UcRXfVuO9KPvpqKUXMswQ==',1,'2026-07-13 15:17:00','系统初始化-连接test_db的MySQL数据源',3,'2026-07-13 15:17:00','2026-07-13 15:28:14',0);

INSERT INTO dev_project (id, project_name, project_code, description, owner_id, owner_name, default_datasource_id, status, script_count, scheduled_count, create_by, create_time, update_by, update_time, deleted) VALUES
(1,'测试项目','test_project','系统初始化测试项目',3,'test',1,1,0,0,3,'2026-07-13 15:17:00',NULL,'2026-07-13 15:17:00',0);

-- 同步各自增列到当前最大 id（H2 的 RESTART WITH 仅支持常量）
ALTER TABLE sys_user ALTER COLUMN id RESTART WITH 4;
ALTER TABLE sys_role ALTER COLUMN id RESTART WITH 7;
ALTER TABLE sys_menu_permission ALTER COLUMN id RESTART WITH 101;
ALTER TABLE sys_role_menu_permission ALTER COLUMN id RESTART WITH 16;
ALTER TABLE sys_role_project_permission ALTER COLUMN id RESTART WITH 46;
ALTER TABLE sys_role_table_permission ALTER COLUMN id RESTART WITH 381;
ALTER TABLE sys_table_permission ALTER COLUMN id RESTART WITH 2;
ALTER TABLE sys_tenant ALTER COLUMN id RESTART WITH 2;
ALTER TABLE sys_tenant_role ALTER COLUMN id RESTART WITH 693;
ALTER TABLE sys_tenant_user ALTER COLUMN id RESTART WITH 2;
ALTER TABLE datasource_config ALTER COLUMN id RESTART WITH 2;
ALTER TABLE dev_project ALTER COLUMN id RESTART WITH 2;