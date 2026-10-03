package com.bigdata.modules.asset.service;

import java.util.Locale;

/**
 * 环境解析 —— 从库名解出环境码（生产 / 灰度 / 测试）。
 *
 * <h3>规范</h3>
 * <p>库名规范：{@code {分层}_{业务域}_{环境}}，环境后缀仅 {@code _prod / _gray / _test}。
 * 例如 {@code dwd_trade_prod} → 环境 prod。</p>
 *
 * <h3>为什么解析不出来就返回 null，而不是猜一个</h3>
 * <p>把没有环境后缀的库（如 {@code dw}、{@code dwd_trade}）猜成测试环境，
 * 会让生产库被误判为测试库 —— 在资产概况这类按环境看盘子大小的页面上，
 * 这是会直接影响结论的错误。因此识别不出来就返回 {@code null}，
 * 由调用方把这些库排除在环境维度看板之外。</p>
 *
 * @author dataforge
 */
public final class AssetEnvParser {

    /** 生产环境 */
    public static final String ENV_PROD = "prod";
    /** 灰度环境 */
    public static final String ENV_GRAY = "gray";
    /** 测试环境 */
    public static final String ENV_TEST = "test";

    private AssetEnvParser() {
    }

    /**
     * 库名 → 环境码。
     *
     * <p>判定条件：库名含下划线，且最后一段为 {@code prod / gray / test}（大小写不敏感）。</p>
     *
     * @return 环境码；无法识别时为 {@code null}
     */
    public static String parseEnv(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            return null;
        }
        String normalized = databaseName.trim().toLowerCase(Locale.ROOT);
        int sep = normalized.lastIndexOf('_');
        if (sep < 0 || sep == normalized.length() - 1) {
            return null;
        }
        String suffix = normalized.substring(sep + 1);
        return switch (suffix) {
            case ENV_PROD, ENV_GRAY, ENV_TEST -> suffix;
            default -> null;
        };
    }
}
