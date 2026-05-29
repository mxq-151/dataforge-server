ALTER TABLE `sys_tenant` ADD COLUMN `password` varchar(200) DEFAULT NULL COMMENT '租户密码' AFTER `owner_id`;
