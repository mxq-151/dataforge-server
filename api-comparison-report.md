# 前后端接口对比分析报告

> 生成时间：2026-05-15
> 前端项目：/Users/mxq/Desktop/data-forge
> 后端项目：/Users/mxq/Desktop/bigdata-platform

---

## 📊 统计概览

| 维度 | 前端 | 后端 |
|------|------|------|
| **API 文件/Controller 数** | 14 个 API 文件 | 17 个 Controller |
| **总接口数** | ~129 个（含重复调用） | **127 个** |
| **唯一路径数** | ~110 个 | **127 个** |

---

## 🔴 后端多余接口清单（前端未使用）

### 一、系统管理模块 (System) — 8 个可清理

#### AuthController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 1 | POST | /system/auth/logout | logout | L97 | 登出 — 前端未调用此接口 |
| 2 | POST | /system/auth/refresh | refreshToken | L182 | 刷新 Token — 前端未实现刷新机制 |
| 3 | PUT | /system/auth/password | changePassword | L227 | 认证模块密码修改 — 前端用的是 UserController 的版本 |

#### UserController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 4 | GET | /system/user/current | getCurrentUserInfo | L110 | 获取当前用户信息 — 前端用 /auth/info 替代 |

#### RoleController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 5 | GET | /system/role/{id}/users | getUsersByRoleId | L53 | 查询角色下的用户列表 — 前端未使用 |

---

### 二、运维管理模块 (DevOps) — 14 个可清理

#### FileExplorerController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 6 | GET | /file/explorer/test-connection | testConnection | L177 | 测试连接 — 前端未调用 |

#### StorageConfigController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 7 | GET | /devops/storage/{id} | getById | L44 | 按 ID 查存储配置详情 — 前端未使用 |

#### DatasourceConfigController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 8 | GET | /devops/datasource/{id} | getById | L45 | 按 ID 查数据源详情 — 前端未使用 |

#### ⚠️ ClusterConfigController — 整个 Controller 未被前端使用！
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 9 | GET | /devops/cluster/list | getList | L24 | 集群列表 |
| 10 | GET | /devops/cluster/all | getAllClusters | L36 | 全部集群 |
| 11 | GET | /devops/cluster/{id} | getById | L43 | 集群详情 |
| 12 | POST | /devops/cluster | save | L49 | 创建集群 |
| 13 | PUT | /devops/cluster | update | L57 | 更新集群 |
| 14 | DELETE | /devops/cluster/{id} | delete | L65 | 删除集群 |
| 15 | GET | /devops/cluster/types | getClusterTypes | L71 | 集群类型 |

**建议：整个 ClusterConfigController 可考虑删除或标记为 @Deprecated**

#### DevJarDependencyController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 16 | GET | /devops/dependency/all | getAllJars | L38 | 全部依赖包 — 前端未调用 |
| 17 | GET | /devops/dependency/{id} | getById | L45 | 依赖包详情 — 前端未调用 |

---

### 三、开发管理模块 (Development) — 6 个可清理

#### DevProjectController
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 18 | GET | /development/project/{id} | getById | L50 | 项目详情 — 前端未调用 |
| 19 | POST | /development/project | save | L56 | 创建项目 — 前端未调用（项目由 DS 管理） |
| 20 | PUT | /development/project | update | L68 | 更新项目 — 前端未调用 |
| 21 | DELETE | /development/project/{id} | delete | L76 | 删除项目 — 前端未调用 |
| 22 | GET | /development/project/{projectId}/params | listProgramParams | L86 | 参数列表（无路径）— 前端未调用 |
| 23 | DELETE | /development/project/{projectId}/params/{filePath:.+} | deleteProgramParams | L136 | 删除参数文件 — 前端未调用 |

---

### 四、元数据管理模块 (Metadata) — 12 个可清理

#### ⚠️ MetadataController — 大部分未使用！（仅 2/14 接口在使用）
| # | HTTP方法 | 路径 | 方法名 | 行号 | 说明 |
|---|---------|------|--------|------|------|
| 24 | GET | /metadata/tables/detail | getTableDetailList | L56 | 表详情列表 — 前端未调用 |
| 25 | GET | /metadata/table/{id} | getTableById | L68 | 表信息按 ID — 前端未调用 |
| 26 | GET | /metadata/table/{id}/detail | getTableDetailById | L74 | 表详情按 ID — 前端未调用 |
| 27 | GET | /metadata/databases | getDatabases | L87 | 数据库列表 — 前端未调用 |
| 28 | PUT | /metadata/table/{id}/comment | updateTableComment | L93 | 修改表注释 — 前端未调用 |
| 29 | PUT | /metadata/column/{id}/comment | updateColumnComment | L99 | 修改列注释 — 前端未调用 |
| 30 | POST | /metadata/sync/{datasourceId} | syncMetadata | L105 | 同步元数据 — 前端未调用 |
| 31 | GET | /metadata/sync-log/{datasourceId} | getLatestSyncLog | L111 | 同步日志 — 前端未调用 |
| 32 | POST | /metadata/ddl/execute | executeDdl | L117 | 执行 DDL — 前端未调用 |
| 33 | POST | /metadata/sql/query | executeSqlQuery | L135 | SQL 查询 — 前端未调用 |
| 34 | GET | /metadata/ddl/logs | getDdlLogs | L142 | DDL 日志 — 前端未调用 |
| 35 | DELETE | /metadata/table/{id} | deleteTable | L155 | 删除表元数据 — 前端未调用 |

**前端仅使用了 2 个元数据接口：GET /metadata/tables 和 GET /metadata/table/{tableId}/columns**

---

## 📋 清理优先级汇总

| 优先级 | Controller | 多余接口数 | 建议 |
|--------|-----------|-----------|------|
| 🔴 高 | ClusterConfigController | 7 个 | 整个 Controller 无前端对接，建议删除或标记废弃 |
| 🔴 高 | MetadataController | 12 个 | 仅 2/14 接口在使用，大量冗余 |
| 🟡 中 | DevProjectController | 6 个 | 项目 CRUD 未对接，保留参数管理相关 |
| 🟡 中 | DevJarDependencyController | 2 个 | 详情和全量查询未使用 |
| 🟢 低 | AuthController | 3 个 | logout/refresh/password 可能用于其他客户端 |
| 🟢 低 | UserController | 1 个 | current 接口与 auth/info 功能重叠 |
| 🟢 低 | RoleController | 1 个 | 角色用户关联查询 |
| 🟢 低 | FileExplorer/Datasource/Storage | 3 个 | 单个 getById 或 test-connection |

**总计可清理接口：35 个**

---

## ✅ 完全匹配的接口（无需改动）

以下后端 Controller 的所有接口均已被前端正确对接：

- ✅ MenuRoleBindController — 4/4 接口已对接
- ✅ PermissionController — 10/10 接口已对接
- ✅ TenantController — 10/10 接口已对接
- ✅ ProjectPermissionController — 8/8 接口已对接
- ✅ AuditLogController — 5/5 接口已对接
- ✅ RoleController — 7/8 接口已对接
- ✅ KnowledgeDocumentController — 9/9 接口已对接
- ✅ CodeExecutionController — 1/1 接口已对接

---

## 🧹 清理建议

### 立即可安全删除的接口（35个）

1. ClusterConfigController 整个类（7个接口）
   - 前端完全没有集群管理功能，整个 Controller 可删除

2. MetadataController 中的 12 个未使用接口
   - 保留：getTableList(L42)、getTableColumns(L80)
   - 可删除：其余 12 个方法

3. DevProjectController 中的 6 个 CRUD 接口
   - 保留：getProgramParams(L93)、saveProgramParams(L104)、updateProgramParams(L119)
   - 可删除：getList(L37)、getById(L50)、save(L56)、update(L68)、delete(L76)、listProgramParams(L86)、deleteProgramParams(L136)

### 需要评估后删除的接口

- AuthController 的 logout/refresh/password — 可能用于移动端或其他客户端
- 各种 getById 单条查询 — 可能未来会用到
- RoleController 的 getUsersByRoleId — 可能用于角色详情展示

### 清理操作步骤

1. 在每个待删除的方法上添加 @Deprecated 注解
2. 添加 @Deprecated 注解说明："前端未使用，计划于下版本移除"
3. 观察一个迭代周期确认无其他客户端调用
4. 确认后删除方法和相关 Service/Mapper 层代码
