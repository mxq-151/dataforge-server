-- ============================================================
-- 大数据开发平台 - 数据开发模块完整数据库初始化脚本
-- 版本: 2.0.0
-- 创建时间: 2026-05-04
-- 说明: 包含项目管理、脚本开发、版本管理、执行历史等完整功能
-- ============================================================

CREATE DATABASE IF NOT EXISTS bigdata_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE bigdata_platform;

-- ============================================================
-- 一、用户与权限体系 (RBAC)
-- ============================================================

DROP TABLE IF EXISTS sys_role_table_permission;
DROP TABLE IF EXISTS sys_role_file_permission;
DROP TABLE IF EXISTS sys_role_button_permission;
DROP TABLE IF EXISTS sys_role_menu_permission;
DROP TABLE IF EXISTS sys_table_permission;
DROP TABLE IF EXISTS sys_file_permission;
DROP TABLE IF EXISTS sys_button_permission;
DROP TABLE IF EXISTS sys_menu_permission;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;

-- 1. 用户表
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
CREATE TABLE sys_role_menu_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_permission_id BIGINT NOT NULL COMMENT '菜单权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_menu (role_id, menu_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单权限关联表';

-- 9. 角色-按钮权限关联表
CREATE TABLE sys_role_button_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    button_permission_id BIGINT NOT NULL COMMENT '按钮权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_button (role_id, button_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-按钮权限关联表';

-- 10. 角色-文件权限关联表
CREATE TABLE sys_role_file_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    file_permission_id BIGINT NOT NULL COMMENT '文件权限ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_file (role_id, file_permission_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-文件权限关联表';

-- 11. 角色-库表权限关联表
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

-- 14. (已移除) 集群配置表 - 前端未使用

-- ============================================================
-- 三、审计日志模块
-- ============================================================

-- 15. 操作审计日志表
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
-- 四、数据开发模块（核心）
-- ============================================================

-- 16. 项目管理表
CREATE TABLE dev_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    project_name VARCHAR(200) NOT NULL COMMENT '项目名称',
    project_code VARCHAR(100) NOT NULL COMMENT '项目编码(英文唯一标识)',
    description TEXT DEFAULT NULL COMMENT '项目描述',
    owner_id BIGINT DEFAULT NULL COMMENT '负责人ID',
    owner_name VARCHAR(50) DEFAULT NULL COMMENT '负责人姓名',
    default_datasource_id BIGINT DEFAULT NULL COMMENT '默认数据源ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-归档 1-正常)',
    script_count INT NOT NULL DEFAULT 0 COMMENT '脚本数量(冗余字段)',
    scheduled_count INT NOT NULL DEFAULT 0 COMMENT '已调度任务数(冗余字段)',
    create_by BIGINT DEFAULT NULL COMMENT '创建人ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT DEFAULT NULL COMMENT '更新人ID',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删除 1-已删除)',
    UNIQUE KEY uk_project_code (project_code),
    KEY idx_owner_id (owner_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开发项目管理表';

-- 17. 开发脚本表（增强版）
CREATE TABLE dev_script (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    script_name VARCHAR(200) NOT NULL COMMENT '脚本名称',
    script_code VARCHAR(100) NOT NULL COMMENT '脚本编码(唯一标识)',
    script_type VARCHAR(50) NOT NULL COMMENT '脚本类型(sql/shell/python/java/scala)',
    content LONGTEXT COMMENT '脚本内容',
    project_id BIGINT DEFAULT NULL COMMENT '所属项目ID',
    parent_folder_id BIGINT DEFAULT NULL COMMENT '父文件夹ID(支持目录层级)',
    folder_path VARCHAR(500) DEFAULT NULL COMMENT '文件夹路径(如:/etl/ods)',
    is_folder TINYINT NOT NULL DEFAULT 0 COMMENT '是否为文件夹(0-否 1-是)',
    depth INT NOT NULL DEFAULT 0 COMMENT '目录深度(0-根级别, 1-一级子目录, 2-二级子目录)',
    datasource_id BIGINT DEFAULT NULL COMMENT '数据源ID',
    engine_id BIGINT DEFAULT NULL COMMENT '执行引擎ID(SQL/Java/Scala使用)',
    jar_dependencies JSON DEFAULT NULL COMMENT 'JAR包依赖配置(JSON数组,Java/Scala使用)',
    status TINYINT DEFAULT 1 COMMENT '状态(1-正常 0-禁用)',
    version INT NOT NULL DEFAULT 1 COMMENT '当前版本号',
    latest_version_id BIGINT DEFAULT NULL COMMENT '最新版本记录ID',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_by BIGINT DEFAULT NULL COMMENT '创建人ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT DEFAULT NULL COMMENT '更新人ID',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_script_code (script_code),
    KEY idx_project_id (project_id),
    KEY idx_parent_folder_id (parent_folder_id),
    KEY idx_script_type (script_type),
    KEY idx_is_folder (is_folder),
    KEY idx_depth (depth)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开发脚本表';

-- 18. 脚本版本历史表
CREATE TABLE dev_script_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    script_id BIGINT NOT NULL COMMENT '脚本ID',
    version_number INT NOT NULL COMMENT '版本号',
    version_content LONGTEXT COMMENT '该版本的脚本内容',
    change_description VARCHAR(500) DEFAULT NULL COMMENT '变更说明',
    change_type VARCHAR(20) NOT NULL DEFAULT 'save' COMMENT '变更类型(save-保存 publish-发布 rollback-回滚)',
    create_by BIGINT DEFAULT NULL COMMENT '操作人ID',
    create_by_name VARCHAR(50) DEFAULT NULL COMMENT '操作人姓名',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    line_count INT DEFAULT NULL COMMENT '代码行数',
    KEY idx_script_id (script_id),
    KEY idx_script_version (script_id, version_number),
    UNIQUE KEY uk_script_version (script_id, version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='脚本版本历史表';

-- 19. 脚本执行历史表
CREATE TABLE dev_script_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    script_id BIGINT NOT NULL COMMENT '脚本ID',
    execution_id VARCHAR(64) NOT NULL COMMENT '执行ID(全局唯一)',
    script_type VARCHAR(50) NOT NULL COMMENT '脚本类型',
    execute_mode VARCHAR(20) NOT NULL DEFAULT 'debug' COMMENT '执行模式(debug-调试 run-正式运行 schedule-调度触发)',
    engine_id BIGINT DEFAULT NULL COMMENT '执行引擎ID',
    datasource_id BIGINT DEFAULT NULL COMMENT '数据源ID',
    status VARCHAR(20) NOT NULL DEFAULT 'submitted' COMMENT '状态(submitted-已提交 running-运行中 success-成功 failed-失败 cancelled-取消 timeout-超时)',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    duration_ms INT DEFAULT NULL COMMENT '执行耗时(毫秒)',
    input_params JSON DEFAULT NULL COMMENT '输入参数',
    output_log LONGTEXT COMMENT '输出日志',
    error_message TEXT DEFAULT NULL COMMENT '错误信息',
    affected_rows INT DEFAULT NULL COMMENT '影响行数(SQL)',
    result_data JSON DEFAULT NULL COMMENT '结果数据(查询类SQL)',
    submit_by BIGINT DEFAULT NULL COMMENT '提交人ID',
    submit_by_name VARCHAR(50) DEFAULT NULL COMMENT '提交人姓名',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_script_id (script_id),
    KEY idx_execution_id (execution_id),
    KEY idx_status (status),
    KEY idx_start_time (start_time),
    KEY idx_execute_mode (execute_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='脚本执行历史表';

-- 20. 执行引擎配置表
CREATE TABLE dev_execution_engine (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    engine_name VARCHAR(100) NOT NULL COMMENT '引擎名称',
    engine_code VARCHAR(50) NOT NULL COMMENT '引擎编码(spark-local/spark-yarn/spark-k8s/flink-stream/flink-batch/hive/presto)',
    engine_type VARCHAR(30) NOT NULL COMMENT '引擎类型(batch-批处理 stream-流处理 query-即席查询)',
    supported_languages JSON DEFAULT NULL COMMENT '支持的语言列表(JSON数组)',
    config_params JSON DEFAULT NULL COMMENT '引擎配置参数(JSON格式)',
    connection_url VARCHAR(500) DEFAULT NULL COMMENT '连接URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-正常)',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_engine_code (engine_code),
    KEY idx_engine_type (engine_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行引擎配置表';

-- 21. JAR包依赖配置表
CREATE TABLE dev_jar_dependency (
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
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_source (source),
    KEY idx_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JAR包依赖配置表';

-- 22. 调度任务配置表
CREATE TABLE dev_schedule_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_name VARCHAR(200) NOT NULL COMMENT '任务名称',
    task_code VARCHAR(100) NOT NULL COMMENT '任务编码',
    script_id BIGINT NOT NULL COMMENT '关联的脚本ID',
    project_id BIGINT NOT NULL COMMENT '所属项目ID',
    schedule_type VARCHAR(20) NOT NULL COMMENT '调度类型(cron-定时 manual-手动 dependent-依赖触发)',
    cron_expression VARCHAR(100) DEFAULT NULL COMMENT 'Cron表达式',
    schedule_interval INT DEFAULT NULL COMMENT '间隔(分钟,仅interval类型)',
    priority INT NOT NULL DEFAULT 5 COMMENT '优先级(1-10,数字越小优先级越高)',
    max_retry INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    retry_interval INT NOT NULL DEFAULT 5 COMMENT '重试间隔(分钟)',
    timeout_minutes INT NOT NULL DEFAULT 60 COMMENT '超时时间(分钟)',
    params JSON DEFAULT NULL COMMENT '运行参数',
    notify_on_success TINYINT NOT NULL DEFAULT 0 COMMENT '成功通知(0-否 1-是)',
    notify_on_failure TINYINT NOT NULL DEFAULT 1 COMMENT '失败通知(0-否 1-是)',
    notify_emails TEXT DEFAULT NULL COMMENT '通知邮箱列表',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态(0-停用 1-启用 2-暂停)',
    last_run_time DATETIME DEFAULT NULL COMMENT '上次运行时间',
    next_run_time DATETIME DEFAULT NULL NULL COMMENT '下次运行时间',
    last_run_status VARCHAR(20) DEFAULT NULL COMMENT '上次运行状态',
    create_by BIGINT DEFAULT NULL COMMENT '创建人ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT DEFAULT NULL COMMENT '更新人ID',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_task_code (task_code),
    KEY idx_script_id (script_id),
    KEY idx_project_id (project_id),
    KEY idx_status (status),
    KEY idx_next_run_time (next_run_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调度任务配置表';

-- ============================================================
-- 五、初始数据插入
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

-- 插入示例菜单权限
INSERT INTO sys_menu_permission (parent_id, menu_name, menu_code, path, icon, sort_order, visible, status) VALUES
(0, '系统管理', 'system', '/system', 'Setting', 1, 1, 1),
(1, '数据源配置', 'datasource', '/system/config/datasource', '', 1, 1, 1),
(1, '存储配置', 'storage', '/system/config/storage', '', 2, 1, 1),
-- (1, '集群配置', 'cluster', '/system/config/cluster', '', 3, 1, 1), -- 已移除：前端未使用
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
(17, '代码开发', 'code', '/development/code', '', 2, 1, 1),
(18, 'SQL开发', 'sql', '/development/code/sql', '', 1, 1, 1),
(18, 'Shell开发', 'shell', '/development/code/shell', '', 2, 1, 1),
(18, 'Python开发', 'python', '/development/code/python', '', 3, 1, 1),
(18, 'Java开发', 'java', '/development/code/java', '', 4, 1, 1),
(18, 'Scala开发', 'scala', '/development/code/scala', '', 5, 1, 1),
(17, '执行历史', 'execution', '/development/execution/history', '', 3, 1, 1),
(17, '血缘视图', 'lineage', '/development/lineage', '', 4, 1, 1),
(0, '数据探查', 'analysis', '/analysis', 'DataAnalysis', 6, 1, 1),
(25, '新建探查', 'create', '/analysis/task/create', '', 1, 1, 1),
(25, '图表分析', 'chart', '/analysis/chart', '', 2, 1, 1),
(25, '自定义查询', 'query', '/analysis/custom-query', '', 3, 1, 1),
(0, '业务知识', 'knowledge', '/knowledge', 'Notebook', 7, 1, 1),
(29, '知识文档', 'document', '/knowledge/documents', '', 1, 1, 1),
(29, '知识图谱', 'graph', '/knowledge/graph', '', 2, 1, 1),
(29, '血缘图谱', 'lineage-graph', '/knowledge/lineage-graph', '', 3, 1, 1);

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

-- 插入示例数据源配置
INSERT INTO datasource_config (datasource_name, datasource_type, url, database_name, username, password, connection_status, description) VALUES
('Kyuubi - 生产环境', 'kyuubi', 'jdbc:kyuubi://prod-kyuubi:10009/default', 'default', 'hive', 'hive-pass', 1, '生产环境Kyuubi Server'),
('Kyuubi - 测试环境', 'kyuubi', 'jdbc:kyuubi://test-kyuubi:10009/default', 'default', 'hive', 'hive-test', 1, '测试环境Kyuubi Server'),
('Doris - 分析库', 'doris', 'jdbc:mysql://doris-fe:9030/analysis_db', 'analysis_db', 'root', 'doris123', 1, 'Doris OLAP分析数据库'),
('MySQL - 元数据库', 'mysql', 'jdbc:mysql://127.0.0.1:3306/bigdata_platform', 'bigdata_platform', 'root', '123456', 1, '平台元数据库');

-- 插入执行引擎配置
INSERT INTO dev_execution_engine (engine_name, engine_code, engine_type, supported_languages, config_params, description, sort_order) VALUES
('Spark - 本地模式', 'spark-local', 'batch', '["sql", "java", "scala", "python"]', '{"master": "local[*]", "memory": "2g"}', 'Spark Local模式用于开发和调试', 1),
('Spark - YARN集群', 'spark-yarn', 'batch', '["sql", "java", "scala", "python"]', '{"master": "yarn", "deploy-mode": "client", "executor-memory": "4g", "executor-cores": 2}', 'Spark on YARN生产环境', 2),
('Spark - Kubernetes', 'spark-k8s', 'batch', '["sql", "java", "scala", "python"]', '{"master": "k8s://https://k8s-api:6443", "namespace": "spark"}', 'Spark on K8s云原生部署', 3),
('Flink - 流处理引擎', 'flink-stream', 'stream', '["sql", "java", "scala", "python"]', '{"parallelism": 4, "checkpoint-interval": "60000"}', 'Flink实时流处理', 4),
('Hive - 批处理引擎', 'hive', 'batch', '["sql"]', '{"execution.engine": "mr"}', 'Hive离线批处理', 5),
('Presto - 即席查询', 'presto', 'query', '["sql"]', '{"catalog": "hive", "schema": "default"}', 'Presto交互式查询', 6);

-- 插入JAR包依赖配置
INSERT INTO dev_jar_dependency (jar_name, group_id, artifact_id, version, scope, source, description) VALUES
('spark-sql_2.12', 'org.apache.spark', 'spark-sql_2.12', '3.5.0', 'compile', 'system', 'Spark SQL核心库'),
('spark-core_2.12', 'org.apache.spark', 'spark-core_2.12', '3.5.0', 'compile', 'system', 'Spark核心库'),
('hadoop-common', 'org.apache.hadoop', 'hadoop-common', '3.3.6', 'compile', 'system', 'Hadoop通用工具库'),
('hadoop-hdfs', 'org.apache.hadoop', 'hadoop-hdfs-client', '3.3.6', 'compile', 'system', 'HDFS客户端'),
('mysql-connector-java', 'mysql', 'mysql-connector-java', '8.0.33', 'runtime', 'system', 'MySQL JDBC驱动'),
('commons-lang3', 'org.apache.commons', 'commons-lang3', '3.14.0', 'compile', 'system', 'Apache Commons Lang工具库'),
('json4s-native', 'org.json4s', 'json4s-native_2.12', '4.0.6', 'compile', 'system', 'JSON处理库'),
('scalaj-http', 'org.scalaj', 'scalaj-http_2.12', '2.4.2', 'runtime', 'system', 'Scala HTTP客户端');

-- 插入示例项目
INSERT INTO dev_project (project_name, project_code, description, owner_id, owner_name, default_datasource_id, status, script_count, scheduled_count) VALUES
('数仓ETL项目', 'dw_etl', '数据仓库ETL开发项目，负责ODS、DWD、DWS、ADS各层数据处理', 1, 'admin', 1, 1, 25, 5),
('实时计算项目', 'realtime_compute', 'Flink实时计算任务，包括实时数据清洗、聚合和告警', 1, 'admin', 1, 1, 12, 3),
('数据分析项目', 'data_analysis', '业务数据分析与报表生成，支持自助式SQL查询', 1, 'admin', 3, 1, 18, 2),
('数据质量监控', 'data_quality', '数据质量检测、异常发现和数据修复脚本', 1, 'admin', 1, 0, 8, 0);

-- 插入示例脚本
INSERT INTO dev_script (script_name, script_code, script_type, content, project_id, parent_folder_id, folder_path, is_folder, depth, datasource_id, engine_id, status, version, description, create_by) VALUES
('数仓ETL项目', 'prj_dw_etl', 'folder', NULL, 1, NULL, '/', 1, 0, NULL, NULL, 1, 1, '项目根目录', 1),
('ODS层', 'fld_ods', 'folder', NULL, 1, 1, '/ods', 1, 1, NULL, NULL, 1, 1, '原始数据层', 1),
('DWD层', 'fld_dwd', 'folder', NULL, 1, 1, '/dwd', 1, 1, NULL, NULL, 1, 1, '明细数据层', 1),
('DWS层', 'fld_dws', 'folder', NULL, 1, 1, '/dws', 1, 1, NULL, NULL, 1, 1, '汇总数据层', 1),
('ADS层', 'fld_ads', 'folder', NULL, 1, 1, '/ads', 1, 1, NULL, NULL, 1, 1, '应用数据层', 1),
('ods_user_sync', 'scr_ods_user_sync', 'sql', '-- ODS层用户数据同步\nSELECT * FROM source_users WHERE dt = ''${biz_date}'';', 1, 2, '/ods/ods_user_sync', 0, 2, 1, 5, 1, 1, '用户数据同步到ODS层', 1),
('ods_order_sync', 'scr_ods_order_sync', 'sql', '-- ODS层订单数据同步\nSELECT * FROM source_orders WHERE dt = ''${biz_date}'';', 1, 2, '/ods/ods_order_sync', 0, 2, 1, 5, 1, 1, '订单数据同步到ODS层', 1),
('dwd_user_detail', 'scr_dwd_user_detail', 'sql', '-- DWD层用户明细\nSELECT \n  user_id,\n  user_name,\n  phone,\n  email,\n  register_time,\n  ''${biz_date}'' AS dt\nFROM ods_user_sync\nWHERE dt = ''${biz_date}'';', 1, 3, '/dwd/dwd_user_detail', 0, 2, 1, 5, 1, 1, '用户明细宽表', 1),
('dwd_order_detail', 'scr_dwd_order_detail', 'sql', '-- DWD层订单明细\nSELECT \n  order_id,\n  user_id,\n  product_id,\n  amount,\n  order_status,\n  create_time,\n  ''${biz_date}'' AS dt\nFROM ods_order_sync\nWHERE dt = ''${biz_date}'';', 1, 3, '/dwd/dwd_order_detail', 0, 2, 1, 5, 1, 1, '订单明细宽表', 1),
('dws_user_summary', 'scr_dws_user_summary', 'sql', '-- DWS层用户汇总\nSELECT \n  user_id,\n  COUNT(*) AS order_cnt,\n  SUM(amount) AS total_amount,\n  MAX(create_time) AS last_order_time,\n  ''${biz_date}'' AS dt\nFROM dwd_order_detail\nWHERE dt = ''${biz_date}''\nGROUP BY user_id;', 1, 4, '/dws/dws_user_summary', 0, 2, 1, 5, 1, 1, '用户日汇总表', 1),
('ads_daily_report', 'scr_ads_daily_report', 'sql', '-- ADS层日报\nSELECT \n  COUNT(DISTINCT user_id) AS active_users,\n  COUNT(*) AS total_orders,\n  SUM(total_amount) AS gmv,\n  ''${biz_date}'' AS report_date\nFROM dws_user_summary\nWHERE dt = ''${biz_date}'';', 1, 5, '/ads/ads_daily_report', 0, 2, 1, 6, 1, 1, '每日运营报表', 1),
('实时计算项目', 'prj_realtime_compute', 'folder', NULL, 2, NULL, '/', 1, 0, NULL, NULL, 1, 1, '项目根目录', 1),
('flink_stream_etl', 'scr_flink_stream_etl', 'java', '// Flink实时ETL作业\npublic class StreamETLJob {\n    public static void main(String[] args) {\n        // TODO: 实现Flink实时处理逻辑\n    }\n}', 2, 7, '/flink_stream_etl', 0, 1, 1, 4, 1, 1, 'Flink实时数据处理', 1),
('数据分析项目', 'prj_data_analysis', 'folder', NULL, 3, NULL, '/', 1, 0, NULL, NULL, 1, 1, '项目根目录', 1),
('query_user_behavior', 'scr_query_user_behavior', 'sql', '-- 用户行为分析查询\nSELECT \n  DATE_FORMAT(event_time, ''%Y-%m-%d'') AS date,\n  event_type,\n  COUNT(*) AS event_cnt,\n  COUNT(DISTINCT user_id) AS unique_users\nFROM user_events\nWHERE event_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)\nGROUP BY DATE_FORMAT(event_time, ''%Y-%m-%d''), event_type\nORDER BY date DESC, event_cnt DESC;', 3, 9, '/query_user_behavior', 0, 1, 3, 6, 1, 1, '近7天用户行为统计', 1);

-- ============================================================
-- 八、知识文档模块
-- ============================================================

CREATE TABLE knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    title VARCHAR(200) NOT NULL COMMENT '文档标题',
    doc_type VARCHAR(30) NOT NULL COMMENT '文档类型(business_term/process_doc/deploy_doc/lineage_doc/other)',
    category VARCHAR(100) DEFAULT NULL COMMENT '分类',
    content LONGTEXT DEFAULT NULL COMMENT '文档内容',
    author_name VARCHAR(50) DEFAULT NULL COMMENT '作者姓名',
    author_id BIGINT DEFAULT NULL COMMENT '作者ID',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态(0-草稿 1-发布)',
    sync_to_rag TINYINT NOT NULL DEFAULT 0 COMMENT '是否同步至RAG(0-未同步 1-已同步)',
    view_count INT NOT NULL DEFAULT 0 COMMENT '浏览量',
    tags VARCHAR(500) DEFAULT NULL COMMENT '标签(逗号分隔)',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删除 1-已删除)',
    KEY idx_doc_type (doc_type),
    KEY idx_status (status),
    KEY idx_author_id (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档表';

-- 插入示例知识文档
INSERT INTO knowledge_document (title, doc_type, category, content, author_name, author_id, status, sync_to_rag, view_count, tags, description) VALUES
('数仓分层设计规范', 'process_doc', '架构设计', '# 数仓分层设计规范\n\n## 1. 分层架构\n\n- ODS层：原始数据层\n- DWD层：明细数据层\n- DWS层：汇总数据层\n- ADS层：应用数据层\n\n## 2. 命名规范\n\n- ODS表：ods_{业务域}_{表名}\n- DWD表：dwd_{业务域}_{表名}\n- DWS表：dws_{业务域}_{汇总维度}\n- ADS表：ads_{业务域}_{应用场景}', '张三', 1, 1, 1, 156, '数仓,分层,规范', '数据仓库分层架构设计规范文档'),
('业务术语词典 - 订单域', 'business_term', '业务术语', '# 订单域业务术语\n\n| 术语 | 英文 | 定义 |\n|------|------|------|\n| 订单 | Order | 用户提交的购买请求 |\n| 订单金额 | Order Amount | 订单中商品的总金额 |\n| 支付金额 | Payment Amount | 用户实际支付的金额 |\n| 退款 | Refund | 用户退回商品并申请退款 |', '李四', 1, 1, 1, 89, '业务术语,订单', '订单域核心业务术语定义'),
('Kyuubi集群部署手册', 'deploy_doc', '运维手册', '# Kyuubi集群部署手册\n\n## 1. 环境要求\n\n- JDK 17+\n- Hadoop 3.x\n- Spark 3.5+\n\n## 2. 部署步骤\n\n1. 下载Kyuubi安装包\n2. 配置环境变量\n3. 修改kyuubi-defaults.conf\n4. 启动Kyuubi Server', '王五', 1, 1, 0, 234, 'Kyuubi,部署,运维', 'Kyuubi集群生产环境部署手册'),
('用户表血缘关系', 'lineage_doc', '血缘文档', '# 用户表血缘关系\n\n## 上游依赖\n\n- source_users (源系统用户表)\n- source_user_profile (源系统用户画像表)\n\n## 下游影响\n\n- dwd_user_detail (用户明细宽表)\n- dws_user_summary (用户汇总表)\n- ads_user_portrait (用户画像应用表)', '张三', 1, 0, 0, 45, '血缘,用户,ETL', '用户相关表的血缘关系文档');

-- 输出完成信息
SELECT '数据库初始化完成!' AS message,
       (SELECT COUNT(*) FROM sys_user) AS user_count,
       (SELECT COUNT(*) FROM sys_role) AS role_count,
       (SELECT COUNT(*) FROM sys_menu_permission) AS menu_count,
       (SELECT COUNT(*) FROM dev_project) AS project_count,
       (SELECT COUNT(*) FROM dev_script) AS script_count,
       (SELECT COUNT(*) FROM dev_execution_engine) AS engine_count,
       (SELECT COUNT(*) FROM dev_jar_dependency) AS jar_count,
       (SELECT COUNT(*) FROM knowledge_document) AS document_count;
