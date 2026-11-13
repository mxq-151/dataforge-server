package com.bigdata.modules.runtime;

import com.bigdata.modules.logs.adapter.HttpLogClient;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.adapter.FlinkRuntimeAdapter;
import com.bigdata.modules.runtime.adapter.RuntimeQueryContext;
import com.bigdata.modules.runtime.adapter.RuntimeRestClient;
import com.bigdata.modules.runtime.adapter.SparkRuntimeAdapter;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.model.MetricFamily;
import com.bigdata.modules.runtime.model.RuntimeMode;
import com.bigdata.modules.runtime.model.RuntimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** runtime 模块单测公共构造器（不启动 Spring、不连外部系统） */
public final class RuntimeTestSupport {

    private RuntimeTestSupport() {
    }

    public static RuntimeProperties properties() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getCache().setEnabled(false);
        properties.getQuery().setTimeoutMs(2000L);
        return properties;
    }

    public static RuntimeProperties.Source sourceConfig(String id, String type, String mode,
                                                        String baseUrl) {
        RuntimeProperties.Source config = new RuntimeProperties.Source();
        config.setId(id);
        config.setName(id);
        config.setServiceType(type);
        config.setCluster("default");
        config.setMode(mode);
        config.setEnabled(true);
        config.setBaseUrl(baseUrl);
        return config;
    }

    public static RuntimeSource source(RuntimeProperties.Source config, ServiceType type,
                                       RuntimeMode mode) {
        return new RuntimeSource(config, type, mode, List.of(), config.getBaseUrl(), "",
                true, "");
    }

    public static RuntimeQueryContext context(RuntimeSource source, String jobId,
                                              MetricFamily... families) {
        RuntimeQueryContext context = new RuntimeQueryContext();
        context.setSource(source);
        context.setJobId(jobId);
        context.setFamilies(List.of(families));
        context.setIncludeComponents(true);
        context.setIncludeVertices(true);
        context.setMaxComponents(50);
        context.setMaxVertices(10);
        context.setCheckpointHistory(5);
        context.setTimeoutMs(2000L);
        return context;
    }

    public static FlinkRuntimeAdapter flink(HttpLogClient http) {
        return new FlinkRuntimeAdapter(new RuntimeRestClient(http, new ObjectMapper()));
    }

    public static SparkRuntimeAdapter spark(HttpLogClient http) {
        return new SparkRuntimeAdapter(new RuntimeRestClient(http, new ObjectMapper()));
    }
}
