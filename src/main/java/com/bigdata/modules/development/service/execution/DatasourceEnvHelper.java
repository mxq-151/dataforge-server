package com.bigdata.modules.development.service.execution;

import com.bigdata.common.utils.AesUtils;
import com.bigdata.modules.devops.entity.DatasourceConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据引用的数据源构造环境变量，并在打印时对 passwd 进行掩码处理。
 * <p>
 * 约定环境变量命名规则为 {@code <数据源名称大写>_URL / _USER / _PASSWD}，
 * 例如数据源 test_datasource 会生成：
 * <pre>
 *   TEST_DATASOURCE_URL
 *   TEST_DATASOURCE_USER
 *   TEST_DATASOURCE_PASSWD
 * </pre>
 */
public final class DatasourceEnvHelper {

    /** passwd 打印时的掩码 */
    private static final String MASK = "******";

    /** 环境变量后缀 */
    private static final String SUFFIX_URL = "_URL";
    private static final String SUFFIX_USER = "_USER";
    private static final String SUFFIX_PASSWD = "_PASSWD";

    private DatasourceEnvHelper() {
    }

    /**
     * 将数据源名称规范化为合法的环境变量前缀（大写，非字母数字下划线统一替换为下划线）。
     */
    private static String toEnvPrefix(String dsName) {
        String upper = dsName.toUpperCase();
        StringBuilder sb = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            sb.append((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    /**
     * 根据引用的数据源列表构造环境变量。
     * <p>
     * 数据库中保存的密码为密文，此处会通过 {@link AesUtils#decryptStored} 还原为明文，
     * 供作业程序直接使用；若 {@code aesUtils} 为 null（如单元测试）则原样保留。
     */
    public static Map<String, String> buildEnv(List<DatasourceConfig> datasourceConfigs, AesUtils aesUtils) {
        Map<String, String> env = new LinkedHashMap<>();
        if (datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            return env;
        }
        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String dsName = dsConfig.getDatasourceName();
            if (dsName == null || dsName.isBlank()) {
                continue;
            }
            String prefix = toEnvPrefix(dsName);
            if (dsConfig.getUrl() != null) {
                env.put(prefix + SUFFIX_URL, dsConfig.getUrl());
            }
            if (dsConfig.getUsername() != null) {
                env.put(prefix + SUFFIX_USER, dsConfig.getUsername());
            }
            if (dsConfig.getPassword() != null) {
                env.put(prefix + SUFFIX_PASSWD, dsConfig.getPlainPassword(aesUtils));
            }
        }
        return env;
    }

    /**
     * 将文本中的密码替换为掩码，用于安全打印（如提交命令、环境变量）。
     * 需与 {@link #buildEnv} 使用相同的解密逻辑，才能匹配命令中的明文密码。
     */
    public static String maskPasswords(String text, List<DatasourceConfig> datasourceConfigs, AesUtils aesUtils) {
        if (text == null || datasourceConfigs == null || datasourceConfigs.isEmpty()) {
            return text;
        }
        String result = text;
        for (DatasourceConfig dsConfig : datasourceConfigs) {
            String pwd = dsConfig.getPassword();
            if (pwd != null && !pwd.isBlank()) {
                result = result.replace(dsConfig.getPlainPassword(aesUtils), MASK);
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
            String value = e.getKey().endsWith(SUFFIX_PASSWD) ? MASK : e.getValue();
            sb.append(e.getKey()).append("=").append(value);
        }
        sb.append("}");
        return sb.toString();
    }
}