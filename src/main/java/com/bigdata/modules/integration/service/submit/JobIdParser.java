package com.bigdata.modules.integration.service.submit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从命令行输出里解析作业 ID 与状态。
 *
 * <p>不同版本 / 不同参数下 Flink 的输出样式不一致，这里覆盖常见的几种：
 * <ul>
 *   <li>{@code Job has been submitted with JobID <32位hex>}</li>
 *   <li>{@code Job ID: <id>}、Web UI 链接 {@code #/job/<id>/overview}</li>
 * </ul>
 * 解析不到时返回 null，由调用方决定「提交成功但未拿到 jobId」的处理方式。</p>
 */
public final class JobIdParser {

    private static final Pattern FLINK_SUBMITTED =
            Pattern.compile("submitted with JobID\\s+([0-9a-fA-F]{32})");
    private static final Pattern FLINK_JOB_ID_LABEL =
            Pattern.compile("Job\\s*ID\\s*[:=]\\s*([0-9a-fA-F]{32})");
    private static final Pattern FLINK_WEB_URL =
            Pattern.compile("#/job/([0-9a-fA-F]{32})");
    private static final Pattern FLINK_BARE_HEX =
            Pattern.compile("\\b([0-9a-fA-F]{32})\\b");

    private JobIdParser() {
    }

    public static String parseFlink(String output) {
        return firstMatch(output, FLINK_SUBMITTED, FLINK_JOB_ID_LABEL, FLINK_WEB_URL, FLINK_BARE_HEX);
    }

    /**
     * 从 {@code flink list} 输出里找指定作业的状态。
     * 典型行：{@code : 8a1b... : RUNNING : job-name}
     */
    public static String findFlinkState(String output, String jobId) {
        if (output == null || jobId == null || jobId.isBlank()) {
            return null;
        }
        Pattern line = Pattern.compile(":\\s*" + Pattern.quote(jobId) + "\\s*:\\s*([A-Z_]+)\\s*:");
        Matcher matcher = line.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return output.contains(jobId) ? "UNKNOWN" : null;
    }

    /** 从任意输出里提取形如 {@code status: RUNNING} / {@code state=RUNNING} 的状态词 */
    public static String findGenericState(String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)(?:status|state)\\s*[:=]\\s*([A-Za-z_]+)").matcher(output);
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    private static String firstMatch(String output, Pattern... patterns) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }
}
