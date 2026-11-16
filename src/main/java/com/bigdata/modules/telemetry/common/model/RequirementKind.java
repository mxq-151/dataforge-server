package com.bigdata.modules.telemetry.common.model;

import com.bigdata.modules.telemetry.service.metadata.spi.ServiceMetadataProvider;

/** 能力缺失时需要补的东西：配置项、接口、实现、凭据或查询目标。 */
public enum RequirementKind {
    CONFIG,
    API,

    // ---- 服务版本 / 配置查询新增 ----
    /** 需要实现并注册 SPI（如 ServiceMetadataProvider）。 */
    IMPLEMENTATION,
    /** 需要凭据（账号 / token / keytab）。 */
    CREDENTIAL,
    /** 需要修正查询目标（集群 / 服务 / 主机不存在）。 */
    TARGET
}
