package com.bigdata.modules.development.service.execution;

import com.bigdata.modules.devops.entity.DatasourceConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据引用的数据源构造环境变量，并在打印时对 passwd 进行掩码处理。
 * <p>
 * 约定环境变量命名规则为 {@code <数据源名称>.url / .user / .passwd}，
 * 例如数据源 test_datasource 会生成：
 * <pre>
 *   test_datasource.url
 *   test_datasource.user
 *   test_datasource.passwd
 * </pre>
 */
public final class DatasourceEnvHelper {

    /** passwd 打印时的掩码 */
    private static final String MASK = "******";

    private DatasourceEnvHelper() {
    }

    /**
     * 根据引用的数据源列表构造环境变量。
     */
    public static Map<String, String> buildEnv(List<DatasourceConfig> datasourceConfigs) {
        Map<String, String> env = new LinkedHashMap<>();
        if (datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            return env;
        }
        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String dsName = dsConfig.getDatasourceName();
            if (dsName == null || dsName.isBlank()) {
                continue;
            }
            if (dsConfig.getUrl() != null) {
                env.put(dsName + ".url", dsConfig.getUrl());
            }
            if (dsConfig.getUsername() != null) {
                env.put(dsName + ".user", dsConfig.getUsername());
            }
            if (dsConfig.getPassword() != null) {
                env.put(dsName + ".passwd", dsConfig.getPassword());
            }
        }
        return env;
    }

    /**
     * 将文本中的密码替换为掩码，用于安全打印（如提交命令、环境变量）。
     */
    public static String maskPasswords(String text, List<DatasourceConfig> datasourceConfigs) {
        if (text == null || datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            return text;
        }
        String result = text;
        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String pwd = dsConfig.getPassword();
            if (pwd != null && !pwd.isBlank()) {
                result = result.replace(pwd, MASK);
            }
        }
        return result;
    }

    /**
     * 构造可供日志打印的环境变量描述（passwd 已掩码）。
     */
    public static String buildEnvDescription(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String value = e.getKey().endsWith(".passwd") ? MASK : e.getValue();
            sb.append(e.getKey()).append("=").append(value);
        }
        sb.append("}");
        return sb.toString();
    }
}