CREATE TABLE IF NOT EXISTS dev_file_meta (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    file_path VARCHAR(500) NOT NULL COMMENT '文件路径',
    datasources JSON DEFAULT NULL COMMENT '数据源列表',
    jar_dependency_catalogs JSON DEFAULT NULL COMMENT '依赖包分类目录',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    engine VARCHAR(100) DEFAULT NULL COMMENT '执行引擎',
    run_params JSON DEFAULT NULL COMMENT '运行参数配置（程序入参）',
    compute_resources JSON DEFAULT NULL COMMENT '计算资源配置（CPU/内存等）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_project_file (project_id, file_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';
