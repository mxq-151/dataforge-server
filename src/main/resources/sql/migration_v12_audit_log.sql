CREATE TABLE IF NOT EXISTS `operation_audit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL COMMENT '操作用户ID',
  `username` varchar(50) DEFAULT NULL COMMENT '操作用户名',
  `tenant_id` bigint(20) DEFAULT NULL COMMENT '租户ID',
  `tenant_name` varchar(100) DEFAULT NULL COMMENT '租户名称',
  `module` varchar(50) NOT NULL COMMENT '模块名称',
  `operation` varchar(100) NOT NULL COMMENT '操作描述',
  `operation_type` varchar(20) DEFAULT NULL COMMENT '操作类型(CREATE/UPDATE/DELETE/LOGIN/EXPORT/QUERY/EXECUTE)',
  `target_type` varchar(50) DEFAULT NULL COMMENT '目标对象类型(User/Role/Tenant/Datasource/Table/File)',
  `target_id` bigint(20) DEFAULT NULL COMMENT '目标对象ID',
  `target_name` varchar(200) DEFAULT NULL COMMENT '目标对象名称',
  `request_method` varchar(10) DEFAULT NULL COMMENT '请求方法(GET/POST/PUT/DELETE)',
  `request_url` varchar(500) DEFAULT NULL COMMENT '请求URL',
  `request_params` text COMMENT '请求参数(JSON格式,敏感数据脱敏)',
  `response_body` text COMMENT '响应内容(仅记录关键信息,截断过长内容)',
  `response_status` int(11) DEFAULT NULL COMMENT 'HTTP响应状态码',
  `error_message` text COMMENT '错误信息',
  `exception_class` varchar(200) DEFAULT NULL COMMENT '异常类名',
  `ip_address` varchar(50) DEFAULT NULL COMMENT '客户端IP地址',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `duration_ms` int(11) DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `is_sensitive` tinyint(1) DEFAULT 0 COMMENT '是否敏感操作(0=否,1=是)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`, `operation_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_module` (`module`),
  KEY `idx_operation_type` (`operation_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE (TO_DAYS(`operation_time`)) (
  PARTITION p_max VALUES LESS THAN MAXVALUE
);

SET @cur_month_start = DATE_FORMAT(CURDATE(), '%Y-%m-01');
SET @next_month_start = DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%Y-%m-01');
SET @after_next_month_start = DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 2 MONTH), '%Y-%m-01');

ALTER TABLE `operation_audit_log` REORGANIZE PARTITION p_max INTO (
  PARTITION p_cur VALUES LESS THAN (TO_DAYS(@next_month_start)),
  PARTITION p_next VALUES LESS THAN (TO_DAYS(@after_next_month_start)),
  PARTITION p_max VALUES LESS THAN MAXVALUE
);

DELIMITER //

DROP PROCEDURE IF EXISTS `sp_create_audit_log_partition` //
CREATE PROCEDURE `sp_create_audit_log_partition`()
BEGIN
    DECLARE v_max_partition_name VARCHAR(20);
    DECLARE v_max_partition_end_days INT;
    DECLARE v_max_partition_end_date DATE;
    DECLARE v_new_partition_name VARCHAR(20);
    DECLARE v_new_partition_end_date DATE;
    DECLARE v_days_until_end INT;
    DECLARE v_partition_exists INT;

    SELECT PARTITION_NAME, TO_DAYS(PARTITION_DESCRIPTION)
    INTO v_max_partition_name, v_max_partition_end_days
    FROM information_schema.PARTITIONS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'operation_audit_log'
      AND PARTITION_NAME != 'p_max'
    ORDER BY PARTITION_ORDINAL_POSITION DESC
    LIMIT 1;

    SET v_max_partition_end_date = FROM_DAYS(v_max_partition_end_days);
    SET v_days_until_end = DATEDIFF(v_max_partition_end_date, CURDATE());

    IF v_days_until_end <= 2 THEN
        SET v_new_partition_end_date = DATE_ADD(v_max_partition_end_date, INTERVAL 1 MONTH);
        SET v_new_partition_name = CONCAT('p_', DATE_FORMAT(v_max_partition_end_date, '%Y%m'));

        SELECT COUNT(*) INTO v_partition_exists
        FROM information_schema.PARTITIONS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'operation_audit_log'
          AND PARTITION_NAME = v_new_partition_name;

        IF v_partition_exists = 0 THEN
            SET @sql = CONCAT(
                'ALTER TABLE operation_audit_log REORGANIZE PARTITION p_max INTO (',
                'PARTITION ', v_new_partition_name,
                ' VALUES LESS THAN (TO_DAYS(''', v_new_partition_end_date, '''))',
                ', PARTITION p_max VALUES LESS THAN MAXVALUE)'
            );
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
    END IF;
END //

DROP PROCEDURE IF EXISTS `sp_cleanup_audit_log_partitions` //
CREATE PROCEDURE `sp_cleanup_audit_log_partitions`()
BEGIN
    DECLARE v_partition_name VARCHAR(20);
    DECLARE v_partition_end_days INT;
    DECLARE v_partition_end_date DATE;
    DECLARE v_retention_days INT DEFAULT 90;
    DECLARE v_done INT DEFAULT FALSE;

    DECLARE cur CURSOR FOR
        SELECT PARTITION_NAME, TO_DAYS(PARTITION_DESCRIPTION)
        FROM information_schema.PARTITIONS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'operation_audit_log'
          AND PARTITION_NAME != 'p_max'
          AND PARTITION_NAME != 'p_cur'
          AND TO_DAYS(PARTITION_DESCRIPTION) < TO_DAYS(DATE_SUB(CURDATE(), INTERVAL v_retention_days DAY))
        ORDER BY PARTITION_ORDINAL_POSITION ASC;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = TRUE;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_partition_name, v_partition_end_days;
        IF v_done THEN
            LEAVE read_loop;
        END IF;
        SET @sql = CONCAT('ALTER TABLE operation_audit_log DROP PARTITION ', v_partition_name);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
END //

DELIMITER ;

