-- ============================================================
-- 数据开发模块 - 统一TreeNode模型数据库重构
-- 版本: 3.0.0
-- 重构时间: 2026-05-07
-- 说明: 按照统一数据模型规范重新设计 dev_script 表
--       解决字段命名混乱、文件/文件夹混存等问题
-- ============================================================

-- ============================================================
-- 一、备份旧表数据（重要！执行前先备份）
-- ============================================================

-- CREATE TABLE dev_script_backup AS SELECT * FROM dev_script;

-- ============================================================
-- 二、删除旧表（如果需要全新开始）
-- 注意：生产环境请谨慎操作，建议使用迁移脚本
-- ============================================================

-- DROP TABLE IF EXISTS dev_script;

-- ============================================================
-- 三、新表设计：dev_tree_node（统一树节点表）
-- 设计原则：
--   1. 单表存储文件和文件夹（保持方案A）
--   2. 字段命名与前端 TreeNode 接口完全一致
--   3. 通过 type 字段区分 'file' | 'folder'
--   4. 文件专用字段允许为 NULL（文件夹时不需要）
-- ============================================================

CREATE TABLE IF NOT EXISTS `dev_tree_node` (
    -- ==================== 基础字段（通用）====================
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    
    -- 核心标识
    `name` VARCHAR(200) NOT NULL COMMENT '名称（文件名或文件夹名，统一字段）',
    `type` ENUM('file', 'folder') NOT NULL DEFAULT 'file' COMMENT '节点类型：file-文件, folder-文件夹',
    
    -- 层级关系
    `path` VARCHAR(500) NOT NULL COMMENT '完整路径（如:/etl/ods/user_sync.sql）',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父节点ID（NULL表示根级别）',
    `depth` INT NOT NULL DEFAULT 0 COMMENT '目录深度（0-根级, 1-一级子目录, 2-二级子目录）',
    
    -- 项目关联
    `project_id` BIGINT NOT NULL COMMENT '所属项目ID',
    
    -- 描述信息
    `description` VARCHAR(500) DEFAULT NULL COMMENT '描述信息',
    
    -- ==================== 文件专用字段（文件夹时为NULL）====================
    `language` VARCHAR(50) DEFAULT NULL COMMENT '编程语言（sql/python/shell/java/scala），仅文件有效',
    `content` LONGTEXT DEFAULT NULL COMMENT '文件内容（仅文件有效，大文本存储）',
    
    -- 执行配置（仅文件有效）
    `datasource_id` BIGINT DEFAULT NULL COMMENT '默认数据源ID（仅文件有效）',
    `engine_id` BIGINT DEFAULT NULL COMMENT '执行引擎ID（仅文件有效）',
    `jar_dependencies` JSON DEFAULT NULL COMMENT 'JAR包依赖列表（JSON数组，仅Java/Scala文件有效）',
    
    -- 版本管理（仅文件有效）
    `version_count` INT NOT NULL DEFAULT 0 COMMENT '版本总数（仅文件有效）',
    `current_version` INT NOT NULL DEFAULT 0 COMMENT '当前版本号（仅文件有效）',
    `latest_version_id` BIGINT DEFAULT NULL COMMENT '最新版本记录ID（仅文件有效）',
    
    -- ==================== 系统字段 ====================
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-正常, 0-禁用, -1-已删除',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_by_name` VARCHAR(50) DEFAULT NULL COMMENT '创建人姓名',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by` BIGINT DEFAULT NULL COMMENT '更新人ID',
    `update_by_name` VARCHAR(50) DEFAULT NULL COMMENT '更新人姓名',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-未删除, 1-已删除',
    
    -- ==================== 主键和索引 ====================
    PRIMARY KEY (`id`),
    
    -- 业务索引
    UNIQUE KEY `uk_project_path` (`project_id`, `path`, `deleted`) COMMENT '项目内路径唯一约束',
    KEY `idx_project_id` (`project_id`) COMMENT '项目ID索引（快速查询项目下所有节点）',
    KEY `idx_parent_id` (`parent_id`) COMMENT '父节点索引（快速查询子节点）',
    KEY `idx_type` (`type`) COMMENT '类型索引（区分文件和文件夹查询）',
    KEY `idx_depth` (`depth`) COMMENT '深度索引（限制层级查询）',
    KEY `idx_language` (`language`) COMMENT '语言索引（按语言筛选文件）',
    KEY `idx_project_type` (`project_id`, `type`) COMMENT '项目+类型复合索引（常用查询场景）',
    KEY `idx_parent_project` (`parent_id`, `project_id`) COMMENT '父子+项目复合索引',
    KEY `idx_create_time` (`create_time`) COMMENT '创建时间索引（排序用）',
    KEY `idx_deleted_status` (`deleted`, `status`) COMMENT '删除状态+状态复合索引'
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一树节点表（文件/文件夹）';

-- ============================================================
-- 四、添加表注释和字段说明
-- ============================================================

ALTER TABLE `dev_tree_node` 
    COMMENT = '统一树节点表 - 存储项目下的所有文件和文件夹\n'
               '遵循 TreeNode 统一数据模型\n'
               '支持扁平数组返回，前端构建树形结构';

-- ============================================================
-- 五、插入示例数据（验证新表结构）
-- ============================================================

-- 示例1：项目根目录（文件夹）
INSERT INTO `dev_tree_node` 
    (name, type, path, parent_id, depth, project_id, description, status, create_by, create_by_name) 
VALUES 
    ('数仓ETL项目', 'folder', '/', NULL, 0, 1, '数仓ETL开发项目', 1, 1, '系统管理员');

-- 示例2：一级子目录（ODS层、DWD层等）
INSERT INTO `dev_tree_node` 
    (name, type, path, parent_id, depth, project_id, description, status, create_by, create_by_name) 
VALUES 
    ('ODS层', 'folder', '/ods', 1, 1, 1, '原始数据层 - 存储从源系统同步的原始数据', 1, 1, '系统管理员'),
    ('DWD层', 'folder', '/dwd', 1, 1, 1, '明细数据层 - 清洗和标准化后的明细数据', 1, 1, '系统管理员'),
    ('DWS层', 'folder', '/dws', 1, 1, 1, '汇总数据层 - 按维度汇总的汇总数据', 1, 1, '系统管理员'),
    ('ADS层', 'folder', '/ads', 1, 1, 1, '应用数据层 - 面向应用的主题宽表', 1, 1, '系统管理员');

-- 示例3：二级子目录及文件
INSERT INTO `dev_tree_node` 
    (name, type, path, parent_id, depth, project_id, language, content, datasource_id, engine_id, version_count, current_version, description, status, create_by, create_by_name) 
VALUES 
    -- ODS层下的SQL文件
    ('ods_user_sync.sql', 'file', '/ods/ods_user_sync.sql', 2, 2, 1, 'sql', 
     '-- ODS层用户数据同步脚本\nSELECT * FROM source_users WHERE dt = ''${biz_date}'';', 
     1, 5, 3, 3, '用户数据同步到ODS层', 1, 1, '系统管理员'),
    
    ('ods_order_sync.sql', 'file', '/ods/ods_order_sync.sql', 2, 2, 1, 'sql',
     '-- ODS层订单数据同步脚本\nSELECT * FROM source_orders WHERE dt = ''${biz_date}'';',
     1, 5, 2, 2, '订单数据同步到ODS层', 1, 1, '系统管理员'),
    
    -- DWD层下的SQL文件
    ('dwd_user_detail.sql', 'file', '/dwd/dwd_user_detail.sql', 3, 2, 1, 'sql',
     '-- DWD层用户明细宽表\nSELECT \n  user_id,\n  user_name,\n  phone,\n  email,\n  register_time\nFROM ods_user_sync\nWHERE dt = ''${biz_date}'';',
     1, 5, 5, 5, '用户明细宽表 - 包含用户基础信息', 1, 1, '系统管理员'),
    
    ('dwd_order_detail.sql', 'file', '/dwd/dwd_order_detail.sql', 3, 2, 1, 'sql',
     '-- DWD层订单明细宽表\nSELECT \n  order_id,\n  user_id,\n  product_id,\n  amount,\n  order_status,\n  create_time\nFROM ods_order_sync\nWHERE dt = ''${biz_date}'';',
     1, 5, 4, 4, '订单明细宽表 - 包含订单完整信息', 1, 1, '系统管理员'),
    
    -- DWS层下的SQL文件
    ('dws_user_summary.sql', 'file', '/dws/dws_user_summary.sql', 4, 2, 1, 'sql',
     '-- DWS层用户日汇总表\nSELECT \n  user_id,\n  COUNT(*) AS order_cnt,\n  SUM(amount) AS total_amount,\n  MAX(create_time) AS last_order_time\nFROM dwd_order_detail\nWHERE dt = ''${biz_date}''\nGROUP BY user_id;',
     1, 5, 2, 2, '用户日汇总统计 - 订单数量和金额汇总', 1, 1, '系统管理员'),
    
    -- ADS层下的SQL文件
    ('ads_daily_report.sql', 'file', '/ads/ads_daily_report.sql', 5, 2, 1, 'sql',
     '-- ADS层每日运营报表\nSELECT \n  COUNT(DISTINCT user_id) AS active_users,\n  COUNT(*) AS total_orders,\n  SUM(total_amount) AS gmv\nFROM dws_user_summary\nWHERE dt = ''${biz_date}'';',
     1, 5, 6, 6, '每日运营报表 - GMV、活跃用户等核心指标', 1, 1, '系统管理员');

-- 示例4：另一个项目的文件（包含Python和Java文件）
INSERT INTO `dev_tree_node` (name, type, path, parent_id, depth, project_id, description, status, create_by, create_by_name)
VALUES ('实时计算项目', 'folder', '/', NULL, 0, 2, 'Flink实时计算项目', 1, 1, '系统管理员');

INSERT INTO `dev_tree_node` 
    (name, type, path, parent_id, depth, project_id, language, content, engine_id, version_count, current_version, jar_dependencies, description, status, create_by, create_by_name) 
VALUES 
    ('FlinkStreamETL.java', 'file', '/FlinkStreamETL.java', 7, 1, 2, 'java',
     '// Flink实时ETL作业\npublic class StreamETLJob {\n    public static void main(String[] args) {\n        // TODO: 实现Flink流处理逻辑\n    }\n}',
     4, 1, 1, '["spark-sql_2.12", "flink-java"]', 'Flink实时数据处理作业', 1, 1, '系统管理员'),
    
    ('data_cleaning.py', 'file', '/data_cleaning.py', 7, 1, 2, 'python',
     '# 数据清洗脚本\nimport pandas as pd\n\ndef clean_data(df):\n    # TODO: 实现数据清洗逻辑\n    pass',
     NULL, NULL, 0, 0, NULL, 'Python数据清洗脚本', 1, 1, '系统管理员');

-- ============================================================
-- 六、验证查询示例（测试新表结构）
-- ============================================================

-- 查询1：获取某个项目的所有节点（扁平列表）
-- SELECT id, name, type, path, parent_id, depth, language 
-- FROM dev_tree_node 
-- WHERE project_id = 1 AND deleted = 0 
-- ORDER BY depth, path;

-- 查询2：只查询文件夹
-- SELECT id, name, path, depth 
-- FROM dev_tree_node 
-- WHERE project_id = 1 AND type = 'folder' AND deleted = 0 
-- ORDER BY path;

-- 查询3：只查询某种语言的文件
-- SELECT id, name, path, language, version_count 
-- FROM dev_tree_node 
-- WHERE project_id = 1 AND type = 'file' AND language = 'sql' AND deleted = 0 
-- ORDER BY path;

-- 查询4：查询某个文件夹下的直接子节点
-- SELECT id, name, type, language 
-- FROM dev_tree_node 
-- WHERE parent_id = 2 AND deleted = 0  -- parent_id=2 是 ODS层文件夹
-- ORDER BY type, name;

-- 查询5：统计项目文件数量
-- SELECT 
--     type,
--     COUNT(*) as count,
--     CASE WHEN type='folder' THEN '文件夹' ELSE '文件' END as type_name
-- FROM dev_tree_node 
-- WHERE project_id = 1 AND deleted = 0 
-- GROUP BY type;

-- ============================================================
-- 七、性能优化建议（可选）
-- ============================================================

-- 1. 如果单表数据量超过100万行，考虑按项目分表
-- 2. 对于content字段的大文本，考虑使用压缩存储或外部对象存储
-- 3. 定期清理已删除的数据（deleted=1）

-- ============================================================
-- 完成提示
-- ============================================================
SELECT '✅ dev_tree_node 表创建完成！' AS message,
       (SELECT COUNT(*) FROM dev_tree_node WHERE deleted = 0) AS total_nodes,
       (SELECT COUNT(*) FROM dev_tree_node WHERE type = 'folder' AND deleted = 0) AS folder_count,
       (SELECT COUNT(*) FROM dev_tree_node WHERE type = 'file' AND deleted = 0) AS file_count;
