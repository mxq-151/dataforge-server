package com.bigdata.modules.telemetry.vo;

import com.bigdata.modules.telemetry.common.model.BackendWarning;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import java.util.List;

/**
 * 引擎异常摘要。异常是结构化证据，不拍平成日志行。
 *
 * <p>{@code empty=true} 表示「查询成功且服务端异常历史为空」，与取不到（outcome != ok）严格区分。
 * 触达任一上限时 {@code truncated=true} 且 {@code truncatedCode=RESULT_TRUNCATED}。</p>
 */
public record ExceptionData(String jobId, String resultState, boolean empty,
                            List<ExceptionEntryView> exceptions, int exceptionCount,
                            int concurrentExceptionCount, boolean truncated,
                            CapabilityCode truncatedCode, boolean serverTruncated,
                            int requestedMaxExceptions,
                            List<BackendWarning> warnings) {
}
