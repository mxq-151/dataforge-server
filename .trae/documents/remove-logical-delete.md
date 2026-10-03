# 去除所有表的逻辑删除（`deleted` 列）

## Context

当前项目用一张全局的 `deleted` 列做逻辑删除：`BaseEntity` 定义该字段，约 31 张表带 `` `deleted` ... COMMENT '逻辑删除' `` 列。但项目**没有**配置 MyBatis-Plus 全局 `@TableLogic`，导致：

- 每次查询都要手写 `.eq(Xxx::getDeleted, 0)`，漏写就会读到"已删除"数据（历史上已出现过 `column_metadata` 无此列导致的报错）；
- 删除走 `setDeleted(1)` + `updateById`，语义不统一（system 模块其实已经是物理 `deleteById`）。

目标：彻底移除 `deleted` 列与全部相关代码，删除行为统一为**物理删除**。

**已确认的决策**
1. 现有"删除"接口一律改为物理删除（直接删行，不可恢复），关联表同步物理删除。
2. 只改 `sql/init/*` 与代码；**不新增**面向存量库的 `ALTER TABLE ... DROP COLUMN deleted` 迁移脚本（存量库由运维自行处理）。

## 影响面

- Java 源码 25 个文件 / 120 处引用
- 9 个 MyBatis Mapper XML（19 处）
- 4 个 init 建表脚本 + 1 个 testdata 脚本
- 5 个集成测试文件（12 处）

## 实施步骤

### 1. 实体层删除字段
- [BaseEntity.java](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/java/com/bigdata/common/entity/BaseEntity.java)：删除 `private Integer deleted;` 及 `getDeleted/setDeleted`。
- [TableMetadata.java](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/java/com/bigdata/modules/metadata/entity/TableMetadata.java)：删除 L40 字段与 L125-126 getter/setter（该类未继承 BaseEntity）。
- [ProgramParams.java](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/java/com/bigdata/modules/development/entity/ProgramParams.java)：删除 L43 字段与 L125-130 getter/setter。

### 2. 查询条件：删掉所有逻辑删除过滤
统一删除 `.eq(Xxx::getDeleted, 0)`、`.eq("deleted", 0)` 这类条件。代表文件（其余为同模式）：
- metadata：`MetadataAnnotationGapService`、`MetadataAgentController`、`TableStorageSizeRefreshService`
- asset：`AssetInventoryService`、`AssetHealthService`、`AssetUsageService`（含 L449/465/524 的原始 `"deleted"` 字符串条件）、`AssetCatalogService`、`AssetCatalogSnapshotService`、`AssetGovernanceService`、`AssetMetaService`、`AssetBookmarkService`、`SchedulerTaskMetricsService`
- quality：`DqRuleService`、`DqTaskService`、`DqRuleTemplateService`、`DqExecutionService`、`DqOverviewService`、`DqRunService`、`DqAlertService`
- 其它：`HBaseSchema`

### 3. 新增路径：删除 `setDeleted(0)`
所有 `setDeleted(0)` 调用直接删除。涉及 `TableMetadataService`、`AssetBookmarkService`、`DqTaskService`、`DqRuleService`、`DqRuleTemplateService`、`SchedulerTaskMetricsService`、`DqRunService`、`AssetCatalogSnapshotService`、`DqAlertService`、`AssetGovernanceService`。

### 4. 删除路径：逻辑删除 → 物理删除
仅 5 处 `setDeleted(1)`，逐一改写：
- `DqTaskService.delete`（L113-126）：任务 `deleteById(id)`；关联 `dq_task_rule_rel` 用 wrapper 按 `taskId` 批量 `delete`。
- `DqTaskService.replaceRuleBindings`（L180-185）：移除绑定时 `dqTaskRuleRelMapper.deleteById(rel.getId())`。
- `DqRuleService.delete`（L154-166）：`deleteById(id)`。
- `DqRuleTemplateService.delete`（L216-228）：`deleteById(id)`。

> 说明：`KnowledgeDocumentService`、`DevProjectService` 及 system 模块本就是物理删除，无需改动。

### 5. 简化"已被删除"判空守卫
将 `x == null || x.getDeleted()==1` 之类的判断简化为 `x == null`；独立 filter 删除：
- `DqTaskService` L80；`DqRuleService` L96 / L109（filter）/ L185 / L391
- `AssetCatalogService` L710-712（循环内跳过逻辑整段删除）/ L1053
- `AssetGovernanceService` L435；`AssetProducerService` L87

### 6. Mapper XML
- `TableMetadataMapper.xml`（[mysql](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/resources/mapper/mysql/TableMetadataMapper.xml) / mariadb / postgresql / h2）：upsert 的列清单去掉 `deleted`，去掉 `#{t.deleted}` 与 `ON DUPLICATE KEY UPDATE ... deleted = #{t.deleted}`；h2 版同理。
- `ProgramParamsMapper.xml`（4 方言）：列清单去掉 `deleted`，值列表去掉末尾 `, 0`。
- [DataQualityCheckResultMapper.xml](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/resources/mapper/quality/DataQualityCheckResultMapper.xml)：`WHERE deleted = 0 AND ...` → `WHERE ...`。

### 7. SQL 脚本（仅 init + testdata）
- 4 个 init 文件各删除约 31 行 `deleted` 列定义（方言差异：mysql/mariadb `` `deleted` tinyint ... COMMENT '逻辑删除', ``、postgresql `deleted SMALLINT NOT NULL DEFAULT 0,`、h2 `deleted TINYINT NOT NULL DEFAULT 0,`）：
  [dataforge-mysql.sql](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/resources/sql/init/dataforge-mysql.sql)、`dataforge-mariadb.sql`、`dataforge-postgresql.sql`、`dataforge-h2.sql`
- 删除 2 条种子 INSERT（`sys_user`、`sys_role`）列清单里的 `deleted` 及各数据行末尾对应的 `,0`（多行 VALUES，逐行处理）。
- [dataforge-testdata-postgresql.sql](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/resources/sql/testdata/dataforge-testdata-postgresql.sql)：6 条 INSERT 去掉 `deleted` 列及对应尾值。
- 按决策，**不改** `sql/migrate/*.sql`（历史增量脚本，`CREATE TABLE IF NOT EXISTS` 幂等，存量库保留该列不影响运行，默认值 0）。

### 8. 集成测试
- [KnowledgeDocumentControllerIntegrationTest.java](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/test/java/com/bigdata/modules/knowledge/controller/KnowledgeDocumentControllerIntegrationTest.java#L202-L207)、[DevProjectControllerIntegrationTest.java](file:///Users/mxq/Desktop/dataforge-docker/dataforge-server/src/test/java/com/bigdata/modules/development/controller/DevProjectControllerIntegrationTest.java#L253-L258)：断言由 `assertEquals(1, afterDelete.getDeleted())` 改为 `assertNull(afterDelete)`（物理删除）。
- `RoleControllerIntegrationTest`、`TenantControllerIntegrationTest`、`UserControllerIntegrationTest`：去掉断言消息中的"（逻辑删除）"字样。

### 9. 文档/记忆（可选，非必需）
`.workbuddy/memory/MEMORY.md` 中"无全局 `@TableLogic`：查询需手动 `.eq(...)`"的约定已失效，可顺手更新。

## 验证

1. 编译：`mvn -q -DskipTests compile`
2. 残留检查（应均为 0）：
   - `src/main` 中 `getDeleted|setDeleted|::getDeleted`
   - `src/main/resources/sql/init` 中 `deleted`
   - `src/main/resources/mapper` 中 `deleted`
3. 目标集成测试（需本地 MySQL，参考 `startup/macos/e2e-test.sh`）：
   `mvn -Dtest=UserControllerIntegrationTest,RoleControllerIntegrationTest,TenantControllerIntegrationTest,KnowledgeDocumentControllerIntegrationTest,DevProjectControllerIntegrationTest test`
   以及质检/资产相关测试。
4. 端到端：向临时 MySQL 灌入新 init 脚本，`SHOW CREATE TABLE dq_task;` 确认无 `deleted` 列；通过接口删除一条质检任务/收藏，确认行被物理删除且列表不再返回。