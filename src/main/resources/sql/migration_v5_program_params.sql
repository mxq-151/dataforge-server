-- ============================================================
-- 程序运行参数表（复合主键：projectId + filePath）
-- 版本: 1.0.0
-- 说明: 存储每个项目下各脚本文件的执行引擎、数据源、JAR依赖、运行参数等配置
-- ============================================================

DROP TABLE IF EXISTS dev_program_params;

CREATE TABLE dev_program_params (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID(自增)',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    file_path VARCHAR(500) NOT NULL COMMENT '文件路径(相对于项目根目录)',
    engine VARCHAR(50) DEFAULT NULL COMMENT '执行引擎类型(spark/flink/kyuubi/hive)',
    datasources TEXT DEFAULT NULL COMMENT '数据源列表(JSON数组)',
    jar_dependency_catalogs TEXT DEFAULT NULL COMMENT 'JAR依赖目录列表(JSON数组)',
    run_params TEXT DEFAULT NULL COMMENT '运行参数(JSON对象)',
    compute_resources TEXT DEFAULT NULL COMMENT '计算资源配置(JSON对象)',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删除 1-已删除)',
    UNIQUE KEY uk_project_file (project_id, file_path),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='程序运行参数表';
