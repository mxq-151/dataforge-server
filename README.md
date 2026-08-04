# 大数据开发平台 - SpringBoot后端服务

## 项目概述

本项目是大数据开发运维平台的SpringBoot主服务,严格遵循开发大纲中的技术栈和代码规范。

## 技术栈

- **框架**: SpringBoot 3.2.1 + Java 17
- **安全认证**: Spring Security 6.x + JWT (jjwt 0.12.3)
- **ORM框架**: MyBatis-Plus 3.5.5
- **数据库**: MySQL 8.x
- **API文档**: Knife4j 4.4.0 (OpenAPI 3)
- **工具库**: Hutool 5.8.24

## 项目结构

```
bigdata-platform/
├── pom.xml                              # Maven配置文件
├── src/main/java/com/bigdata/
│   ├── BigDataPlatformApplication.java # 主启动类
│   ├── common/                          # 通用工具层
│   │   ├── config/
│   │   │   └── MyBatisPlusConfig.java  # MyBatis-Plus自动填充配置
│   │   ├── entity/
│   │   │   └── BaseEntity.java         # 基础实体类(含通用字段)
│   │   ├── exception/
│   │   │   ├── BusinessException.java  # 业务异常类
│   │   │   └── GlobalExceptionHandler.java # 全局异常处理器
│   │   ├── security/
│   │   │   ├── JwtAuthenticationFilter.java # JWT认证过滤器
│   │   │   └── SecurityConfig.java     # Spring Security配置
│   │   ├── utils/
│   │   │   ├── JwtTokenUtils.java      # JWT工具类
│   │   │   └── PasswordUtils.java      # 密码加密工具类
│   │   └── vo/
│   │       ├── Result.java             # 统一返回值对象
│   │       └── PageVO.java             # 分页VO对象
│   └── modules/                         # 业务模块层
│       ├── system/                      # 系统管理模块
│       │   ├── controller/
│       │   │   ├── AuthController.java  # 认证控制器(登录/登出)
│       │   │   ├── UserController.java  # 用户管理控制器
│       │   │   └── RoleController.java  # 角色管理控制器
│       │   ├── dto/
│       │   │   └── LoginDTO.java        # 登录请求DTO
│       │   ├── entity/
│       │   │   ├── SysUser.java         # 用户实体
│       │   │   └── SysRole.java         # 角色实体
│       │   ├── mapper/
│       │   │   ├── SysUserMapper.java   # 用户Mapper
│       │   │   └── SysRoleMapper.java   # 角色Mapper
│       │   └── service/
│       │       ├── SysUserService.java  # 用户服务
│       │       └── SysRoleService.java  # 角色服务
│       └── devops/                      # 运维管理模块
│           ├── controller/
│           │   └── DatasourceConfigController.java # 数据源控制器
│           ├── entity/
│           │   └── DatasourceConfig.java # 数据源配置实体
│           ├── mapper/
│           │   └── DatasourceConfigMapper.java
│           └── service/
│               └── DatasourceConfigService.java
├── src/main/resources/
│   ├── application.yml                  # 应用配置文件
│   └── sql/
│       └── init.sql                     # 数据库初始化脚本
└── README.md                            # 项目说明文档
```

## 核心功能实现

### 1. 用户认证与权限管控 ✅

**已实现功能:**
- 用户登录(JWT Token生成)
- 用户登出(Token失效)
- 获取当前用户信息
- Spring Security无状态Session配置
- JWT过滤器自动解析Token
- CORS跨域支持

**接口列表:**
- `POST /api/system/auth/login` - 用户登录
- `POST /api/system/auth/logout` - 用户登出
- `GET /api/system/auth/info` - 获取用户信息

### 2. 系统用户管理 ✅

**已实现功能:**
- 用户CRUD操作
- 分页查询(支持按用户名、状态筛选)
- 密码MD5+Salt加密存储
- 登录时间记录

**接口列表:**
- `GET /api/system/user/list` - 用户列表(分页)
- `GET /api/system/user/{id}` - 用户详情
- `POST /api/system/user` - 新增用户
- `PUT /api/system/user` - 更新用户
- `DELETE /api/system/user/{id}` - 删除用户

### 3. 角色管理 ✅

**已实现功能:**
- 角色CRUD操作
- 分页查询(支持按角色名搜索)
- 获取所有可用角色(下拉选择)

**接口列表:**
- `GET /api/system/role/list` - 角色列表(分页)
- `GET /api/system/role/all` - 所有角色
- `GET /api/system/role/{id}` - 角色详情
- `POST /api/system/role` - 新增角色
- `PUT /api/system/role` - 更新角色
- `DELETE /api/system/role/{id}` - 删除角色

### 4. 数据源配置管理 ✅

**已实现功能:**
- 数据源CRUD操作
- 连接测试(MySQL/Kyuubi)
- 支持多种数据源类型(kyuubi/doris/clickhouse/mysql等)
- 连接状态跟踪

**接口列表:**
- `GET /api/devops/datasource/list` - 数据源列表
- `GET /api/devops/datasource/all` - 所有可用数据源
- `GET /api/devops/datasource/{id}` - 数据源详情
- `POST /api/devops/datasource` - 新增数据源
- `PUT /api/devops/datasource` - 更新数据源
- `DELETE /api/devops/datasource/{id}` - 删除数据源
- `POST /api/devops/datasource/{id}/test` - 测试连接

### 5. 全局异常处理 ✅

**已实现:**
- 业务异常统一处理(BusinessException)
- 参数校验异常处理(@Valid)
- 参数绑定异常处理
- 全局未知异常捕获和日志记录

### 6. 数据库表结构 ✅

**已创建核心表:**
- sys_user - 用户表
- sys_role - 角色表
- sys_user_role - 用户角色关联表
- datasource_config - 数据源配置表
- storage_config - 存储配置表
- operation_audit_log - 操作审计日志表

**初始数据:**
- 默认管理员账号: admin / 123456
- 预置5个角色: admin, ops, developer, analyst, viewer

## 开发规范遵循情况

✅ **不使用Lombok注解** - 所有getter/setter手动编写
✅ **统一返回值格式** - Result<T>统一封装
✅ **分页查询** - MyBatis-Plus分页插件
✅ **逻辑删除** - 全局deleted字段
✅ **自动填充** - createTime/updateTime自动填充
✅ **密码安全** - MD5+Salt加密
✅ **JWT无状态认证** - SessionCreationPolicy.STATELESS
✅ **全局异常处理** - @RestControllerAdvice
✅ **参数校验** - Jakarta Validation API
✅ **CORS跨域支持** - 前后端分离必备
✅ **API文档** - Knife4j集成

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+

### 2. 初始化数据库

```bash
mysql -u root -p < src/main/resources/sql/init.sql
```

### 3. 修改配置

编辑 [application.yml](src/main/resources/application.yml) 修改数据库连接信息:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/bigdata_platform?...
    username: your_username
    password: your_password
```

### 4. 启动项目

```bash
cd bigdata-platform
mvn spring-boot:run
```

或直接运行主类:
```bash
BigDataPlatformApplication.main()
```

### 5. 访问接口

- 应用地址: http://localhost:8080/api
- API文档: http://localhost:8080/api/doc.html (Knife4j)

### 6. 测试登录

```bash
curl -X POST http://localhost:8080/api/system/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

响应示例:
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```

## 后续开发计划

根据开发大纲,接下来需要实现的模块:

### 阶段2:基础功能模块(P0/P1优先级)
- [ ] 库表管理模块(对接Kyuubi)
- [ ] 文件浏览模块(HDFS/S3/JuiceFS)
- [ ] 任务监控模块(YARN/K8s/Grafana)

### 阶段3:核心业务模块(P2优先级)
- [ ] 数据开发模块(DolphinScheduler对接)
- [ ] 数据探查模块(ECharts可视化)
- [ ] 业务知识模块(知识库管理)

### 阶段4:AI能力落地
- [ ] Python AI服务搭建(OpenHarness)
- [ ] RAG知识库构建(Milvus)
- [ ] AI辅助能力集成

## 注意事项

1. **安全性**: 生产环境请修改JWT密钥和数据库密码
2. **性能**: 建议添加Redis缓存层(用户会话、权限缓存等)
3. **监控**: 接入Prometheus + Grafana进行应用监控
4. **日志**: 生产环境建议使用ELK收集日志
5. **部署**: 建议使用Docker容器化部署

## 项目亮点

✨ **完全符合开发大纲规范**
✨ **清晰的分层架构(common/modules)**
✨ **完善的异常处理机制**
✨ **安全的认证授权体系**
✨ **可扩展的模块化设计**
✨ **完整的CRUD示例代码**
✨ **即开即用的基础框架**

---

**开发者**: BigData Platform Team
**版本**: 1.0.0-SNAPSHOT
**最后更新**: 2024年
