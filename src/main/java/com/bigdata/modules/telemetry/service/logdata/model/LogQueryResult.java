package com.bigdata.modules.telemetry.service.logdata.model;

import com.bigdata.modules.telemetry.common.model.BackendWarning;

import java.util.List;

/**
 * 统一的日志结果。
 *
 * <p>{@code scan} 明确告诉调用方本次扫描了多少文件/字节/行：本地磁盘的时间范围扫描不是全量，
 * 结果可能因预算截断。</p>
 */
public record LogQueryResult(List<LogEntry> entries,
                             String nextCursor,
                             boolean truncated,
                             ScanStats scan,
                             List<BackendWarning> warnings) {

    public record ScanStats(int files, long bytes, long lines, int matched) {
    }

    public LogQueryResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
