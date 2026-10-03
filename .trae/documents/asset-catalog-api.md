# 资产目录 查询接口文档

> 服务：`dataforge-server` ｜ 模块：`com.bigdata.modules.asset`
> 实现：`AssetCatalogController`（`@RequestMapping("/asset")`）
> 上下文路径：`server.servlet.context-path = /api`，故下文所有路径需加前缀 `/api`

---

## 一、通用约定

### 1.1 统一响应包络

所有接口返回 `Result<T>`：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

| 字段 | 说明 |
| --- | --- |
| `code` | `200` 成功；业务异常由全局异常处理器转成 **HTTP 200 + body.code**，前端按 `code` 判错 |
| `message` | 提示信息 |
| `data` | 业务数据，无返回时为 `null` |

常见 `code`：

| code | 含义 |
| --- | --- |
| 200 | 成功 |
| 400 | 参数缺失/非法 |
| 403 | 无权限访问该资产 |
| 404 | 资产不存在 |
| 500 | 服务端异常 |

### 1.2 字段命名

返回体统一 **camelCase**（由 `WebMvcConfig` 的 `@Primary` `ObjectMapper` 序列化，`application.yml` 里的 `property-naming-strategy: SNAKE_CASE` 对其不生效）。例如 `nodeKind`、`tableMetadataId`、`browseCount30d`。

### 1.3 权限口径

- **服务端过滤，fail-close**：目录树、检索、详情、预览全部在服务端按当前用户的表级 SELECT 权限过滤；判定不通过即不可见，不存在"取不到权限就放行"。
- **按 id 直取**（详情、页签、预览）走单表校验，无权限返回 `403`。
- **列级权限**：详情中的字段列表、预览的列都会按列白名单裁剪；配置失误时返回空而不是"不过滤"。
- **指标**按项目授权（`projectCode`）过滤。

### 1.4 时间格式

| 场景 | 格式 |
| --- | --- |
| 详情/热度等时间字段 | `yyyy-MM-dd HH:mm:ss` |
| 使用趋势的统计日 `statDate` | `yyyy-MM-dd` |

### 1.5 数据来源与时延（T+1）

资产目录的部分查询读 **T+1 日快照** `asset_catalog_daily`（每日 02:30 由 `AssetCatalogSnapshotTask` 生成，保留 180 天）：

| 接口 | 数据来源 | 时延 |
| --- | --- | --- |
| 目录树 `/catalog/tree` | 日快照 | 次日生效 |
| 表检索 `/search`（`types=table`） | 日快照 | 次日生效 |
| 盘点 `/inventory/overview` | 日快照 | 次日生效 |
| 治理项 `/inventory/health` | 日快照 | 次日生效 |
| 大小突增 `/inventory/size-growth` | 日快照（本期 = 最新快照，上期 = 30 天前快照） | 次日生效 |
| 环境×项目 `/inventory/env-project`、TOP 资产 `/inventory/top-assets` | `table_metadata` 实时读取 | 实时 |
| 调度看板 `/inventory/top-compute`、`/inventory/task-delay` | `scheduler_task_run_daily`（每日 03:30 从 DolphinScheduler 采集） | T+1 |
| 字段/指标检索、详情及各页签、预览 | 实时计算 | 实时 |

> - 快照为 **T+1**：当天新建/变更/删除的表，次日才体现在目录树、表检索、盘点、治理项中。
> - 快照表为空时（首次部署尚未跑过任务），上述接口**自动回退到实时计算**，不会出现空目录。
> - 权限**不进快照**，始终按请求实时判定，授权变更即时生效。

---

## 二、接口清单

| # | 方法 | 路径 | 说明 | 数据来源 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/api/asset/catalog/tree` | 资产目录树（引擎视角 / 规范视角） | 快照 |
| 2 | GET | `/api/asset/search` | 统一检索（表 / 字段 / 指标） | 快照 + 实时 |
| 3 | GET | `/api/asset/suggest` | 检索输入建议 | 实时 |
| 4 | GET | `/api/asset/table/{id}` | 资产（表）详情 | 实时 |
| 5 | GET | `/api/asset/table/{id}/usage` | 使用热度（趋势 / 字段热度 / 高频读者） | 实时 |
| 6 | GET | `/api/asset/table/{id}/producers` | 产出任务（代理调度系统） | 实时 |
| 7 | GET | `/api/asset/table/{id}/indicators` | 关联指标 | 实时 |
| 8 | GET | `/api/asset/table/{id}/quality` | 质量规则摘要 | 实时 |
| 9 | GET | `/api/asset/table/{id}/preview` | 数据预览 | 实时 |
| 10 | GET | `/api/asset/table/{id}/doc` | 使用说明 | 实时 |
| 11 | GET | `/api/asset/table/{id}/doc/history` | 使用说明历史版本 | 实时 |
| 12 | GET | `/api/asset/bookmark` | 收藏列表（分页） | 实时 |
| 13 | GET | `/api/asset/tag` | 标签字典 | 实时 |
| 14 | GET | `/api/asset/category` | 分类字典（分层 / 业务域） | 实时 |
| 15 | GET | `/api/asset/inventory/overview` | 资产盘点概览 | 快照 |
| 16 | GET | `/api/asset/inventory/health` | 治理项清单 | 快照 |
| 17 | GET | `/api/asset/inventory/env-project` | 资产概况①：按环境×项目的表数量与占用空间 | 实时 |
| 18 | GET | `/api/asset/inventory/top-assets` | 资产概况②：访问频率 / 占用空间 TOP 资产 | 实时 |
| 19 | GET | `/api/asset/inventory/top-compute` | 资产概况③：消耗计算资源 / 运行时长 TOP 任务 | 调度采集 |
| 20 | GET | `/api/asset/inventory/task-delay` | 资产概况④：任务延迟率 | 调度采集 |
| 21 | GET | `/api/asset/inventory/size-growth` | 资产概况⑤：数据大小增长率突增表 | 快照对比 |

**写能力**：`PUT /api/asset/table/{id}/lifecycle`（热/冷/保留天数）已接通，是资产模块当前唯一的写接口。

> 资产概况的五块看板已全部接入。其中 ③④ 的数据由每日采集任务从 DolphinScheduler 落库，
> 需要配置 `scheduler.dolphinscheduler.base-url`（见第五章）。

> 治理写接口（`PUT /table/{id}/owner|category|tags|doc`、`POST /table/{id}/refresh`、收藏增删、`POST /access` 埋点）不在本文档范围。

---

## 三、接口详情

### 1. 资产目录树

`GET /api/asset/catalog/tree`

**入参**

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `view` | string | 否 | `engine` | `engine`=数据源→库→表；`convention`=分层→业务域→表 |
| `datasourceId` | long | 否 | - | 限定数据源 |
| `databaseName` | string | 否 | - | 限定库名 |
| `keyword` | string | 否 | - | 按表名/表注释模糊过滤 |

**响应**：`CatalogTreeNode[]`

```json
[
  {
    "key": "engine:1",
    "label": "hive-prod",
    "nodeKind": "datasource",
    "datasourceId": 1,
    "storageEngine": "hive",
    "assetCount": 120,
    "children": [
      {
        "key": "db:1:dw",
        "label": "dw",
        "nodeKind": "database",
        "datasourceId": 1,
        "databaseName": "dw",
        "assetCount": 40,
        "children": [
          {
            "key": "table:101",
            "label": "dwd_order_detail_d",
            "nodeKind": "table",
            "tableMetadataId": 101,
            "datasourceId": 1,
            "storageEngine": "hive",
            "databaseName": "dw",
            "tableName": "dwd_order_detail_d",
            "layerCode": "dwd",
            "domainCode": "order",
            "assetCount": 1,
            "unclassified": false,
            "children": null
          }
        ]
      }
    ]
  }
]
```

**CatalogTreeNode**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | string | 稳定节点键：`engine:{dsId}` / `db:{dsId}:{db}` / `table:{tableMetadataId}` / `layer:{code}` / `domain:{layer}.{domain}` |
| `label` | string | 显示名 |
| `nodeKind` | string | `datasource` / `database` / `table` / `layer` / `domain` |
| `tableMetadataId` | long | 仅 `table` 节点，用于跳详情 |
| `datasourceId` | long | 所属数据源 |
| `storageEngine` | string | 存储引擎编码 |
| `databaseName` / `tableName` | string | 库名 / 表名 |
| `layerCode` / `domainCode` | string | 分层 / 业务域编码（规范视角） |
| `assetCount` | int | 该节点下资产数 |
| `unclassified` | boolean | 解析失败/未分类，供前端标记待治理 |
| `children` | CatalogTreeNode[] | 子节点 |

> `convention` 视角下，未解析出分层或业务域的表会归入 `__unclassified` 分组，该分组永远排在最后。
> `keyword` 过滤发生在建树前，父节点 `assetCount` 与可见子节点一致。

---

### 2. 统一检索

`GET /api/asset/search`

**入参**

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 是 | - | 为空直接返回空结果 |
| `types` | string | 否 | `table,column,indicator` | 逗号分隔，取值 `table` / `column` / `indicator` |
| `current` | int | 否 | 1 | 页码，三类各自分页 |
| `size` | int | 否 | 20 | 每页条数，夹到 `1~200` |
| `layerCode` | string | 否 | - | 分层筛选 |
| `domainCode` | string | 否 | - | 业务域筛选 |

**响应**：`AssetSearchResult`

```json
{
  "tables": [
    {
      "assetType": "table",
      "id": 101,
      "tableMetadataId": 101,
      "title": "dwd_order_detail_d",
      "subtitle": "dw.dwd_order_detail_d",
      "matchedText": "订单明细",
      "ownerName": "zhangsan",
      "layerCode": "dwd",
      "domainCode": "order"
    }
  ],
  "columns": [],
  "indicators": [],
  "total": 1
}
```

**AssetSearchHit**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `assetType` | string | `table` / `column` / `indicator` |
| `id` | long | `table`/`indicator` 为自身 id；`column` 为所属表 id |
| `tableMetadataId` | long | 命中表元数据 id |
| `title` | string | 标题 |
| `subtitle` | string | 副标题：`库.表` / `表.字段` / 指标编码 |
| `matchedText` | string | 命中片段，用于高亮 |
| `ownerName` | string | 负责人 |
| `layerCode` / `domainCode` | string | 分层 / 业务域编码 |

**口径说明**

- `current` / `size` **分别作用于三个分组**，前端三个页签各自翻页；`total` 是三类命中数之和。
- **扫描上限**：单类最多扫描 2000 条候选，命中数达到上限时 `total` 是"已扫描范围内的命中数"，前端应提示用户收窄关键词（不返回假精确总数）。
- 字段检索先按关键词取候选，再按"所属表是否可见"过滤。

---

### 3. 检索输入建议

`GET /api/asset/suggest`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 是 | 表名模糊匹配 |

**响应**：`string[]`，最多 10 条去重表名，已按权限过滤。

```json
["dwd_order_detail_d", "dwd_order_pay_di"]
```

---

### 4. 资产（表）详情

`GET /api/asset/table/{id}`

| 参数 | 位置 | 类型 | 说明 |
| --- | --- | --- | --- |
| `id` | path | long | 表元数据 id |

**响应**：`AssetTableDetail`

```json
{
  "id": 101,
  "datasourceId": 1,
  "datasourceName": "hive-prod",
  "storageEngine": "hive",
  "databaseName": "dw",
  "tableName": "dwd_order_detail_d",
  "tableType": "MANAGED_TABLE",
  "tableComment": "订单明细",
  "description": "# 表说明\n口径：...",
  "businessOwner": "zhangsan",
  "techOwner": "lisi",
  "topic": "交易",
  "visibility": "internal",
  "categoryCode": "trade.order",
  "layerCode": "dwd",
  "domainCode": "order",
  "conventionParsed": true,
  "refreshCycle": "d",
  "assetLevel": "L1",
  "rowCount": 1200000,
  "sizeBytes": 5000000000,
  "hotDays": 90,
  "coldDays": 180,
  "keepDays": 720,
  "storageFormat": "ORC",
  "compression": "SNAPPY",
  "location": "hdfs:///warehouse/dw.db/dwd_order_detail_d",
  "partitionColumns": ["dt"],
  "lastModifyTime": "2026-09-28 02:10:00",
  "syncTime": "2026-09-29 02:00:00",
  "browseCount30d": 31,
  "readCount30d": 7,
  "bookmarkCount": 3,
  "lastAccessTime": "2026-09-28 10:12:33",
  "healthIssueCount": 2,
  "bookmarked": true,
  "columns": [
    {
      "id": 1001,
      "columnName": "order_id",
      "columnType": "bigint",
      "columnComment": "订单号",
      "businessDesc": null,
      "isPartition": false,
      "isPrimaryKey": true,
      "nullable": false,
      "ordinalPosition": 1,
      "securityLevel": "L2",
      "hotScore": 5
    }
  ],
  "tags": [
    { "id": 1, "tagCode": "CORE", "tagName": "核心资产", "color": "#c62828" }
  ]
}
```

**字段说明**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `tableComment` | string | 技术侧表注释（来自元数据） |
| `description` | string | markdown 使用说明（来自 `asset_doc`，区别于 `tableComment`） |
| `categoryCode` | string | 人工覆盖的分类编码；为空表示采用表名解析结果 |
| `layerCode` | string | 有效分层：人工覆盖 `categoryCode` 优先，否则取表名解析结果 |
| `domainCode` | string | 表名解析出的业务域 |
| `conventionParsed` | boolean | 命名规范是否解析成功；`false` 时计入治理项 |
| `refreshCycle` | string | 刷新周期后缀（`d/h/w/m/y/r/f`） |
| `compression` | string | 压缩算法（口径：Hive/Kyuubi 表参数、Doris PROPERTIES、ClickHouse 列编码、HBase 列族；空 = 未采集到） |
| `healthIssueCount` | int | 该表治理项数量（非加权评分） |
| `bookmarked` | boolean | 当前登录用户是否已收藏 |
| `columns` | AssetColumn[] | 字段列表，**已按列级权限裁剪** |
| `tags` | AssetTag[] | 标签列表 |

**AssetColumn**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 字段元数据 id |
| `columnName` / `columnType` | string | 字段名 / 类型 |
| `columnComment` | string | 技术侧注释 |
| `businessDesc` | string | 业务描述（治理补充） |
| `isPartition` / `isPrimaryKey` / `nullable` | boolean | 分区 / 主键 / 可空 |
| `ordinalPosition` | int | 字段序号 |
| `securityLevel` | string | 安全等级 |
| `hotScore` | int | 字段热度星级 `0~5` |

> 资产不存在或不可见返回 `404`。

---

### 5. 使用热度

`GET /api/asset/table/{id}/usage`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | long | - | 表元数据 id |
| `days` | int | 否 | 30 | 趋势窗口天数，夹到 `1~365` |

**响应**：`AssetUsage`

```json
{
  "browseTrend": [
    { "statDate": "2026-09-16", "browseCount": 12, "readCount": 4 }
  ],
  "topColumns": [
    { "columnName": "order_id", "hotScore": 5, "hitCount": 20 }
  ],
  "topReaders": [
    { "userName": "zhangsan", "readCount": 4 }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `browseTrend` | 近 `days` 日浏览/读取趋势；**缺失日期补 0**，保证折线图不断档 |
| `topColumns` | 字段热度 TOP；`hotScore` 来自 `column_metadata.hot_score`，`hitCount` 为该字段真实使用次数 |
| `topReaders` | 高频读者 TOP（最多 10 条） |

> 热度数据由 `asset_access_log` 经 T+1 汇总到 `asset_usage_daily` 并回写。

---

### 6. 产出任务

`GET /api/asset/table/{id}/producers`

| 参数 | 位置 | 类型 | 说明 |
| --- | --- | --- | --- |
| `id` | path | long | 表元数据 id |

**响应**：`AssetProducer[]`

```json
[
  {
    "projectCode": "1",
    "workflowCode": 10001,
    "workflowName": "dwd_order_di",
    "nodeName": "dwd_order_detail_d",
    "ownerName": "zhangsan",
    "scheduleCycle": "ONLINE",
    "lastRunState": "SUCCESS",
    "lastRunTime": "2026-09-17 02:03:11"
  }
]
```

> - 实时代理调度系统（DolphinScheduler），**不在服务端落副本**，避免展示过期状态。
> - 调度系统**未配置**或调用失败时返回**空列表**（不报错、不返回占位数据），前端渲染空态。
> - 单次上游请求超时 2s 连接 / 5s 读取，检索结果上限 50。

---

### 7. 关联指标

`GET /api/asset/table/{id}/indicators`

| 参数 | 位置 | 类型 | 说明 |
| --- | --- | --- | --- |
| `id` | path | long | 表元数据 id |

**响应**：`AssetIndicator[]`

```json
[
  {
    "id": 501,
    "code": "IND_ORDER_AMT",
    "name": "订单金额",
    "topic": "交易",
    "businessOwner": "zhangsan",
    "fieldName": "order_id"
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `fieldName` | 该指标在此表上引用的字段名 |

> - 匹配口径为 **库名 + 表名**（数据源名作补充，历史数据可能为空）。
> - 一个指标引用多个字段时**展开成多行**，前端每条可点回字段。
> - 指标按项目授权过滤，无权限的指标不返回。

---

### 8. 质量规则摘要

`GET /api/asset/table/{id}/quality`

| 参数 | 位置 | 类型 | 说明 |
| --- | --- | --- | --- |
| `id` | path | long | 表元数据 id |

**响应**：`AssetQualitySummary`

```json
{
  "ruleCount": 6,
  "enabledRuleCount": 5,
  "lastRunStatus": "FAILED",
  "lastRunTime": "2026-09-17 02:10:00",
  "errorCount": 1,
  "failCount": 0,
  "problemRuleCount": 1
}
```

| 字段 | 说明 |
| --- | --- |
| `ruleCount` | 匹配到的规则总数 |
| `enabledRuleCount` | 启用规则数 |
| `lastRunStatus` / `lastRunTime` | 最近一次运行状态 / 时间；无运行记录时为空字符串 |
| `errorCount` | 状态为 `ERROR` 的结果数 |
| `failCount` | 状态为 `FAILED` 的结果数 |
| `problemRuleCount` | 去重后的未通过规则条数 |

> 规则按「库 + 表」匹配；数据源已绑定的规则再按数据源筛一次，避免同名库表串味。

---

### 9. 数据预览

`GET /api/asset/table/{id}/preview`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | long | - | 表元数据 id |
| `limit` | int | 否 | 20 | 行数上限，夹到 `1~200` |
| `partition` | string | 否 | - | 分区条件，格式 `dt=${yyyyMMdd}` |

**响应**：`AssetPreview`

```json
{
  "columns": ["order_id", "amt"],
  "rows": [
    ["1001", "88.50"],
    ["1002", null]
  ]
}
```

> - 预览会**真实下发查询**，经执行服务，行级过滤与列白名单在**引擎侧**生效；返回前再做列交集与按脱敏规则处理取值。
> - 表未关联数据源、数据源不支持 SQL、或无权限时返回业务异常。
> - 执行失败的原因**如实回传**，不伪装成空结果。
> - 单元格为字符串，数据库 `NULL` 保留为 `null`。

---

### 10. 使用说明

`GET /api/asset/table/{id}/doc`

**响应**：`AssetDoc`

```json
{
  "content": "# 表说明",
  "updateTime": "2026-09-17 10:00:00",
  "updateBy": "zhangsan"
}
```

### 11. 使用说明历史版本

`GET /api/asset/table/{id}/doc/history`

**响应**：`AssetDocHistory[]`

```json
[
  { "version": 2, "updateTime": "2026-09-17 09:00:00", "updateBy": "zhangsan" }
]
```

> 历史列表**只返回版本元信息，不返回各版本正文**，避免响应体随文档长度膨胀。

---

### 12. 收藏列表

`GET /api/asset/bookmark`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `current` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 20 | 每页条数 |

**响应**：MyBatis-Plus 分页结构 `Page<AssetSearchHit>`

```json
{
  "records": [
    {
      "assetType": "table",
      "id": 101,
      "tableMetadataId": 101,
      "title": "dwd_order_detail_d",
      "subtitle": "dw.dwd_order_detail_d",
      "matchedText": null,
      "ownerName": "zhangsan",
      "layerCode": "dwd",
      "domainCode": "order"
    }
  ],
  "total": 1,
  "size": 20,
  "current": 1
}
```

---

### 13. 标签字典

`GET /api/asset/tag`

**响应**：`AssetTag[]`

```json
[
  { "id": 1, "tagCode": "CORE", "tagName": "核心资产", "color": "#c62828" }
]
```

### 14. 分类字典

`GET /api/asset/category`

**响应**：`AssetCategory[]`（分层 + 业务域）

```json
[
  { "code": "trade.order", "name": "交易-订单", "parentCode": "trade", "categoryKind": "domain" }
]
```

| 字段 | 说明 |
| --- | --- |
| `categoryKind` | `layer`=数仓分层；`domain`=业务域 |
| `parentCode` | 父级分类编码 |

---

### 15. 资产盘点概览

`GET /api/asset/inventory/overview`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `projectCode` | string | 否 | 按项目收窄；不传则全量 |

**响应**：`AssetInventory`

```json
{
  "totalTables": 120,
  "totalSizeBytes": 5000000000,
  "totalColumns": 2400,
  "unclassifiedCount": 7,
  "byLayer": [
    { "layerCode": "dwd", "tableCount": 40, "sizeBytes": 1000000000 }
  ],
  "byDomain": [
    { "domainCode": "order", "tableCount": 15, "sizeBytes": 400000000 }
  ],
  "byDatasource": [
    { "datasourceId": 1, "datasourceName": "hive-prod", "tableCount": 120 }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `totalTables` / `totalColumns` / `totalSizeBytes` | 表数 / 字段总数 / 存储总量（字节） |
| `unclassifiedCount` | 命名规范解析失败的表数 |
| `byLayer` / `byDomain` / `byDatasource` | 按分层 / 业务域 / 数据源统计，按表数倒序 |

**`projectCode` 口径（重要）**：平台目前**没有项目↔资产绑定表**，传入 `projectCode` 时盘点范围被收窄为"该项目默认数据源（`dev_project.default_datasource_id`）下的资产"。这是明确的近似，不是完整项目资产台账。项目不存在或未配置默认数据源会返回业务异常（不静默放宽范围）。

---

### 16. 治理项清单

`GET /api/asset/inventory/health`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `projectCode` | string | 否 | 同盘点概览口径 |

**响应**：`AssetHealthIssue[]`（固定 8 项，顺序稳定）

```json
[
  {
    "issueCode": "NO_TABLE_COMMENT",
    "issueName": "缺少表注释",
    "count": 12,
    "impact": "共 12 张表（占纳管资产 10.0%）；检索与目录只显示表名，使用者无法判断这张表能不能用",
    "samples": [
      { "tableMetadataId": 101, "databaseName": "dw", "tableName": "dwd_order_detail_d" }
    ]
  }
]
```

**治理项编码**

| issueCode | issueName |
| --- | --- |
| `NAMING_UNCONFORMED` | 表名不符合命名规范 |
| `NO_BUSINESS_OWNER` | 未标注业务负责人 |
| `NO_TABLE_COMMENT` | 缺少表注释 |
| `NO_COLUMN_COMMENT` | 存在无注释字段 |
| `NO_DOC` | 缺少使用说明 |
| `NO_QUALITY_RULE` | 未配置质量规则 |
| `NO_TAG` | 未打标签 |
| `NO_ACCESS_30D` | 近 30 日无访问 |

| 字段 | 说明 |
| --- | --- |
| `count` | 命中该治理项的表数 |
| `impact` | 影响面描述（含数量、占比与该项含义） |
| `samples` | 样例对象，最多 5 条，便于直接跳转处理 |

---

## 四、资产概况看板接口

对应前端「资产管理 → 资产概况」页签（`AssetOverviewTab.tsx`）。五块看板各自独立探测，互不影响。

### 17. 按环境与项目的资产规模

`GET /api/asset/inventory/env-project`

**入参**：无。

**响应**：`AssetEnvProjectStat[]`

```json
[
  { "env": "prod", "projectCode": "olist", "projectName": "Olist 数仓", "tableCount": 128, "sizeBytes": 5368709120 }
]
```

| 字段 | 说明 |
| --- | --- |
| `env` | `prod` / `gray` / `test` |
| `projectCode` | 项目编码；未归属任何项目时为空串 |
| `projectName` | 项目显示名；未归属任何项目时为「未归属项目」 |
| `tableCount` | 该环境 × 项目下的表数 |
| `sizeBytes` | 占用空间（字节） |

**口径（重要）**

- **环境由库名解析**：库名规范 `{分层}_{业务域}_{环境}`，取最后一段，仅认 `_prod / _gray / _test`（大小写不敏感）。**无环境后缀的库不计入本看板**——不猜测环境，避免生产库被误判为测试库。因此本看板合计可能小于 `/inventory/overview` 的总表数。
- **项目归属为近似**：平台**没有项目↔资产绑定表**，只能按 `dev_project.default_datasource_id` 间接归属；未命中任何项目的资产归入「未归属项目」。多个项目共用同一默认数据源时取先出现的一个。
- 排序：环境固定 `prod → gray → test`，同环境内按表数倒序。
- 权限：与其余资产接口一致，服务端按当前用户表级 SELECT 权限过滤。
- 环境汇总卡（生产/灰度/测试三张 + 合计）由**前端就地聚合**，服务端不另开汇总接口。

### 18. 访问频率 / 占用空间 TOP 资产

`GET /api/asset/inventory/top-assets`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `sortBy` | enum | 否 | `access` | `access`=近 30 天访问次数；`size`=占用空间。非法值按 `access` 处理 |
| `limit` | int | 否 | 100 | 条数上限，硬上限 500 |

**响应**：`AssetTopAsset[]`

```json
[
  {
    "tableMetadataId": 1001,
    "databaseName": "dwd_trade_prod",
    "tableName": "dwd_trade_order_d",
    "env": "prod",
    "projectCode": "olist",
    "projectName": "Olist 数仓",
    "accessCount30d": 3281,
    "sizeBytes": 21474836480,
    "ownerName": "zhangsan"
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `accessCount30d` | 近 30 天访问次数 = 浏览 + 读取（`browse_cnt_30d + read_cnt_30d`，含 query 计入读取） |
| `ownerName` | 业务负责人；无为空串 |
| 其余字段 | 同第 17 接口 |

**口径**：**排序与截断都在服务端完成**，前端不做全量拉取后本地排序。环境与项目口径同第 17 接口（无环境后缀的库同样不计入）。

### 19. 数据大小增长率突增表

`GET /api/asset/inventory/size-growth`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `growthPercent` | int | 否 | 10 | 增长率阈值（百分比） |
| `minSizeBytes` | long | 否 | 10737418240（10GB） | 当前大小下限（字节） |

**响应**：`AssetSizeGrowth[]`（按增长率降序）

```json
[
  {
    "tableMetadataId": 1002,
    "databaseName": "dwd_trade_prod",
    "tableName": "dwd_trade_log_d",
    "env": "prod",
    "projectName": "Olist 数仓",
    "previousSizeBytes": 17179869184,
    "currentSizeBytes": 32212254720,
    "growthBytes": 15032385536,
    "growthRate": 0.875
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `previousSizeBytes` / `currentSizeBytes` | 上期 / 本期大小（字节） |
| `growthBytes` | 增长量 = 本期 − 上期 |
| `growthRate` | 增长率 0~1 小数 = `(current − previous) / previous` |

**口径（重要）**

- 上期与本期**都取自资产目录日快照** `asset_catalog_daily`（同一套采集口径，不拿实时值与历史值比较）：本期 = 最新快照日，上期 = 30 天前的最近一个快照日。
- **历史不足 30 天**时，上期退到最早的快照日（增长率因此会小于「30 天累计增长」的直觉值，前端提示语已标注筛选口径）。
- **只有一天快照时无法比较，返回空数组**。
- 筛选与排序在服务端完成：`growthRate > growthPercent` 且 `currentSizeBytes > minSizeBytes`，按 `growthRate` 降序。上期大小为 0 的表跳过（增长率无定义）。
- 环境口径同第 17 接口。

### 20. 消耗计算资源 / 运行时长 TOP 任务

`GET /api/asset/inventory/top-compute`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `sortBy` | enum | 否 | `resource` | `resource`=按消耗计算资源；`duration`=按运行时长。非法值按 `resource` 处理 |
| `limit` | int | 否 | 50 | 条数上限，硬上限 500 |

**响应**：`AssetTopComputeTask[]`

```json
[
  {
    "workflowName": "ads_olist_daily",
    "nodeName": "spark_ads_trade",
    "taskType": "SPARK",
    "projectName": "Olist 数仓",
    "cpuHours": null,
    "memoryGbHours": null,
    "runDurationSeconds": 111600,
    "execCount": 30
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `workflowName` / `nodeName` | 工作流与节点名 |
| `taskType` | 任务类型（SPARK / HIVE / FLINK…） |
| `projectName` | 调度项目名 |
| `cpuHours` / `memoryGbHours` | **恒为 `null`**（见下方口径） |
| `runDurationSeconds` | 统计窗口内**累计**运行时长（秒），非单次 |
| `execCount` | 统计窗口内执行次数 |

**口径（重要）**

- 数据来自调度运行记录的**日聚合表**（每日 03:30 采集），非实时；统计窗口为**近 30 天**，按「项目 + 工作流 + 节点」聚合。
- **`cpuHours` / `memoryGbHours` 恒为 `null`**：这两项只有 YARN 能提供（由 `allocatedMB` / `allocatedVCores` 与运行时长换算）。本平台 Spark 以 `local[*]` 模式运行、**没有 YARN**，故无数据源。刻意不填 `0`——`0` 会被读成「不消耗资源」。
  > 前端现状：`row.cpuHours ?? '—'` 会渲染为「—」，不会报错；建议把 TS 类型由 `number` 放宽为 `number | null` 以反映真实契约。
- 因此 `sortBy` 的 `resource` 与 `duration` **当前都按「窗口内累计运行时长」降序**（`resource` 维度降级）。前端「按计算资源」的文案建议标注为「按时长（资源指标待接入）」。
- 权限：按当前用户可见的**调度项目**过滤（与指标的项目授权口径一致）。

### 21. 任务延迟率

`GET /api/asset/inventory/task-delay`

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `days` | int | 否 | 30 | 统计窗口天数，夹到 `1~365` |

**响应**：`AssetTaskDelay`

```json
{
  "totalTasks": 1200,
  "delayedTasks": 156,
  "delayRate": 0.13,
  "windowDays": 30,
  "byQueue": []
}
```

| 字段 | 说明 |
| --- | --- |
| `totalTasks` | 统计窗口内任务实例总数 |
| `delayedTasks` | 判定为延迟的实例数 |
| `delayRate` | 延迟率 0~1 小数 = `delayedTasks / totalTasks`（无数据时为 0） |
| `windowDays` | 统计窗口天数（回显用） |
| `byQueue` | **恒为空数组**（见下方口径） |

**口径（重要）**

- **延迟定义**：任务实际开始时间晚于其所属流程实例的计划开始时间（DolphinScheduler 的 `scheduleTime`）。计划时间取自流程实例，因此**跨流程实例的延迟判定依赖同窗口内取到该实例**。
- **`byQueue` 恒为空数组**：按队列拆分需要 YARN 队列维度。本平台没有 YARN，故无数据源。前端的队列表在 `byQueue` 为空时不渲染，整体延迟率不受影响。
- 窗口按统计日计算，数据为 T+1；权限口径同第 20 接口。

### 生命周期修改（写）

`PUT /api/asset/table/{id}/lifecycle`

**入参**（三个字段均必填，整体覆盖写）

```json
{ "hotDays": 90, "coldDays": 180, "keepDays": 720 }
```

**校验**：`hotDays ≤ coldDays ≤ keepDays` 且均为正整数，不满足返回业务异常；写前做表级 SELECT 权限校验（同其余治理写接口）。

**响应**：`Result<Void>`（`data` 为 `null`）。

> 三个字段整体覆盖而非增量提交：它们是有序三元组，分开增量提交会短暂出现非法组合。写入 `table_metadata` 既有列，不产生副本。

---

## 五、调度运行数据采集与配置

资产概况 ③④ 两个看板的数据由采集链路落库，本节说明其配置、上游接口与落库结构。

### 5.1 采集任务

| 项 | 说明 |
| --- | --- |
| 任务 | `SchedulerTaskMetricsCollectTask` |
| 频率 | 每日 **03:30**（排在热度汇总 01:30、审计分区 02:00、目录快照 02:30 之后） |
| 窗口 | 重拉**近 30 天**并整体覆盖写 → 幂等、漏跑可自愈，无需补偿脚本 |
| 保留 | 180 天，超期随采集一并清理 |
| 落库 | `scheduler_task_run_daily`（粒度：统计日 × 项目 × 工作流 × 节点 × 任务类型） |
| 失败策略 | 调度系统未配置 → 静默跳过；单个项目失败 → 跳过该项目并告警；**全部项目失败 → 保留上一轮结果，不用空数据覆盖** |

### 5.2 配置项

| 配置 | 说明 | 默认 |
| --- | --- | --- |
| `scheduler.dolphinscheduler.base-url` | 调度系统地址；客户端在其后拼接 `/dolphinscheduler/...`。**为空表示不采集**（接口返回空数据，不报错） | 空 |
| `scheduler.dolphinscheduler.token` | 访问令牌，写入请求头 `token` | 空 |
| `scheduler.dolphinscheduler.default-project-code` | 项目列表拉取失败时的兜底项目编码 | `1` |

> 与 `/asset/table/{id}/producers` 共用同一组配置：前者实时反查单表产出工作流，本链路批量采集运行记录落库。

### 5.3 上游接口（只读）

| 用途 | 路径 |
| --- | --- |
| 项目列表 | `GET {base}/dolphinscheduler/projects` |
| 流程实例（取计划开始时间与工作流归属） | `GET {base}/dolphinscheduler/projects/{projectCode}/process-instances` |
| 任务实例（取节点、类型、状态、开始/结束时间） | `GET {base}/dolphinscheduler/projects/{projectCode}/task-instances` |

分页 `pageNo/pageSize`，单项目最多 50 页（1 万条），达上限打 WARN。窗口过滤在本地做（服务端日期参数各版本支持不一致），时间戳按 `yyyy-MM-dd HH:mm:ss` 解析。
运行时长由 `endTime - startTime` 自行换算，**不解析 DS 的 `duration` 字段**（各版本格式不一致）。

### 5.4 落库字段要点

`stat_date`（任务实际开始日）、`project_code/name`、`workflow_code/name`、`node_name`、`task_type`、
`task_run_count`、`delayed_count`、`success_count`、`failed_count`、`total_duration_seconds`；
唯一键 `(stat_date, project_code, workflow_name, node_name, task_type)`。

### 5.5 能力边界：无 YARN

本平台部署为 DolphinScheduler 3.4.1 standalone + Spark `local[*]`，**没有 YARN / ResourceManager**。因此：

| 指标 | 现状 | 补齐条件 |
| --- | --- | --- |
| `cpuHours` / `memoryGbHours` | 恒为 `null` | 接入 YARN RM（`/ws/v1/cluster/apps` 的 `allocatedMB`、`allocatedVCores` × 时长） |
| `byQueue` | 恒为空数组 | 同上（YARN 队列维度） |

> 这两项**不伪造、不填 0**：`0` 会被读成「不消耗资源」。补齐时只需在 `SchedulerTaskMetricsService`
> 增加 YARN 采集与两个字段的填充，接口契约与落库表无需变更（表已预留扩展位）。

---

## 六、典型调用流程

**找数 → 认数 → 用数**

```
1. GET /api/asset/catalog/tree?view=convention      # 按分层/业务域浏览
2. GET /api/asset/search?keyword=order&types=table,column,indicator
3. GET /api/asset/table/101                          # 认数：负责人、口径、字段、标签
4. GET /api/asset/table/101/doc                      # 细读使用说明
5. GET /api/asset/table/101/quality                  # 质量是否可信
6. GET /api/asset/table/101/preview?limit=20         # 看真实数据（受行/列权限与脱敏约束）
7. GET /api/asset/table/101/usage?days=30            # 热度与谁在用
8. GET /api/asset/table/101/producers                # 谁产出、是否正常调度
```

**治理视角**

```
GET /api/asset/inventory/overview            # 一共多少资产、分布在哪儿
GET /api/asset/inventory/health              # 哪些需要治理，按项看样例
```

---

## 七、任务手动触发接口（运维）

三个每日采集/汇总任务支持手动跑一次，与定时任务**共用同一执行入口**（`runOnce`），口径一致、幂等可重放。
权限 `SUPER_ADMIN`；统计日缺省为**昨天**（T+1 口径），显式传参可补算历史日期，**未来日期直接拒绝**。

| 接口 | 任务 | 定时 | 返回 |
| --- | --- | --- | --- |
| `POST /api/asset/task/catalog-snapshot/run?statDate=yyyy-MM-dd` | 目录快照（生成 + 超期清理） | 02:30 | `{statDate, tableCount, purged}` |
| `POST /api/asset/task/usage-rollup/run?statDate=yyyy-MM-dd` | 使用热度汇总（聚合 + 冗余列回写） | 01:30 | `{statDate, assetCount, hotUpdated, tablesUpdated}` |
| `POST /api/asset/task/scheduler-metrics/run?anchorDate=yyyy-MM-dd` | 调度指标采集（重拉近 30 天） | 03:30 | `{anchorDate, projectCount, taskInstanceCount, rowCount, skippedReason}` |

> - `skippedReason` 非空表示调度系统未配置，属预期跳过而非失败。
> - 存储大小刷新另有运维入口：`POST /api/devops/datasource/{id}/refresh-table-size`（单数据源，返回更新表数）
>   与 `POST /api/devops/datasource/refresh-table-size`（全部数据源，返回 `{datasourceCount, updatedTables, failedDatasources}`），
>   权限同为 `SUPER_ADMIN`，与每日 02:15 的刷新任务同一口径。
