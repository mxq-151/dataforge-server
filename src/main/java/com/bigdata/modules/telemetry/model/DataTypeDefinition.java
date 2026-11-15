package com.bigdata.modules.telemetry.model;

import java.util.List;

/**
 * 数据类型的目录定义。实现状态以服务端为准，调用方据此判断是否值得发起查询。
 */
public record DataTypeDefinition(String dataTypeId,
                                 String label,
                                 DataTypeStatus status,
                                 String payloadSchema,
                                 List<String> transports) {

    public boolean implemented() {
        return status == DataTypeStatus.IMPLEMENTED;
    }
}
