-- =============================================================================
-- 数据集: Social Media Advertisement Performance (Synthetic)
-- 来源: https://www.kaggle.com/datasets/alperenmyung/social-media-advertisement-performance
-- 元数据: social-media-advertisement-performance-metadata.json (Croissant 1.0)
-- 说明: 合成数据集,模拟 Facebook/Instagram 广告投放性能数据,包含 4 个实体:
--       users(用户) / campaigns(营销活动) / ads(广告) / ad_events(广告事件)
-- 注意: 此为合成数据,不含任何真实用户 PII 信息。
-- 命名规范: 表名/字段名均采用 snake_case 小写命名(符合 MySQL 建表标准)
-- 字段类型依据: 元数据 JSON 中每个字段的 dataType (sc:Text/sc:Integer/sc:Float/sc:Date)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 营销活动表 (campaigns.csv) - 6 个字段
--    营销活动的战略层级信息,包括预算、起止日期、活动周期。
--    是 ads 表的父表(ads.campaign_id 引用本表)。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `campaigns`;
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

-- -----------------------------------------------------------------------------
-- 2. 广告创意表 (ads.csv) - 7 个字段
--    广告创意信息,关联到所属营销活动,包括平台、形式及定向参数。
--    campaign_id 关联 campaigns.campaign_id;ad_id 被 ad_events.ad_id 引用。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `ads`;
CREATE TABLE `ads` (
  `ad_id` varchar(50) NOT NULL COMMENT '广告唯一ID(主键,源数据为文本类型)',
  `campaign_id` varchar(50) NOT NULL COMMENT '关联营销活动ID(外键,关联 campaigns.campaign_id)',
  `ad_platform` varchar(50) DEFAULT NULL COMMENT '广告投放平台(Facebook/Instagram)',
  `ad_type` varchar(50) DEFAULT NULL COMMENT '广告形式(Image/Video/Carousel)',
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

-- -----------------------------------------------------------------------------
-- 3. 用户表 (users.csv) - 7 个字段
--    用户画像信息,包括人口统计、地理位置和兴趣标签。
--    user_id 被 ad_events.user_id 引用。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `users`;
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

-- -----------------------------------------------------------------------------
-- 4. 广告事件表 (ad_events.csv) - 7 个字段
--    用户与广告交互的中央事务日志,从曝光到购买,记录完整转化漏斗。
--    ad_id 关联 ads.ad_id;user_id 关联 users.user_id。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `ad_events`;
CREATE TABLE `ad_events` (
  `event_id` varchar(50) NOT NULL COMMENT '交互事件唯一ID(主键,源数据为文本类型)',
  `ad_id` varchar(50) NOT NULL COMMENT '关联广告ID(外键,关联 ads.ad_id)',
  `user_id` varchar(50) NOT NULL COMMENT '关联用户ID(外键,关联 users.user_id)',
  `timestamp` datetime DEFAULT NULL COMMENT '交互发生的时间戳(源数据为 sc:Date)',
  `day_of_week` varchar(20) DEFAULT NULL COMMENT '事件发生的工作日(Monday~Sunday)',
  `time_of_day` varchar(50) DEFAULT NULL COMMENT '事件发生的时间段(如:Morning/Afternoon/Evening)',
  `event_type` varchar(50) DEFAULT NULL COMMENT '交互事件类型(Impression/Click/Like/Purchase 等)',
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
