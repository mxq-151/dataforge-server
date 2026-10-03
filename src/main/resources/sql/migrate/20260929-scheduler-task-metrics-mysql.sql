-- ---------------------------------------------------------------------------
-- 数据迁移：调度任务运行指标表（MySQL / MariaDB 版）
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
-- 执行方式（MySQL / MariaDB）
--   mysql -h 127.0.0.1 -P 3306 -u root -p dataforge \
--     < 20260929-scheduler-task-metrics-mysql.sql
--   可重复执行（CREATE TABLE IF NOT EXISTS 本身幂等）。
--
--   注意：init/dataforge-mysql.sql、init/dataforge-mariadb.sql 已同步包含本表，
--   全新部署无需执行本脚本；本脚本只用于已存在库的增量升级。
--
-- 校验
--   SHOW CREATE TABLE scheduler_task_run_daily;
--   SELECT stat_date, COUNT(*) FROM scheduler_task_run_daily GROUP BY stat_date;
-- ---------------------------------------------------------------------------

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `scheduler_task_run_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日(任务实际开始日)',
  `project_code` varchar(100) NOT NULL DEFAULT '' COMMENT '调度项目编码',
  `project_name` varchar(200) DEFAULT NULL COMMENT '调度项目名',
  `workflow_code` bigint DEFAULT NULL COMMENT '工作流编码',
  `workflow_name` varchar(200) NOT NULL DEFAULT '' COMMENT '工作流名',
  `node_name` varchar(200) NOT NULL DEFAULT '' COMMENT '节点(任务)名',
  `task_type` varchar(40) NOT NULL DEFAULT '' COMMENT '任务类型(SPARK/HIVE/FLINK等)',
  `task_run_count` int NOT NULL DEFAULT 0 COMMENT '任务实例数(窗口内执行次数)',
  `delayed_count` int NOT NULL DEFAULT 0 COMMENT '延迟实例数(实际开始晚于计划开始)',
  `success_count` int NOT NULL DEFAULT 0 COMMENT '成功实例数',
  `failed_count` int NOT NULL DEFAULT 0 COMMENT '失败实例数',
  `total_duration_seconds` bigint NOT NULL DEFAULT 0 COMMENT '累计运行时长(秒)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduler_task_run_daily` (`stat_date`,`project_code`,`workflow_name`,`node_name`,`task_type`),
  KEY `idx_scheduler_task_run_date` (`stat_date`),
  KEY `idx_scheduler_task_run_project` (`stat_date`,`project_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='调度任务运行日汇总表(T+1，由 DolphinScheduler 采集)';
