package com.bigdata.common.utils;

import java.util.Locale;

/**
 * 部署环境解析 —— 从「带环境后缀的名称」解出环境码（生产 / 灰度 / 测试）。
 *
 * <h3>规范</h3>
 * <p>命名规范：{@code {分层}_{业务域}_{环境}}，环境后缀仅 {@code _prod / _gray / _test}。
 * 例如库名 {@code dwd_trade_prod} → 环境 prod；工作流名 {@code olist_code_ods_test} → 环境 test
 * （提交调度时环境就拼在工作流名末尾，见 deployEnv 的环境后缀约定）。</p>
 *
 * <h3>为什么解析不出来就返回 null，而不是猜一个</h3>
 * <p>把没有环境后缀的库（如 {@code dw}、{@code dwd_trade}）猜成测试环境，
 * 会让生产库被误判为测试库 —— 在资产概况这类按环境看盘子大小的页面上，
 * 这是会直接影响结论的错误。因此识别不出来就返回 {@code null}，
 * 由调用方自行决定如何呈现。</p>
 *
 * <h3>调用方不要「丢弃」解析不出的名称</h3>
 * <p>返回 {@code null} 只是说「不知道是什么环境」，不等于「这个对象不存在」。
 * 早期调用方把它直接 {@code continue} 掉，结果 {@code test_db} 这类源表库
 * 被整层排除在环境看板之外（几十上百 MB 凭空消失）。
 * 需要按环境看盘子时，应把识别不出的名称归入 {@link #ENV_UNKNOWN} 分组并计入总量，
 * 既保留「不猜测」的安全语义，又不至于漏算。</p>
 *
 * <h3>「按环境过滤」与「不猜测」如何共存</h3>
 * <p>用户显式选定某个环境（如生产）时，含义是「只看生产」——此时识别不出环境的对象
 * <b>不属于</b>生产，必须被排除；这与「不猜测」并不矛盾。统一口径见 {@link #matches}。</p>
 *
 * @author dataforge
 */
public final class DeployEnvParser {

    /** 生产环境 */
    public static final String ENV_PROD = "prod";
    /** 灰度环境 */
    public static final String ENV_GRAY = "gray";
    /** 测试环境 */
    public static final String ENV_TEST = "test";
    /**
     * 无法识别环境时的兜底分组编码。
     *
     * <p>刻意不是 {@code prod/gray/test} 中的任何一个：它表达的是「名称不符合环境命名规范」，
     * 用于让这些对象在看板上可见、可统计，而不是被静默丢弃。</p>
     */
    public static final String ENV_UNKNOWN = "unknown";

    private DeployEnvParser() {
    }

    /**
     * 名称 → 环境码。
     *
     * <p>判定条件：名称含下划线，且最后一段为 {@code prod / gray / test}（大小写不敏感）。</p>
     *
     * @return 环境码；无法识别时为 {@code null}
     */
    public static String parseEnv(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
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

    /**
     * 该名称是否属于指定环境。
     *
     * <p>过滤口径的唯一实现：{@code env} 为空表示「不按环境过滤」（返回 true）；
     * 否则要求名称解析出的环境与之一致 —— 解析不出环境的名称在显式过滤时被排除。</p>
     *
     * @param name 库名 / 工作流名等带环境后缀的名称
     * @param env  目标环境码，null 或空白表示不过滤
     */
    public static boolean matches(String name, String env) {
        if (env == null || env.isBlank()) {
            return true;
        }
        return env.trim().equalsIgnoreCase(parseEnv(name));
    }

    /** 归一化环境入参：非法值一律视为「不过滤」，避免脏参数把结果集清空 */
    public static String normalize(String env) {
        if (env == null || env.isBlank()) {
            return null;
        }
        String value = env.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case ENV_PROD, ENV_GRAY, ENV_TEST -> value;
            default -> null;
        };
    }
}
