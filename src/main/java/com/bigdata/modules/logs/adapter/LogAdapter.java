package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogArtifact;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.ServiceType;

import java.util.List;

/**
 * 日志适配器：一种服务 + 一种日志种类 + 一组角色对应一个实现。
 *
 * <p>新增服务时只新增实现类并注册为 Spring Bean，{@link LogAdapterRegistry} 自动发现，
 * 查询编排（{@code LogQueryService}）与 Controller 不需要改动。</p>
 */
public interface LogAdapter {

    boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role);

    AdapterResult query(LogQueryContext context);

    default boolean supportsArtifacts() {
        return false;
    }

    default List<LogArtifact> listArtifacts(LogQueryContext context) {
        throw new LogAdapterException(LogAdapterException.Code.UNSUPPORTED, "该日志源不支持列举产物");
    }

    default byte[] fetchArtifact(LogQueryContext context, String artifactPath) {
        throw new LogAdapterException(LogAdapterException.Code.UNSUPPORTED, "该日志源不支持下载产物");
    }
}
