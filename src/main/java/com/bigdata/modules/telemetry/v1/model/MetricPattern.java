package com.bigdata.modules.telemetry.v1.model;

import java.util.regex.Pattern;

/**
 * 指标语义模式：目录不再存物理全名，只用族/前缀/后缀/正则绑定类型、单位与 rate 适用性。
 *
 * <p>发现结果必须命中模式后才能套用类型；匹配不到时按「类型未知」处理，不猜测。</p>
 */
public record MetricPattern(Kind kind, String expression) {

    public enum Kind {
        EXACT,
        PREFIX,
        SUFFIX,
        REGEX
    }

    private static final int EXACT_SPECIFICITY = 4;
    private static final int REGEX_SPECIFICITY = 3;
    private static final int FAMILY_SPECIFICITY = 2;

    public MetricPattern {
        if (kind == null || expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("指标模式必须包含 kind 与非空 expression");
        }
    }

    public static MetricPattern exact(String name) {
        return new MetricPattern(Kind.EXACT, name);
    }

    public static MetricPattern prefix(String prefix) {
        return new MetricPattern(Kind.PREFIX, prefix);
    }

    public static MetricPattern suffix(String suffix) {
        return new MetricPattern(Kind.SUFFIX, suffix);
    }

    public static MetricPattern regex(String regex) {
        return new MetricPattern(Kind.REGEX, regex);
    }

    public boolean matches(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return false;
        }
        return switch (kind) {
            case EXACT -> metricName.equals(expression);
            case PREFIX -> metricName.startsWith(expression);
            case SUFFIX -> metricName.endsWith(expression);
            case REGEX -> Pattern.compile(expression).matcher(metricName).matches();
        };
    }

    /** 越具体的模式优先；EXACT > REGEX > PREFIX/SUFFIX。 */
    public int specificity() {
        return switch (kind) {
            case EXACT -> EXACT_SPECIFICITY;
            case REGEX -> REGEX_SPECIFICITY;
            case PREFIX, SUFFIX -> FAMILY_SPECIFICITY;
        };
    }

    public String wire() {
        return kind.name().toLowerCase() + ":" + expression;
    }
}
