package com.bigdata.modules.telemetry.model;

/** 能力缺失时需要补的东西：配置项、接口、插件、部署或权限。 */
public enum RequirementKind {
    CONFIG,
    API,
    PLUGIN,
    DEPLOYMENT,
    PERMISSION,

    // ---- 服务版本 / 配置查询新增 ----
    /** 需要实现并注册 SPI（如 ServiceMetadataProvider）。 */
    IMPLEMENTATION,
    /** 需要凭据（账号 / token / keytab）。 */
    CREDENTIAL,
    /** 需要修正查询目标（集群 / 服务 / 主机不存在）。 */
    TARGET
}
