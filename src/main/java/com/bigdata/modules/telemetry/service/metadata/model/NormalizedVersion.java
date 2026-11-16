package com.bigdata.modules.telemetry.service.metadata.model;

/**
 * 版本号标准化结果。
 *
 * <p>只有能取到连续三段数字 {@code major.minor.patch} 时才输出；解析失败时整体为 null，
 * 由 {@code VersionView.reason} 说明原因，绝不猜测。</p>
 */
public record NormalizedVersion(String semver,
                                Integer major,
                                Integer minor,
                                Integer patch,
                                String prerelease,
                                String build) {
}
