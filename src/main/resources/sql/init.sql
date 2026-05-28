CREATE DATABASE IF NOT EXISTS bigdata_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE bigdata_platform;

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码(加密存储)',
    real_name VARCHAR(50) COMMENT '真实姓名',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    avatar VARCHAR(255) COMMENT '头像URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    last_login_time DATETIME COMMENT '最后登录时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删除 1-已删除)',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    role_type VARCHAR(30) NOT NULL DEFAULT 'VIEWER' COMMENT '角色类型(SUPER_ADMIN/OPS/DEVELOPER/ANALYST/VIEWER)',
    description VARCHAR(255) COMMENT '角色描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 数据源配置表
CREATE TABLE IF NOT EXISTS datasource_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_name VARCHAR(100) NOT NULL COMMENT '数据源名称',
    datasource_type VARCHAR(50) NOT NULL COMMENT '类型(kyuubi/doris/clickhouse/mysql/hdfs/s3/juicefs)',
    url VARCHAR(500) NOT NULL COMMENT '连接URL',
    database_name VARCHAR(100) COMMENT '数据库名称',
    username VARCHAR(100) COMMENT '用户名',
    password VARCHAR(255) COMMENT '密码(加密存储)',
    extra_params TEXT COMMENT '额外参数(JSON格式)',
    connection_status TINYINT NOT NULL DEFAULT 0 COMMENT '连接状态(0-未测试 1-正常 2-异常)',
    last_test_time DATETIME COMMENT '最后测试时间',
    description VARCHAR(500) COMMENT '描述',
    create_by BIGINT COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_datasource_type (datasource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置表';

-- 存储配置表
CREATE TABLE IF NOT EXISTS storage_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    storage_name VARCHAR(100) NOT NULL COMMENT '存储名称',
    storage_type VARCHAR(20) NOT NULL COMMENT '类型(hdfs/s3/juicefs)',
    endpoint VARCHAR(255) COMMENT '访问端点',
    bucket_name VARCHAR(100) COMMENT '桶名称(S3使用)',
    access_key VARCHAR(255) COMMENT 'Access Key(S3使用)',
    secret_key VARCHAR(255) COMMENT 'Secret Key(S3使用)',
    root_path VARCHAR(255) NOT NULL DEFAULT '/' COMMENT '根路径',
    local_mount_path VARCHAR(500) COMMENT '本地挂载路径(JuiceFS使用)',
    extra_params TEXT COMMENT '额外配置(JSON)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存储配置表';

-- 集群配置表
CREATE TABLE IF NOT EXISTS cluster_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cluster_name VARCHAR(100) NOT NULL COMMENT '集群名称',
    cluster_type VARCHAR(20) NOT NULL COMMENT '集群类型(yarn/k8s)',
    resource_manager_url VARCHAR(255) COMMENT 'YARN RM地址',
    k8s_api_server VARCHAR(255) COMMENT 'K8s API Server地址',
    k8s_namespace VARCHAR(100) COMMENT 'K8s命名空间',
    kubeconfig_path VARCHAR(500) COMMENT 'Kubeconfig文件路径',
    grafana_url VARCHAR(255) COMMENT 'Grafana地址',
    grafana_api_key VARCHAR(255) COMMENT 'Grafana API Key',
    dolphin_scheduler_url VARCHAR(255) COMMENT 'DolphinScheduler地址',
    dolphin_scheduler_token VARCHAR(255) COMMENT 'DS Token',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态',
    description VARCHAR(500) COMMENT '描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='集群配置表';

-- 依赖包配置表
CREATE TABLE IF NOT EXISTS dev_jar_dependency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    jar_name VARCHAR(200) NOT NULL COMMENT 'JAR包名称',
    group_id VARCHAR(200) DEFAULT NULL COMMENT 'Maven Group ID',
    artifact_id VARCHAR(200) DEFAULT NULL COMMENT 'Maven Artifact ID',
    version VARCHAR(50) DEFAULT NULL COMMENT '版本号',
    scope VARCHAR(20) NOT NULL DEFAULT 'compile' COMMENT '作用域(compile-编译 runtime-运行时 provided-提供 test-测试)',
    source VARCHAR(20) NOT NULL DEFAULT 'system' COMMENT '来源(system-系统内置 custom-自定义 maven-maven仓库 local-本地路径)',
    jar_path VARCHAR(500) DEFAULT NULL COMMENT 'JAR包路径(local来源时使用)',
    repository_url VARCHAR(500) DEFAULT NULL COMMENT 'Maven仓库地址(maven来源时使用)',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    checksum VARCHAR(64) DEFAULT NULL COMMENT '文件校验和(MD5/SHA256)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-正常)',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    category VARCHAR(100) DEFAULT NULL COMMENT '功能分类(用户自定义)',
    program_type VARCHAR(20) NOT NULL DEFAULT 'flink' COMMENT '程序类型(flink-flink任务 spark-spark任务)',
    auto_choose TINYINT NOT NULL DEFAULT 0 COMMENT '是否自动选择(0-否 1-是)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_source (source),
    KEY idx_scope (scope),
    KEY idx_program_type (program_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JAR包依赖配置表';

-- 操作审计日志表
CREATE TABLE IF NOT EXISTS operation_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '操作用户ID',
    username VARCHAR(50) NOT NULL COMMENT '操作用户名',
    module VARCHAR(50) NOT NULL COMMENT '操作模块',
    operation VARCHAR(50) NOT NULL COMMENT '操作类型(create/update/delete/query/export/configure/execute/login/logout)',
    target_type VARCHAR(50) COMMENT '目标对象类型',
    target_id BIGINT COMMENT '目标对象ID',
    target_name VARCHAR(200) COMMENT '目标对象名称',
    request_method VARCHAR(10) COMMENT '请求方法(GET/POST/PUT/DELETE)',
    request_url VARCHAR(500) COMMENT '请求URL',
    request_params TEXT COMMENT '请求参数(脱敏后)',
    response_status INT COMMENT '响应状态码',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '用户代理',
    operation_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    duration_ms INT COMMENT '耗时(毫秒)',
    is_sensitive TINYINT NOT NULL DEFAULT 0 COMMENT '是否敏感操作(0-否 1-是)',
    remark VARCHAR(500) COMMENT '备注',
    INDEX idx_user_id (user_id),
    INDEX idx_module (module),
    INDEX idx_operation (operation),
    INDEX idx_operation_time (operation_time),
    INDEX idx_is_sensitive (is_sensitive),
    INDEX idx_target_type_target_id (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- 初始管理员账号
INSERT INTO sys_user (username, password, real_name, status) VALUES
('admin', '357f3724ff339b2a1602c390d59498fd', '超级管理员', 1);

-- 初始角色
INSERT INTO sys_role (role_name, role_code, role_type, description, status) VALUES
('超级管理员', 'admin', 'SUPER_ADMIN', '系统超级管理员,拥有所有权限', 1),
('运维人员', 'ops', 'OPS', '运维管理人员,负责系统配置和监控', 1),
('开发人员', 'developer', 'DEVELOPER', '数据开发工程师,负责SQL开发和任务提交', 1),
('分析师', 'analyst', 'ANALYST', '数据分析人员,负责数据探查和分析', 1),
('普通用户', 'viewer', 'VIEWER', '普通用户,拥有只读权限', 1);

-- 管理员分配角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 库表CRUD权限表
CREATE TABLE IF NOT EXISTS sys_table_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
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
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库表CRUD权限表';

-- 权限-表关联表（多对多）
CREATE TABLE IF NOT EXISTS sys_permission_table (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    table_permission_id BIGINT NOT NULL COMMENT '库表权限ID',
    table_metadata_id BIGINT NOT NULL COMMENT '库表元数据ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_perm_table (table_permission_id, table_metadata_id),
    KEY idx_table_permission_id (table_permission_id),
    KEY idx_table_metadata_id (table_metadata_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限-表关联表';
