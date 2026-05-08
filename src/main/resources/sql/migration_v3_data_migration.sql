-- ============================================================
-- 数据迁移脚本：dev_script → dev_tree_node
-- 版本: 3.0.0-migration
-- 说明：将旧表数据迁移到新的统一TreeNode模型表
-- 注意：执行前请备份数据！
-- ============================================================

-- ==================== 迁移步骤 ====================

-- 步骤1：备份旧表数据
CREATE TABLE IF NOT EXISTS `dev_script_backup_v2` AS 
SELECT * FROM `dev_script`;

SELECT CONCAT('✅ 已备份旧表数据，共 ', COUNT(*), ' 条记录') AS backup_info 
FROM dev_script_backup_v2;

-- 步骤2：映射规则说明
/*
  旧字段 (dev_script)          →  新字段 (dev_tree_node)
  ──────────────────────────────────────────────────────
  id                           →  id (保持不变)
  script_name                  →  name (统一命名)
  CASE is_folder               →  type (转换逻辑)
    WHEN 1 THEN 'folder'           
    WHEN 0 THEN 'file'             
    ELSE 'file'                    
  END                           
  folder_path                  →  path (统一命名)
  parent_folder_id             →  parent_id (统一命名)
  depth                        →  depth (保持不变)
  project_id                   →  project_id (保持不变)
  description                  →  description (保持不变)
  
  -- 文件专用字段（仅当 is_folder=0 时有值）
  script_type                  →  language (统一命名，仅文件)
  content                      →  content (仅文件)
  datasource_id                →  datasource_id (仅文件)
  engine_id                    →  engine_id (仅文件)
  jar_dependencies             →  jar_dependencies (仅文件)
  version                      →  current_version (重命名)
  version                      →  version_count (假设版本数=version值)
  latest_version_id            →  latest_version_id (保持不变)
  
  -- 系统字段
  status                       →  status (保持不变)
  create_by                    →  create_by (保持不变)
  create_time                  →  create_time (保持不变)
  update_by                    →  update_by (保持不变)
  update_time                  →  update_time (保持不变)
  deleted                      →  deleted (BaseEntity字段)
*/

-- 步骤3：执行数据迁移
CREATE TABLE IF NOT EXISTS `dev_tree_node` 
 AS
SELECT 
    script_name AS name,
    CASE 
        WHEN is_folder = 1 THEN 'folder'
        WHEN is_folder = 0 THEN 'file'
        ELSE 'file'
    END AS type,
    COALESCE(folder_path, CONCAT('/', script_name)) AS path,
    parent_folder_id AS parent_id,
    COALESCE(depth, 0) AS depth,
    project_id,
    description,
    
    -- 文件专用字段（根据is_folder条件判断）
    CASE WHEN is_folder = 0 THEN script_type ELSE NULL END AS language,
    CASE WHEN is_folder = 0 THEN content ELSE NULL END AS content,
    CASE WHEN is_folder = 0 THEN datasource_id ELSE NULL END AS datasource_id,
    CASE WHEN is_folder = 0 THEN engine_id ELSE NULL END AS engine_id,
    CASE WHEN is_folder = 0 THEN jar_dependencies ELSE NULL END AS jar_dependencies,
    
    CASE WHEN is_folder = 0 THEN COALESCE(version, 0) ELSE 0 END AS version_count,
    CASE WHEN is_folder = 0 THEN COALESCE(version, 0) ELSE 0 END AS current_version,
    CASE WHEN is_folder = 0 THEN latest_version_id ELSE NULL END AS latest_version_id,
    
    status,
    create_by,
    create_time,
    update_by,
    update_time,
    COALESCE(deleted, 0) AS deleted
FROM `dev_script`
WHERE deleted = 0 OR deleted IS NULL;  -- 只迁移未删除的数据

-- 步骤4：验证迁移结果
SELECT '📊 数据迁移完成报告' AS report_title;

SELECT 
    '总记录数' AS metric,
    COUNT(*) AS value
FROM dev_tree_node
UNION ALL
SELECT 
    '文件夹数量' AS metric,
    COUNT(*) AS value
FROM dev_tree_node WHERE type = 'folder'
UNION ALL
SELECT 
    '文件数量' AS metric,
    COUNT(*) AS value
FROM dev_tree_node WHERE type = 'file'
UNION ALL
SELECT 
    'SQL文件数' AS metric,
    COUNT(*) AS value
FROM dev_tree_node WHERE language = 'sql'
UNION ALL
SELECT 
    'Python文件数' AS metric,
    COUNT(*) AS value
FROM dev_tree_node WHERE language = 'python'
UNION ALL
SELECT 
    'Java/Scala文件数' AS metric,
    COUNT(*) AS value
FROM dev_tree_node WHERE language IN ('java', 'scala')
UNION ALL
SELECT 
    'Shell文件数' AS metric,
    COUNT(*) AS value
FROM dev_tree_node WHERE language = 'shell';

-- 步骤5：抽样验证数据完整性（检查前5条记录）
SELECT '✅ 抽样验证 - 前5条记录:' AS verify_title;
SELECT 
    id, name, type, path, parent_id, depth, 
    project_id, language, version_count
FROM dev_tree_node 
ORDER BY id 
LIMIT 5;

-- 步骤6：（可选）验证新旧数据一致性
/*
SELECT 
    old.id AS old_id,
    old.script_name AS old_name,
    new.id AS new_id,
    new.name AS new_name,
    new.type AS new_type,
    CASE 
        WHEN old.script_name = new.name AND 
             CASE WHEN old.is_folder=1 THEN 'folder' ELSE 'file' END = new.type
        THEN '✅ 一致'
        ELSE '❌ 不一致'
    END AS check_result
FROM dev_script_backup_v2 old
LEFT JOIN dev_tree_node new ON old.id = new.id
LIMIT 10;
*/

-- ==================== 回滚方案（如果需要）====================
/*
-- 如果迁移出现问题，可以回滚：
-- 1. 清空新表
TRUNCATE TABLE dev_tree_node;

-- 2. 从备份恢复旧表（如果需要）
-- TRUNCATE TABLE dev_script;
-- INSERT INTO dev_script SELECT * FROM dev_script_backup_v2;
*/

-- ==================== 完成提示 ====================
SELECT '🎉 数据迁移成功完成！' AS success_message,
       (SELECT COUNT(*) FROM dev_tree_node) AS total_migrated,
       '条记录已从 dev_script 迁移到 dev_tree_node 表' AS note;
