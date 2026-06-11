-- Migration v13: 数据源配置表添加连接方式字段
-- 将连接协议从数据源类型中解耦，支持 JDBC / HTTP / Arrow Flight 等多种连接方式

ALTER TABLE `datasource_config`
    ADD COLUMN `connection_type` VARCHAR(20) DEFAULT 'jdbc' COMMENT '连接方式(jdbc/http/arrow)'
    AFTER `datasource_type`;

-- 为已有数据设置默认值：ES 类型默认 HTTP，其余默认 JDBC
UPDATE `datasource_config` SET `connection_type` = 'http' WHERE `datasource_type` = 'es';
UPDATE `datasource_config` SET `connection_type` = 'jdbc' WHERE `connection_type` IS NULL OR `connection_type` = '';

-- 添加索引以支持按连接方式查询
CREATE INDEX idx_connection_type ON `datasource_config` (`connection_type`);
