package com.bigdata.modules.telemetry.provider;

import com.bigdata.modules.telemetry.model.Capability;
import com.bigdata.modules.telemetry.model.CapabilityCode;

import java.util.Map;

/**
 * 可插拔取数实现。
 *
 * <p>用 Spring Bean 注册（{@code @Component}/{@code @Bean}），新增服务或数据类型只新增实现，
 * 不改 Controller。选 Spring 而非 Java SPI 的原因：实现需要注入配置、脱敏、熔断、RBAC，
 * SPI 的 ServiceLoader 拿不到容器能力。</p>
 */
public interface TelemetryProvider {

    /** 全局唯一，重复会在启动期失败。 */
    String providerId();

    /** 是否声明支持该四元组。 */
    boolean supports(String serviceType, String componentId, String dataTypeId);

    /** 结构化可用性判定；不可用时必须给出 code 与 requirements。 */
    Capability evaluate(CapabilityContext context);

    /**
     * 取数。实现方只能返回真实数据；失败要抛 {@link TelemetryQueryException}，
     * 不要用 0 值或空数组冒充。
     */
    default Map<String, Object> fetch(TelemetryQueryContext context) {
        throw new TelemetryQueryException(CapabilityCode.NO_PROVIDER, "该实现未提供取数能力");
    }

    /** 数值越小优先级越高。 */
    default int order() {
        return 100;
    }
}
