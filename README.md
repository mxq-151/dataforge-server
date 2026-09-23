# dataforge-server

DataForge 平台的业务后端服务，为 **dataforge 桌面端**提供 REST 接口：统一身份与多级权限、数据源与存储配置、分布式文件浏览、库表与字段元数据、指标、知识库、数据质量与操作审计。

> 本 README 描述的是**当前代码实际状态**。历史版本曾声称"仅实现系统管理 + 运维管理两个模块、6 张表"，已不适用。

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 框架 | SpringBoot 3.2.1 + Java 17（jar 打包） |
| 安全 | Spring Security 6 + JWT（jjwt 0.12.3，默认有效期 24h，`Authorization: Bearer <token>`） |
| Web | spring-boot-starter-web / validation / aop / websocket |
| ORM | MyBatis-Plus 3.5.5（`mybatis-plus-spring-boot3-starter`，`mapper/**/*.xml`） |
| 数据库 | MySQL 8（默认）、PostgreSQL；`sql/init` 另提供 MariaDB / H2 方言脚本 |
| 接口文档 | Knife4j 4.4.0（`knife4j-openapi3-jakarta-spring-boot-starter`，OpenAPI 3） |
| 工具库 | Hutool 5.8.24、Guava 33.0.0-jre、HttpClient 4.5.14 |
| 计算/存储对接 | Hadoop 3.4.3、HBase 2.5.9、JuiceFS Hadoop 1.2.3、Calcite 1.37.0、JSqlParser 4.9、Apache ORC |

## 业务模块

共 6 个业务模块、20 个控制器，位于 `src/main/java/com/bigdata/modules/`：

| 模块 | 职责 | 控制器（类级基路径） |
| --- | --- | --- |
| `system` | 认证、用户、角色、权限、菜单角色绑定、项目权限、租户、审计日志 | `/system/auth`、`/system/user`、`/system/role`、`/system/permission`、`/system/menu-role`、`/system/project-permission`、`/system/tenant`、`/system/audit-log` |
| `devops` | 数据源、存储配置、执行引擎、分布式文件浏览 | `/devops/datasource`、`/devops/storage`、`/devops/execution`、`/file/explorer` |
| `development` | 开发工程、代码执行、Jar 依赖 | `/development/project`、`/development/execution`、`/devops/dependency` |
| `metadata` | 库表与字段元数据、元数据代理（供 AI 使用）、指标 | `/metadata`、`/metadata/agent`、`/metadata/indicators` |
| `knowledge` | 知识文档 | `/knowledge/document` |
| `quality` | 数据质量校验结果 | `/quality/check-result` |

通用层位于 `src/main/java/com/bigdata/common/`：`config`（MyBatis-Plus 填充、WebMvc、Jackson）、`security`（JWT 过滤器、安全配置、数据访问鉴权）、`exception`（业务异常 + 全局异常处理）、`utils`（令牌、密码）、`vo`（`Result` / `PageVO`）、`aspect`（审计日志切面）、`sql`（Calcite DDL 解析）。

完整接口清单请访问 Knife4j 文档页（见「快速开始」）。

## 数据模型

`src/main/resources/sql/init/` 下的初始化脚本共 **28 张表**，按用途分为五组：

| 分组 | 表 |
| --- | --- |
| 身份与权限（12） | `sys_user`、`sys_role`、`sys_tenant`、`sys_tenant_user`、`sys_tenant_role`、`sys_role_project_permission`、`sys_role_menu_permission`、`sys_menu_permission`、`sys_role_table_permission`、`sys_table_permission`、`sys_table_permission_column`、`sys_table_permission_row` |
| 文件权限（2） | `sys_file_permission`、`sys_role_file_permission` |
| 数据源与存储（2） | `datasource_config`、`storage_config` |
| 开发与执行（5） | `dev_project`、`dev_program_params`、`dev_script_execution`、`ddl_execution_log`、`dev_jar_dependency` |
| 元数据与治理（7） | `table_metadata`、`column_metadata`、`indicators`、`indicators_fields`、`knowledge_document`、`data_quality_check_result`、`operation_audit_log` |

权限模型支持到**行级与列级**（`sys_table_permission_row` / `sys_table_permission_column`）；文件权限按路径 + 存储配置维度控制。

## 数据源类型

统一枚举 `modules/devops/enums/DatasourceType`，共 13 种，通过 `exec` / `store` 标记区分计算能力与存储能力，并声明各自支持的代码类型：

| code | 名称 | 计算 | 存储 | 支持语言 |
| --- | --- | --- | --- | --- |
| `spark` | Apache Spark | ✓ | | Java / Scala / Python / SQL |
| `flink` | Apache Flink | ✓ | | Java / Scala / Python / SQL |
| `kyuubi` | Apache Kyuubi | ✓ | | SQL |
| `doris` | Apache Doris | ✓ | ✓ | SQL |
| `hbase` | Apache HBase | ✓ | ✓ | SQL |
| `mysql` | MySQL | ✓ | ✓ | SQL |
| `postgresql` | PostgreSQL | ✓ | ✓ | SQL |
| `clickhouse` | ClickHouse | ✓ | ✓ | SQL |
| `hive` | Hive | | ✓ | SQL |
| `starrocks` | StarRocks | ✓ | ✓ | SQL |
| `es` | Elasticsearch | | ✓ | — |
| `hudi` | Apache Hudi | | ✓ | SQL |
| `iceberg` | Apache Iceberg | | ✓ | SQL |

支持的存储类型（`GET /devops/storage/types`）：`hdfs`、`s3`、`juicefs`，另有本地文件系统 `local`。

## 认证与权限

- **无状态 JWT**：登录签发令牌，请求头 `Authorization: Bearer <token>`；安全配置使用 `SessionCreationPolicy.STATELESS`。
- **免鉴权路径**：`POST /api/system/auth/login`、`GET /api/devops/datasource/types`、`GET /api/devops/storage/types`、`GET /api/file/explorer/storage-types`、Knife4j 文档资源（`/doc.html`、`/webjars/**`、`/v3/api-docs/**`），以及所有 `OPTIONS` 预检。
- **接口级鉴权**：写操作普遍带 `@PreAuthorize`；数据访问另由 `common/security/DataAccessPermissionService` 做表级 / 文件级校验。
- **密码存储**：`MD5(明文 + 固定盐)`，盐值硬编码在 `common/utils/PasswordUtils`。

### 数据访问权限模型（务必先读）

**租户不是数据隔离边界。** 租户与用户是 1:1 绑定（`sys_tenant_user` 对 `tenant_id` 与 `user_id` 都加了唯一约束），
数据源、存储配置、开发项目、库表与文件权限表都**没有**租户字段；租户只影响角色绑定与审计日志。
跨租户的资源可见性完全依赖授权时的人为约束，**不要**把租户当作隔离边界来做安全设计。

### 表级 / 行级 / 列级权限的执行语义

| 环节 | 行为 |
| --- | --- |
| 校验入口 | `AbstractBaseExecutionStrategy.checkPermission`（JDBC / Kyuubi / Spark / Flink / HBase 全部继承同一基类，共用同一套校验） |
| 语句拆分 | 按分号拆成独立语句**逐条**校验；字符串字面量与注释里的分号不参与拆分（`common/sql/SqlScriptSplitter`） |
| 表引用覆盖 | FROM 与各 JOIN 右侧、FROM 子句里的派生表、WHERE / HAVING / ON / 投影列中的子查询、UNION 各分支与 CTE（`common/sql/SqlTableCollector`） |
| DDL | 仅超级管理员 / `admin` / `ops` 角色可执行；Calcite 无法识别但以 DDL 关键字（CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT/GRANT/REVOKE/ANALYZE）开头的语句**默认拒绝** |
| **fail-close** | 以 DML 关键字开头但无法解析的语句**默认拒绝**并抛 `SecurityException`，不再「打一条 debug 日志后放行」；权限判定过程异常同样按默认拒绝 |
| 行 / 列权限合并 | **取交集收紧**：同一张表命中多条权限时，某个权限未配置约束只是「它自己不额外限制」，不会放宽其他权限已配置的行过滤条件与列白名单；脱敏规则按列合并并取更严格的规则 |
| 执行路径 | **全部 SQL 路径统一在下发前改写**（`SqlPermissionRewriter`）：把行过滤条件 AND 进 WHERE、把投影列裁剪为列白名单。JDBC / Kyuubi 另保留「结果集后过滤 + 脱敏 + 列裁剪」作为第二道防线；行过滤在改写已生效时不再二次应用（多表场景下后过滤只能取第一张表的条件，重复过滤会把正确结果裁错） |
| 聚合查询 | 行级权限不再是「结果集后过滤」，因此 `count / sum` 等聚合口径也被正确限定，不再泄露被挡住的行的数值 |
| 改写边界 | 列裁剪只允许单表顶层查询（集合运算分支 / 子查询里裁剪会改变外层列集合 → 拒绝）；多表注入行过滤要求该层 JOIN 全为内连接（外连接下注入会改变连接语义 → 拒绝）；投影表达式引用了白名单外的列 → 拒绝 |
| 改写内容去向 | 改写结果放在 `CodeExecutionContext.effectiveCodeContent`，只有真正提交给引擎的内容被替换；日志、审计与回显仍展示用户原始代码 |
| 不在本层范围 | Scala / Python / Jar 等以任意代码访问数据源的作业，不做 SQL 语句级校验（非 SQL 代码类型直接跳过） |

### 元数据同步失败的告警入口

DDL 执行成功后会触发元数据同步；同步失败不再只写 warn 日志，而是产出两条可观测信号：

- **审计日志**：`module=metadata`、`operation=METADATA_SYNC_FAILED`（单表）/ `METADATA_FULL_SYNC_FAILED`（数据源全量），
  `response_status=500`，错误原因在 `error_message`。可通过 `GET /api/system/audit-log/list?module=metadata` 检索。
- **告警级日志**：固定前缀 `[METADATA-SYNC-ALERT]`，便于日志采集侧直接配告警规则。

### 元数据缺口清单

`GET /api/metadata/annotation-gaps?datasourceId=&databaseName=&limit=` 返回表注释为空、和/或存在字段注释为空的表清单
（含缺失字段明细，只列出存在缺失的表），并按调用者的库 / 表权限过滤。用于把「描述缺失」变成可见清单。

## 快速开始

**环境要求**：JDK 17+、Maven 3.6+、MySQL 8（或 PostgreSQL）。

```bash
# 1. 初始化数据库（按数据库方言选择脚本）
mysql -u root -p < src/main/resources/sql/init/dataforge-mysql.sql

# 2. 修改连接信息：src/main/resources/application.yml -> spring.datasource.*

# 3. 启动
mvn spring-boot:run
```

- 服务地址：<http://localhost:8083/api>
- 接口文档：<http://localhost:8083/api/doc.html>
- 默认账号：`admin` / `123456`（初始化脚本预置 1 个 `SUPER_ADMIN` 角色；测试用的用户 / 角色 / 菜单 / 租户 / 数据源见 `sql/testdata/`）

登录自测：

```bash
curl -X POST http://localhost:8083/api/system/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

## 配置项（`application.yml`）

| 配置 | 说明 |
| --- | --- |
| `server.port` | 默认 `8083`（容器内由启动参数覆盖为 `8082`） |
| `server.servlet.context-path` | `/api`，所有接口路径的前缀 |
| `spring.datasource.*` | 数据库连接，HikariCP 连接池（min 5 / max 20） |
| `spring.sql.init.mode` | 保持 `never`——置为 `always` 会在每次启动时重建表并丢失数据 |
| `jwt.secret` / `jwt.expiration` | 令牌密钥与有效期（默认 86400000 ms） |
| `jwt.header` / `jwt.prefix` | 默认 `Authorization` / `Bearer` |
| `aes.key` | 数据源等敏感配置的加解密密钥 |
| `engine.spark.home` / `engine.flink.home` / `engine.jar.base-path` | 提交执行时使用的引擎安装路径与 jar 存放根目录 |

## 容器化部署（All-in-One）

`build/` 与 `startup/` 提供单容器一体镜像：镜像内以 supervisord 托管 **PostgreSQL 14 + dataforge-server + DolphinScheduler + Marquez（API 与 Web）**，另预置 Spark 3.5.9 与 OpenLineage Spark 插件。

```
dataforge-server/
├── build/            # 构建
│   ├── build.sh                        # 单平台构建（--load 到本地）
│   ├── build-all.sh                    # 多平台构建（--push 到仓库）
│   ├── build-lib.sh                    # 两者共享的构建逻辑
│   ├── Dockerfile                      # 多阶段构建（Maven 打包 + Gradle 构建 Marquez + Node 构建 Marquez Web）
│   ├── conf/                           # supervisord.conf、marquez-web nginx.conf
│   ├── scripts/                        # init-dataforge.sh / run-dataforge.sh / run-ds-standalone.sh
│   ├── dist/                           # 预下载发行包（spark / dolphinscheduler / marquez / openlineage）
│   └── hot-update-dataforge-server.sh  # 热替换 server jar（不改镜像）
└── startup/          # 启停
    ├── compose/                        # docker-compose.yml / docker-compose.persist.yml
    ├── macos/                          # start / start-persist / stop / clear-data / e2e-test / get_password
    └── windows/                        # 与 macos 功能一一对应的 .bat 版本
```

构建脚本可用的环境变量：`FULL`（全量重建基础镜像）、`PLATFORMS`、`REGISTRY`（多平台构建必须指定）、`SKIP_TESTS`、`IMAGE_NAME` / `IMAGE_TAG` / `IMAGE_VER`。

```bash
# 单平台：增量更新 server jar（日常迭代最快）
./build/build.sh
FULL=1 ./build/build.sh          # 全量重建基础镜像（首次或基础层变更时）

# 多平台：需先注册 binfmt，且必须指定 REGISTRY（多平台产物无法 --load）
docker run --privileged --rm tonistiigi/binfmt --install all
REGISTRY=<仓库前缀> ./build/build-all.sh
```

```bash
./startup/macos/start.sh          # 启动（数据写入容器内）
./startup/macos/start-persist.sh  # 启动（挂载命名卷 df-pgdata / df-data）
./startup/macos/stop.sh           # 停止，保留数据
./startup/macos/clear-data.sh     # 停止并删除数据卷（不可恢复）
./startup/macos/e2e-test.sh       # 端到端自检
```

容器端口映射与访问地址：

| 端口 | 服务 | 访问地址 |
| --- | --- | --- |
| 8082 | dataforge-server | <http://localhost:8082/api>（文档页 `/api/doc.html`） |
| 5432 | PostgreSQL 14 | — |
| 12345 | DolphinScheduler | <http://localhost:12345/dolphinscheduler/ui> |
| 5000 / 5001 | Marquez API | <http://localhost:5000> |
| 3000 | Marquez Web | <http://localhost:3000> |

> 容器内通过 `--server.port=8082` 与 PostgreSQL 连接参数覆盖 `application.yml`，并切换为 `mapper/postgresql/*.xml`。因此**直接运行是 8083、容器内是 8082**。

## 编码约定

- **不使用 Lombok**：所有实体手动编写 getter/setter（`pom.xml` 中虽声明了 lombok 依赖，但代码零引用）。
- **统一返回值**：`Result<T>` 封装；分页使用 `PageVO`。
- **逻辑删除**：全局 `deleted` 字段；`create_time` / `update_time` 由 MyBatis-Plus 自动填充。
- **全局异常**：`@RestControllerAdvice` 统一处理业务异常、参数校验、参数绑定与未知异常。
- **参数校验**：Jakarta Validation（`@Valid`）。
- **跨域**：已在安全配置中放行。

## 注意事项

1. **上线前必须更换**：`jwt.secret`、`aes.key`、数据库口令，以及默认管理员口令 `admin/123456`。
2. **密码策略较弱**：`MD5 + 固定盐`，无逐用户随机盐、无迭代次数。如需更高强度应迁移至 BCrypt / Argon2（涉及存量数据迁移）。
3. **端口以实际运行方式为准**：直接运行 `8083`，All-in-One 容器 `8082`。桌面端登录页填写的是**服务根地址、不含 `/api`**（客户端自行拼接 `/api/...`）。
4. **Jackson 命名策略有陷阱**：`application.yml` 中的 `spring.jackson.property-naming-strategy: SNAKE_CASE` 实际未生效——`common/config/WebMvcConfig` 声明了 `@Primary` 的 `ObjectMapper`，接口字段按默认 camelCase 序列化（前端类型定义据此编写）。**不要依赖该配置项**。
5. **中文不转义**：`WebMvcConfig` 关闭了 `ESCAPE_NON_ASCII`，响应中的中文以原字符输出。

## 许可

Proprietary（与 DataForge 平台一致）。
