-- 开发脚本表
CREATE TABLE IF NOT EXISTS `dev_script` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `script_name` varchar(200) NOT NULL COMMENT '脚本名称',
  `script_code` varchar(100) NOT NULL COMMENT '脚本编码',
  `script_type` varchar(50) NOT NULL COMMENT '脚本类型: sql, spark-scala, spark-java, spark-python, flink-sql, flink-java, flink-python, shell',
  `content` longtext COMMENT '脚本内容',
  `project_id` bigint DEFAULT NULL COMMENT '项目ID',
  `datasource_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `status` tinyint DEFAULT 1 COMMENT '状态: 1-正常, 0-禁用',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_project_id` (`project_id`),
  KEY `idx_script_type` (`script_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='开发脚本表';
