package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.enums.DataSourceKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标标识推导规则。
 *
 * <p>一份目标端连接要写多张表，topic / 索引 / 路径 / Hive 表名不能只填一个值，
 * 因此统一按「命名模板」逐表推导：</p>
 * <ul>
 *   <li>{@code {table}}：源表名（去掉库名前缀），默认值；</li>
 *   <li>{@code {db}}：源表的库名前缀。</li>
 * </ul>
 *
 * <p>默认模板 {@code {table}}，即 {@code ods.orders → topic/索引 orders}；
 * HDFS/S3 会再拼上目标端配置里的基础路径，Hive 会拼上目标库名。</p>
 *
 * <p>推导结果必须是非空、只含字母/数字/下划线/点/短横线，且同一任务内唯一，
 * 否则保存时直接报错，不把错误留到集群运行时。</p>
 */
public final class TargetNaming {

    /** 目标端配置里可选的命名模板键 */
    public static final String TEMPLATE_KEY = "nameTemplate";
    public static final String DEFAULT_TEMPLATE = "{table}";
    /** HDFS 未配置基础路径时的默认前缀 */
    public static final String DEFAULT_HDFS_BASE = "/user/dataforge";
    /** S3 未配置对象前缀时的默认值 */
    public static final String DEFAULT_S3_PREFIX = "dataforge";

    private TargetNaming() {
    }

    public static String template(Map<String, Object> targetConfig) {
        Object value = targetConfig == null ? null : targetConfig.get(TEMPLATE_KEY);
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? DEFAULT_TEMPLATE : text;
    }

    /** 校验模板本身是否合法：必须包含 {table} 或 {db}，且不能出现未知占位符 */
    public static void checkTemplate(String template, String where) {
        if (template == null || template.trim().isEmpty()) {
            throw new BusinessException(400, where + "的命名模板不能为空，默认可用 {table}");
        }
        if (!template.contains("{table}") && !template.contains("{db}")) {
            throw new BusinessException(400, where + "的命名模板必须包含 {table} 或 {db}，当前为: " + template);
        }
        String rest = template.replace("{table}", "").replace("{db}", "");
        if (rest.contains("{") || rest.contains("}")) {
            throw new BusinessException(400, where + "的命名模板包含不支持的占位符，仅支持 {table} 与 {db}: "
                    + template);
        }
    }

    /** 把模板渲染成单个路径/名称片段，例如 ods.orders → orders */
    public static String render(String template, String sourceTable) {
        TableSpec spec = new TableSpec(sourceTable, List.of(), 0);
        if (template.contains("{db}") && spec.database().isEmpty()) {
            throw new BusinessException(400, "表名「" + sourceTable
                    + "」缺少库名前缀，无法解析命名模板中的 {db}；请填写 库名.表名");
        }
        String rendered = template.replace("{db}", spec.database()).replace("{table}", spec.table());
        return validateSegment(rendered, sourceTable);
    }

    /** 单表的目标标识：Kafka topic / ES 索引 / HDFS 路径 / S3 对象前缀 / Hive 表名 */
    public static String identifier(DataSourceKind kind, Map<String, Object> config, String sourceTable) {
        String rendered = render(template(config), sourceTable);
        switch (kind) {
            case HDFS -> {
                return join(basePath(config, DEFAULT_HDFS_BASE), rendered);
            }
            case S3 -> {
                String bucket = str(config, "bucket");
                String prefix = join(str(config, "path"), DEFAULT_S3_PREFIX);
                String key = join(prefix, rendered);
                return "s3://" + bucket + "/" + key;
            }
            case HIVE -> {
                String database = str(config, "database");
                return (database.isEmpty() ? "default" : database) + "." + rendered;
            }
            default -> {
                return rendered;
            }
        }
    }

    /**
     * 一次性推导任务内全部表的目标标识，并校验唯一性。
     *
     * @return 源表 → 目标标识（保持入参顺序）
     */
    public static Map<String, String> resolveAll(DataSourceKind kind, Map<String, Object> config,
                                                 List<TableSpec> tables) {
        checkTemplate(template(config), "目标端配置");
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (TableSpec table : tables) {
            String identifier = identifier(kind, config, table.getSourceTable());
            String lower = identifier.toLowerCase();
            if (seen.containsKey(lower)) {
                throw new BusinessException(400, "源表「" + table.getSourceTable() + "」与「"
                        + seen.get(lower) + "」推导出的目标标识相同: " + identifier
                        + "；请调整命名模板（如 {db}_{table}）保证唯一");
            }
            seen.put(lower, table.getSourceTable());
            result.put(table.getSourceTable(), identifier);
        }
        return result;
    }

    /** HDFS 基础路径 / S3 前缀：去掉结尾斜杠 */
    public static String basePath(Map<String, Object> config, String fallback) {
        String path = str(config, "path");
        if (path.isEmpty()) {
            return fallback;
        }
        String cleaned = path;
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static String join(String prefix, String suffix) {
        String left = prefix == null ? "" : prefix.trim();
        while (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        return left.isEmpty() ? suffix : left + "/" + suffix;
    }

    private static String validateSegment(String rendered, String sourceTable) {
        if (rendered == null || rendered.trim().isEmpty()) {
            throw new BusinessException(400, "源表「" + sourceTable + "」推导出的目标标识为空，请检查命名模板");
        }
        if (!rendered.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new BusinessException(400, "源表「" + sourceTable + "」推导出的目标标识「" + rendered
                    + "」包含非法字符；只允许字母、数字、下划线、点和短横线，且需以字母或数字开头");
        }
        return rendered;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
