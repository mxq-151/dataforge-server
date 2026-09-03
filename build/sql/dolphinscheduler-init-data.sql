-- =====================================================================
-- DolphinScheduler 补充初始化数据(PostgreSQL 版)
-- 用途: 构建镜像时, 在导入官方 dolphinscheduler_postgresql.sql schema 之后执行,
--       预置 测试用户/租户/环境/项目/令牌。
-- 说明: 官方 PG schema 自带默认数据(admin 用户/default 租户/default 队列/版本),
--       故本文件只含 schema 默认之外的补充记录, 避免主键冲突。
--       密码为 DS 默认加密值, 对应明文 "123456"; 时间戳沿用导出值。
-- 目标库: dolphinscheduler (容器内 PostgreSQL)
-- =====================================================================

-- 测试用户 (t_ds_user) - 官方 schema 已含 admin(id=1, 序列自增)
INSERT INTO public.t_ds_user (id,user_name,user_password,user_type,email,phone,tenant_id,create_time,update_time,queue,state,time_zone) VALUES
(2,'test_tenant','7ad2410b2f4c074479a8937a28a22b8f',1,'578038303@qq.com','',-1,'2026-09-01 09:22:11.179','2026-09-01 09:22:11.179','default',1,NULL);

-- 测试租户 (t_ds_tenant) - 官方 schema 已含 default(id=-1)
INSERT INTO public.t_ds_tenant (id,tenant_code,description,queue_id,create_time,update_time) VALUES
(2,'test_tenant','',1,'2026-09-01 09:21:46.33','2026-09-01 09:21:46.33');

-- 环境 (t_ds_environment) 及关联 (t_ds_environment_worker_group_relation)
INSERT INTO public.t_ds_environment (id,code,name,config,description,operator,create_time,update_time) VALUES
(1,183115959322880,'spark_env','export JAVA_HOME=/opt/java/openjdk/
export SPARK_HOME=/opt/spark/
export TEST_DATASOURCE_URL="jdbc:postgresql://localhost:5432/test_db"
export TEST_DATASOURCE_USER="test_tenant"
export TEST_DATASOURCE_PASSWORD="123456"','spark',1,'2026-09-01 09:22:58.994','2026-09-01 09:22:58.994');

INSERT INTO public.t_ds_environment_worker_group_relation (id,environment_code,worker_group,operator,create_time,update_time) VALUES
(1,183115959322880,'default',1,'2026-09-01 09:22:58.995','2026-09-01 09:22:58.995');

-- 项目 (t_ds_project) 及其关联/偏好
INSERT INTO public.t_ds_project (id,name,code,description,user_id,flag,create_time,update_time) VALUES
(1,'test_project',183115978154240,'',1,1,'2026-09-01 09:23:17.384','2026-09-01 09:23:17.384');

INSERT INTO public.t_ds_relation_project_user (id,user_id,project_id,perm,create_time,update_time) VALUES
(1,2,1,7,'2026-09-01 09:23:28.843','2026-09-01 09:23:28.843');

INSERT INTO public.t_ds_project_preference (id,code,project_code,preferences,user_id,state,create_time,update_time) VALUES
(1,183126239314560,183115978154240,'{"taskPriority":"MEDIUM","workerGroup":"default","environmentCode":183115959322880,"failRetryTimes":0,"failRetryInterval":1,"cpuQuota":-1,"memoryMax":-1,"timeoutFlag":false,"timeoutNotifyStrategy":["WARN"],"timeout":30,"warningType":"NONE","tenant":"test_tenant"}',2,1,'2026-09-01 12:10:18.142','2026-09-01 12:10:18.142');

-- 令牌 (t_ds_access_token)
INSERT INTO public.t_ds_access_token (id,user_id,token,expire_time,create_time,update_time) VALUES
(1,2,'227d4b22f1b552ef0ce33562bd802df9','2029-09-01 17:23:01','2026-09-01 09:23:06.75','2026-09-01 09:23:06.75');