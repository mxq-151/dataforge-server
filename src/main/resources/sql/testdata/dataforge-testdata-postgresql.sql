-- ============================================================
-- dataforge - PostgreSQL 测试数据初始化脚本
-- 前置：
--   * 必须先执行 init/dataforge-postgresql.sql（建表 + admin/SUPER_ADMIN）
-- 内容：
--   * 测试用户 test（密码 123456）及相关业务角色（OPS/DATA_DEV/BUSINESS/DATA_ANALYST/DATA_ARCHITECT）
--   * 菜单定义与角色-菜单授权
--   * 测试租户、租户用户、租户角色
--   * 测试数据源 test_datasource（指向 PG 演示库 test_db，见 test_db-postgresql.sql）
--   * 库表资源授权（test_db 读写）与项目权限、测试项目
--   * JAR 依赖包配置 dev_jar_dependency（spark 核心依赖 + PG JDBC 驱动）
-- 说明：带唯一键的语句走 ON CONFLICT，可重复执行；datasource_config /
--       sys_table_permission 无唯一键，重复导入会插入重复行（init 脚本已按
--       test 用户存在性做幂等保护，正常流程不会重复执行）
-- ============================================================
BEGIN;

-- 测试用户（密码 123456，MD5）
INSERT INTO sys_user (id, username, password, real_name, email, phone, status, is_admin, last_login_time, create_time, update_time) VALUES
(3,'test','357f3724ff339b2a1602c390d59498fd','测试用户','test@example.com','13800138000',1,0,'2026-07-13 15:59:21','2026-07-13 15:17:00','2026-07-13 15:17:00')
ON CONFLICT (username) DO NOTHING;

-- 业务角色（SUPER_ADMIN 角色见 init 脚本；其余角色由测试租户使用）
INSERT INTO sys_role (id, role_name, role_code, role_type, description, status, create_time, update_time) VALUES
(2,'运维工程师','OPS_ENGINEER','OPS_ENGINEER','负责系统运维和基础设施管理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(3,'数据开发工程师','DATA_DEV_ENGINEER','DATA_DEV_ENGINEER','负责数据开发、ETL和数据处理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(4,'业务需求方','BUSINESS_REQUESTER','BUSINESS_REQUESTER','提出业务需求并查看数据结果',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(5,'数据分析师','DATA_ANALYST','DATA_ANALYST','负责数据分析和探索',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(6,'数据架构师','DATA_ARCHITECT','DATA_ARCHITECT','负责数据架构设计和治理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00')
ON CONFLICT (role_code) DO NOTHING;

-- 菜单（系统 UI 定义）
INSERT INTO sys_menu_permission (id, parent_id, menu_name, menu_code, path, icon, sort_order, visible, status, create_time, update_time) VALUES
(1,0,'开发助手','dev_assist','/dev_assist','0',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(2,0,'数据探索','data_explore','/data_explore','',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(3,0,'知识库','knowledge_base','/knowledge_base','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(4,0,'文件浏览','file_browser','/file_browser','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(5,0,'任务调度','schedule_management','/schedule_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(6,0,'系统管理','system_management','/system_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(7,0,'设置','settings','/settings','',3,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(100,0,'指标管理','indicator_management','/indicator_management','',3,1,1,'2026-07-13 15:17:00','2026-07-13 15:47:41'),
-- 2026-09-17 新增两个一级入口：数据质量、资产目录
-- 注意：init/dataforge-*.sql 只建菜单表结构、不含菜单数据，部署脚本导入的是本文件，
-- 因此新增一级菜单必须同时补这里，否则新入口无权限码可取（前端会静默过滤掉）
(101,0,'数据质量','data_quality','/data_quality','',4,1,1,'2026-09-17 22:00:00','2026-09-17 22:00:00'),
(102,0,'资产目录','asset_catalog','/asset_catalog','',5,1,1,'2026-09-17 22:00:00','2026-09-17 22:00:00')
ON CONFLICT (menu_code) DO NOTHING;

-- 角色-菜单授权（数据开发工程师 -> 开发助手/数据探索/知识库/指标管理/任务调度/数据质量/资产目录）
INSERT INTO sys_role_menu_permission (id, role_id, menu_permission_id, create_time) VALUES
(12,3,1,'2026-07-13 15:17:00'),(13,3,2,'2026-07-13 15:17:00'),(14,3,3,'2026-07-13 15:17:00'),(15,3,100,'2026-07-13 15:17:00'),(16,3,5,'2026-07-13 15:17:00'),
-- 2026-09-17 两个新菜单的授权
--   data_quality(101)   -> 超级管理员(1) / 运维工程师(2) / 数据开发工程师(3) / 数据架构师(6)
--   asset_catalog(102)  -> 全部角色（业务需求方与数据分析师最需要找数认数）
(17,1,101,'2026-09-17 22:00:00'),(18,2,101,'2026-09-17 22:00:00'),(19,3,101,'2026-09-17 22:00:00'),(20,6,101,'2026-09-17 22:00:00'),
(21,1,102,'2026-09-17 22:00:00'),(22,2,102,'2026-09-17 22:00:00'),(23,3,102,'2026-09-17 22:00:00'),(24,4,102,'2026-09-17 22:00:00'),(25,5,102,'2026-09-17 22:00:00'),(26,6,102,'2026-09-17 22:00:00')
ON CONFLICT (role_id, menu_permission_id) DO NOTHING;

-- 测试项目
INSERT INTO dev_project (id, project_name, project_code, description, owner_id, owner_name, default_datasource_id, status, script_count, scheduled_count, create_by, create_time, update_by, update_time) VALUES
(1,'测试项目','test_project','系统初始化测试项目',3,'test',1,1,0,0,3,'2026-07-13 15:17:00',NULL,'2026-07-13 15:17:00')
ON CONFLICT (project_code) DO NOTHING;

-- 项目权限（SUPER_ADMIN 完全控制；数据开发工程师 可编辑）
INSERT INTO sys_role_project_permission (id, role_id, project_id, permission_type, create_time) VALUES
(1,1,1,3,'2026-07-13 15:17:00'),(45,3,1,2,'2026-07-13 15:59:02')
ON CONFLICT (role_id, project_id) DO NOTHING;

-- JAR 依赖包配置（与 dataforge-allinone 容器内已初始化的数据保持一致）：
--   * spark-sql/spark-core：source=system，jar 位于 Spark 安装目录 lib/，auto_choose=1 自动随任务携带
--   * postgresql JDBC 驱动：source=custom，jar 位于 /opt/dataforge/jars/，需显式勾选
INSERT INTO dev_jar_dependency (id, jar_name, group_id, artifact_id, version, scope, source, jar_path, repository_url, file_size, checksum, status, description, category, program_type, auto_choose, create_time, update_time) VALUES
(1,'spark-sql_2.12-3.5.9.jar','org.apache.spark','spark-sql_2.12','3.5.9','compile','system',NULL,NULL,NULL,NULL,1,NULL,'','spark',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(2,'spark-core_2.12-3.5.9.jar','org.apache.spark','spark-core_2.12','3.5.9','compile','system',NULL,NULL,NULL,NULL,1,NULL,'','spark',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(3,'postgresql-42.7.12.jar','org.postgresql','postgresql','42.7.12','compile','custom','postgresql-42.7.12.jar',NULL,NULL,NULL,1,'PostgreSQL JDBC 驱动','数据库驱动','spark',0,'2026-07-13 15:17:00','2026-07-13 15:17:00')
ON CONFLICT (id) DO NOTHING;

-- 测试租户（密码与 test 用户一致，AES 加密）
INSERT INTO sys_tenant (id, tenant_name, tenant_code, contact_name, contact_phone, contact_email, description, status, owner_id, password, create_time, update_time) VALUES
(1,'测试租户','test_tenant','测试联系人','13800138000','test@example.com','系统初始化测试租户',1,3,'3UcRXfVuO9KPvpqKUXMswQ==','2026-07-13 15:17:00','2026-07-13 15:17:00')
ON CONFLICT (tenant_code) DO NOTHING;

-- 租户-用户关联（test 属于测试租户）
INSERT INTO sys_tenant_user (id, tenant_id, user_id, create_time) VALUES
(1,1,3,'2026-07-13 15:17:00')
ON CONFLICT (tenant_id) DO NOTHING;

-- 租户-角色关联（测试租户启用：数据开发/业务需求方/数据架构师）
INSERT INTO sys_tenant_role (id, tenant_id, role_id, create_time) VALUES
(690,1,3,'2026-07-13 15:41:32'),(691,1,4,'2026-07-13 15:41:32'),(692,1,6,'2026-07-13 15:41:32')
ON CONFLICT (tenant_id, role_id) DO NOTHING;

-- 数据源（PG 演示库 test_db 由 test_db-postgresql.sql 创建；
-- PostgreSQL 类型已由 dataforge-server 原生支持：连接/测试/元数据同步/代码执行）
INSERT INTO datasource_config (id, datasource_name, datasource_type, connection_type, storage_engine, url, database_name, username, password, connection_status, last_test_time, description, create_by, create_time, update_time) VALUES
(1,'test_datasource','postgresql','jdbc','POSTGRESQL','jdbc:postgresql://127.0.0.1:5432/test_db?currentSchema=test_db','["test_db"]','test_tenant','3UcRXfVuO9KPvpqKUXMswQ==',1,'2026-07-13 15:17:00','系统初始化-连接test_db的PostgreSQL数据源',3,'2026-07-13 15:17:00','2026-07-13 15:28:14');

-- 库表资源授权：测试租户下数据开发/数据架构师角色对 test_db 全部表读写
INSERT INTO sys_table_permission (id, permission_name, datasource_name, database_name, table_names, permission_scope, can_select, can_insert, can_update, can_delete, can_export, description, create_time, update_time) VALUES
(1,'test_db读写权限','test_datasource','test_db','*',1,1,1,1,1,1,'test用户对test_db的全部表读写权限','2026-07-13 15:17:00','2026-07-13 15:55:04');

INSERT INTO sys_role_table_permission (id, role_id, table_permission_id, create_time) VALUES
(379,3,1,'2026-07-13 15:55:04'),(380,6,1,'2026-07-13 15:55:04')
ON CONFLICT (role_id, table_permission_id) DO NOTHING;

-- ------------------------------------------------------------------
-- 同步各自增序列到当前最大 id
-- ------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('sys_user','id'), (SELECT COALESCE(MAX(id),1) FROM sys_user));
SELECT setval(pg_get_serial_sequence('sys_role','id'), (SELECT COALESCE(MAX(id),1) FROM sys_role));
SELECT setval(pg_get_serial_sequence('sys_menu_permission','id'), (SELECT COALESCE(MAX(id),1) FROM sys_menu_permission));
SELECT setval(pg_get_serial_sequence('sys_role_menu_permission','id'), (SELECT COALESCE(MAX(id),1) FROM sys_role_menu_permission));
SELECT setval(pg_get_serial_sequence('dev_project','id'), (SELECT COALESCE(MAX(id),1) FROM dev_project));
SELECT setval(pg_get_serial_sequence('sys_role_project_permission','id'), (SELECT COALESCE(MAX(id),1) FROM sys_role_project_permission));
SELECT setval(pg_get_serial_sequence('sys_tenant','id'), (SELECT COALESCE(MAX(id),1) FROM sys_tenant));
SELECT setval(pg_get_serial_sequence('sys_tenant_user','id'), (SELECT COALESCE(MAX(id),1) FROM sys_tenant_user));
SELECT setval(pg_get_serial_sequence('sys_tenant_role','id'), (SELECT COALESCE(MAX(id),1) FROM sys_tenant_role));
SELECT setval(pg_get_serial_sequence('datasource_config','id'), (SELECT COALESCE(MAX(id),1) FROM datasource_config));
SELECT setval(pg_get_serial_sequence('sys_table_permission','id'), (SELECT COALESCE(MAX(id),1) FROM sys_table_permission));
SELECT setval(pg_get_serial_sequence('sys_role_table_permission','id'), (SELECT COALESCE(MAX(id),1) FROM sys_role_table_permission));
SELECT setval(pg_get_serial_sequence('dev_jar_dependency','id'), (SELECT COALESCE(MAX(id),1) FROM dev_jar_dependency));

COMMIT;