package com.bigdata.modules.telemetry.v1.runtime.model;

import java.util.List;

/**
 * 两份进程内快照的差值。
 *
 * <p>{@code reason} 只在 {@code comparable=false} 时存在，取值：
 * {@code SAMPLE_INSUFFICIENT}（样本不足）、{@code JOB_MISMATCH}（作业不同）、
 * {@code TIME_WINDOW_EXCEEDED}（时间窗超限）。</p>
 */
public record CompareData(boolean comparable,
                          String reason,
                          long windowSeconds,
                          Long fromCapturedAt,
                          Long toCapturedAt,
                          List<Row> rows,
                          List<String> warnings) {

    public record Row(String component,
                      String componentType,
                      String family,
                      String name,
                      String unit,
                      double from,
                      double to,
                      double delta,
                      double perSecond) {
    }
}
