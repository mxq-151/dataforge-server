package com.bigdata.modules.telemetry.service.metadata.spi;

import java.util.List;
import java.util.Set;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;

/**
 * 作业目标发现 provider：只读引擎 API 的作业/应用清单，不承载指标或配置。
 *
 * <p>provider 内部完成精确 / 模糊匹配；多条命中原样返回，由编排器判定歧义，
 * 不允许 provider 自行挑一条。</p>
 */
public interface TargetDiscoveryProvider {

    String providerId();

    Set<String> serviceTypes();

    /** 空集合表示覆盖该 serviceType 的全部组件。 */
    Set<String> componentIds();

    int order();

    boolean enabled();

    List<RawTarget> discover(TargetDiscoveryQuery query);
}
