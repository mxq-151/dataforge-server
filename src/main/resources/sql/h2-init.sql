-- H2数据库初始化脚本（系统管理模块）
-- 去除了MySQL特定语法，适配H2内存数据库

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(50),
    email VARCHAR(100),
    phone VARCHAR(20),
    avatar VARCHAR(255),
    status INT NOT NULL DEFAULT 1,
    last_login_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

-- 2. 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    status INT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

-- 3. 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. 菜单/页面权限表
CREATE TABLE IF NOT EXISTS sys_menu_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NOT NULL DEFAULT 0,
    menu_name VARCHAR(100) NOT NULL,
    menu_code VARCHAR(100) NOT NULL,
    path VARCHAR(255),
    component VARCHAR(255),
    icon VARCHAR(100),
    sort_order INT NOT NULL DEFAULT 0,
    visible INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

-- 5. 按钮操作权限表
CREATE TABLE IF NOT EXISTS sys_button_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    menu_permission_id BIGINT NOT NULL,
    button_name VARCHAR(50) NOT NULL,
    button_code VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    sort_order INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 6. 文件路径权限表
CREATE TABLE IF NOT EXISTS sys_file_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_name VARCHAR(100) NOT NULL,
    storage_type VARCHAR(50),
    file_path VARCHAR(500),
    permission_type INT NOT NULL DEFAULT 1,
    recursive INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 7. 库表权限表
CREATE TABLE IF NOT EXISTS sys_table_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    datasource_id BIGINT,
    database_name VARCHAR(100),
    table_name VARCHAR(100),
    table_metadata_id BIGINT,
    permission_name VARCHAR(100),
    crud_permissions VARCHAR(500),
    permission_scope INT NOT NULL DEFAULT 1,
    row_filter_sql VARCHAR(1000),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 8. 操作审计日志表
CREATE TABLE IF NOT EXISTS operation_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50),
    module VARCHAR(50),
    operation VARCHAR(50),
    target_type VARCHAR(50),
    target_name VARCHAR(200),
    request_method VARCHAR(10),
    response_status INT,
    ip_address VARCHAR(50),
    operation_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    duration_ms INT,
    is_sensitive INT NOT NULL DEFAULT 0,
    request_params TEXT,
    response_body TEXT,
    error_message TEXT
);

-- 插入初始测试数据

-- 插入测试用户
INSERT INTO sys_user (username, password, real_name, email, phone, status) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '管理员', 'admin@bigdata.com', '13800138001', 1),
('zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '张三', 'zhangsan@bigdata.com', '13800138002', 1),
('lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '李四', 'lisi@bigdata.com', '13800138003', 1),
('wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '王五', 'wangwu@bigdata.com', '13800138004', 0);

-- 插入测试角色
INSERT INTO sys_role (role_name, role_code, description, status) VALUES
('超级管理员', 'admin', '拥有所有权限', 1),
('运维人员', 'ops', '负责系统运维和监控', 1),
('开发人员', 'developer', '数据开发和脚本编写', 1),
('分析师', 'analyst', '数据探查和分析', 1),
('普通用户', 'viewer', '只读访问权限', 1);

-- 分配用户角色（admin拥有超级管理员角色）
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
INSERT INTO sys_user_role (user_id, role_id) VALUES (2, 2);
INSERT INTO sys_user_role (user_id, role_id) VALUES (3, 3);
INSERT INTO sys_user_role (user_id, role_id) VALUES (4, 4);

-- 插入菜单权限测试数据
INSERT INTO sys_menu_permission (parent_id, menu_name, menu_code, path, icon, sort_order, visible, status) VALUES
(0, '系统管理', 'system_management', '', 'Setting', 1, 1, 1),
(1, '数据源配置', 'datasource_config', '/system/config/datasource', '', 1, 1, 1),
(1, '存储配置', 'storage_config', '/system/config/storage', '', 2, 1, 1),
(1, '用户与角色', 'user_manage', '/system/user/manage', '', 3, 1, 1),
(1, '权限管理', 'permission_manage', '/system/permission/manage', '', 4, 1, 1),
(1, '操作审计', 'audit_log', '/system/audit/log', '', 5, 1, 1),
(0, '库表管理', 'metadata_management', '', 'Coin', 2, 1, 1),
(7, '库表搜索', 'table_search', '/metadata/tables', '', 1, 1, 1),
(7, 'SQL工作台', 'sql_workspace', '/metadata/workspace', '', 2, 1, 1);

-- 插入审计日志测试数据
INSERT INTO operation_audit_log (username, module, operation, target_type, target_name, request_method, response_status, ip_address, duration_ms, is_sensitive) VALUES
('admin', 'system', 'create', 'user', '创建用户张三', 'POST', 200, '127.0.0.1', 156, 0),
('admin', 'system', 'update', 'role', '更新角色描述', 'PUT', 200, '127.0.0.1', 89, 0),
('zhangsan', 'system', 'query', 'user', '查询用户列表', 'GET', 200, '192.168.1.100', 45, 0),
('admin', 'permission', 'create', 'menu_permission', '新增菜单权限', 'POST', 200, '127.0.0.1', 234, 0),
('lisi', 'audit', 'export', 'audit_log', '导出审计日志', 'GET', 200, '192.168.1.101', 1234, 1);
