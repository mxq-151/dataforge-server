package com.bigdata.modules.retrieval.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索词切分 —— 把「一句话」拆成可用于匹配的词元。
 *
 * <h3>为什么中文要额外产出二元组</h3>
 * <p>库里没有分词器（也没有向量检索），而业务提问是连续中文：「订单退款口径」。
 * 整串做子串匹配只能命中把这句话原样写进正文的文档，换个语序就失效。
 * 因此在保留整串的同时补一层二元组（订单/单退/退款/款口/口径），
 * 用「命中多少个二元组」近似相关度 —— 这不是分词，但足够把明显相关的段落排到前面。</p>
 *
 * <h3>为什么不做同义词替换</h3>
 * <p>同义词典属于平台参考数据（知识库术语表），不能硬编码在这一层：
 * 写死在代码里的同义词一定会与业务实际说法脱节，且改一次要发版。
 * 本层只负责「切」，扩展留给后续的术语表接入。</p>
 *
 * @author dataforge
 */
public final class QueryTokens {

    /** 单次检索最多使用多少个词元（防止长问句把扫描放大成 N 倍） */
    private static final int MAX_TOKENS = 12;

    /** 单字符词元信息量太低，不参与打分 */
    private static final int MIN_TOKEN_LENGTH = 2;

    private QueryTokens() {
    }

    public static List<String> of(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : split(query)) {
            addPart(part, tokens);
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        List<String> result = new ArrayList<>(tokens);
        return new ArrayList<>(result.subList(0, Math.min(result.size(), MAX_TOKENS)));
    }

    /** 按「非字母数字汉字」切段 */
    private static List<String> split(String query) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (isWordChar(ch)) {
                current.append(ch);
            } else if (current.length() > 0) {
                parts.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static void addPart(String raw, Set<String> tokens) {
        if (raw == null) {
            return;
        }
        String part = raw.toLowerCase(Locale.ROOT);
        if (part.length() < MIN_TOKEN_LENGTH) {
            return;
        }
        if (containsCjk(part)) {
            tokens.add(part);
            for (int i = 0; i + 1 < part.length(); i++) {
                String bigram = part.substring(i, i + 2);
                // 下划线是词边界，不应进入二元组（否则会产出「单_」「_d」这类噪声）
                if (bigram.indexOf('_') >= 0) {
                    continue;
                }
                if (isCjk(bigram.charAt(0)) || isCjk(bigram.charAt(1))) {
                    tokens.add(bigram);
                }
                if (tokens.size() >= MAX_TOKENS) {
                    return;
                }
            }
            // 中英混写时把英文段也补进来（如「订单_order」）
            addSegments(part, tokens);
            return;
        }
        tokens.add(part);
        addSegments(part, tokens);
    }

    /** 英文表名常用下划线分段：dwd_trade_order_detail_d 额外补 trade/order/detail */
    private static void addSegments(String part, Set<String> tokens) {
        if (part.indexOf('_') < 0) {
            return;
        }
        for (String segment : part.split("_")) {
            if (segment.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(segment);
            }
            if (tokens.size() >= MAX_TOKENS) {
                return;
            }
        }
    }

    private static boolean isWordChar(char ch) {
        // 下划线视为词内字符：表名是检索的主要入口，先保留整串，再在 addPart 里拆段
        return Character.isLetterOrDigit(ch) || ch == '_' || isCjk(ch);
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isCjk(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF);
    }
}
