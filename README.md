# dataforge-server

DataForge 平台的业务后端服务，为 **dataforge 桌面端**提供 REST 接口：统一身份与多级权限、数据源与存储配置、分布式文件浏览、库表与字段元数据、指标、知识库、数据质量与操作审计。

> 本 README 描述的是**当前代码实际状态**。历史版本曾声称"仅实现系统管理 + 运维管理两个模块、6 张表"，已不适用。

## 技术栈

| 分类      | 选型                                                                                     |
| ------- | -------------------------------------------------------------------------------------- |
| 框架      | SpringBoot 3.2.1 + Java 17（jar 打包）                                                     |
| 安全      | Spring Security 6 + JWT（jjwt 0.12.3，默认有效期 24h，`Authorization: Bearer <token>`）         |
| Web     | spring-boot-starter-web / validation / aop / websocket                                 |
| ORM     | MyBatis-Plus 3.5.5（`mybatis-plus-spring-boot3-starter`，`mapper/**/*.xml`）              |
| 数据库     | MySQL 8（默认）、PostgreSQL；`sql/init` 另提供 MariaDB / H2 方言脚本                                |
| 接口文档    | Knife4j 4.4.0（`knife4j-openapi3-jakarta-spring-boot-starter`，OpenAPI 3）                |
| 工具库     | Hutool 5.8.24、Guava 33.0.0-jre、HttpClient 4.5.14                                       |
| 计算/存储对接 | Hadoop 3.4.3、HBase 2.5.9、JuiceFS Hadoop 1.2.3、Calcite 1.37.0、JSqlParser 4.9、Apache ORC |

## 业务模块

共 6 个业务模块、20 个控制器，位于 `src/main/java/com/bigdata/modules/`：

| 模块            | 职责                              | 控制器（类级基路径）                                                                                                                                              |
| ------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `system`      | 认证、用户、角色、权限、菜单角色绑定、项目权限、租户、审计日志 | `/system/auth`、`/system/user`、`/system/role`、`/system/permission`、`/system/menu-role`、`/system/project-permission`、`/system/tenant`、`/system/audit-log` |
| `devops`      | 数据源、存储配置、执行引擎、分布式文件浏览           | `/devops/datasource`、`/devops/storage`、`/devops/execution`、`/file/explorer`                                                                             |
| `development` | 开发工程、代码执行、Jar 依赖                | `/development/project`、`/development/execution`、`/devops/dependency`                                                                                    |
| `metadata`    | 库表与字段元数据、元数据代理（供 AI 使用）、指标      | `/metadata`、`/metadata/agent`、`/metadata/indicators`                                                                                                    |
| `knowledge`   | 知识文档                            | `/knowledge/document`                                                                                                                                   |
| `quality`     | 数据质量：规则、模板、结果留痕                  | `/quality/rule`、`/quality/template`、`/quality/overview`、`/quality/run/report`                                                                             |

通用层位于 `src/main/java/com/bigdata/common/`：`config`（MyBatis-Plus 填充、WebMvc、Jackson）、`security`（JWT 过滤器、安全配置、数据访问鉴权）、`exception`（业务异常 + 全局异常处理）、`utils`（令牌、密码）、`vo`（`Result` / `PageVO`）、`aspect`（审计日志切面）、`sql`（Calcite DDL 解析）。

完整接口清单请访问 Knife4j 文档页（见「快速开始」）。

## 数据模型

`src/main/resources/sql/init/` 下的初始化脚本共 **27 张表**，按用途分为五组：

| 分组        | 表                                                                                                                                                                                                                                                                 |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 身份与权限（12） | `sys_user`、`sys_role`、`sys_tenant`、`sys_tenant_user`、`sys_tenant_role`、`sys_role_project_permission`、`sys_role_menu_permission`、`sys_menu_permission`、`sys_role_table_permission`、`sys_table_permission`、`sys_table_permission_column`、`sys_table_permission_row` |
| 文件权限（2）   | `sys_file_permission`、`sys_role_file_permission`                                                                                                                                                                                                                  |
| 数据源与存储（2） | `datasource_config`、`storage_config`                                                                                                                                                                                                                              |
| 开发与执行（5）  | `dev_project`、`dev_program_params`、`dev_script_execution`、`ddl_execution_log`、`dev_jar_dependency`                                                                                                                                                                |
| 元数据与治理（6） | `table_metadata`、`column_metadata`、`indicators`、`indicators_fields`、`knowledge_document`、`operation_audit_log`                                                                                                                        |

权限模型支持到**行级与列级**（`sys_table_permission_row` / `sys_table_permission_column`）；文件权限按路径 + 存储配置维度控制。

## 数据源类型

统一枚举 `modules/devops/enums/DatasourceType`，共 13 种，通过 `exec` / `store` 标记区分计算能力与存储能力，并声明各自支持的代码类型：

| code         | 名称             | 计算 | 存储 | 支持语言                        |
| ------------ | -------------- | -- | -- | --------------------------- |
| `spark`      | Apache Spark   | ✓  |    | Java / Scala / Python / SQL |
| `flink`      | Apache Flink   | ✓  |    | Java / Scala / Python / SQL |
| `kyuubi`     | Apache Kyuubi  | ✓  |    | SQL                         |
| `doris`      | Apache Doris   | ✓  | ✓  | SQL                         |
| `hbase`      | Apache HBase   | ✓  | ✓  | SQL                         |
| `mysql`      | MySQL          | ✓  | ✓  | SQL                         |
| `postgresql` | PostgreSQL     | ✓  | ✓  | SQL                         |
| `clickhouse` | ClickHouse     | ✓  | ✓  | SQL                         |
| `hive`       | Hive           |    | ✓  | SQL                         |
| `starrocks`  | StarRocks      | ✓  | ✓  | SQL                         |
| `es`         | Elasticsearch  |    | ✓  | —                           |
| `hudi`       | Apache Hudi    |    | ✓  | SQL                         |
| `iceberg`    | Apache Iceberg |    | ✓  | SQL                         |

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

| 环节             | 行为                                                                                                                                                                          |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 校验入口           | `AbstractBaseExecutionStrategy.checkPermission`（JDBC / Kyuubi / Spark / Flink / HBase 全部继承同一基类，共用同一套校验）                                                                     |
| 语句拆分           | 按分号拆成独立语句**逐条**校验；字符串字面量与注释里的分号不参与拆分（`common/sql/SqlScriptSplitter`）                                                                                                        |
| 表引用覆盖          | FROM 与各 JOIN 右侧、FROM 子句里的派生表、WHERE / HAVING / ON / 投影列中的子查询、UNION 各分支与 CTE（`common/sql/SqlTableCollector`）                                                                  |
| DDL            | 仅超级管理员 / `admin` / `ops` 角色可执行；Calcite 无法识别但以 DDL 关键字（CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT/GRANT/REVOKE/ANALYZE）开头的语句**默认拒绝**                                          |
| **fail-close** | 以 DML 关键字开头但无法解析的语句**默认拒绝**并抛 `SecurityException`，不再「打一条 debug 日志后放行」；权限判定过程异常同样按默认拒绝                                                                                       |
| 行 / 列权限合并      | **取交集收紧**：同一张表命中多条权限时，某个权限未配置约束只是「它自己不额外限制」，不会放宽其他权限已配置的行过滤条件与列白名单；脱敏规则按列合并并取更严格的规则                                                                                         |
| 执行路径           | **全部 SQL 路径统一在下发前改写**（`SqlPermissionRewriter`）：把行过滤条件 AND 进 WHERE、把投影列裁剪为列白名单。JDBC / Kyuubi 另保留「结果集后过滤 + 脱敏 + 列裁剪」作为第二道防线；行过滤在改写已生效时不再二次应用（多表场景下后过滤只能取第一张表的条件，重复过滤会把正确结果裁错） |
| 聚合查询           | 行级权限不再是「结果集后过滤」，因此 `count / sum` 等聚合口径也被正确限定，不再泄露被挡住的行的数值                                                                                                                   |
| 改写边界           | 列裁剪只允许单表顶层查询（集合运算分支 / 子查询里裁剪会改变外层列集合 → 拒绝）；多表注入行过滤要求该层 JOIN 全为内连接（外连接下注入会改变连接语义 → 拒绝）；投影表达式引用了白名单外的列 → 拒绝                                                                   |
| 改写内容去向         | 改写结果放在 `CodeExecutionContext.effectiveCodeContent`，只有真正提交给引擎的内容被替换；日志、审计与回显仍展示用户原始代码                                                                                        |
| 不在本层范围         | Scala / Python / Jar 等以任意代码访问数据源的作业，不做 SQL 语句级校验（非 SQL 代码类型直接跳过）                                                                                                            |

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

| 配置                                                                 | 说明                                      |
| ------------------------------------------------------------------ | --------------------------------------- |
| `server.port`                                                      | 默认 `8083`（容器内由启动参数覆盖为 `8082`）           |
| `server.servlet.context-path`                                      | `/api`，所有接口路径的前缀                        |
| `spring.datasource.*`                                              | 数据库连接，HikariCP 连接池（min 5 / max 20）      |
| `spring.sql.init.mode`                                             | 保持 `never`——置为 `always` 会在每次启动时重建表并丢失数据 |
| `jwt.secret` / `jwt.expiration`                                    | 令牌密钥与有效期（默认 86400000 ms）                |
| `jwt.header` / `jwt.prefix`                                        | 默认 `Authorization` / `Bearer`           |
| `aes.key`                                                          | 数据源等敏感配置的加解密密钥                          |
| `engine.spark.home` / `engine.flink.home` / `engine.jar.base-path` | 提交执行时使用的引擎安装路径与 jar 存放根目录               |

## 容器化部署（All-in-One）

`build/` 与 `startup/` 提供单容器一体镜像：镜像内以 supervisord 托管 **PostgreSQL 14 + dataforge-server + DolphinScheduler + Marquez（API 与 Web）**，另预置 Spark 3.5.9 与 OpenLineage Spark 插件。

```
dataforge-server/
├── build/            # 构建
│   ├── build.sh                        # 单平台构建（--load 到本地）
│   ├── build-all.sh                    # 多平台构建（--push 到仓库）
│   ├── build-lib.sh                    # 两者共享的构建逻辑
│   ├── Dockerfile                      # 多阶段构建（Gradle 构建 Marquez API + Node 构建 Marquez Web）
│   ├── conf/                           # spark-defaults.conf、supervisord.conf、marquez-web nginx.conf
│   ├── scripts/                        # init-dataforge.sh / run-dataforge.sh / run-ds-standalone.sh
│   │                                   # + set-write-compliance.sh（写入合规监听器开关）
│   ├── dist/                           # 构建产物与预下载发行包
│   │                                   # + dataforge-server.jar（宿主机 mvn 产物，Dockerfile 直接 COPY）
│   │                                   # + spark-listeners/（宿主机构建的写入合规监听器 jar）
│   │                                   # + spark / dolphinscheduler / marquez / openlineage 发行包
│   └── hot-update-dataforge-server.sh  # 热替换 server jar（不改镜像）
└── startup/          # 启停
    ├── compose/                        # docker-compose.yml / docker-compose.persist.yml
    ├── macos/                          # start / start-persist / stop / clear-data / e2e-test / get_password
    └── windows/                        # 与 macos 功能一一对应的 .bat 版本
```

### dataforge-server 在宿主机编译（不在镜像内）

`dataforge-server` 的 jar **不由 Docker 构建**：`build/build.sh` 先用本机 `mvn package` 打包，
产物固定写到 `build/dist/dataforge-server.jar`，`Dockerfile` 只做 `COPY`。因此：

- 两种构建模式（增量 / `FULL=1`）都要求宿主机有可用的 **mvn 与 JDK 17+**（`JAVA_HOME` 未设置时会尝试用
  macOS 的 `/usr/libexec/java_home -v 17` 自动定位）；镜像内不再有 Maven 阶段，也没有"容器内编译"的退路。
- 免去了容器内预热 `~/.m2/repository`（原 `M2_SEED_DIR` 已废弃），构建上下文不再需要 `pom.xml` / `src/main/java`，
  依赖直接走宿主仓库、**可离线构建**，编译错误也在宿主终端直接可见。
- `HADOOP_VERSION` / `HBASE_VERSION` / `JUICEFS_VERSION` / `PARQUET_VERSION` / `ORC_VERSION`
  现在是作为 `-D` 参数透传给宿主机的 mvn。

构建脚本可用的环境变量：`FULL`（全量重建基础镜像）、`PLATFORMS`、`REGISTRY`（多平台构建必须指定）、`SKIP_TESTS`、`JAVA_HOME`、`IMAGE_NAME` / `IMAGE_TAG` / `IMAGE_VER`；另有写入合规监听器相关的 `SPARK_LISTENER_DIR`（源码目录）、`SPARK_LISTENER_MODULES`（默认 `core,spark-3.5`）、`SKIP_SPARK_LISTENER=1`（跳过构建，沿用 `build/dist/spark-listeners/` 已有 jar）。

```bash
# 单平台：宿主机编译 server jar + 增量覆盖（日常迭代最快）
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

| 端口          | 服务               | 访问地址                                             |
| ----------- | ---------------- | ------------------------------------------------ |
| 8082        | dataforge-server | <http://localhost:8082/api>（文档页 `/api/doc.html`） |
| 5432        | PostgreSQL 14    | —                                                |
| 12345       | DolphinScheduler | <http://localhost:12345/dolphinscheduler/ui>     |
| 5000 / 5001 | Marquez API      | <http://localhost:5000>                          |
| 3000        | Marquez Web      | <http://localhost:3000>                          |

> 容器内通过 `--server.port=8082` 与 PostgreSQL 连接参数覆盖 `application.yml`，并切换为 `mapper/postgresql/*.xml`。因此**直接运行是 8083、容器内是 8082**。

## Spark 写入合规监听器（容器内全局装载）

镜像把 `spark-listener` 工程（`com.writecompliance:spark-write-compliance`）构建成 jar 放进 `SPARK_HOME/jars`，  
使容器内**所有** `spark-submit` / `spark-sql` 任务（DolphinScheduler SPARK 任务、代码执行提交的任务）都自动带上写入合规校验，  
**无需修改任何作业代码**。三个装载点都在 `build/conf/spark-defaults.conf` 里：

| 配置                                             | 作用                                                                                                       |
| ---------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `/opt/spark/jars/spark-write-compliance-*.jar` | `core`（纯 Java 规则引擎）+ `spark-3.5`（适配 Spark 3.5.9 / Scala 2.12）                                            |
| `spark.sql.extensions`                         | 分析期注入 CheckRule —— 命中禁止规则时**在写入真正发生前**抛异常阻断任务（这是与 `QueryExecutionListener` 事后回调的本质区别）                    |
| `spark.sql.queryExecutionListeners`            | 执行结束后解析执行计划并上报（审计/告警），不承担阻断职责                                                                            |
| `spark.write.compliance.*`                     | `enabled` / `fail.on.violation` / `allow.on.unknown` / `log.plans` / `rule.<idx>` / `permission.check.*` |


### 运行期开关

容器内提供 `set-write-compliance.sh`，改写 `spark-defaults.conf` 中由它管理的配置块，对**此后新提交**的任务生效：

```bash
docker exec -it dataforge-allinone /opt/dataforge/scripts/set-write-compliance.sh status
docker exec -it dataforge-allinone /opt/dataforge/scripts/set-write-compliance.sh enforce test   # 真实拦截（镜像默认）
docker exec -it dataforge-allinone /opt/dataforge/scripts/set-write-compliance.sh relaxed test   # 真实拦截，但解析不出目标时放行
docker exec -it dataforge-allinone /opt/dataforge/scripts/set-write-compliance.sh strict          # 真实拦截，按 Spark 运行用户判定
docker exec -it dataforge-allinone /opt/dataforge/scripts/set-write-compliance.sh on              # 仅演示：admin 校验，不拦
docker exec -it dataforge-allinone /opt/dataforge/scripts/set-write-compliance.sh off             # 停用
```

| 档位                    | 规则校验 | 权限校验身份                    | `allow.on.unknown` | 效果                                                                    |
| --------------------- | ---- | ------------------------- | ------------------ | --------------------------------------------------------------------- |
| `enforce [用户]`（镜像默认）  | 开    | `enforce test`，省略则回落 sparkUser | `false`            | **真实拦截**：命中禁止规则、越权访问、解析不出写入目标、权限服务不可达，都会让任务在写入发生前失败                |
| `relaxed [用户]`        | 开    | 同上                        | `true`             | 真实拦截，但「解析不出写入目标」只告警放行 —— 观察期用，先看告警再收紧                              |
| `strict`              | 开    | 回落 Spark 运行用户             | `false`            | 同 `enforce`，只是不显式指定身份（要求 sparkUser 是已登记平台用户）                       |
| `on`                  | 开    | 超管 `admin`                | `true`             | 校验链路真实打通但判定必然通过，**不要用于生产**                                            |
| `off`                 | 关    | —                         | —                  | jar 与扩展仍装载，但不再校验                                                       |

> **镜像默认档为什么是 `enforce test`**：容器内 Spark 作业的 OS 用户是 `test_tenant`，而它是 `sys_tenant.tenant_code`
> （**租户编码，不是平台用户名**），`sys_user` 里只有 `admin` / `test`，所以 `strict`（回落 sparkUser）会因
> 「无法确定校验用户」返回 400 而拦掉**所有**任务。显式指定种子数据里的平台用户 `test`
> （有 `test_db.*` 权限、无 `ods_olist_test` 权限）即可让拦截真实生效。
> 要做到「按提交人逐一判定」，需为每个运行用户登记同名 `sys_user`，再切 `strict`。
>
> 临时放行单个任务（不改全局档位）：`--conf spark.write.compliance.enabled=false`。
> 档位切换只影响此后新提交的进程，已运行的 Spark 进程不受影响。

### 若干必须知道的坑（改配置前先看）

1. **配置键必须带 `spark.` 前缀**。Spark 3.5 起 `SparkSubmitArguments` 会把 `spark-defaults.conf` 与 `--conf` 中  
   所有非 `spark.` 前缀的配置项**直接丢弃**（只打一条 `Ignoring non-Spark config property` 告警），  
   所以 `write.compliance.enabled` 这种裸前缀根本到不了 SparkConf，必须写 `spark.write.compliance.enabled`。  
   监听器侧已做前缀归一化，两种写法都能识别（裸前缀在进程内 `SparkConf` 上仍可用）。
2. **jar 必须放 `SPARK_HOME/jars`，不能只靠 `--jars`**。`spark.sql.extensions` / `spark.sql.queryExecutionListeners`  
   是 Spark 自己按类名反射装载的，类对驱动端类加载器不可见时只会打一条  
   `Cannot use ... to configure session extensions` 告警然后静默跳过，任务照跑但完全没有校验。
3. **规则禁用列表默认是空的**，即默认只做权限校验。需要按正则拦截（如禁止写 `/tmp/`、`raw_` 前缀表）时，  
   在 `build/conf/spark-defaults.conf` 里取消 `spark.write.compliance.rule.<idx>` 的注释。
4. **作业若自行覆盖 `spark.sql.extensions`**（例如为了挂 Hudi / Iceberg 扩展），会顶掉这里的分析期阻断扩展；  
   此时只剩 `queryExecutionListeners` 的事后上报。需要同时保留时把两个类名用逗号拼在一起下发。
5. **改监听器源码后**：`./build/build.sh`（增量，包含监听器 jar + `spark-defaults.conf` + `build/scripts/`）即可刷新，无需 `FULL=1`。
6. **已知覆盖缺口**：写入目标是 JDBC 子查询形态（`dbtable=(select ...) t`）、或使用提取器不认识的  
   V2 自定义 Source 时，解析不出目标。`enforce`（`allow.on.unknown=false`）档下这会直接阻断任务，  
   可先用 `relaxed` 观察「存在 [N] 个无法判定的写入目标」告警，确认无误伤再收紧。
   2026-10-08 已修掉两个让「目标看不见」的遍历缺陷（Spark 的 `children` 是 Scala `Seq`、**不是** `java.lang.Iterable`；
   命令节点的子计划挂在 `innerChildren` 而非 `children`），并补齐 JDBC 读写目标的提取，
   详见 spark-listener 仓库 README 的「已知限制与覆盖范围」。

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
