-- =============================================================================
-- Social Media Advertisement Performance 数据导入脚本
-- 目标库: test_db (127.0.0.1 root/123456)
-- 数据源: /Users/mxq/Desktop/archive2/*.csv
-- =============================================================================

-- 1. 开启 local_infile
SET GLOBAL local_infile = 1;

-- 2. 选择数据库
USE `test_db`;

-- 3. 删除旧表(按外键依赖逆序)
DROP TABLE IF EXISTS `ad_events`;
DROP TABLE IF EXISTS `ads`;
DROP TABLE IF EXISTS `users`;
DROP TABLE IF EXISTS `campaigns`;

-- 4. 建表
CREATE TABLE `campaigns` (
  `campaign_id` varchar(50) NOT NULL COMMENT '营销活动唯一ID(主键,源数据为文本类型)',
  `name` varchar(200) NOT NULL COMMENT '营销活动名称',
  `start_date` date DEFAULT NULL COMMENT '活动开始日期(格式 YYYY-MM-DD)',
  `end_date` date DEFAULT NULL COMMENT '活动结束日期(格式 YYYY-MM-DD)',
  `duration_days` int DEFAULT NULL COMMENT '活动持续天数(整数,源数据为 sc:Integer)',
  `total_budget` decimal(15,2) DEFAULT NULL COMMENT '活动总预算(浮点数,源数据为 sc:Float,单位:美元)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
  PRIMARY KEY (`campaign_id`),
  KEY `idx_name` (`name`),
  KEY `idx_start_date` (`start_date`),
  KEY `idx_end_date` (`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销活动表-合成活动/预算/周期数据';

CREATE TABLE `ads` (
  `ad_id` varchar(50) NOT NULL COMMENT '广告唯一ID(主键,源数据为文本类型)',
  `campaign_id` varchar(50) NOT NULL COMMENT '关联营销活动ID(外键,关联 campaigns.campaign_id)',
  `ad_platform` varchar(50) DEFAULT NULL COMMENT '广告投放平台(Facebook/Instagram)',
  `ad_type` varchar(50) DEFAULT NULL COMMENT '广告形式(Image/Video/Carousel/Stories)',
  `target_gender` varchar(50) DEFAULT NULL COMMENT '目标受众性别定向',
  `target_age_group` varchar(50) DEFAULT NULL COMMENT '目标受众年龄段定向',
  `target_interests` varchar(500) DEFAULT NULL COMMENT '目标受众兴趣标签列表(逗号分隔)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
  PRIMARY KEY (`ad_id`),
  KEY `idx_campaign_id` (`campaign_id`),
  KEY `idx_ad_platform` (`ad_platform`),
  KEY `idx_ad_type` (`ad_type`),
  KEY `idx_target_gender` (`target_gender`),
  KEY `idx_target_age_group` (`target_age_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='广告创意表-合成广告/平台/定向参数数据';

CREATE TABLE `users` (
  `user_id` varchar(50) NOT NULL COMMENT '用户唯一ID(主键,源数据为文本类型)',
  `user_gender` varchar(20) DEFAULT NULL COMMENT '用户性别',
  `user_age` int DEFAULT NULL COMMENT '用户年龄(整数,源数据为 sc:Integer)',
  `age_group` varchar(50) DEFAULT NULL COMMENT '用户所属年龄段(便于分群分析)',
  `country` varchar(100) DEFAULT NULL COMMENT '用户所在国家',
  `location` varchar(200) DEFAULT NULL COMMENT '用户精确位置/城市',
  `interests` varchar(500) DEFAULT NULL COMMENT '用户兴趣标签列表(逗号分隔,用于定向)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
  PRIMARY KEY (`user_id`),
  KEY `idx_user_gender` (`user_gender`),
  KEY `idx_user_age` (`user_age`),
  KEY `idx_age_group` (`age_group`),
  KEY `idx_country` (`country`),
  KEY `idx_location` (`location`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户画像表-合成用户/人口统计/地理/兴趣数据';

CREATE TABLE `ad_events` (
  `event_id` varchar(50) NOT NULL COMMENT '交互事件唯一ID(主键,源数据为文本类型)',
  `ad_id` varchar(50) NOT NULL COMMENT '关联广告ID(外键,关联 ads.ad_id)',
  `user_id` varchar(50) NOT NULL COMMENT '关联用户ID(外键,关联 users.user_id)',
  `timestamp` datetime DEFAULT NULL COMMENT '交互发生的时间戳(源数据为 sc:Date)',
  `day_of_week` varchar(20) DEFAULT NULL COMMENT '事件发生的工作日(Monday~Sunday)',
  `time_of_day` varchar(50) DEFAULT NULL COMMENT '事件发生的时间段(如:Morning/Afternoon/Evening/Night)',
  `event_type` varchar(50) DEFAULT NULL COMMENT '交互事件类型(Impression/Click/Like/Purchase/Share 等)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
  PRIMARY KEY (`event_id`),
  KEY `idx_ad_id` (`ad_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_timestamp` (`timestamp`),
  KEY `idx_day_of_week` (`day_of_week`),
  KEY `idx_time_of_day` (`time_of_day`),
  KEY `idx_event_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='广告事件表-合成用户广告交互日志/转化漏斗数据';

-- 5. 导入数据 (CSV 表头已是 snake_case,与字段名一一对应)
-- 5.1 campaigns
LOAD DATA LOCAL INFILE '/Users/mxq/Desktop/archive2/campaigns.csv'
INTO TABLE `campaigns`
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(`campaign_id`, `name`, @start_date, @end_date, `duration_days`, @total_budget)
SET
  `start_date`   = STR_TO_DATE(@start_date, '%Y-%m-%d'),
  `end_date`     = STR_TO_DATE(@end_date, '%Y-%m-%d'),
  `total_budget` = @total_budget;

-- 5.2 ads
LOAD DATA LOCAL INFILE '/Users/mxq/Desktop/archive2/ads.csv'
INTO TABLE `ads`
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(`ad_id`, `campaign_id`, `ad_platform`, `ad_type`, `target_gender`, `target_age_group`, `target_interests`);

-- 5.3 users
LOAD DATA LOCAL INFILE '/Users/mxq/Desktop/archive2/users.csv'
INTO TABLE `users`
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(`user_id`, `user_gender`, `user_age`, `age_group`, `country`, `location`, `interests`);

-- 5.4 ad_events
LOAD DATA LOCAL INFILE '/Users/mxq/Desktop/archive2/ad_events.csv'
INTO TABLE `ad_events`
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES
(`event_id`, `ad_id`, `user_id`, `timestamp`, `day_of_week`, `time_of_day`, `event_type`);

-- 6. 验证数据导入结果
SELECT 'campaigns' AS table_name, COUNT(*) AS row_count FROM campaigns
UNION ALL SELECT 'ads', COUNT(*) FROM ads
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'ad_events', COUNT(*) FROM ad_events;
