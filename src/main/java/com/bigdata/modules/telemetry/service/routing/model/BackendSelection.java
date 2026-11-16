package com.bigdata.modules.telemetry.service.routing.model;

import java.util.List;

/** 选择结果：按最终顺序排好的候选，加上选择阶段被跳过的候选。 */
public record BackendSelection(List<BackendCandidate> candidates,
                               List<BackendSkip> skipped,
                               String preferred) {

    public BackendSelection {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
}
