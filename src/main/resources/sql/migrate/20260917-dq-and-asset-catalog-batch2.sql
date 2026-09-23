-- ---------------------------------------------------------------------------
-- 数据迁移：「数据质量」与「资产目录」两个模块（第二批 / 第三批）
--
-- 背景
--   第一批已落地规则模板、监控规则、运行实例、规则级结果，以及资产分类、
--   收藏、访问埋点。但前端契约（dataforge/src/api/quality.ts、assetCatalog.ts）
--   里还有几块落点缺失，本批补齐：
--     1) 数据质量：质检任务（规则集的执行计划）、任务与规则关联、告警订阅与
--        告警发送记录。没有 dq_task 就无法把「一组规则 + 触发方式 + 处置策略」
--        组织成一次可重复执行的计划，手动触发也无从谈起；
--     2) 资产目录：标签、使用说明（含版本历史）、使用热度日汇总。前端
--        /asset/table/{id}/usage、/asset/tag、/asset/table/{id}/doc 三个接口
--        分别依赖 asset_usage_daily、asset_tag(_rel)、asset_doc(_history)。
--
-- 影响
--   - 新增 9 张表：asset_doc / asset_doc_history / asset_tag / asset_tag_rel /
--     asset_usage_daily / dq_alert_log / dq_alert_subscription / dq_task /
--     dq_task_rule_rel；
--   - 改造 2 张表（仅新增列，均带默认值）：table_metadata、column_metadata；
--   - 写入 4 个默认资产标签（与 init 一致）；
--   - 不修改、不删除任何既有数据；第一批接口与老 /quality/check-result/* 不受影响。
--
--   说明：dq_alert_subscription 未出现在设计文档 5.1 的表清单里，但前端契约
--   GET/POST /quality/alert 返回的正是「订阅」对象（QualityAlertSubscriptionVO
--   含 notifyOn / channels / receivers / enabled），没有订阅表该接口无法实现；
--   dq_alert_log 只承担「发送记录」。故两张表并存，职责不重叠。
--
-- 执行方式（PostgreSQL）
--   PGPASSWORD=123456 psql -h 127.0.0.1 -p 5432 -U postgres -d dataforge \
--     -f 20260917-dq-and-asset-catalog-batch2.sql
--   可重复执行（CREATE TABLE/INDEX IF NOT EXISTS、ADD COLUMN IF NOT EXISTS、
--   INSERT ... ON CONFLICT DO NOTHING）。
--
--   注意：init/dataforge-*.sql 已同步包含本脚本的全部建表与新增列，
--   全新部署无需执行本脚本；本脚本只用于已上线环境的增量升级。
--
-- 校验
--   1) 表：\dt asset_tag; \dt asset_doc; \dt asset_usage_daily; \dt dq_task;
--          应分别返回 2 / 2 / 1 / 2 张（前缀匹配）
--   2) 列：\d table_metadata   应含 browse_cnt_30d、read_cnt_30d、bookmark_cnt、
--                              last_access_time
--          \d column_metadata  应含 hot_score
--   3) 种子：SELECT tag_code FROM asset_tag;  应 4 行
-- ---------------------------------------------------------------------------

BEGIN;

-- ===========================================================================
-- 一、新增表
-- ===========================================================================

CREATE TABLE IF NOT EXISTS asset_doc (
  id BIGSERIAL NOT NULL,
  table_metadata_id BIGINT NOT NULL,
  content TEXT,
  version INTEGER NOT NULL DEFAULT 1,
  update_by VARCHAR(100),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_doc_table UNIQUE (table_metadata_id)
);

CREATE TABLE IF NOT EXISTS asset_doc_history (
  id BIGSERIAL NOT NULL,
  table_metadata_id BIGINT NOT NULL,
  version INTEGER NOT NULL,
  content TEXT,
  update_by VARCHAR(100),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_asset_doc_history_table ON asset_doc_history (table_metadata_id);

CREATE TABLE IF NOT EXISTS asset_tag (
  id BIGSERIAL NOT NULL,
  tag_code VARCHAR(64) NOT NULL,
  tag_name VARCHAR(100) NOT NULL,
  color VARCHAR(32),
  remark VARCHAR(500),
  enabled SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_tag_code UNIQUE (tag_code)
);

CREATE TABLE IF NOT EXISTS asset_tag_rel (
  id BIGSERIAL NOT NULL,
  tag_id BIGINT NOT NULL,
  asset_type VARCHAR(16) NOT NULL,
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_tag_rel UNIQUE (tag_id, asset_type, asset_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_tag_rel_asset ON asset_tag_rel (asset_type, asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_tag_rel_table ON asset_tag_rel (table_metadata_id);

CREATE TABLE IF NOT EXISTS asset_usage_daily (
  id BIGSERIAL NOT NULL,
  asset_type VARCHAR(16) NOT NULL DEFAULT 'table',
  asset_id BIGINT NOT NULL,
  table_metadata_id BIGINT,
  stat_date DATE NOT NULL,
  browse_count INTEGER NOT NULL DEFAULT 0,
  read_count INTEGER NOT NULL DEFAULT 0,
  bookmark_count INTEGER NOT NULL DEFAULT 0,
  relate_count INTEGER NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_asset_usage_daily UNIQUE (asset_type, asset_id, stat_date)
);
CREATE INDEX IF NOT EXISTS idx_asset_usage_table ON asset_usage_daily (table_metadata_id);
CREATE INDEX IF NOT EXISTS idx_asset_usage_date ON asset_usage_daily (stat_date);

CREATE TABLE IF NOT EXISTS dq_alert_log (
  id BIGSERIAL NOT NULL,
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
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_alert_log_run ON dq_alert_log (run_id);
CREATE INDEX IF NOT EXISTS idx_dq_alert_log_task ON dq_alert_log (task_id);
CREATE INDEX IF NOT EXISTS idx_dq_alert_log_time ON dq_alert_log (send_time);

CREATE TABLE IF NOT EXISTS dq_alert_subscription (
  id BIGSERIAL NOT NULL,
  task_id BIGINT NOT NULL,
  notify_on VARCHAR(64) NOT NULL DEFAULT 'ERROR,FAILED',
  channels VARCHAR(255),
  receivers VARCHAR(500),
  enabled SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_alert_sub_task ON dq_alert_subscription (task_id);

CREATE TABLE IF NOT EXISTS dq_task (
  id BIGSERIAL NOT NULL,
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
  enabled SMALLINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dq_task_table ON dq_task (database_name, table_name);
CREATE INDEX IF NOT EXISTS idx_dq_task_trigger ON dq_task (trigger_type);

CREATE TABLE IF NOT EXISTS dq_task_rule_rel (
  id BIGSERIAL NOT NULL,
  task_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_dq_task_rule UNIQUE (task_id, rule_id)
);
CREATE INDEX IF NOT EXISTS idx_dq_task_rule_rule ON dq_task_rule_rel (rule_id);


-- ===========================================================================
-- 二、既有表新增列（均可空或带默认值，不影响既有数据与写入）
-- ===========================================================================

ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS browse_cnt_30d INTEGER NOT NULL DEFAULT 0;
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS read_cnt_30d INTEGER NOT NULL DEFAULT 0;
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS bookmark_cnt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE table_metadata ADD COLUMN IF NOT EXISTS last_access_time TIMESTAMP;
ALTER TABLE dq_rule_result ADD COLUMN IF NOT EXISTS metric_value VARCHAR(64);
COMMENT ON COLUMN dq_rule_result.metric_value IS '原始度量值(行数类=当期行数 跨表比对=主表值,对照值)；仅存百分比无法回推历史基线';
ALTER TABLE dq_rule ADD COLUMN IF NOT EXISTS params TEXT;
COMMENT ON COLUMN dq_rule.params IS '自定义模板占位符取值(JSON 对象)；内置模板按规则类型使用固定参数名';
ALTER TABLE column_metadata ADD COLUMN IF NOT EXISTS hot_score SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN table_metadata.browse_cnt_30d IS '近30日浏览数(由 asset_access_log 日汇总回写)';
COMMENT ON COLUMN table_metadata.read_cnt_30d IS '近30日读取数(由 asset_access_log 日汇总回写)';
COMMENT ON COLUMN table_metadata.bookmark_cnt IS '收藏数(由 asset_bookmark 回写)';
COMMENT ON COLUMN table_metadata.last_access_time IS '最近访问时间(埋点回写，用于热度排序)';
COMMENT ON COLUMN column_metadata.hot_score IS '字段热度星级(0~5，由使用记录日汇总回写)';


-- ===========================================================================
-- 三、平台级参考数据：默认资产标签（与 init 脚本一致，幂等）
-- ===========================================================================

INSERT INTO asset_tag (tag_code, tag_name, color, remark, enabled) VALUES
('CORE', '核心资产', '#c62828', '对业务有直接支撑作用的核心表', 1),
('HOT', '高频使用', '#1565c0', '近30日读取热度高', 1),
('PENDING_GOVERN', '待治理', '#f9a825', '命名不合规、未标注或无负责人等', 1),
('SENSITIVE', '敏感数据', '#6a1b9a', '含个人信息或需脱敏的字段', 1)
ON CONFLICT (tag_code) DO NOTHING;

COMMIT;
