# dataforge-server — 项目长期约定

## 构建 / 运行

- `mvn` 不在 PATH：用 `/opt/homebrew/bin/mvn`，且 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`。
- 本机 MySQL 在 `127.0.0.1:3306`（库 `dataforge`），`@SpringBootTest` 类测试会真连它。
- 只跑新测试：`mvn -o test -Dtest=XxxTest -DfailIfNoTests=false`（整包 5 分钟，多数失败是环境依赖）。

## 接口契约（务必先看，容易踩）

- **JSON 字段名是 camelCase**。`application.yml` 里的 `spring.jackson.property-naming-strategy: SNAKE_CASE`
  **不生效** —— `common/config/WebMvcConfig` 声明了 `@Primary ObjectMapper`，Boot 自动配置让位，
  线上按默认 camelCase 序列化，前端 `src/api/*.ts` 类型定义据此编写（README 注意事项 4 有记载）。
  改 VO/DTO 字段名时以 camelCase 为准，不要被 yml 误导去写 `@JsonProperty`。
- **写接口契约测试必须用 `ContractMockMvcSupport.productionMockMvc(...)`**。
  该类里另有一组 `objectMapper()/mockMvc()` 是早期按 yml 语义（SNAKE_CASE + NON_NULL）手工构造的，
  与生产不符，仅 `MetadataApiContractTest` / `DatasourceApiContractTest` 两个历史用例在用；
  用它会断言出线上并不存在的 snake_case 字段名，产出"假绿"契约。
- **`Result<T>` 包络**：`{code, message, data}`，成功 `code=200`。
- **两套错误语义并存**，前端依赖这一点：
  - 业务异常（`BusinessException`）→ **HTTP 200** + `body.code` 携带真实错误码（403/404/…）；
  - 客户端请求错误（缺参/类型不匹配/路径不存在/方法不支持）→ **HTTP 4xx**，
    由 `GlobalExceptionHandler` 精确匹配后返回，不能落到 `Exception` 兜底分支（否则变 500）。

## 代码风格 / 持久层

- **无 Lombok**，getter/setter 手写（`Boolean` 包装类型用 `getIsXxx`/`setIsXxx`）。
- **无逻辑删除**：全表已移除 `deleted` 列与相关字段，删除一律物理删除（`deleteById`），查询无需再加 `deleted` 过滤。
- 分页用 MyBatis-Plus `Page`，返回体字段为 `records`/`total`（前端依赖）。
- 方言族收敛在各自模块的 `*Dialect`（如 `DqDialect`：MYSQL/POSTGRESQL/HIVE），
  引用符 / 转字符串 / LIMIT 差异不散落到业务代码。
- 建表与增量脚本：`src/main/resources/sql/init/`（4 方言全量）+ `sql/migrate/`（增量，须幂等，
  且 PG 与 MySQL 各一份，MySQL 用存储过程做 `ADD COLUMN IF NOT EXISTS` 等价物）。

## 数据访问权限

- 语义是 **fail-close**：`DataAccessPermissionService` 的 `getMergedRowFilterSql` 返回 null 才表示不过滤；
  `getAllowedColumns` 返回 null 表示不限制、空集合表示无可见列。
- 库存批量方法：`filterAccessibleTables(...)` / `hasDatabaseAccess(...)`，新模块不要自己造过滤逻辑。
- **租户不是数据隔离边界**（租户与用户 1:1，资源表无租户字段），只有库/表/列权限是。
- 数据预览类功能必须走 `CodeExecutionService`，以复用行/列权限改写与脱敏，不要另起查询通道。

## 模块边界（避免重复建设）

- 血缘复用桌面端 Marquez 客户端；产出任务实时代理 DolphinScheduler（未接入返回空列表，不造假数据）；
  元数据标注缺口复用 `/metadata/annotation-gaps`；分层/业务域由表名实时解析（`TableNameConventionParser`），不落库。
