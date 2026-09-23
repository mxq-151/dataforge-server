package com.bigdata.modules.asset.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表名命名规范解析器 —— 从物理表名解出「分层 / 业务域 / 刷新周期」。
 *
 * <h3>规范</h3>
 * <pre>{@code {分层}_{业务域}_{实体描述}_{刷新周期}}</pre>
 * <p>例如 {@code dwd_order_detail_d} → 分层 dwd、业务域 order、实体描述 detail、刷新周期 d（日）。</p>
 *
 * <h3>为什么解析而不是人工维护分类</h3>
 * <p>人工维护的分类一定会腐化：新表上线时没人记得去改分类表，于是「未分类」越积越多，
 * 目录的规范视角就慢慢失去意义。按表名解析则是「零人工成本 + 上线即可见」，
 * 解析失败的资产反而自动成为一条明确的治理项（见 {@code unclassified}）。</p>
 *
 * <p>解析失败不入库、不落状态，是纯函数 —— 每次读取时实时算。
 * 这样当规范本身调整（例如新增一个分层）时，历史资产会立刻按新规范重新归类，
 * 不需要做数据回刷。</p>
 *
 * <h3>分层来源</h3>
 * <p>分层集合与提示词「分层规范」以及 asset_category 的层种子保持一致：
 * ods / dwd / dws / ads / dim / dqc / tmp。刻意不在代码里写死成枚举的 value()，
 * 而是接收调用方传入的分层集合 —— 分层属于平台参考数据，允许运维在 asset_category 里增补，
 * 解析器需要能跟着走。</p>
 */
public final class TableNameConventionParser {

    /** 规范分层：与提示词「分层规范」及 asset_category 分层种子一致 */
    public static final Set<String> DEFAULT_LAYERS = Set.of(
            "ods", "dwd", "dws", "ads", "dim", "dqc", "tmp");

    /**
     * 分层解析失败时，目录树与盘点里用来承接这些表的占位分层编码。
     *
     * <p>刻意不是一个真实分层（前缀 {@code __} 保证不会与运维在
     * asset_category 里新增的分层编码撞名）：它只用于分组展示，
     * 永远不落库、不写进 asset_category，从而不会把「解析失败」固化成一种分类。</p>
     */
    public static final String UNCLASSIFIED_LAYER = "__unclassified";

    /**
     * 刷新周期后缀的合法取值（单字符）：
     * d=日 h=时 w=周 m=月 y=年 r=实时 f=全量/不定期。
     */
    private static final Set<String> REFRESH_CYCLES = Set.of("d", "h", "w", "m", "y", "r", "f");

    /** 业务域允许的字符集：字母数字，避免把「detail」这类实体描述误当业务域 */
    private static final Pattern DOMAIN = Pattern.compile("^[a-z][a-z0-9]{1,31}$");

    /** 分段允许的字符集（小写字母、数字、连字符） */
    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private TableNameConventionParser() {
    }

    /**
     * 解析结果。
     *
     * @param layerCode      分层编码；解析不出来时为 {@code null}
     * @param domainCode     业务域编码；解析不出来时为 {@code null}
     * @param entityDesc     实体描述（分层与周期之间的部分，多段以下划线还原）
     * @param refreshCycle   刷新周期后缀；无合法后缀时为 {@code null}
     * @param parsed         是否符合完整规范（分层 + 业务域 + 实体描述 + 刷新周期 四要素齐备）
     */
    public record Parsed(
            String layerCode,
            String domainCode,
            String entityDesc,
            String refreshCycle,
            boolean parsed) {

        /** 解析失败时的空结果 */
        public static Parsed unparsed(String refreshCycle) {
            return new Parsed(null, null, null, refreshCycle, false);
        }
    }

    /** 用默认分层集合解析 */
    public static Parsed parse(String tableName) {
        return parse(tableName, DEFAULT_LAYERS);
    }

    /**
     * 按给定分层集合解析表名。
     *
     * <p>判定为「合规」的条件（四要素齐备）：</p>
     * <ol>
     *   <li>第一段是已知分层；</li>
     *   <li>最后一段是单字符刷新周期；</li>
     *   <li>倒数第二段到第一段之间至少还有两段（业务域 + 实体描述）；</li>
     *   <li>业务域段匹配业务域字符集。</li>
     * </ol>
     * <p>任一条不满足、但第一段仍是已知分层时，仍返回分层编码（部分解析）——
     * 这样目录树能把它放进正确的分层下，同时标为「未分类」提请治理。</p>
     */
    public static Parsed parse(String tableName, Set<String> layers) {
        if (tableName == null || tableName.isBlank()) {
            return Parsed.unparsed(null);
        }
        Set<String> effectiveLayers = layers == null || layers.isEmpty() ? DEFAULT_LAYERS : layers;
        String normalized = tableName.trim().toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("_");

        // 刷新周期：末段是合法的单字符周期标记
        String refreshCycle = null;
        int bodyEnd = parts.length;
        if (parts.length >= 2 && REFRESH_CYCLES.contains(parts[parts.length - 1])) {
            refreshCycle = parts[parts.length - 1];
            bodyEnd = parts.length - 1;
        }

        String layerCode = effectiveLayers.contains(parts[0]) ? parts[0] : null;
        String domainCode = null;
        String entityDesc = null;

        if (bodyEnd >= 3) {
            String candidate = parts[1];
            if (DOMAIN.matcher(candidate).matches()) {
                domainCode = candidate;
                entityDesc = String.join("_", java.util.Arrays.asList(parts).subList(2, bodyEnd));
            } else {
                entityDesc = String.join("_", java.util.Arrays.asList(parts).subList(1, bodyEnd));
            }
        } else if (bodyEnd == 2) {
            // 只有「分层_某段」：无法区分业务域与实体描述，视为解析失败但保留分层
            entityDesc = parts[1];
        }

        boolean parsed = layerCode != null
                && domainCode != null
                && entityDesc != null && !entityDesc.isBlank()
                && refreshCycle != null;
        return new Parsed(layerCode, domainCode, entityDesc, refreshCycle, parsed);
    }

    /** 分段是否合法（供元数据同步时提示命名问题） */
    public static boolean isLegalSegment(String segment) {
        return segment != null && SEGMENT.matcher(segment).matches();
    }

    /** 刷新周期编码 → 中文描述 */
    public static String refreshCycleLabel(String cycle) {
        if (cycle == null) {
            return "";
        }
        return switch (cycle.toLowerCase(Locale.ROOT)) {
            case "d" -> "日";
            case "h" -> "小时";
            case "w" -> "周";
            case "m" -> "月";
            case "y" -> "年";
            case "r" -> "实时";
            case "f" -> "全量";
            default -> cycle;
        };
    }

    /**
     * 分层编码 → 中文显示名。
     *
     * <p>集中在这里而不是散在目录树、盘点、分类列表三处：同一份分层在三个界面
     * 必须显示成同一个名字，否则用户会以为它们是不同的东西。
     * 未登记的分层编码原样返回（分层属于平台参考数据，允许运维在 asset_category 里增补）。</p>
     */
    public static String layerLabel(String layerCode) {
        if (layerCode == null || layerCode.isBlank()) {
            return "";
        }
        if (UNCLASSIFIED_LAYER.equals(layerCode)) {
            return "未分类";
        }
        return switch (layerCode.toLowerCase(Locale.ROOT)) {
            case "ods" -> "贴源层";
            case "dwd" -> "明细层";
            case "dws" -> "汇总层";
            case "ads" -> "应用层";
            case "dim" -> "维度层";
            case "dqc" -> "质检层";
            case "tmp" -> "临时层";
            default -> layerCode;
        };
    }
}
