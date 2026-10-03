-- ---------------------------------------------------------------------------
-- 数据迁移：调度任务运行指标表（PostgreSQL 版）
--
-- 背景
--   「资产概况」的「消耗计算资源 / 运行时长 TOP 任务」与「任务延迟率」两个看板
--   需要跨项目、跨时间窗口聚合调度运行记录。原先只能在详情页按表名实时反查
--   DolphinScheduler（AssetProducerService），无法支撑窗口聚合，故新增本表由
--   采集任务（SchedulerTaskMetricsCollectTask，每日 03:30）落库。
--
--   资源口径说明：cpuHours / memoryGbHours 依赖 YARN，本平台 Spark 以 local 模式
--   运行、没有 YARN，故这两项不落库也不返回（前端显示「—」）；byQueue 同理为空数组。
--
-- 影响
--   - 新增 1 张表：scheduler_task_run_daily；不修改、不删除任何既有数据与列。
--   - 采集为幂等覆盖写（重拉近 30 天），保留 180 天。
--   - 依赖配置项 scheduler.dolphinscheduler.base-url / .token；
--     未配置时采集任务静默跳过，接口返回空数据，不影响其余功能。
--
-- 执行方式（PostgreSQL）
--   PGPASSWORD=123456 psql -h 127.0.0.1 -p 5432 -U postgres -d dataforge \
--     -f 20260929-scheduler-task-metrics.sql
--   可重复执行（CREATE TABLE/INDEX IF NOT EXISTS）。
--
--   注意：init/dataforge-postgresql.sql 已同步包含本表，
--   全新部署无需执行本脚本；本脚本只用于已存在库的增量升级。
--
-- 校验
--   \d scheduler_task_run_daily
--   SELECT stat_date, COUNT(*) FROM scheduler_task_run_daily GROUP BY stat_date;
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS scheduler_task_run_daily (
  id BIGSERIAL NOT NULL,
  stat_date DATE NOT NULL,
  project_code VARCHAR(100) NOT NULL DEFAULT '',
  project_name VARCHAR(200),
  workflow_code BIGINT,
  workflow_name VARCHAR(200) NOT NULL DEFAULT '',
  node_name VARCHAR(200) NOT NULL DEFAULT '',
  task_type VARCHAR(40) NOT NULL DEFAULT '',
  task_run_count INTEGER NOT NULL DEFAULT 0,
  delayed_count INTEGER NOT NULL DEFAULT 0,
  success_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  total_duration_seconds BIGINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_scheduler_task_run_daily UNIQUE (stat_date, project_code, workflow_name, node_name, task_type)
);
CREATE INDEX IF NOT EXISTS idx_scheduler_task_run_date ON scheduler_task_run_daily (stat_date);
CREATE INDEX IF NOT EXISTS idx_scheduler_task_run_project ON scheduler_task_run_daily (stat_date, project_code);

COMMENT ON TABLE scheduler_task_run_daily IS '调度任务运行日汇总表(T+1，由 DolphinScheduler 采集)';
COMMENT ON COLUMN scheduler_task_run_daily.stat_date IS '统计日(任务实际开始日)';
COMMENT ON COLUMN scheduler_task_run_daily.delayed_count IS '延迟实例数(实际开始晚于计划开始)';
