package com.bigdata.modules.telemetry.common.model;

/**
 * 能力补齐项。{@code key} 必须是真实配置键或拟增接口，调用方可直接据此排障。
 */
public record Requirement(RequirementKind kind, String key, String description) {
}
