CREATE TABLE IF NOT EXISTS `sys_tenant_role` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint(20) NOT NULL COMMENT '租户ID',
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_role` (`tenant_id`, `role_id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户角色关联表';

INSERT INTO sys_tenant_role (tenant_id, role_id, create_time)
SELECT tu.tenant_id, ur.role_id, ur.create_time
FROM sys_user_role ur
INNER JOIN sys_tenant_user tu ON ur.user_id = tu.user_id
WHERE NOT EXISTS (
    SELECT 1 FROM sys_tenant_role tr
    WHERE tr.tenant_id = tu.tenant_id AND tr.role_id = ur.role_id
);
