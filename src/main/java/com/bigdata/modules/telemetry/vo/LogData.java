package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.service.logdata.model.LogEntry;
import java.util.List;

public record LogData(String dataType, String stream, String resultState,
                      List<LogEntry> entries, int entryCount) {
}
