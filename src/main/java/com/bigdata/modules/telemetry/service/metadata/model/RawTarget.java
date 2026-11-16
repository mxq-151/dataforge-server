package com.bigdata.modules.telemetry.service.metadata.model;

import java.util.Map;

/** provider 返回的一条原始作业目标；只放引擎原值，状态不做推断。 */
public record RawTarget(String serviceType,
                        String componentId,
                        Map<String, String> ref,
                        String name,
                        String state) {

    public RawTarget {
        ref = ref == null ? Map.of() : Map.copyOf(ref);
    }
}
