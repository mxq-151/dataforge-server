package com.bigdata.modules.quality.service;

import com.bigdata.modules.quality.enums.QualityCheckStatus;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 质检模块的批次与状态换算辅助。
 *
 * <p>批次号（{@code batchNo}）在全模块用两种形态出现：契约里的字符串（如 {@code 20260917}）
 * 与分区表达式里的 {@link LocalDate}。换算只在这里做一次，避免各处各写一套解析，
 * 出现「大盘按 A 口径、运行记录按 B 口径」的错位。</p>
 */
public final class DqBatchSupport {

    /** 契约约定的批次号形态：yyyyMMdd */
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 兼容的备选形态：yyyy-MM-dd */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private DqBatchSupport() {
    }

    /**
     * 解析批次号。
     *
     * <p>无法识别时回落到今天：批次号缺失只影响分区表达式取哪一天的数据，
     * 让整个校验动作失败代价过大；同时批次号本身会原样落库，
     * 事后能从运行记录看出这一期到底用的是哪个批次。</p>
     */
    public static LocalDate parseBatchDate(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return LocalDate.now();
        }
        String trimmed = batchNo.trim();
        try {
            return LocalDate.parse(trimmed, BATCH_NO);
        } catch (DateTimeParseException ignored) {
            // 继续尝试 yyyy-MM-dd
        }
        try {
            return LocalDate.parse(trimmed, ISO_DATE);
        } catch (DateTimeParseException ignored) {
            return LocalDate.now();
        }
    }

    /** 生成今天的批次号（yyyyMMdd），供手动触发与结果上报兜底 */
    public static String todayBatchNo() {
        return LocalDate.now().format(BATCH_NO);
    }

    /** 校验对象所在表标识：{@code 库.表}，用于 TOP 问题表统计 */
    public static String tableKey(String databaseName, String tableName) {
        if (databaseName == null || databaseName.isBlank()) {
            return tableName == null ? "" : tableName;
        }
        return databaseName + "." + tableName;
    }

    /**
     * 任务级状态判定。
     *
     * <p>规则：只要有「校验失败（SQL 没跑通）」就是 FAILED —— 这类问题必须有人看，
     * 不能因为其它规则通过就整体算成功。全部跑通但有红色异常时，
     * 仅在处置方式为「阻断下游」时判 FAILED，否则算 SUCCESS（异常已由计数体现）。</p>
     */
    public static String resolveRunStatus(boolean anyFailed, boolean anyError, boolean blockOnError) {
        if (anyFailed) {
            return "FAILED";
        }
        if (anyError && blockOnError) {
            return "FAILED";
        }
        return "SUCCESS";
    }

    /** 统计某个状态在结果集合里出现的次数 */
    public static int count(List<QualityCheckStatus> statuses, QualityCheckStatus target) {
        if (statuses == null) {
            return 0;
        }
        return (int) statuses.stream().filter(s -> s == target).count();
    }
}
