ALTER TABLE `sys_user` ADD COLUMN `is_admin` tinyint(1) DEFAULT 0 COMMENT '是否超级管理员(0=否,1=是)' AFTER `status`;
UPDATE `sys_user` SET `is_admin` = 1 WHERE `username` = 'admin';
