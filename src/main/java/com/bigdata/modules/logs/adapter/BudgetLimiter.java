package com.bigdata.modules.logs.adapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行数 / 字节预算裁剪。
 *
 * <p>适配器在推进游标之前调用：只返回预算内的行，游标按实际返回的行推进，
 * 因此「截断」不会造成丢行或重复行。单行超预算时按 UTF-8 字符边界截断并标记 truncated。</p>
 */
public final class BudgetLimiter {

    private BudgetLimiter() {
    }

    public static Result limitText(String text, int maxLines, int maxBytes) {
        return limit(splitLines(text), maxLines, maxBytes);
    }

    public static Result limit(List<String> lines, int maxLines, int maxBytes) {
        List<String> kept = new ArrayList<>();
        int bytes = 0;
        boolean truncated = false;
        boolean lastLineTruncated = false;
        if (lines != null) {
            for (String line : lines) {
                if (kept.size() >= Math.max(maxLines, 0)) {
                    truncated = true;
                    break;
                }
                String safe = line == null ? "" : line;
                int separator = kept.isEmpty() ? 0 : 1;
                int lineBytes = utf8Length(safe) + separator;
                if (bytes + lineBytes > maxBytes) {
                    int remain = maxBytes - bytes - separator;
                    if (remain > 0) {
                        String cut = truncateUtf8(safe, remain);
                        if (!cut.isEmpty()) {
                            kept.add(cut);
                            bytes += utf8Length(cut) + separator;
                        }
                    }
                    truncated = true;
                    lastLineTruncated = true;
                    break;
                }
                kept.add(safe);
                bytes += lineBytes;
            }
        }
        return new Result(kept, bytes, truncated, lastLineTruncated);
    }

    public static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = text.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        Collections.addAll(lines, parts);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    public static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String truncateUtf8(String value, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        if (utf8Length(value) <= maxBytes) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) {
                break;
            }
            builder.appendCodePoint(codePoint);
            used += size;
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    /** 裁剪结果 */
    public static final class Result {
        private final List<String> lines;
        private final String content;
        private final int byteSize;
        private final boolean truncated;
        private final boolean lastLineTruncated;

        private Result(List<String> lines, int byteSize, boolean truncated, boolean lastLineTruncated) {
            this.lines = lines;
            this.content = String.join("\n", lines);
            this.byteSize = byteSize;
            this.truncated = truncated;
            this.lastLineTruncated = lastLineTruncated;
        }

        public List<String> getLines() {
            return lines;
        }

        public String getContent() {
            return content;
        }

        public int getByteSize() {
            return byteSize;
        }

        public boolean isTruncated() {
            return truncated;
        }

        /** 最后一行是否被字节预算从中间截断（行游标下不能继续翻页，否则会重复该行） */
        public boolean isLastLineTruncated() {
            return lastLineTruncated;
        }
    }
}
