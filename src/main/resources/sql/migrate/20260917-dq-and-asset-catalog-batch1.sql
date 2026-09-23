-- ---------------------------------------------------------------------------
-- 数据迁移：新增「数据质量」与「资产目录」两个模块（第一批）
--
-- 背景
--   对标阿里云 DataWorks 后确认本项目缺少两条能力线：
--     1) 数据质量：平台侧只有结果表 data_quality_check_result（「检查项」为自由文本），
--        没有规则定义、执行计划与运行记录，而智能体提示词里早已约定 7 类规则类型
--        （主键唯一性/非空/值域/行数波动/行数环比/上下游对账/口径一致性）与 DQC 质量层，
--        形成「契约完备、落点缺失」的断裂；
--     2) 资产目录：元数据、血缘、权限、指标都已有，但缺少以「资产」为主语的浏览与检索入口，
--        负责人、分类、标签、收藏与使用热度这些治理属性也无处存放。
--   本脚本落地第一批数据模型（规则定义 + 运行记录 + 资产治理属性 + 菜单授权）。
--
-- 影响
--   - 新增 7 张表：dq_rule_template / dq_rule / dq_run_instance / dq_rule_result /
--     asset_category / asset_bookmark / asset_access_log；
--   - 改造 3 张表（仅新增列，均可空或带默认值）：table_metadata、column_metadata、
--     data_quality_check_result；
--   - 新增 2 条一级菜单（data_quality / asset_catalog）与角色授权；
--   - 不修改、不删除任何既有数据；老接口 /quality/check-result/* 继续可用。
--
--   说明：dq_run_instance 原计划属第二批，因「手动触发校验 + 运行记录」在第一批就要交付，
--   没有运行实例则规则级结果无处挂载，故提前到本批。
--
-- 执行方式（PostgreSQL）
--   在 dataforge 库执行一次即可，幂等（CREATE TABLE/INDEX IF NOT EXISTS、ADD COLUMN IF NOT
--   EXISTS、INSERT ... ON CONFLICT DO NOTHING）。
--   docker 部署可进入容器执行：
--     docker exec -i <容器名> su postgres -c \
--       "psql -d dataforge" < 20260917-dq-and-asset-catalog-batch1.sql
--   或宿主机：
--     PGPASSWORD=123456 psql -h 127.0.0.1 -p 5432 -U postgres -d dataforge \
--       -f 20260917-dq-and-asset-catalog-batch1.sql
--
--   注意：init/dataforge-*.sql 已同步包含本脚本的全部建表与新增列，
--   全新部署无需执行本脚本；本脚本只用于已上线环境的增量升级。
--
-- 校验
--   1) 表：\dt dq_*; \dt asset_*;            应各有 4 / 3 张表
--   2) 列：\d table_metadata                  应含 business_owner、tech_owner、topic、
--                                            visibility、category_code、asset_level
--          \d column_metadata                 应含 business_desc、security_level
--          \d data_quality_check_result       应含 rule_type、rule_name
--   3) 菜单：SELECT menu_code FROM sys_menu_permission WHERE menu_code IN
--            ('data_quality','asset_catalog');  应返回 2 行
--   4) 授权：SELECT r.role_code, m.menu_code FROM sys_role_menu_permission rm
--            JOIN sys_role r ON r.id = rm.role_id
--            JOIN sys_menu_permission m ON m.id = rm.menu_permission_id
--            WHERE m.menu_code IN ('data_quality','asset_catalog');
--          asset_catalog 应覆盖全部未删除角色，data_quality 应覆盖
--          DATA_ARCHITECT / DATA_DEV_ENGINEER / OPS_ENGINEER
-- ---------------------------------------------------------------------------

-- ===========================================================================
-- 一、新增表：资产目录（asset_*）
-- ===========================================================================

-- 资产分类：分层与业务域。正常由表名规范解析自动生成，本表仅存放人工覆盖与显示名
CREATE TABLE IF NOT EXISTS asset_category (
  id BIGSERIAL,
  category_code VARCHAR(64) NOT NULL,
  category_name VARCHAR(100) NOT NULL,
  parent_code VARCHAR(64),
  category_kind VARCHAR(16) NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  remark VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_category_code UNIQUE (category_code)
);
CREATE INDEX IF NOT EXISTS idx_asset_category_kind ON asset_category (category_kind);
CREATE INDEX IF NOT EXISTS idx_asset_category_parent ON asset_category (parent_code);
COMMENT ON TABLE asset_category IS '资产分类表(分层/业务域，支持人工覆盖)';
COMMENT ON COLUMN asset_category.category_kind IS '分类维度(layer=数仓分层 domain=业务域)';

-- 资产收藏
CREATE TABLE IF NOT EXISTS asset_bookmark (
  id BIGSERIAL,
  user_id BIGINT NOT NULL,
  asset_type VARCHAR(16) NOT NULL,
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_bookmark UNIQUE (user_id, asset_type, asset_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_bookmark_user ON asset_bookmark (user_id);
CREATE INDEX IF NOT EXISTS idx_asset_bookmark_table ON asset_bookmark (table_metadata_id);
COMMENT ON TABLE asset_bookmark IS '资产收藏表';
COMMENT ON COLUMN asset_bookmark.asset_type IS '资产类型(table/column/indicator)';

-- 资产访问埋点：高频写入的轻量表，刻意与 operation_audit_log 分离，
-- 避免浏览行为淹没真正的操作留痕
CREATE TABLE IF NOT EXISTS asset_access_log (
  id BIGSERIAL,
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
COMMENT ON TABLE asset_access_log IS '资产访问埋点表(仅追加，按日汇总进 asset_usage_daily)';
COMMENT ON COLUMN asset_access_log.access_type IS '访问类型(browse=浏览 read=读取 bookmark=收藏 query=带查询)';

-- ===========================================================================
-- 二、新增表：数据质量（dq_*）
-- ===========================================================================

-- 规则模板：内置模板即 7 类规则类型（template_code 与规则类型编码同名），
-- 自定义模板仍需归属 7 类之一（差异在 SQL 模板，不在类型），否则覆盖率统计会失去口径
CREATE TABLE IF NOT EXISTS dq_rule_template (
  id BIGSERIAL,
  template_code VARCHAR(64) NOT NULL,
  template_name VARCHAR(200) NOT NULL,
  rule_type VARCHAR(64) NOT NULL,
  template_kind VARCHAR(16) NOT NULL DEFAULT 'custom',
  sql_template TEXT,
  field_types VARCHAR(255),
  param_schema TEXT,
  remark VARCHAR(500),
  enabled SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_dq_template_code UNIQUE (template_code)
);
CREATE INDEX IF NOT EXISTS idx_dq_template_rule_type ON dq_rule_template (rule_type);
COMMENT ON TABLE dq_rule_template IS '数据质量规则模板表';
COMMENT ON COLUMN dq_rule_template.rule_type IS '规则类型(7类之一:PRIMARY_KEY_UNIQUE/NOT_NULL/VALUE_RANGE/ROW_COUNT_FLUCTUATION/ROW_COUNT_MOM/UPSTREAM_DOWNSTREAM_RECON/METRIC_CONSISTENCY)';
COMMENT ON COLUMN dq_rule_template.template_kind IS '模板类别(builtin=内置 custom=自定义)';

-- 监控规则：模板的具体应用，含阈值与处置方式
CREATE TABLE IF NOT EXISTS dq_rule (
  id BIGSERIAL,
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
  owner_name VARCHAR(100),
  enabled SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_rule_table ON dq_rule (database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_dq_rule_type ON dq_rule (rule_type);
CREATE INDEX IF NOT EXISTS idx_dq_rule_template ON dq_rule (template_id);
CREATE INDEX IF NOT EXISTS idx_dq_rule_datasource ON dq_rule (datasource_id);
COMMENT ON TABLE dq_rule IS '数据质量监控规则表';
COMMENT ON COLUMN dq_rule.compare_mode IS '比较方式(EQ/NE/GT/GTE/LT/LTE)';
COMMENT ON COLUMN dq_rule.dispose_mode IS '处置方式(BLOCK=阻断下游 ALERT=告警)，与提示词 S2 的「处置方式」同义';
COMMENT ON COLUMN dq_rule.warn_threshold IS '橙色(警告)阈值，空表示不设';

-- 质检任务运行实例
CREATE TABLE IF NOT EXISTS dq_run_instance (
  id BIGSERIAL,
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
  pass_count INTEGER NOT NULL DEFAULT 0,
  warn_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
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

-- 规则级校验结果：对应提示词「校验输出须可定位：规则名/校验对象/阈值/实际值/是否通过」
CREATE TABLE IF NOT EXISTS dq_rule_result (
  id BIGSERIAL,
  run_id BIGINT NOT NULL,
  rule_id BIGINT,
  rule_name VARCHAR(200) NOT NULL,
  rule_type VARCHAR(64) NOT NULL,
  check_object VARCHAR(500),
  threshold VARCHAR(255),
  actual_value VARCHAR(500),
  status VARCHAR(16) NOT NULL DEFAULT 'PASS',
  severity VARCHAR(16),
  exec_sql TEXT,
  error_msg VARCHAR(1000),
  cost_ms BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_result_run ON dq_rule_result (run_id);
CREATE INDEX IF NOT EXISTS idx_dq_result_rule ON dq_rule_result (rule_id);
CREATE INDEX IF NOT EXISTS idx_dq_result_status ON dq_rule_result (status);
COMMENT ON TABLE dq_rule_result IS '数据质量规则级校验结果表';
COMMENT ON COLUMN dq_rule_result.status IS '校验状态(PASS=通过 WARN=橙色异常 ERROR=红色异常 FAILED=校验失败)';
COMMENT ON COLUMN dq_rule_result.check_object IS '校验对象(表.字段 或 上下游两表)';

-- ===========================================================================
-- 三、既有表新增列（均为可空或带默认值，不影响既有数据与写入）
-- ===========================================================================

-- 库表元数据：补资产治理属性。owner/topic/visibility 的字段名与语义对齐既有
-- indicators 表（business_owner/tech_owner/topic/visibility），避免同平台两套 owner 语义
-- 宽度说明：indicators.business_owner/tech_owner 是 varchar(1024) 且未建索引；
--   本表的负责人是资产目录的筛选维度，必须可建索引，而 MySQL/InnoDB 的索引键上限为
--   3072 字节，utf8mb4 下 varchar(1024) 建索引会直接报 "Specified key was too long"，
--   故此处取 varchar(255)。字段名与语义仍与 indicators 对齐，仅宽度不同。
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS business_owner VARCHAR(255);
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS tech_owner VARCHAR(255);
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS topic VARCHAR(200);
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'internal';
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS category_code VARCHAR(64);
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS asset_level VARCHAR(16);
COMMENT ON COLUMN table_metadata.business_owner IS '业务负责人(语义对齐 indicators.business_owner；本表按负责人筛选，需可建索引，故宽度取 255)';
COMMENT ON COLUMN table_metadata.tech_owner IS '技术负责人(同上)';
COMMENT ON COLUMN table_metadata.topic IS '所属主题域(对齐 indicators.topic)';
COMMENT ON COLUMN table_metadata.visibility IS '可见性(public/internal/restricted，对齐 indicators.visibility)';
COMMENT ON COLUMN table_metadata.category_code IS '人工覆盖的分类编码；为空表示采用表名规范解析结果';
COMMENT ON COLUMN table_metadata.asset_level IS '资产等级(L1~L5)';
CREATE INDEX IF NOT EXISTS idx_table_metadata_owner ON table_metadata (business_owner);
CREATE INDEX IF NOT EXISTS idx_table_metadata_category ON table_metadata (category_code);

-- 与 init 保持一致：visibility 取值受 CHECK 约束（PG 无 enum 类型，用 CHECK 等价表达）
-- 新列已有 DEFAULT 'internal' 且 NOT NULL，存量行均为合法值，加约束不会失败
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'chk_table_metadata_visibility'
  ) THEN
    ALTER TABLE table_metadata
      ADD CONSTRAINT chk_table_metadata_visibility
      CHECK (visibility IN ('public','internal','restricted'));
  END IF;
END $$;

-- 字段元数据：业务描述（区别于技术注释）与安全等级
ALTER TABLE column_metadata ADD COLUMN IF NOT EXISTS business_desc VARCHAR(1000);
ALTER TABLE column_metadata ADD COLUMN IF NOT EXISTS security_level VARCHAR(16);
COMMENT ON COLUMN column_metadata.business_desc IS '业务描述(与 column_comment 的技术注释区分，供资产目录展示)';
COMMENT ON COLUMN column_metadata.security_level IS '字段安全等级';

-- 质量结果表：兼容改造。check_item 是自由文本，无法与 7 类规则类型对齐，
-- 也无法统计覆盖率，因此补两列作为兼容桥；新链路写入 dq_rule_result
ALTER TABLE data_quality_check_result ADD COLUMN IF NOT EXISTS rule_type VARCHAR(64);
ALTER TABLE data_quality_check_result ADD COLUMN IF NOT EXISTS rule_name VARCHAR(200);
COMMENT ON COLUMN data_quality_check_result.rule_type IS '规则类型(7类之一)，历史数据为空表示未分类';
COMMENT ON COLUMN data_quality_check_result.rule_name IS '规则名';
CREATE INDEX IF NOT EXISTS idx_dqc_rule_type ON data_quality_check_result (rule_type);

-- ===========================================================================
-- 四、菜单与角色授权
-- ===========================================================================
-- 说明：init/dataforge-*.sql 只建菜单表结构、不含菜单数据，菜单原先仅存在于
-- testdata 脚本中。因此已上线环境必须由本脚本补菜单，否则新入口无权限码可取。
--
-- 升级兼容（重要）：
--   前端的菜单可见性有一条兜底 —— 当用户的 menuTags 覆盖「全部一级菜单码」时直接
--   视为可见全部，否则退化为按码过滤。一级菜单码集合由前端映射表演进，本次由 9 个
--   增至 11 个。补授规则如下，可保证「存量角色可见菜单数不减少」：
--     * asset_catalog 授予全部未删除角色 → 任何角色至少新增 1 个可见入口；
--     * data_quality 仅授予 ARCHITECT / DEV / OPS 三类治理角色；
--   SUPER_ADMIN 不需要显式授权（前端按 isSuperAdmin 直接取全部一级菜单码），
--   这里一并插入以便服务端菜单权限接口返回一致。

INSERT INTO sys_menu_permission (parent_id, menu_name, menu_code, path, icon, sort_order, visible, status)
VALUES (0, '数据质量', 'data_quality', '/data_quality', '', 4, 1, 1)
ON CONFLICT (menu_code) DO NOTHING;

INSERT INTO sys_menu_permission (parent_id, menu_name, menu_code, path, icon, sort_order, visible, status)
VALUES (0, '资产目录', 'asset_catalog', '/asset_catalog', '', 5, 1, 1)
ON CONFLICT (menu_code) DO NOTHING;

-- 资产目录：对全部角色开放（业务需求方与数据分析师最需要找数认数，
-- 只给治理角色会直接违背「业务方提需求」的产品定位）
INSERT INTO sys_role_menu_permission (role_id, menu_permission_id)
SELECT r.id, m.id
FROM sys_role r
CROSS JOIN sys_menu_permission m
WHERE m.menu_code = 'asset_catalog'
  AND r.deleted = 0
  AND m.deleted = 0
ON CONFLICT (role_id, menu_permission_id) DO NOTHING;

-- 数据质量：限治理角色（架构师定规则、开发实现、运维处置告警与阻断）
INSERT INTO sys_role_menu_permission (role_id, menu_permission_id)
SELECT r.id, m.id
FROM sys_role r
CROSS JOIN sys_menu_permission m
WHERE m.menu_code = 'data_quality'
  AND r.deleted = 0
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN', 'DATA_ARCHITECT', 'DATA_DEV_ENGINEER', 'OPS_ENGINEER')
ON CONFLICT (role_id, menu_permission_id) DO NOTHING;

-- ===========================================================================
-- 五、内置规则模板种子（7 类规则类型，与前端 types/quality.ts 及
--     智能体提示词同源；template_code 与规则类型编码同名）
-- ===========================================================================

INSERT INTO dq_rule_template (template_code, template_name, rule_type, template_kind, remark, enabled) VALUES
('PRIMARY_KEY_UNIQUE', '主键唯一性', 'PRIMARY_KEY_UNIQUE', 'builtin', '主键/唯一键重复检测', 1),
('NOT_NULL', '非空', 'NOT_NULL', 'builtin', '关键字段空值与空串检测', 1),
('VALUE_RANGE', '值域', 'VALUE_RANGE', 'builtin', '枚举字典取值与数值区间合法性', 1),
('ROW_COUNT_FLUCTUATION', '行数波动', 'ROW_COUNT_FLUCTUATION', 'builtin', '当日行数相对历史基线的涨跌幅', 1),
('ROW_COUNT_MOM', '行数环比', 'ROW_COUNT_MOM', 'builtin', '当期行数与上期行数的对比', 1),
('UPSTREAM_DOWNSTREAM_RECON', '上下游对账', 'UPSTREAM_DOWNSTREAM_RECON', 'builtin', '上下游同口径记录数/金额一致性', 1),
('METRIC_CONSISTENCY', '口径一致性', 'METRIC_CONSISTENCY', 'builtin', '同一指标在不同表、不同层之间的计算结果一致性', 1)
ON CONFLICT (template_code) DO NOTHING;

-- 分层分类种子：与提示词的「分层规范」一致（ODS/DWD/DWS/ADS/DIM/DQC/TMP）
INSERT INTO asset_category (category_code, category_name, parent_code, category_kind, sort_order) VALUES
('ods', 'ODS 贴源层', NULL, 'layer', 1),
('dwd', 'DWD 明细层', NULL, 'layer', 2),
('dws', 'DWS 汇总层', NULL, 'layer', 3),
('ads', 'ADS 应用层', NULL, 'layer', 4),
('dim', 'DIM 维度层', NULL, 'layer', 5),
('dqc', 'DQC 质量层', NULL, 'layer', 6),
('tmp', 'TMP 临时层', NULL, 'layer', 7)
ON CONFLICT (category_code) DO NOTHING;
