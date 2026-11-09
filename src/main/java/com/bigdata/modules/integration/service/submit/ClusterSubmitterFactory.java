package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.enums.ClusterType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按集群类型路由到对应的提交适配器。
 * 新增集群类型时由 Spring 自动注入所有 {@link DataIntegrationSubmitter} 实现，无需改这里。
 */
@Component
public class ClusterSubmitterFactory {

    private final Map<ClusterType, DataIntegrationSubmitter> submitters = new EnumMap<>(ClusterType.class);

    public ClusterSubmitterFactory(List<DataIntegrationSubmitter> submitterList) {
        for (DataIntegrationSubmitter submitter : submitterList) {
            submitters.put(submitter.clusterType(), submitter);
        }
    }

    public DataIntegrationSubmitter get(String clusterTypeCode) {
        ClusterType type = ClusterType.fromCode(clusterTypeCode);
        if (type == null) {
            throw new BusinessException(400, "不支持的运行集群类型: " + clusterTypeCode);
        }
        DataIntegrationSubmitter submitter = submitters.get(type);
        if (submitter == null) {
            throw new BusinessException(500, "集群适配器未注册: " + clusterTypeCode);
        }
        return submitter;
    }
}
