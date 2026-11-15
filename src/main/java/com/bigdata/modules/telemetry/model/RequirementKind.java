package com.bigdata.modules.telemetry.model;

/** 能力缺失时需要补的东西：配置项、接口、插件、部署或权限。 */
public enum RequirementKind {
    CONFIG,
    API,
    PLUGIN,
    DEPLOYMENT,
    PERMISSION
}
