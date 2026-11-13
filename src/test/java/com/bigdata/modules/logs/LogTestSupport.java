package com.bigdata.modules.logs;

import com.bigdata.modules.logs.adapter.LogQueryContext;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;

import java.util.List;
import java.util.Map;

/** 日志模块单测公共构造器（不启动 Spring、不连外部系统）。 */
public final class LogTestSupport {

    private LogTestSupport() {
    }

    public static LogAccessProperties properties() {
        LogAccessProperties properties = new LogAccessProperties();
        properties.getCache().setEnabled(false);
        properties.getQuery().setTimeoutMs(2000L);
        properties.getExecutor().setCorePoolSize(1);
        properties.getExecutor().setMaxPoolSize(2);
        properties.getExecutor().setQueueCapacity(8);
        return properties;
    }

    public static LogAccessProperties.Source sourceConfig(String id, String serviceType, String baseUrl) {
        LogAccessProperties.Source config = new LogAccessProperties.Source();
        config.setId(id);
        config.setName(id);
        config.setServiceType(serviceType);
        config.setCluster("default");
        config.setEnabled(true);
        config.setBaseUrl(baseUrl);
        return config;
    }

    public static LogSource source(LogAccessProperties.Source config, ServiceType type) {
        return new LogSource(config, type, List.of(ComponentRole.GENERIC), List.of(),
                config.getBaseUrl(), "", true, "");
    }

    public static LogSource disabledSource(LogAccessProperties.Source config, ServiceType type,
                                           String reason) {
        return new LogSource(config, type, List.of(ComponentRole.GENERIC), List.of(),
                "", "", false, reason);
    }

    public static LogQueryContext context(LogSource source, int maxLines, int maxBytes,
                                          Map<String, String> ref) {
        LogQueryContext context = new LogQueryContext();
        context.setSource(source);
        context.setLogKind(LogKind.RUNTIME);
        context.setRole(ComponentRole.GENERIC);
        context.setRef(ref);
        context.setMaxLines(maxLines);
        context.setMaxBytes(maxBytes);
        context.setTimeoutMs(2000L);
        return context;
    }
}
