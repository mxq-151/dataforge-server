package com.bigdata.modules.telemetry.v1.runtime.spi;

import com.bigdata.modules.telemetry.v1.model.BackendType;
import com.bigdata.modules.telemetry.v1.runtime.model.ConfigData;
import com.bigdata.modules.telemetry.v1.runtime.model.DiscoveryData;
import com.bigdata.modules.telemetry.v1.runtime.model.RuntimeComponent;
import com.bigdata.modules.telemetry.v1.runtime.model.SnapshotData;

import java.util.List;

/**
 * 运行时引擎 SPI：只读的作业发现 / 组件 / 快照 / 配置。
 *
 * <p>实现必须直接访问引擎 REST 端点；取不到的指标返回 UNAVAILABLE / NOT_SUPPORTED 并带 reason。</p>
 */
public interface RuntimeEngine {

    String backendId();

    BackendType backendType();

    String serviceType();

    /** live / history。 */
    String mode();

    boolean configured();

    /** 未配置时给出具体原因（缺 base-url、认证解析失败等）。 */
    String unconfiguredReason();

    List<DiscoveryData.JobView> jobs(RuntimeQuerySpec spec);

    List<RuntimeComponent> components(RuntimeQuerySpec spec);

    SnapshotData snapshot(RuntimeQuerySpec spec);

    ConfigData config(RuntimeQuerySpec spec);
}
