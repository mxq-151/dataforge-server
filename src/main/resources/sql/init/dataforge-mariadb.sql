-- ============================================================
-- dataforge - MariaDB 10.6+ 初始化数据
-- 前置：先执行 sql/ddl/dataforge-mariadb.sql（建表）
-- 内容：基础数据（admin / SUPER_ADMIN + 平台级参考数据）
--       测试数据（test 用户 / 业务角色 / 菜单 / 租户 / Kyuubi 数据源 / 授权 / 测试项目）
-- 说明：幂等（INSERT IGNORE / ON DUPLICATE KEY UPDATE），可重复执行
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------------
-- 基础初始化数据（仅 admin 用户与 SUPER_ADMIN 角色）
--    ⚠ 本脚本会被反复执行，本段所有 INSERT 必须幂等：
--      一律用 INSERT IGNORE。不带冲突处理会让老库每次启动报重复键错误
--      （asset_category / asset_tag 这类参考数据更会静默插出重复行）。
-- ------------------------------------------------------------------

INSERT IGNORE INTO `sys_user` (`id`,`username`,`password`,`real_name`,`email`,`phone`,`status`,`is_admin`,`last_login_time`,`create_time`,`update_time`) VALUES
(1,'admin','357f3724ff339b2a1602c390d59498fd','超级管理员','admin@example.com','13800000000',1,1,'2026-07-13 15:53:37','2026-05-07 09:08:00','2026-06-04 14:48:05');

INSERT IGNORE INTO `sys_role` (`id`,`role_name`,`role_code`,`role_type`,`description`,`status`,`create_time`,`update_time`) VALUES
(1,'超级管理员','SUPER_ADMIN','SUPER_ADMIN','拥有系统所有权限的超级管理员',1,'2026-07-13 15:17:00','2026-07-13 15:54:03');

-- ------------------------------------------------------------------
-- 平台级参考数据（非测试数据，任何部署都需要）
--   1) 数据质量 7 类内置规则模板：与智能体提示词、前端 types/quality.ts 同源，
--      template_code 即规则类型编码；三处必须同时演进，禁止自造同义词。
--   2) 资产目录数仓分层：与提示词「分层规范」一致（ODS/DWD/DWS/ADS/DIM/DQC/TMP）。
--      业务域不预置，由表名规范 {分层}_{业务域}_{实体描述}_{刷新周期} 解析产生，
--      解析失败的资产自动进入「未分类」并成为治理项。
-- ------------------------------------------------------------------

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

-- 默认资产标签（平台级参考数据；标签本身仍由人工施加到具体资产上）
INSERT IGNORE INTO `asset_tag`
  (`tag_code`,`tag_name`,`color`,`remark`,`enabled`)
VALUES
  ('CORE','核心资产','#c62828','对业务有直接支撑作用的核心表',1),
  ('HOT','高频使用','#1565c0','近30日读取热度高',1),
  ('PENDING_GOVERN','待治理','#f9a825','命名不合规、未标注或无负责人等',1),
  ('SENSITIVE','敏感数据','#6a1b9a','含个人信息或需脱敏的字段',1);

-- ------------------------------------------------------------------
-- 内置示例业务术语表（业务知识文档）
--   为什么放这里：新装环境知识库是空的，GET /api/agent/retrieval/terms 返回空词典，
--   「业务方说法 → 平台命名」的映射能力没有可演示的样本。本文档只是**示例**：
--   英文名取自 TPC-DS 演示数据集（零售）的表名，接入真实业务后应替换为
--   客户的术语表，或直接删除本文档。
--   幂等/只播种一次：只在知识库为空时插入。不能按 title 判重 —— 用户删掉本文档后
--   下次启动会被重新插回来；按「空库」判断才是真正的一次性播种（本脚本每次启动都会执行）。
-- ------------------------------------------------------------------

INSERT INTO `knowledge_document`
  (`title`, `doc_type`, `category`, `content`, `author_name`, `author_id`,
   `status`, `sync_to_rag`, `view_count`, `tags`, `description`, `create_time`, `update_time`)
SELECT '业务术语表示例（TPC-DS 零售）', 'business_knowledge', '电商',
'> 本文档由平台初始化脚本预置，是**示例**而非真实业务口径：接入业务后请替换为你们自己的术语表，或直接删除本文档。
>
> 表格中的英文名取自 TPC-DS 演示数据集（零售）的表名，用于演示「业务方说法 → 平台命名」的映射。

## 术语表

| 术语 | 别名 | 英文名 |
| --- | --- | --- |
| 订单 | 订单主表、下单记录 | store_sales |
| 订单商品明细 | 订单流水、订单明细 | store_returns |
| 客户 | 买家、顾客 | customer |
| 卖家 | 商家、供应商 | store |
| 商品 | 产品、货品 | item |
| 支付记录 | 付款、支付流水 | catalog_sales |
| 订单评价 | 评论、评分 | web_returns |
| 收货地址 | 收货地、地理信息 | customer_address |
| 商品类目 | 品类、类目翻译 | product_category_name_translation |

## 使用说明

- 一行一个术语，别名用「、」分隔，英文名列写物理表名或字段名。
- 章节标题需包含「术语表」且表头需包含「术语」，写成散文不会被解析。
- 同一术语只在一篇业务知识文档中定义，多篇重复时按文档更新时间取最新。
',
'admin', 1, 1, 0, 0, '示例,术语表',
'示例术语表：把业务方说法映射到 test_db 演示库表名（可替换或删除）',
CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `knowledge_document`);

-- ------------------------------------------------------------------
-- 测试数据（test 用户 / 业务角色 / 菜单 / 租户 / Kyuubi 数据源 / 授权 / 测试项目）
--   全部语句幂等（INSERT IGNORE / ON DUPLICATE KEY UPDATE），可重复执行。
-- ------------------------------------------------------------------

-- 测试用户（密码 123456，MD5）
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `is_admin`, `last_login_time`, `create_time`, `update_time`) VALUES
(3,'test','357f3724ff339b2a1602c390d59498fd','测试用户','test@example.com','13800138000',1,0,'2026-07-13 15:59:21','2026-07-13 15:17:00','2026-07-13 15:17:00');

-- 业务角色（SUPER_ADMIN 角色见上方基础数据）
INSERT IGNORE INTO `sys_role` (`id`, `role_name`, `role_code`, `role_type`, `description`, `status`, `create_time`, `update_time`) VALUES
(2,'运维工程师','OPS_ENGINEER','OPS_ENGINEER','负责系统运维和基础设施管理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(3,'数据开发工程师','DATA_DEV_ENGINEER','DATA_DEV_ENGINEER','负责数据开发、ETL和数据处理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(4,'业务需求方','BUSINESS_REQUESTER','BUSINESS_REQUESTER','提出业务需求并查看数据结果',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(5,'数据分析师','DATA_ANALYST','DATA_ANALYST','负责数据分析和探索',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(6,'数据架构师','DATA_ARCHITECT','DATA_ARCHITECT','负责数据架构设计和治理',1,'2026-07-13 15:17:00','2026-07-13 15:17:00');

-- 菜单（系统 UI 定义）
INSERT IGNORE INTO `sys_menu_permission` (`id`, `parent_id`, `menu_name`, `menu_code`, `path`, `icon`, `sort_order`, `visible`, `status`, `create_time`, `update_time`) VALUES
(1,0,'开发助手','dev_assist','/dev_assist','0',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(2,0,'数据探索','data_explore','/data_explore','',1,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(3,0,'知识库','knowledge_base','/knowledge_base','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(4,0,'文件浏览','file_browser','/file_browser','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(5,0,'任务调度','schedule_management','/schedule_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(6,0,'系统管理','system_management','/system_management','',2,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(7,0,'设置','settings','/settings','',3,1,1,'2026-05-14 22:59:15','2026-05-14 22:59:15'),
(100,0,'指标管理','indicator_management','/indicator_management','',3,1,1,'2026-07-13 15:17:00','2026-07-13 15:47:41'),
-- 2026-09-17 新增两个一级入口：数据质量、资产目录
-- 注意：菜单数据只在本初始化脚本维护，新增一级菜单必须同时补这里，
-- 否则新入口无权限码可取（前端会静默过滤掉）
(101,0,'数据质量','data_quality','/data_quality','',4,1,1,'2026-09-17 22:00:00','2026-09-17 22:00:00'),
(102,0,'资产目录','asset_catalog','/asset_catalog','',5,1,1,'2026-09-17 22:00:00','2026-09-17 22:00:00'),
-- 2026-11-09 新增数据集成入口：前端 MENU_CODE_MAP 里已有 data_integration，
-- 但菜单表缺这一行，导致非超管角色永远拿不到该权限码（入口被静默过滤）。
(103,0,'数据集成','data_integration','/data_integration','',6,1,1,'2026-11-09 18:00:00','2026-11-09 18:00:00');

-- 角色-菜单授权（数据开发工程师 -> 开发助手/数据探索/知识库/指标管理/任务调度/数据质量/资产目录）
INSERT IGNORE INTO `sys_role_menu_permission` (`id`, `role_id`, `menu_permission_id`, `create_time`) VALUES
(12,3,1,'2026-07-13 15:17:00'),(13,3,2,'2026-07-13 15:17:00'),(14,3,3,'2026-07-13 15:17:00'),(15,3,100,'2026-07-13 15:17:00'),(16,3,5,'2026-07-13 15:17:00'),
-- 2026-09-17 两个新菜单的授权
--   data_quality(101)   -> 超级管理员(1) / 运维工程师(2) / 数据开发工程师(3) / 数据架构师(6)
--   asset_catalog(102)  -> 全部角色（业务需求方与数据分析师最需要找数认数）
(17,1,101,'2026-09-17 22:00:00'),(18,2,101,'2026-09-17 22:00:00'),(19,3,101,'2026-09-17 22:00:00'),(20,6,101,'2026-09-17 22:00:00'),
(21,1,102,'2026-09-17 22:00:00'),(22,2,102,'2026-09-17 22:00:00'),(23,3,102,'2026-09-17 22:00:00'),(24,4,102,'2026-09-17 22:00:00'),(25,5,102,'2026-09-17 22:00:00'),(26,6,102,'2026-09-17 22:00:00'),
-- 2026-11-09 data_integration(103) -> 超级管理员(1) / 运维工程师(2) / 数据开发工程师(3) / 数据架构师(6)
(27,1,103,'2026-11-09 18:00:00'),(28,2,103,'2026-11-09 18:00:00'),(29,3,103,'2026-11-09 18:00:00'),(30,6,103,'2026-11-09 18:00:00');

-- 测试项目
INSERT IGNORE INTO `dev_project` (`id`, `project_name`, `project_code`, `description`, `owner_id`, `owner_name`, `default_datasource_id`, `status`, `script_count`, `scheduled_count`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES
(1,'测试项目','test_project','系统初始化测试项目',3,'test',2,1,0,0,3,'2026-07-13 15:17:00',NULL,'2026-07-13 15:17:00');

-- 项目权限（SUPER_ADMIN 完全控制；数据开发工程师 可编辑）
INSERT IGNORE INTO `sys_role_project_permission` (`id`, `role_id`, `project_id`, `permission_type`, `create_time`) VALUES
(1,1,1,3,'2026-07-13 15:17:00'),(45,3,1,2,'2026-07-13 15:59:02');

-- JAR 依赖包配置（与 dataforge-allinone 容器内已初始化的数据保持一致）：
--   * spark-sql/spark-core：source=system，jar 位于 Spark 安装目录 lib/，auto_choose=1 自动随任务携带
--   * postgresql JDBC 驱动：source=custom，jar 位于 /opt/dataforge/jars/，需显式勾选
INSERT IGNORE INTO `dev_jar_dependency` (`id`, `jar_name`, `group_id`, `artifact_id`, `version`, `scope`, `source`, `jar_path`, `repository_url`, `file_size`, `checksum`, `status`, `description`, `category`, `program_type`, `auto_choose`, `create_time`, `update_time`) VALUES
(1,'spark-sql_2.12-3.5.9.jar','org.apache.spark','spark-sql_2.12','3.5.9','compile','system',NULL,NULL,NULL,NULL,1,NULL,'','spark',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(2,'spark-core_2.12-3.5.9.jar','org.apache.spark','spark-core_2.12','3.5.9','compile','system',NULL,NULL,NULL,NULL,1,NULL,'','spark',1,'2026-07-13 15:17:00','2026-07-13 15:17:00'),
(3,'postgresql-42.7.12.jar','org.postgresql','postgresql','42.7.12','compile','custom','postgresql-42.7.12.jar',NULL,NULL,NULL,1,'PostgreSQL JDBC 驱动','数据库驱动','spark',0,'2026-07-13 15:17:00','2026-07-13 15:17:00');

-- 测试租户（密码与 test 用户一致，AES 加密）
INSERT IGNORE INTO `sys_tenant` (`id`, `tenant_name`, `tenant_code`, `contact_name`, `contact_phone`, `contact_email`, `description`, `status`, `owner_id`, `password`, `create_time`, `update_time`) VALUES
(1,'测试租户','test_tenant','测试联系人','13800138000','test@example.com','系统初始化测试租户',1,3,'3UcRXfVuO9KPvpqKUXMswQ==','2026-07-13 15:17:00','2026-07-13 15:17:00');

-- 租户-用户关联（test 属于测试租户）
INSERT IGNORE INTO `sys_tenant_user` (`id`, `tenant_id`, `user_id`, `create_time`) VALUES
(1,1,3,'2026-07-13 15:17:00');

-- 租户-角色关联（测试租户启用：数据开发/业务需求方/数据架构师）
INSERT IGNORE INTO `sys_tenant_role` (`id`, `tenant_id`, `role_id`, `create_time`) VALUES
(690,1,3,'2026-07-13 15:41:32'),(691,1,4,'2026-07-13 15:41:32'),(692,1,6,'2026-07-13 15:41:32');

-- Kyuubi 数据源：REST(HTTP) 前端 http://127.0.0.1:10099，默认库 test_db，
-- 25 张 TPC-DS 表由容器内 Hive Metastore 2.3.9 管理。
-- 认证 kyuubi.authentication=NONE，故密码为空；username 仅用于 Kyuubi 会话/引擎归属。
-- 说明：不再使用 HIVE + jdbc:hive2://...:10009（那条链路的 REST 地址只能靠全局配置兜底）。
INSERT INTO `datasource_config` (`id`, `datasource_name`, `datasource_type`, `connection_type`, `storage_engine`, `url`, `database_name`, `username`, `password`, `connection_status`, `last_test_time`, `description`, `create_by`, `create_time`, `update_time`) VALUES
(2,'kyuubi_datasource','kyuubi','http','KYUUBI','http://127.0.0.1:10099','["test_db"]','test_tenant','',1,'2026-11-14 01:00:00','系统初始化-Kyuubi REST数据源(test_db, 25张TPC-DS表)',3,'2026-11-13 16:00:00','2026-11-14 01:00:00')
ON DUPLICATE KEY UPDATE
  datasource_name = VALUES(datasource_name),
  datasource_type = VALUES(datasource_type),
  connection_type = VALUES(connection_type),
  storage_engine = VALUES(storage_engine),
  url = VALUES(url),
  database_name = VALUES(database_name),
  username = VALUES(username),
  description = VALUES(description),
  update_time = VALUES(update_time);

-- Kyuubi 数据源库表授权（test_db 下 25 张 TPC-DS 表只读）
INSERT INTO `sys_table_permission` (`id`, `permission_name`, `datasource_name`, `database_name`, `table_names`, `permission_scope`, `can_select`, `can_insert`, `can_update`, `can_delete`, `can_export`, `description`, `create_time`, `update_time`) VALUES
(2,'kyuubi_test_db读权限','kyuubi_datasource','test_db','*',1,1,0,0,0,1,'test用户对Kyuubi test_db的查询权限','2026-11-13 16:00:00','2026-11-14 01:00:00')
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  datasource_name = VALUES(datasource_name),
  description = VALUES(description),
  update_time = VALUES(update_time);

-- Kyuubi 库表权限授予数据开发工程师/数据架构师
INSERT IGNORE INTO `sys_role_table_permission` (`id`, `role_id`, `table_permission_id`, `create_time`) VALUES
(379,3,2,'2026-07-13 15:55:04'),(380,6,2,'2026-07-13 15:55:04');
