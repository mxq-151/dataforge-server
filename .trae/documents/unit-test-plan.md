# BigData Platform 单元测试规划文档

## 📋 项目概述

### 技术栈
- **框架**: Spring Boot 3.2.1 + MyBatis-Plus 3.5.5
- **数据库**: MySQL (生产) / H2 (测试)
- **测试框架**: JUnit 5 + Spring Boot Test
- **测试策略**: 真实数据集成测试（不使用Mock）

### 测试原则
✅ **使用真实数据库连接** - 通过 `@SpringBootTest` 启动完整上下文  
✅ **事务回滚** - `@Transactional` + `@Rollback` 保证测试数据不污染生产库  
✅ **场景覆盖** - 覆盖正常流程、边界条件、异常情况、权限控制  
✅ **接口级测试** - 通过REST API端点进行端到端验证  

---

## 🎯 测试覆盖范围总览

| 模块 | Controller数量 | 核心Service | 预计测试类数 | 优先级 |
|------|---------------|------------|-------------|--------|
| System (系统管理) | 9 | 7+ | 12 | P0 |
| Development (开发) | 3 | 6+ | 10 | P0 |
| DevOps (运维) | 3 | 4+ | 8 | P0 |
| Metadata (元数据) | 1 | 2 | 4 | P1 |
| Knowledge (知识库) | 1 | 1 | 3 | P1 |
| Common (公共组件) | - | 3 | 5 | P0 |

**总计**: 约 **42个测试类**, **300+ 个测试用例**

---

## 🔐 模块一：System（系统管理）- 12个测试类 [P0]

### 1.1 AuthController 集成测试
**文件**: `AuthControllerIntegrationTest.java`

#### 测试场景：
**场景1: 用户登录成功**
- ✅ 正确的用户名密码登录返回token
- ✅ 返回的LoginVO包含userId, username, realName
- ✅ 普通用户角色为 ROLE_USER
- ✅ 管理员用户包含 ROLE_ADMIN
- ✅ 包含租户信息（tenantId, tenantName）

**场景2: 用户登录失败**
- ❌ 用户名为空或密码错误抛出异常
- ❌ 不存在的用户名返回错误提示
- ❌ 密码格式校验失败

**场景3: 获取当前用户信息 (/system/auth/info)**
- ✅ 已登录用户能获取完整信息（用户基本信息、角色列表、菜单权限、项目权限）
- ✅ 未登录用户返回401错误
- ✅ 不存在的用户ID返回业务异常
- ✅ 验证menuTags正确解析
- ✅ 验证projectCodes正确关联

**场景4: 多租户场景**
- ✅ 用户绑定单个租户
- ✅ 用户未绑定租户（tenantId=null）
- ✅ 角色与租户关联查询

---

### 1.2 UserController 集成测试
**文件**: `UserControllerIntegrationTest.java`

#### 测试场景：
**场景1: 用户CRUD操作**
- ✅ 创建用户（正常数据）
- ✅ 创建用户（重复用户名检测）
- ✅ 更新用户信息
- ✅ 查询用户列表（分页）
- ✅ 按ID查询用户详情
- ✅ 删除用户（软删除）
- ✅ 删除已删除用户（幂等性）

**场景2: 用户状态管理**
- ✅ 启用/禁用用户账号
- ✅ 重置用户密码
- ✅ 修改密码（旧密码校验）

**场景3: 权限控制**
- ✅ ADMIN才能创建/删除用户
- ❌ 普通用户尝试创建返回403

---

### 1.3 RoleController 集成测试
**文件**: `RoleControllerIntegrationTest.java`

#### 测试场景：
**场景1: 角色CRUD**
- ✅ 创建角色（roleName, roleCode唯一性）
- ✅ 更新角色信息
- ✅ 删除角色（检查是否有关联用户）
- ✅ 查询角色列表
- ✅ 获取角色详情（包含权限列表）

**场景2: 角色权限分配**
- ✅ 分配菜单权限给角色
- ✅ 分配表权限给角色
- ✅ 分配文件权限给角色
- ✅ 分配项目权限给角色
- ✅ 批量分配权限

**场景3: 边界条件**
- ❌ 创建同名角色失败
- ❌ roleCode重复检测
- ❌ 删除有用户的角色失败

---

### 1.4 TenantController 集成测试
**文件**: `TenantControllerIntegrationTest.java`

#### 测试场景：
**场景1: 租户管理**
- ✅ 创建租户（tenantCode唯一性）
- ✅ 更新租户信息
- ✅ 查询租户列表
- ✅ 删除租户（级联清理）
- ✅ 租户状态启用/禁用

**场景2: 租户用户管理**
- ✅ 将用户加入租户
- ✅ 将用户从租户移除
- ✅ 查询租户下的所有用户

**场景3: 租户角色管理**
- ✅ 为租户分配角色
- ✅ 查询租户可用角色

---

### 1.5 PermissionController 集成测试
**文件**: `PermissionControllerIntegrationTest.java`

#### 测试场景：
**场景1: 表权限管理**
- ✅ 创建表权限（datasource/database/table级别）
- ✅ 更新表权限（增删改查导出权限位）
- ✅ 设置行过滤规则（rowFilterSql）
- ✅ 设置数据脱敏规则（dataMaskingRule JSON）
- ✅ 删除表权限
- ✅ 查询权限列表（按数据源/数据库筛选）

**场景2: 文件权限管理**
- ✅ 创建文件权限（精确路径/递归目录/通配符）
- ✅ 设置读写权限类型
- ✅ 更新文件权限
- ✅ 删除文件权限

**场景3: 权限验证**
- ✅ 权限名称唯一性
- ✅ 权限参数合法性校验

---

### 1.6 AuditLogController 集成测试
**文件**: `AuditLogControllerIntegrationTest.java`

#### 测试场景：
**场景1: 审计日志查询**
- ✅ 按时间范围查询
- ✅ 按模块筛选
- ✅ 按操作类型筛选
- ✅ 按用户筛选
- ✅ 分页查询性能

**场景2: 审计统计**
- ✅ 按模块统计操作次数
- ✅ 按用户统计活跃度
- ✅ 按时间段统计趋势

**场景3: 日志完整性**
- ✅ 验证CRUD操作自动记录审计日志
- ✅ 验证日志包含操作人、时间、目标对象

---

### 1.7 ProjectPermissionController 集成测试
**文件**: `ProjectPermissionControllerIntegrationTest.java`

#### 测试场景：
**场景1: 项目权限分配**
- ✅ 为角色分配项目访问权限
- ✅ 设置项目权限级别（读/写/删除）
- ✅ 批量分配项目权限
- ✅ 移除项目权限

**场景2: 权限查询**
- ✅ 查询角色的所有项目权限
- ✅ 查询项目的所有授权角色

---

### 1.8 MenuRoleBindController 集成测试
**文件**: `MenuRoleBindControllerIntegrationTest.java`

#### 测试场景：
**场景1: 菜单角色绑定**
- ✅ 为角色绑定菜单权限
- ✅ 解除菜单角色绑定
- ✅ 批量绑定菜单
- ✅ 查询角色的菜单列表

---

### 1.9 SysUserService 业务逻辑测试
**文件**: `SysUserServiceRealDataTest.java`

#### 测试场景：
**场景1: 用户认证**
- ✅ login() 方法验证（成功/失败/锁定）
- ✅ 密码加密验证（BCrypt）
- ✅ 用户状态检查（禁用用户不能登录）

**场景2: 用户信息管理**
- ✅ getUserById() / getUserByUsername()
- ✅ createUser() 数据完整性
- ✅ updateUser() 字段更新
- ✅ 逻辑删除机制

**场景3: 关联查询**
- ✅ 查询用户角色
- ✅ 查询用户租户
- ✅ 查询用户权限汇总

---

### 1.10 SysRoleService 业务逻辑测试
**文件**: `SysRoleServiceRealDataTest.java`

#### 测试场景：
**场景1: 角色生命周期**
- ✅ createRole() 唯一性约束
- ✅ updateRole() 并发控制
- ✅ deleteRole() 关联检查

**场景2: 权限聚合**
- ✅ getRolePermissions() 合并多来源权限
- ✅ getRoleUsers() 查询角色成员

---

### 1.11 SysTenantService 业务逻辑测试
**文件**: `SysTenantServiceRealDataTest.java`

#### 测试场景：
**场景1: 租户隔离**
- ✅ 不同租户数据隔离验证
- ✅ 租户下用户/角色独立管理

**场景2: 租户状态**
- ✅ 租户禁用时用户无法登录

---

### 1.12 PermissionService 权限服务测试
**文件**: `PermissionServiceRealDataTest.java`

> ⚠️ 注意：已有 `DataAccessPermissionRealDataTest.java`，此处补充更多场景

#### 测试场景：
**场景1: 权限继承链路**
- ✅ 用户 → 角色 → 权限 完整链路
- ✅ 多角色权限合并（取最大权限）
- ✅ 权限优先级冲突解决

**场景2: 动态权限变更**
- ✅ 实时生效（无需重新登录）
- ✅ 权限撤销立即生效

---

## 💻 模块二：Development（开发模块）- 10个测试类 [P0]

### 2.1 CodeExecutionController 集成测试
**文件**: `CodeExecutionControllerIntegrationTest.java`

#### 测试场景：
**场景1: SQL代码执行**
- ✅ SELECT查询执行（Doris引擎）
- ✅ SELECT查询执行（Kyuubi/Hive引擎）
- ✅ DDL语句执行（CREATE/DROP TABLE）
- ✅ DML语句执行（INSERT/UPDATE/DELETE）
- ✅ 多语句批量执行（分号分隔）
- ✅ 查询结果集返回（含列名和数据行）
- ✅ 大结果集分页返回

**场景2: Python代码执行**
- ✅ 简单Python脚本执行
- ✅ 带依赖的脚本执行
- ✅ 脚本超时处理
- ✅ 脚本标准输出/错误输出捕获

**场景3: Java/Scala代码执行**
- ✅ 内联代码执行（非JAR模式）
- ✅ JAR包上传并执行
- ✅ 主类指定执行
- ✅ 自定义JAR依赖加载

**场景4: 引擎适配**
- ✅ Spark引擎提交任务
- ✅ Flink引擎提交任务
- ✅ HBase读写操作
- ✅ Doris高速查询

**场景5: 任务管理**
- ✅ 取消正在运行的任务
- ✅ 查询运行中的任务列表
- ✅ 任务超时自动终止
- ✅ 任务执行历史记录

**场景6: 参数化执行**
- ✅ ProgramParams配置保存/读取
- ✅ 运行时参数注入
- ✅ 数据源动态切换
- ✅ 计算资源配置（executor/memory/core）

**场景7: 异常处理**
- ❌ SQL语法错误返回友好提示
- ❌ 数据源连接失败处理
- ❌ 权限不足拒绝执行（403）
- ❌ 未登录拒绝访问（401）
- ❌ 请求参数校验失败（400）
- ❌ JAR文件格式错误
- ❌ 引擎不可用时的降级处理

**场景8: 安全控制**
- ✅ 行级过滤应用（rowFilterSql）
- ✅ 数据脱敏应用（dataMaskingRule）
- ✅ SQL注入防护（仅允许SELECT）
- ✅ 数据源访问权限校验

**场景9: 文件上传**
- ✅ JAR文件上传保存到临时目录
- ✅ 文件大小限制
- ✅ 文件格式校验（仅.jar）
- ✅ 上传路径安全（防止路径遍历）

---

### 2.2 DevProjectController 集成测试
**文件**: `DevProjectControllerIntegrationTest.java`

#### 测试场景：
**场景1: 项目CRUD**
- ✅ 创建开发项目（projectName/projectCode唯一性）
- ✅ 更新项目信息
- ✅ 查询项目列表（支持按名称/编码/状态筛选）
- ✅ 按ID查询项目详情
- ✅ 删除项目（级联删除关联数据）
- ✅ 项目状态管理（active/archived/disabled）

**场景2: 项目权限控制**
- ✅ 读权限验证（checkProjectPermission READ）
- ✅ 写权限验证（checkProjectPermission EDIT）
- ✅ 删除权限验证（checkProjectPermission DELETE）
- ❌ 无权限用户访问返回403

**场景3: 程序参数管理**
- ✅ 保存程序参数（ProgramParamsDTO）
- ✅ 按项目+文件路径查询参数
- ✅ 更新程序参数
- ✅ 删除程序参数
- ✅ JSON字段解析（datasources/jarDependencyCatalogs/runParams/computeResources）

**场景4: 边界条件**
- ❌ 重复的项目名称/编码
- ❌ 删除不存在项目
- ❌ 参数JSON格式错误

---

### 2.3 DevJarDependencyController 集成测试
**文件**: `DevJarDependencyControllerIntegrationTest.java`

#### 测试场景：
**场景1: JAR依赖管理**
- ✅ 添加Maven坐标依赖（groupId/artifactId/version）
- ✅ 添加本地JAR文件依赖
- ✅ 查询项目的所有依赖
- ✅ 删除依赖
- ✅ 依赖去重检查

**场景2: 依赖解析**
- ✅ Maven仓库依赖下载验证
- ✅ 依赖版本冲突检测
- ✅ 传递依赖解析

---

### 2.4 CodeExecutionService 业务逻辑测试
**文件**: `CodeExecutionServiceRealDataTest.java`

#### 测试场景：
**场景1: 执行流程编排**
- ✅ 接收请求 → 参数校验 → 权限检查 → 引擎路由 → 执行 → 结果封装
- ✅ 执行上下文构建（CodeExecutionContext）
- ✅ 执行结果标准化（CodeExecutionResult）

**场景2: 引擎路由策略**
- ✅ ExecutionStrategyFactory 正确选择策略
- ✅ EngineType枚举匹配
- ✅ 不支持的引擎类型报错

**场景3: 进程管理**
- ✅ ProcessManager 启动/监控/终止进程
- ✅ 超时控制机制
- ✅ 资源释放（进程/连接/临时文件）

---

### 2.5 ExecutionStrategy 各实现类测试
**文件**: 
- `DorisExecutionStrategyRealDataTest.java`
- `SparkExecutionStrategyRealDataTest.java`
- `FlinkExecutionStrategyRealDataTest.java`
- `KyuubiExecutionStrategyRealDataTest.java`
- `HBaseExecutionStrategyRealDataTest.java`

#### 共同测试场景：
**场景1: 连接建立**
- ✅ 从DatasourceConfig获取连接参数
- ✅ JDBC连接池获取连接
- ✅ 连接失败重试机制

**场景2: SQL执行**
- ✅ executeQuery() 返回结果集
- ✅ executeUpdate() 返回影响行数
- ✅ Session设置（如SET变量）

**场景3: 结果处理**
- ✅ ResultSet转换为List<List<String>>
- ✅ 列元数据提取
- ✅ 大结果集流式处理

**场景4: 特有功能**
- **Doris**: 高并发查询、物化视图
- **Spark**: 提交YARN作业、SparkSession管理
- **Flink**: 流批一体、Checkpoint
- **Kyuubi**: Hive兼容、UDF注册
- **HBase**: Scan/Get/Put/Delete、过滤器

---

### 2.6 DevProjectService 业务逻辑测试
**文件**: `DevProjectServiceRealDataTest.java`

#### 测试场景：
**场景1: 项目生命周期**
- ✅ saveProject() 数据校验
- ✅ updateProject() 乐观锁
- ✅ deleteProject() 级联清理（params/executions/jars）

**场景2: 查询优化**
- ✅ 分页查询性能
- ✅ 多条件组合查询索引命中

---

### 2.7 ProgramParamsService 业务逻辑测试
**文件`: `ProgramParamsServiceRealDataTest.java`

#### 测试场景：
**场景1: 参数持久化**
- ✅ save() JSON序列化存储
- ✅ getByProjectIdAndFilePath() 唯一性约束
- ✅ update() 原子更新
- ✅ delete() 物理删除

**场景2: 参数校验**
- ✅ engine字段枚举值校验
- ✅ datasources数组非空校验
- ✅ runParams结构校验

---

## 🔧 模块三：DevOps（运维模块）- 8个测试类 [P0]

### 3.1 DatasourceConfigController 集成测试
**文件**: `DatasourceConfigControllerIntegrationTest.java`

#### 测试场景：
**场景1: 数据源CRUD**
- ✅ 创建数据源（MySQL/PostgreSQL/Oracle/ClickHouse等）
- ✅ 更新数据源配置（主机/端口/用户名/密码）
- ✅ 查询数据源列表（分页+筛选）
- ✅ 查询所有数据源（下拉选项）
- ✅ 删除数据源（检查是否被引用）
- ✅ DatasourceType枚举值完整性

**场景2: 连接测试**
- ✅ testConnection(id) 测试已有数据源连接
- ✅ testConnectionByConfig(dto) 测试未保存配置
- ✅ ConnectionTestResult 返回结构（success/message/responseTimeMs）
- ✅ 连接超时处理
- ✅ 错误凭据友好提示

**场景3: 元数据同步**
- ✅ syncMetadata(id) 全量同步触发
- ✅ 同步进度跟踪
- ✅ 同步失败回滚
- ✅ 同步结果通知

**场景4: 权限控制**
- ✅ ADMIN才能创建/更新/删除/同步
- ❌ 普通用户只能查看和测试连接

**场景5: 敏感信息安全**
- ✅ 密码加密存储（不应明文返回）
- ✅ 返回VO时不暴露password字段

---

### 3.2 FileExplorerController 集成测试
**文件**: `FileExplorerControllerIntegrationTest.java`

#### 测试场景：
**场景1: 文件浏览**
- ✅ 列出根目录内容
- ✅ 列出子目录内容（递归/非递归）
- ✅ 获取文件详细信息（大小/修改时间/类型）
- ✅ 目录树结构展示（FolderTreeNode）

**场景2: 文件操作**
- ✅ 创建目录
- ✅ 上传文件
- ✅ 下载文件
- ✅ 删除文件/目录
- ✅ 重命名文件/目录
- ✅ 移动文件/目录

**场景3: 存储后端适配**
- ✅ LocalFileSystem 本地文件系统操作
- ✅ HdfsFileSystem HDFS操作（需Hadoop环境）
- ✅ S3FileSystem S3操作（需AWS凭证）
- ✅ FileSystemFactory 工厂模式路由

**场景4: 权限控制**
- ✅ 文件读权限检查
- ✅ 文件写权限检查
- ✅ 路径遍历攻击防护（../）
- ✅ 符号链接安全

**场景5: Git集成（如果支持）**
- ✅ Git仓库浏览
- ✅ 提交历史查看
- ✅ 文件差异对比
- ✅ 分支切换

---

### 3.3 StorageConfigController 集成测试
**文件**: `StorageConfigControllerIntegrationTest.java`

#### 测试场景：
**场景1: 存储配置CRUD**
- ✅ 创建存储配置（Local/HDFS/S3/JuiceFS）
- ✅ 更新存储配置
- ✅ 查询配置列表
- ✅ 删除配置（检查是否有文件引用）
- ✅ StorageEngineType枚举完整性

**场景2: 连接测试**
- ✅ 测试存储后端连通性
- ✅ 返回StorageConnectionTestVO（bucket列表/根目录等）

**场景3: 配置校验**
- ❌ HDFS配置缺少core-site.xml路径
- ❌ S3配置缺少accessKey/secretKey
- ❌ Local路径不存在或无写权限

---

### 3.4 DatasourceConfigService 业务逻辑测试
**文件**: `DatasourceConfigServiceRealDataTest.java`

#### 测试场景：
**场景1: 连接池管理**
- ✅ DatasourceConnectionPoolManager 连接池初始化
- ✅ 连接复用验证
- ✅ 连接泄漏检测
- ✅ 最大连接数限制
- ✅ 空闲连接回收

**场景2: 配置热更新**
- ✅ 更新配置后连接池重建
- ✅ 旧连接优雅关闭

**场景3: 元数据缓存**
- ✅ 数据源列表缓存
- ✅ 缓存失效策略

---

### 3.5 FileExplorerService 业务逻辑测试
**文件`: `FileExplorerServiceRealDataTest.java`

#### 测试场景：
**场景1: 路径处理**
- ✅ 路径规范化（去除多余/）
- ✅ 相对路径转绝对路径
- ✅ 路径拼接安全性

**场景2: 文件系统抽象**
- ✅ FileSystem接口统一操作
- ✅ 不同后端的特性屏蔽

**场景3: 性能测试**
- ✅ 大目录列出性能（1000+文件）
- ✅ 大文件上传/下载速度

---

### 3.6 MetadataSyncService 补充测试
> ⚠️ 已有 `MetadataSyncServiceRealDataTest.java`，补充以下场景

**文件**: `MetadataSyncServiceExtendedTest.java`

#### 测试场景：
**场景1: 增量同步**
- ✅ 仅同步变更的表
- ✅ 新增表检测
- ✅ 删除表检测
- ✅ 字段变更检测

**场景2: 同步冲突**
- ✅ 并发同步冲突处理
- ✅ 断点续传

**场景3: 多数据源同步**
- ✅ 同时同步多个数据源
- ✅ 失败隔离（一个失败不影响其他）

---

## 📊 模块四：Metadata（元数据）- 4个测试类 [P1]

### 4.1 MetadataController 集成测试
**文件**: `MetadataControllerIntegrationTest.java`

#### 测试场景：
**场景1: 表元数据查询**
- ✅ 按数据源查询所有表
- ✅ 按数据库筛选表列表
- ✅ 获取表的详细元数据（列名/类型/注释/主键）
- ✅ 搜索表名（模糊匹配）

**场景2: 列元数据查询**
- ✅ 获取表的所有列信息
- ✅ 列类型映射（MySQL→通用类型）
- ✅ 列注释和默认值

**场景3: 元数据刷新**
- ✅ 手动触发元数据刷新
- ✅ 定时自动刷新验证

---

### 4.2 TableMetadataService 业务逻辑测试
**文件**: `TableMetadataServiceRealDataTest.java`

#### 测试场景：
**场景1: 元数据缓存**
- ✅ 查询结果缓存
- ✅ 缓存失效机制
- ✅ 缓存命中率监控

**场景2: 元数据一致性**
- ✅ 与实际数据库schema对比
- ✅ 变更检测和告警

---

### 4.3 ColumnMetadataService 业务逻辑测试
**文件**: `ColumnMetadataServiceRealDataTest.java`

#### 测试场景：
**场景1: 列信息管理**
- ✅ 列元数据CRUD
- ✅ 类型转换工具方法

---

## 📚 模块五：Knowledge（知识库）- 3个测试类 [P1]

### 5.1 KnowledgeDocumentController 集成测试
**文件**: `KnowledgeDocumentControllerIntegrationTest.java`

#### 测试场景：
**场景1: 文档CRUD**
- ✅ 上传知识文档（支持Markdown/PDF/Word等）
- ✅ 更新文档内容
- ✅ 查询文档列表（分页+搜索）
- ✅ 按ID获取文档详情
- ✅ 删除文档
- ✅ DocType枚举完整性（MARKDOWN/PDF/WORD/OTHER）

**场景2: 文档检索**
- ✅ 全文搜索
- ✅ 按标签筛选
- ✅ 按文档类型筛选

**场景3: 版本管理**
- ✅ 文档版本历史
- ✅ 版本对比
- ✅ 回滚到指定版本

---

### 5.2 KnowledgeDocumentService 业务逻辑测试
**文件**: `KnowledgeDocumentServiceRealDataTest.java`

#### 测试场景：
**场景1: 文档存储**
- ✅ 文件上传保存
- ✅ 文件存储路径规范
- ✅ 存储空间管理

**场景2: 文档解析**
- ✅ Markdown渲染预览
- ✅ PDF文本提取
- ✅ 索引建立

---

## 🛠️ 模块六：Common（公共组件）- 5个测试类 [P0]

### 6.1 JwtTokenUtils 工具类测试
**文件**: `JwtTokenUtilsRealDataTest.java`

#### 测试场景：
**场景1: Token生成与验证**
- ✅ 生成有效JWT Token
- ✅ 验证有效Token（解析出userId/username）
- ✅ Token过期检测
- ✅ Token篡改检测（签名无效）
- ✅ 过期Token刷新

**场景2: Token安全**
- ✅ Secret强度验证
- ✅ Claims信息完整性
- ✅ 时间戳防重放

---

### 6.2 PasswordUtils 工具类测试
**文件**: `PasswordUtilsRealDataTest.java`

#### 测试场景：
**场景1: 密码加密**
- ✅ BCrypt哈希生成
- ✅ 同一密码不同哈希值（salt随机）
- ✅ 密码验证（matches方法）
- ✅ 弱密码检测（长度/复杂度）

**场景2: 密码安全**
- ✅ 明文密码不在日志中出现
- ✅ 内存清零处理

---

### 6.3 SecurityUtils 工具类测试
**文件**: `SecurityUtilsRealDataTest.java`

#### 测试场景：
**场景1: 上下文获取**
- ✅ getCurrentUserId() 从SecurityContext获取
- ✅ getCurrentUsername() 获取
- ✅ 未登录返回null
- ✅ 多线程上下文隔离

---

### 6.4 GlobalExceptionHandler 异常处理测试
**文件**: `GlobalExceptionHandlerRealDataTest.java`

#### 测试场景：
**场景1: 异常分类处理**
- ✅ BusinessException → 对应HTTP状态码和错误消息
- ✅ IllegalArgumentException → 400 Bad Request
- ✅ MethodArgumentNotValidException → 校验错误详情
- ✅ AccessDeniedException → 403 Forbidden
- ✅ AuthenticationException → 401 Unauthorized
- ✅ 未知异常 → 500 Internal Server Error

**场景2: 错误响应格式**
- ✅ Result.error() 格式统一
- ✅ 错误码规范
- ✅ 错误消息脱敏（不泄露堆栈）

---

### 6.5 AuditLogAspect 切面测试
**文件**: `AuditLogAspectRealDataTest.java`

#### 测试场景：
**场景1: 审计记录自动写入**
- ✅ 带@AuditLog注解的方法调用后自动记录
- ✅ 记录module/operation/targetType/targetId
- ✅ 记录操作人和时间戳
- ✅ 记录方法参数（脱敏处理）

**场景2: 异常场景**
- ✅ 方法抛异常时也记录审计日志
- ✅ 记录异常信息

---

## 📝 实施计划

### 第一阶段：核心基础测试（高优先级）
**预计工作量**: 15个测试类，约120个用例

1. ✅ AuthController 集成测试（登录/用户信息）
2. ✅ DataAccessPermissionService 权限测试（已有，补充完善）
3. ✅ JwtTokenUtils / PasswordUtils / SecurityUtils 工具类测试
4. ✅ GlobalExceptionHandler 异常处理测试
5. ✅ DatasourceConfigController 数据源管理测试
6. ✅ CodeExecutionController 代码执行测试（SQL基本场景）
7. ✅ DevProjectController 项目管理测试
8. ✅ RowFilterAndDataMaskingRealDataTest（已有，继续补充）

### 第二阶段：业务逻辑测试（中优先级）
**预计工作量**: 15个测试类，约100个用例

9. ✅ UserController / RoleController / TenantController CRUD测试
10. ✅ PermissionController 权限配置测试
11. ✅ FileExplorerController 文件操作测试
12. ✅ StorageConfigController 存储配置测试
13. ✅ CodeExecutionService / ExecutionStrategy 引擎测试
14. ✅ ProgramParamsService 参数管理测试
15. ✅ DatasourceConfigService 连接池测试

### 第三阶段：完善与边缘场景（低优先级）
**预计工作量**: 12个测试类，约80个用例

16. ✅ AuditLogController / AuditLogAspect 审计日志测试
17. ✅ ProjectPermissionController / MenuRoleBindController 绑定测试
18. ✅ MetadataController / TableMetadataService 元数据测试
19. ✅ KnowledgeDocumentController 知识库测试
20. ✅ DevJarDependencyController 依赖管理测试
21. ✅ 边界条件和异常场景补充

---

## 🧪 测试代码规范

### 文件命名规范
```
{ClassName}IntegrationTest.java      # Controller/API集成测试
{ClassName}RealDataTest.java          # Service业务逻辑测试（真实数据）
{Feature}ExtendedTest.java            # 补充/扩展测试
```

### 测试类模板
```java
@SpringBootTest
@Transactional
@Rollback
@DisplayName("模块名 - 功能描述")
class XxxIntegrationTest {

    @Autowired
    private XxxService xxxService;
    
    @Autowired
    private XxxMapper xxxMapper;

    @BeforeEach
    void setUp() {
        // 初始化测试数据
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        // 清理资源
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("场景组1: 功能描述")
    class ScenarioGroup1 {

        @Test
        @DisplayName("✅ 正常场景描述")
        void testNormalCase() {
            // Given: 准备数据
            // When: 执行操作
            // Then: 验证结果
        }

        @Test
        @DisplayName("❌ 异常场景描述")
        void testExceptionCase() {
            // 验证异常情况
        }
    }
}
```

### 断言规范
```java
// 成功场景
assertEquals(expected, actual, "错误提示信息");
assertTrue(condition, "条件应为true");
assertNotNull(object, "对象不应为null");
assertDoesNotThrow(() -> {...}, "不应抛异常");

// 异常场景
Exception exception = assertThrows(ExceptionClass.class, () -> {
    // 触发异常的操作
}, "应抛出指定异常");
assertEquals(expectedCode, exception.getCode(), "异常code应匹配");
assertEquals(expectedMessage, exception.getMessage(), "异常消息应匹配");
```

### 测试数据准备
```java
private SysUser createTestUser(String username) {
    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPassword("encoded_password"); // BCrypt编码
    user.setRealName("Test User");
    user.setStatus(1);
    user.setDeleted(0);
    sysUserMapper.insert(user);
    return user;
}

private void setUpSecurityContext(Long userId, String username, List<String> roles) {
    List<SimpleGrantedAuthority> authorities = roles.stream()
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());
    
    CustomUserDetails userDetails = new CustomUserDetails(
        userId, username, null, roles, authorities, true);
    
    Authentication auth = new UsernamePasswordAuthenticationToken(
        userDetails, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(auth);
}
```

---

## ✅ 覆盖率目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| Controller覆盖率 | ≥90% | 所有API端点 |
| Service覆盖率 | ≥85% | 核心业务逻辑 |
| 分支覆盖率 | ≥75% | if/else/switch |
| 场景覆盖率 | ≥95% | 正常/异常/边界 |
| 权限场景覆盖率 | 100% | ADMIN/普通用户/匿名 |

---

## 🚀 执行命令

```bash
# 运行全部测试
mvn test

# 运行指定测试类
mvn test -Dtest=AuthControllerIntegrationTest

# 运行指定测试方法
mvn test -Dtest=AuthControllerIntegrationTest#testLoginSuccess

# 生成覆盖率报告
mvn jacoco:report

# 运行System模块所有测试
mvn test -Dtest="com.bigdata.modules.system.**.*Test"

# 运行Development模块所有测试
mvn test -Dtest="com.bigdata.modules.development.**.*Test"
```

---

## 📌 注意事项

⚠️ **环境要求**:
1. MySQL服务运行中（127.0.0.1:3306，数据库bigdata_platform）
2. Spark/Flink环境可选（部分测试会跳过如果环境不可用）
3. HDFS/S3环境可选（文件系统相关测试）

⚠️ **数据安全**:
- 所有测试使用 `@Transactional + @Rollback` 自动回滚
- 不产生脏数据
- 生产数据库不受影响

⚠️ **性能考虑**:
- 单个测试类执行时间 < 30秒
- 总测试套件执行时间 < 5分钟
- 避免在@Test中Thread.sleep

⚠️ **维护性**:
- 测试代码与产品代码同步更新
- 新功能必须配套测试
- CI/CD流水线集成测试门禁

---

## 📊 预期成果

完成后的测试体系将提供：

✅ **质量保障**: 所有API端点和核心业务逻辑都有测试覆盖  
✅ **回归保护**: 新代码引入bug能快速发现  
✅ **文档价值**: 测试用例即功能规格说明  
✅ **重构信心**: 安全重构底层实现  
✅ **团队协作**: 统一的测试规范降低沟通成本  

---

*文档版本: v1.0*  
*创建时间: 2026-05-30*  
*预计总测试用例数: 300+*
