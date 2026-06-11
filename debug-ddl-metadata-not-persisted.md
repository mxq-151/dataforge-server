# Debug Session: ddl-metadata-not-persisted

## Status: [OPEN]

## Symptom
用户通过平台执行 `CREATE EXTERNAL TABLE IF NOT EXISTS tmall_dw.ods_product (...)` 建表语句后：
- SQL 在 Kyuubi 上执行成功（表确实创建了）
- 但 `table_metadata` 和 `column_metadata` 表中没有写入任何数据
- 单元测试 16/16 通过（但单元测试使用 mock，不代表生产行为）

## Reproduction Steps
1. 在平台上选择 HIVE/KYUUBI 数据源
2. 执行以下建表 SQL：
```sql
CREATE EXTERNAL TABLE IF NOT EXISTS tmall_dw.ods_product (
     item_id    BIGINT  COMMENT '商品 ID',
     title      STRING  COMMENT '商品标题关键词（NLP 提取，空格分隔）',
     pict_url   STRING  COMMENT '商品主图 URL',
     category   STRING  COMMENT '商品类目编码，格式：父类目-叶子类目',
     brand_id   STRING  COMMENT '品牌 ID',
     seller_id  STRING  COMMENT '卖家 ID'
) ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION 'jfs://myjfs/test/product'
TBLPROPERTIES (
    'comment' = '天猫商品属性原始数据外部表',
    'skip.header.line.count' = '0'
);
```
3. 查询 `table_metadata` 表 → 无数据

## Call Chain Analysis
```
用户提交SQL
  → KyuubiExecutionStrategy.execute(context)
    → submitOperation(sql)  → Kyuubi执行成功 (FINISHED)
    → handleDdlMetadataSync(sql, context, executionId)   ← 关键入口
      → detectDdlStatement(sql)                          ← 断点1: DDL检测
        → stripHiveSpecificClauses(sql)                  ← 新增的Hive语法剥离
        → CCJSqlParserUtil.parse(normalizedSql)         ← JSqlParser解析
      → [如果ddlInfo==null] 静默返回！无任何日志！       ← 高度可疑
      → [如果ddlInfo!=null]
        → metadataSyncService.syncSingleTableMetadata()  ← 断点2: 元数据同步
          → executionStrategyFactory.getStrategy(config)
          → strategy.listColumns(config, db, table)      ← 断点3: 获取字段(Kyuubi REST)
          → convertToColumnMetadataEntities()
          → tableMetadataService.syncSingleTable()        ← 断点4: 持久化写入DB
```

## Hypotheses

### H1 [HIGH]: detectDdlStatement 返回 null — JSqlParser 解析 Hive DDL 失败
**证据线索：**
- `detectDdlStatement` 的 catch 块使用 `getLog().debug()` 记录（第177行），生产环境通常不输出 DEBUG 级别
- `handleDdlMetadataSync` 第775行 `if (dllInfo == null) return;` **完全静默**，没有任何日志
- 我们新增了 `stripHiveSpecificClauses()` 方法，但 **应用可能未重启**，旧代码仍在运行
- 即使已重启，正则可能对某些边界情况处理不当

**验证方式：** 在 detectDdlStatement 入口和返回处加 INFO 级别日志

### H2 [MEDIUM]: strategy.listColumns() 返回空列表或抛异常
**证据线索：**
- `KyuubiExecutionStrategy.listColumns()` 通过 Kyuubi REST API 执行 `DESCRIBE db.table`
- 如果刚建完表就立即 DESCRIBE，可能存在元数据同步延迟
- 异常在 listColumns 内部被 catch 并返回空列表（第824-826行）
- 空列表 → convertToColumnMetadataEntities 返回空 → syncSingleTable 写入0列

**验证方式：** 在 listColumns 调用前后加日志记录返回数量

### H3 [MEDIUM]: syncSingleTableMetadata 内部异常被吞掉
**证据线索：**
- 第72行 catch 块 `log.error(...)` 会记录，但如果日志级别配置不当可能看不到
- `@Transactional` 注解可能导致异常时整个事务回滚（但 catch 应该能捕获）

**验证方式：** 在 try 块每个关键步骤后加日志

### H4 [LOW]: 应用未重启 / 代码未生效
**证据线索：**
- 我们修改了 AbstractBaseExecutionStrategy（新增 stripHiveSpecificClauses）
- 修改了 MetadataSyncService（新增 targetTable==null 守卫）
- 如果应用运行的是旧编译版本，这些修复都不存在

**验证方式：** 在代码中加一个明显的"版本标记"日志

## Instrumentation Plan
在以下位置添加 INFO 级别的诊断日志（不修改业务逻辑）：

| ID | 文件 | 位置 | 目的 |
|----|------|------|------|
| D1 | AbstractBaseExecutionStrategy.java | detectDdlStatement 入口 + 返回值 | 确认是否进入、解析结果 |
| D2 | AbstractBaseExecutionStrategy.java | handleDdlMetadataSync ddlInfo==null 分支 | 确认静默返回 |
| D3 | MetadataSyncService.java | syncSingleTableMetadata 关键步骤 | 确认每步执行结果 |
| D4 | MetadataSyncService.java | listColumns 返回后 | 确认字段数量 |

## Timeline
- Created: 2026-06-11
