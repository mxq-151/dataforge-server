-- ============================================================
-- 大数据开发平台 - 完整数据库初始化脚本
-- 版本: 1.0.0
-- 创建时间: 2024
-- 说明: 包含所有系统管理模块的表结构和初始数据
-- ============================================================

CREATE DATABASE IF NOT EXISTS bigdata_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE bigdata_platform;

-- ============================================================
-- 一、用户与权限体系 (RBAC)
-- ============================================================

-- 1. 用户表
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码(MD5加密存储)',
    real_name VARCHAR(50) DEFAULT NULL COMMENT '真实姓名',
    email VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    avatar VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    last_login_time DATETIME DEFAULT NULL COMMENT '最后登录时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删除 1-已删除)',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 角色表
DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码(唯一标识)',
    description VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 3. 用户角色关联表
DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_id (user_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 4. 菜单/页面权限表
DROP TABLE IF EXISTS sys_menu_permission;
CREATE TABLE sys_menu_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父级ID(0表示顶级)',
    menu_name VARCHAR(100) NOT NULL COMMENT '菜单名称',
    menu_code VARCHAR(100) NOT NULL COMMENT '菜单编码(唯一标识)',
    path VARCHAR(255) DEFAULT NULL COMMENT '路由路径',
    component VARCHAR(255) DEFAULT NULL COMMENT '组件路径',
    icon VARCHAR(100) DEFAULT NULL COMMENT '图标',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    visible TINYINT NOT NULL DEFAULT 1 COMMENT '是否可见(0-隐藏 1-显示)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_menu_code (menu_code),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单/页面权限表';

-- 5. 按钮操作权限表
DROP TABLE IF EXISTS sys_button_permission;
CREATE TABLE sys_button_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    menu_permission_id BIGINT NOT NULL COMMENT '所属菜单权限ID',
    button_name VARCHAR(50) NOT NULL COMMENT '按钮名称',
    button_code VARCHAR(100) NOT NULL COMMENT '按钮编码(如:user:add,user:edit)',
    description VARCHAR(255) DEFAULT NULL COMMENT '描述',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_button_code (button_code),
    KEY idx_menu_permission_id (menu_permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按钮操作权限表';

-- 6. 文件路径权限表
DROP TABLE IF EXISTS sys_file_permission;
CREATE TABLE sys_file_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    storage_config_id BIGINT NOT NULL COMMENT '存储配置ID',
    permission_name VARCHAR(100) NOT NULL COMMENT '权限名称(如:生产数据目录)',
    file_path VARCHAR(1000) NOT NULL COMMENT '文件/目录路径(支持通配符)',
    permission_type TINYINT NOT NULL DEFAULT 1 COMMENT '权限类型(1-可读 2-读写 3-完全控制)',
    `recursive` TINYINT NOT NULL DEFAULT 0 COMMENT '是否递归子目录(0-否 1-是)',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_storage_config_id (storage_config_id),
    KEY idx_file_path (file_path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件路径权限表';

-- 7. 库表CRUD权限表
DROP TABLE IF EXISTS sys_table_permission;
CREATE TABLE sys_table_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    table_metadata_id BIGINT NOT NULL COMMENT '库表元数据ID',
    permission_name VARCHAR(100) NOT NULL COMMENT '权限名称',
    permission_scope TINYINT NOT NULL DEFAULT 1 COMMENT '权限范围(1-整表 2-指定字段 3-指定行 4-字段+行级组合)',
    can_select TINYINT NOT NULL DEFAULT 1 COMMENT '查询权限(0-否 1-是)',
    can_insert TINYINT NOT NULL DEFAULT 0 COMMENT '插入权限(0-否 1-是)',
    can_update TINYINT NOT NULL DEFAULT 0 COMMENT '更新权限(0-否 1-是)',
    can_delete TINYINT NOT NULL DEFAULT 0 COMMENT '删除权限(0-否 1-是)',
    can_export TINYINT NOT NULL DEFAULT 0 COMMENT '导出权限(0-否 1-是)',
    row_filter_sql TEXT DEFAULT NULL COMMENT '行级过滤SQL条件(WHERE子句)',
    data_masking_rule VARCHAR(500) DEFAULT NULL COMMENT '数据脱敏规则(JSON格式)',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_table_metadata_id (table_metadata_id),
    KEY idx_permission_scope (permission_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库表CRUD权限表';

-- 8. 角色-菜单权限关联表
DROP TABLE IF EXISTS sys_role_menu_permission;
CREATE TABLE sys_role_menu_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_permission_id BIGINT NOT NULL COMMENT '菜单权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_menu (role_id, menu_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单权限关联表';

-- 9. 角色-按钮权限关联表
DROP TABLE IF EXISTS sys_role_button_permission;
CREATE TABLE sys_role_button_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    button_permission_id BIGINT NOT NULL COMMENT '按钮权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_button (role_id, button_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-按钮权限关联表';

-- 10. 角色-文件权限关联表
DROP TABLE IF EXISTS sys_role_file_permission;
CREATE TABLE sys_role_file_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    file_permission_id BIGINT NOT NULL COMMENT '文件权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_file (role_id, file_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-文件权限关联表';

-- 11. 角色-库表权限关联表
DROP TABLE IF EXISTS sys_role_table_permission;
CREATE TABLE sys_role_table_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    table_permission_id BIGINT NOT NULL COMMENT '库表权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_table (role_id, table_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-库表权限关联表';

-- ============================================================
-- 二、运维配置模块
-- ============================================================

-- 12. 数据源配置表
DROP TABLE IF EXISTS datasource_config;
CREATE TABLE datasource_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    datasource_name VARCHAR(100) NOT NULL COMMENT '数据源名称',
    datasource_type VARCHAR(50) NOT NULL COMMENT '类型(kyuubi/doris/clickhouse/es/starrock/mysql/hdfs/s3/juicefs)',
    url VARCHAR(500) NOT NULL COMMENT '连接URL',
    database_name VARCHAR(100) DEFAULT NULL COMMENT '数据库名称',
    username VARCHAR(100) DEFAULT NULL COMMENT '用户名',
    password VARCHAR(255) DEFAULT NULL COMMENT '密码(加密存储)',
    extra_params TEXT DEFAULT NULL COMMENT '额外参数(JSON格式)',
    connection_status TINYINT NOT NULL DEFAULT 0 COMMENT '连接状态(0-未测试 1-正常 2-异常)',
    last_test_time DATETIME DEFAULT NULL COMMENT '最后测试时间',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_by BIGINT DEFAULT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_datasource_type (datasource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置表';

-- 13. 存储配置表
DROP TABLE IF EXISTS storage_config;
CREATE TABLE storage_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    storage_name VARCHAR(100) NOT NULL COMMENT '存储名称',
    storage_type VARCHAR(20) NOT NULL COMMENT '类型(hdfs/s3/juicefs)',
    endpoint VARCHAR(255) DEFAULT NULL COMMENT '访问端点',
    bucket_name VARCHAR(100) DEFAULT NULL COMMENT '桶名称(S3使用)',
    access_key VARCHAR(255) DEFAULT NULL COMMENT 'Access Key(S3使用)',
    secret_key VARCHAR(255) DEFAULT NULL COMMENT 'Secret Key(S3使用)',
    root_path VARCHAR(255) NOT NULL DEFAULT '/' COMMENT '根路径',
    local_mount_path VARCHAR(500) DEFAULT NULL COMMENT '本地挂载路径(JuiceFS使用)',
    extra_params TEXT DEFAULT NULL COMMENT '额外配置(JSON)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-正常)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存储配置表';

-- 14. 集群配置表
DROP TABLE IF EXISTS cluster_config;
CREATE TABLE cluster_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    cluster_name VARCHAR(100) NOT NULL COMMENT '集群名称',
    cluster_type VARCHAR(20) NOT NULL COMMENT '集群类型(yarn/k8s)',
    resource_manager_url VARCHAR(255) DEFAULT NULL COMMENT 'YARN RM地址',
    k8s_api_server VARCHAR(255) DEFAULT NULL COMMENT 'K8s API Server地址',
    k8s_namespace VARCHAR(100) DEFAULT NULL COMMENT 'K8s命名空间',
    kubeconfig_path VARCHAR(500) DEFAULT NULL COMMENT 'Kubeconfig文件路径',
    grafana_url VARCHAR(255) DEFAULT NULL COMMENT 'Grafana地址',
    grafana_api_key VARCHAR(255) DEFAULT NULL COMMENT 'Grafana API Key',
    dolphin_scheduler_url VARCHAR(255) DEFAULT NULL COMMENT 'DolphinScheduler地址',
    dolphin_scheduler_token VARCHAR(255) DEFAULT NULL COMMENT 'DS Token',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-正常)',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='集群配置表';

-- ============================================================
-- 三、审计日志模块
-- ============================================================

-- 15. 操作审计日志表
DROP TABLE IF EXISTS operation_audit_log;
CREATE TABLE operation_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '操作用户ID',
    username VARCHAR(50) NOT NULL COMMENT '操作用户名',
    module VARCHAR(50) NOT NULL COMMENT '操作模块',
    operation VARCHAR(50) NOT NULL COMMENT '操作类型(create/update/delete/query/export/configure/execute/login/logout)',
    target_type VARCHAR(50) DEFAULT NULL COMMENT '目标对象类型',
    target_id BIGINT DEFAULT NULL COMMENT '目标对象ID',
    target_name VARCHAR(200) DEFAULT NULL COMMENT '目标对象名称',
    request_method VARCHAR(10) DEFAULT NULL COMMENT '请求方法(GET/POST/PUT/DELETE)',
    request_url VARCHAR(500) DEFAULT NULL COMMENT '请求URL',
    request_params TEXT DEFAULT NULL COMMENT '请求参数(脱敏后)',
    response_status INT DEFAULT NULL COMMENT '响应状态码',
    ip_address VARCHAR(50) DEFAULT NULL COMMENT 'IP地址',
    user_agent VARCHAR(500) DEFAULT NULL COMMENT '用户代理',
    operation_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    duration_ms INT DEFAULT NULL COMMENT '耗时(毫秒)',
    is_sensitive TINYINT NOT NULL DEFAULT 0 COMMENT '是否敏感操作(0-否 1-是)',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    KEY idx_user_id (user_id),
    KEY idx_module (module),
    KEY idx_operation (operation),
    KEY idx_operation_time (operation_time),
    KEY idx_is_sensitive (is_sensitive)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- ============================================================
-- 四、初始数据插入
-- ============================================================

-- 插入默认管理员账号 (admin / 123456)
INSERT INTO sys_user (username, password, real_name, status) VALUES
('admin', '357f3724ff339b2a1602c390d59498fd', '超级管理员', 1);

-- 插入预置角色
INSERT INTO sys_role (role_name, role_code, description, status) VALUES
('超级管理员', 'admin', '系统超级管理员,拥有所有权限', 1),
('运维人员', 'ops', '运维管理人员,负责系统配置和监控', 1),
('开发人员', 'developer', '数据开发工程师,负责SQL开发和任务提交', 1),
('分析师', 'analyst', '数据分析人员,负责数据探查和分析', 1),
('普通用户', 'viewer', '普通用户,拥有只读权限', 1);

-- 管理员分配超级管理员角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 插入示例菜单权限(系统管理模块)
INSERT INTO sys_menu_permission (parent_id, menu_name, menu_code, path, icon, sort_order, visible, status) VALUES
(0, '系统管理', 'system', '/system', 'Setting', 1, 1, 1),
(1, '数据源配置', 'datasource', '/system/config/datasource', '', 1, 1, 1),
(1, '存储配置', 'storage', '/system/config/storage', '', 2, 1, 1),
(1, '集群配置', 'cluster', '/system/config/cluster', '', 3, 1, 1),
(1, '用户管理', 'user', '/system/user/list', '', 4, 1, 1),
(1, '角色管理', 'role', '/system/role/list', '', 5, 1, 1),
(1, '权限管理', 'permission', '/system/permission/manage', '', 6, 1, 1),
(1, '操作审计', 'audit', '/system/audit/log', '', 7, 1, 1),
(0, '库表管理', 'metadata', '/metadata', 'Coin', 2, 1, 1),
(9, '库表搜索', 'table-list', '/metadata/tables', '', 1, 1, 1),
(9, 'SQL工作台', 'workspace', '/metadata/workspace', '', 2, 1, 1),
(0, '文件浏览', 'file', '/file', 'FolderOpened', 3, 1, 1),
(12, '文件浏览器', 'explorer', '/file/explorer', '', 1, 1, 1),
(0, '任务监控', 'monitor', '/monitor', 'Monitor', 4, 1, 1),
(14, '任务列表', 'task-list', '/monitor/tasks', '', 1, 1, 1),
(14, '告警管理', 'alert', '/monitor/alerts', '', 2, 1, 1),
(0, '数据开发', 'development', '/development', 'EditPen', 5, 1, 1),
(17, '项目管理', 'project', '/development/projects', '', 1, 1, 1),
(17, '执行历史', 'execution', '/development/execution/history', '', 2, 1, 1),
(17, '血缘视图', 'lineage', '/development/lineage', '', 3, 1, 1),
(0, '数据探查', 'analysis', '/analysis', 'DataAnalysis', 6, 1, 1),
(21, '新建探查', 'create', '/analysis/task/create', '', 1, 1, 1),
(21, '图表分析', 'chart', '/analysis/chart', '', 2, 1, 1),
(21, '自定义查询', 'query', '/analysis/custom-query', '', 3, 1, 1),
(0, '业务知识', 'knowledge', '/knowledge', 'Notebook', 7, 1, 1),
(25, '知识文档', 'document', '/knowledge/documents', '', 1, 1, 1),
(25, '知识图谱', 'graph', '/knowledge/graph', '', 2, 1, 1),
(25, '血缘图谱', 'lineage-graph', '/knowledge/lineage-graph', '', 3, 1, 1);

-- 为管理员分配所有菜单权限
INSERT INTO sys_role_menu_permission (role_id, menu_permission_id)
SELECT 1, id FROM sys_menu_permission WHERE deleted = 0;

-- 插入示例按钮权限
INSERT INTO sys_button_permission (menu_permission_id, button_name, button_code, description, sort_order) VALUES
(2, '新增', 'datasource:add', '新增数据源', 1),
(2, '编辑', 'datasource:edit', '编辑数据源', 2),
(2, '删除', 'datasource:delete', '删除数据源', 3),
(2, '测试连接', 'datasource:test', '测试数据源连接', 4),
(5, '新增', 'user:add', '新增用户', 1),
(5, '编辑', 'user:edit', '编辑用户', 2),
(5, '删除', 'user:delete', '删除用户', 3),
(5, '重置密码', 'user:reset-pwd', '重置用户密码', 4),
(5, '分配角色', 'user:assign-role', '分配用户角色', 5),
(6, '新增', 'role:add', '新增角色', 1),
(6, '编辑', 'role:edit', '编辑角色', 2),
(6, '删除', 'role:delete', '删除角色', 3),
(6, '分配权限', 'role:assign-perm', '分配角色权限', 4);

-- 为管理员分配所有按钮权限
INSERT INTO sys_role_button_permission (role_id, button_permission_id)
SELECT 1, id FROM sys_button_permission;

-- 输出完成信息
SELECT '数据库初始化完成!' AS message,
       (SELECT COUNT(*) FROM sys_user) AS user_count,
       (SELECT COUNT(*) FROM sys_role) AS role_count,
       (SELECT COUNT(*) FROM sys_menu_permission) AS menu_count,
       (SELECT COUNT(*) FROM sys_button_permission) AS button_count;
