-- ============================================================
-- 数据源类型迁移：HIVE + jdbc:hive2://...:10009 -> KYUUBI + http://127.0.0.1:10099
--
-- 背景：Kyuubi 的 JDBC(Thrift, 10009) 与 REST(HTTP, 10099) 是两个前端。
-- 后端 KyuubiExecutionStrategy 只实现 REST，旧的 HIVE 数据源只能靠全局
-- engine.kyuubi.rest-base-url 兜底，容易和真实地址不一致；改为 KYUUBI 类型后
-- 数据源 URL 本身就是 REST 地址。
--
-- 该脚本在每次容器启动时执行（init-dataforge.sh 第 5.1 步），必须保持幂等：
--   * 只处理 id=2 且仍是 hive 类型的记录，重复执行无副作用；
--   * 不删除任何记录，权限行按 datasource_name 同步改名，避免授权失效；
--   * 用户手工新建的 HIVE 数据源不受影响（有独立 id）。
-- ============================================================

UPDATE datasource_config
SET datasource_type = 'kyuubi',
    connection_type = 'http',
    storage_engine  = 'KYUUBI',
    url             = 'http://127.0.0.1:10099',
    database_name   = '["test_db"]',
    datasource_name = 'kyuubi_datasource',
    description     = '系统初始化-Kyuubi REST数据源(test_db, 25张TPC-DS表)',
    update_time     = now()
WHERE id = 2
  AND datasource_type = 'hive';

UPDATE sys_table_permission
SET datasource_name = 'kyuubi_datasource',
    permission_name = 'kyuubi_test_db读权限',
    description     = 'test用户对Kyuubi test_db的查询权限',
    update_time     = now()
WHERE datasource_name = 'hive_datasource';

-- 元数据归属迁移：table_metadata 的唯一键是 (storage_engine, database_name, table_name)，
-- 数据源类型从 HIVE 改成 KYUUBI 后，旧记录挂在 storage_engine='HIVE' 下，再同步就会
-- 插入一份 'KYUUBI' 副本（实测 25 -> 50）。这里把该数据源的记录统一改成 KYUUBI，
-- 并按 (database_name, table_name) 去重（保留最新一条，含其字段记录）。
UPDATE table_metadata
SET storage_engine = 'KYUUBI',
    update_time    = now()
WHERE datasource_id = 2
  AND storage_engine <> 'KYUUBI';

DELETE FROM column_metadata
WHERE table_metadata_id IN (
    SELECT id FROM (
        SELECT id, row_number() OVER (PARTITION BY database_name, table_name ORDER BY id DESC) AS rn
        FROM table_metadata
        WHERE datasource_id = 2
    ) dup WHERE rn > 1
);

DELETE FROM table_metadata
WHERE id IN (
    SELECT id FROM (
        SELECT id, row_number() OVER (PARTITION BY database_name, table_name ORDER BY id DESC) AS rn
        FROM table_metadata
        WHERE datasource_id = 2
    ) dup WHERE rn > 1
);

UPDATE column_metadata c
SET storage_engine = 'KYUUBI'
FROM table_metadata t
WHERE c.table_metadata_id = t.id
  AND t.datasource_id = 2
  AND c.storage_engine <> 'KYUUBI';
