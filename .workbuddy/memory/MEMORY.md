# dataforge-server — 项目长期约定

## 构建 / 运行

- `mvn` 不在 PATH：用 `/opt/homebrew/bin/mvn`，且 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`。
  注：`build/build-lib.sh` 的 `ensure_maven()` 已能自动兜底这两件事（PATH 补 `/opt/homebrew/bin`、
  `JAVA_HOME` 缺省时走 `/usr/libexec/java_home -v 17`），直接跑 `./build/build.sh` 即可。
- **dataforge-server 的 jar 在宿主机编译，不在镜像内**：`build/build-lib.sh` 的 `build_server_jar()`
  跑宿主 `mvn package`，产物固定落 `build/dist/dataforge-server.jar`，`Dockerfile` 只做 COPY。
  Dockerfile 里原本的 `app-builder`（maven 阶段）与 `M2_SEED_DIR`/`m2repo` 构建上下文已删除；
  增量与 FULL 两种模式都要求宿主有 mvn + JDK 17+（没有"容器内编译"退路）。
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
- 建表脚本：`src/main/resources/sql/init/`（4 方言全量）+ `sql/testdata/`（种子：菜单/角色/授权/演示库）。
  **2026-10-09 起已无迁移脚本**：`sql/migrate/` 目录与 `df_gen_mysql_migrate.py` 生成器整体删除，
  项目在开发阶段 —— 新库直接建、开发库直接重建，**init 是 schema 的唯一来源**。
  顺带解决了历史包袱：migrate 里残留的 `deleted` 列（早前"全表移除 deleted"时没跟着改）已随之消失。

## 数据访问权限

- 语义是 **fail-close**：`DataAccessPermissionService` 的 `getMergedRowFilterSql` 返回 null 才表示不过滤；
  `getAllowedColumns` 返回 null 表示不限制、空集合表示无可见列。
- 库存批量方法：`filterAccessibleTables(...)` / `hasDatabaseAccess(...)`，新模块不要自己造过滤逻辑。
- **租户不是数据隔离边界**（租户与用户 1:1，资源表无租户字段），只有库/表/列权限是。
- 数据预览类功能必须走 `CodeExecutionService`，以复用行/列权限改写与脱敏，不要另起查询通道。

## 模块边界（避免重复建设）

- 血缘复用桌面端 Marquez 客户端；产出任务实时代理 DolphinScheduler（未接入返回空列表，不造假数据）；
  元数据标注缺口复用 `/metadata/annotation-gaps`；分层/业务域由表名实时解析（`TableNameConventionParser`），不落库。

## 资产热度/收藏已整体下线（2026-10-10）

资产模块原先的「访问频率」是**前端 UI 埋点**（目录树点选/搜索打开/收藏/预览四类），
既采不到真实取数行为（数据探索、调度任务、下游消费全都没有），字段热度链路还因前端从不发
`column` 埋点而恒为空。已按需求**整体删除**，不要在新代码里复活：

- 类：`AssetUsageService` / `AssetUsageRollupTask` / `AssetBookmarkService` 及
  `asset_access_log` / `asset_usage_daily` / `asset_bookmark` 三张表与对应 Mapper/DTO/VO。
- 接口：`POST /asset/access`、`GET /asset/table/{id}/usage`、`GET|POST|DELETE /asset/bookmark`、
  `POST /asset/task/usage-rollup/run`。治理项 `NO_ACCESS_30D` 也一并移除（依赖 last_access_time）。
- 列：`table_metadata`(browse_cnt_30d/read_cnt_30d/bookmark_cnt/last_access_time)、
  `column_metadata.hot_score`、`asset_catalog_daily` 同名列。init 表数 **41 → 38**。
- `AssetInventoryService.topAssets(limit, env)` 已**去掉 sortBy**，固定按 sizeBytes 降序。
- **后续方向**：要的是「各处理任务对表的读写信息」，需另起一轮设计（调度任务产出/消费关系、
  查询引擎取数记录或库侧统计），届时新增独立的表与接口，不要复用已删的三张表名。

## 容器内 DolphinScheduler（DS 3.4.1 standalone，2026-10-09 摸清）

- 单进程 `org.apache.dolphinscheduler.StandaloneServer`，supervisord 程序名 **`dolphinscheduler`**
  （改配置/清数据前用 `supervisorctl stop|start dolphinscheduler` 控制，autorestart=true，直接 kill 会被拉起）。
- API：`http://127.0.0.1:12345/dolphinscheduler/`，认证用请求头 `token: <t_ds_access_token.token>`
  （库里已有长期 token，`psql -tA -c "select token from t_ds_access_token order by id limit 1"`）。
  容器内 curl 要加 `--noproxy '*'`。
- **DS 的库是容器内 PostgreSQL，不是 h2**：`postgresql://127.0.0.1:5432/dolphinscheduler`，`postgres/123456`。
  启动脚本用 `SPRING_DATASOURCE_*` 环境变量覆盖了 yaml 里的 h2 默认值，别被 `application.yaml` 里
  `spring.profiles.active: h2` 骗到。表名是新命名（`t_ds_workflow_instance` / `t_ds_workflow_definition`）。
- 语义映射：**补数 = `command_type=5` (COMPLEMENT_DATA)**；`7` = REPEAT_RUNNING（重跑），`6` = SCHEDULER。
  实例接口：`GET /projects/{pc}/workflow-instances`、`POST /projects/{pc}/executors/execute`（STOP/PAUSE/…）、
  `DELETE /projects/{pc}/workflow-instances/{id}`（**仅终态可删**，RUNNING 报 50006）。`test_project` code = `183115978154240`。
- **坑**：DS 崩溃被 supervisord 拉起后，master 会自动"恢复"库里 `RUNNING_EXECUTION` 的实例并重跑，
  于是"停不掉也删不掉"。要清实例必须先 `supervisorctl stop dolphinscheduler`，再直接清库，最后启动。
- **坑**：**直接 `kill` DS 的 java 进程一律无效**——supervisord 的无条件重启链条是
  `[program:dolphinscheduler] autorestart=true` → 但被托管的进程其实是包装脚本 `run-ds-standalone.sh`
  而非 java（脚本末尾 `while pgrep -f StandaloneServer; … ; done; exit 1`，java 一没脚本就 exit 1 被重启）。
  停它必须用 `supervisorctl stop dolphinscheduler`。另外该 program 段**缺 `stopasgroup/killasgroup`**
  （postgres 段有），stop 后 java 可能成孤儿继续占 12345，需补 `pkill -f org.apache.dolphinscheduler.StandaloneServer`。
- 容器总内存只有 8G（cgroup `memory.max=8GiB`），master `-Xms4g -Xmx4g` + 多个 Spark local 作业并发时极易把 DS 挤死。
  实测容器 `/sys/fs/cgroup/memory.events` 的 `oom_kill` 已累计 74 次，DS 曾在 1 小时内被重启 17 次；
  且反复退出时**没有 hs_err/hprof 转储**，说明是**被内核 OOM killer SIGKILL**，不是 JVM 堆内 OOM。
  跑大批量补数前留意，根治办法是把 DS 堆降到 1~1.5g。

## 容器内 Spark 与写入合规监听器（2026-10-08 集成）

- 容器内 Spark 是 **3.5.9 (local)**，`SPARK_HOME=/opt/spark`；镜像里放进 `$SPARK_HOME/jars` 的 jar
  才会被 `spark.sql.extensions` / `spark.sql.queryExecutionListeners` 反射装载（只给 `--jars` 不够）。
- 监听器源码独立仓库：`~/Desktop/spark-listener`（`com.writecompliance:spark-write-compliance`），
  容器只构建 `core,spark-3.5` 两个模块，产物落 `build/dist/spark-listeners/`（该目录 gitignore）。
- **Spark 3.5+ 的 `SparkSubmitArguments` 会丢弃非 `spark.` 前缀的配置**，所以监听器配置一律写
  `spark.write.compliance.*`（组件内部原名是 `write.compliance.*`，已做前缀归一化）。
- 运行期开关：容器内 `/opt/dataforge/scripts/set-write-compliance.sh status|on|strict|off`
  （`on`=超管身份校验、不拦任务；`strict`=按 Spark 运行用户、fail-close）。镜像默认 `on`。
- 增量构建（`./build/build.sh`）已覆盖 server jar + 监听器 jar + `spark-defaults.conf` + `build/scripts/` 四份，
  改这几类内容无需 `FULL=1`。
- **本机 `docker build` / `docker buildx` 会卡在 buildx 状态目录**（`~/.docker/buildx/activity` rename
  operation not permitted）。解法：`cp -R ~/.docker/cli-plugins ~/.docker/config.json ~/.docker/contexts /tmp/dkcfg/`
  后 `DOCKER_CONFIG=/tmp/dkcfg ./build/build.sh`（只搬 config 不搬 cli-plugins 会导致 `docker buildx` 不可用）。
- 容器内 `curl localhost` 需加 `--noproxy '*'`；宿主机 zsh 不做无引号变量的词分割，多参数变量要用 `bash -c` 包裹。
- 监听器写入目标覆盖：表 / CTAS / `df.write.<format>(path)` / `INSERT OVERWRITE [LOCAL] DIRECTORY '<path>'`
  （`InsertIntoHiveDirCommand` / `InsertIntoDir`，目标取自 `storage.locationUri`，需解包 `Option`）均已覆盖；
  以逻辑计划为主要判定依据，物理侧 `DataWritingCommandExec` 包装节点不单独提取。
  `WriteTarget.Type.PATH` 现在会被真正产出（历史实现恒返回 TABLE）。

