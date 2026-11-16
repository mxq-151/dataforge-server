package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标名称匹配：精确优先，精确为空时按 matchMode 决定是否模糊。
 *
 * <p>这里只做过滤、不做截断：命中多条要交给调用方报歧义，先截断会把歧义伪装成唯一命中，
 * 也会让「结果被截断」这件事在上层不可见。</p>
 */
public final class TargetDiscoverySupport {

    private TargetDiscoverySupport() {
    }

    public static List<RawTarget> filter(List<RawTarget> targets, TargetDiscoveryQuery query) {
        List<RawTarget> all = targets == null ? List.of() : targets;
        String taskName = query.taskName();
        if (taskName == null || taskName.isBlank()) {
            return all;
        }
        String wanted = taskName.trim();
        List<RawTarget> exact = new ArrayList<>();
        for (RawTarget target : all) {
            if (wanted.equals(target.name())) {
                exact.add(target);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        if (!query.allowsFuzzy()) {
            return List.of();
        }
        String lower = wanted.toLowerCase();
        List<RawTarget> fuzzy = new ArrayList<>();
        for (RawTarget target : all) {
            String name = target.name();
            if (name != null && name.toLowerCase().contains(lower)) {
                fuzzy.add(target);
            }
        }
        return fuzzy;
    }
}
