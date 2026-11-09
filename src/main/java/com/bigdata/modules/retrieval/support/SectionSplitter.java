package com.bigdata.modules.retrieval.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 章节切分 —— 知识片段检索的基础。
 *
 * <h3>为什么按标题切而不是定长滑窗</h3>
 * <p>定长滑窗会把一句话切成两半，返回的片段读起来没头没尾，模型还得回头找上下文。
 * 知识库文档本身是按「一节一个主题」写的（见《知识管理》的编写规范），
 * 按标题切出来的每一节天然是一个语义单元，也天然带有「这一节讲什么」的标题。</p>
 *
 * <p>文档没有标题时整篇作为一节（标题记为「正文」）。</p>
 *
 * @author dataforge
 */
public final class SectionSplitter {

    /** 无标题文档的占位标题 */
    public static final String DEFAULT_TITLE = "正文";

    private SectionSplitter() {
    }

    /**
     * 章节。
     *
     * @param index 章节序号，从 0 开始
     * @param title 标题文本（不含 # 号）
     * @param body  正文（不含标题行）
     * @param level 标题层级 1~6；无标题文档为 0
     */
    public record Section(int index, String title, String body, int level) {
    }

    public static List<Section> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<Section> sections = new ArrayList<>();
        String[] lines = content.split("\\R", -1);

        String currentTitle = DEFAULT_TITLE;
        int currentLevel = 0;
        StringBuilder currentBody = new StringBuilder();

        for (String line : lines) {
            int level = headingLevel(line);
            if (level > 0) {
                // 新标题：结算上一节。文档以标题开头时，前面那个「正文」占位是空的，直接丢弃。
                flush(sections, currentTitle, currentBody.toString(), currentLevel);
                currentTitle = stripHeading(line);
                currentLevel = level;
                currentBody.setLength(0);
            } else {
                currentBody.append(line).append('\n');
            }
        }
        flush(sections, currentTitle, currentBody.toString(), currentLevel);
        return sections;
    }

    /** 空占位节（无标题文档前的空正文）不入列表；索引按列表长度连续分配 */
    private static void flush(List<Section> sections, String title, String body, int level) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty() && DEFAULT_TITLE.equals(title)) {
            return;
        }
        sections.add(new Section(sections.size(), title, trimmed, level));
    }

    /** 标题层级；非标题行返回 0（允许最多 3 个前导空格，与 CommonMark 一致） */
    private static int headingLevel(String line) {
        if (line == null) {
            return 0;
        }
        int spaces = 0;
        while (spaces < line.length() && spaces < 4 && line.charAt(spaces) == ' ') {
            spaces++;
        }
        if (spaces > 3) {
            return 0;
        }
        int level = 0;
        while (spaces + level < line.length() && line.charAt(spaces + level) == '#') {
            level++;
        }
        if (level < 1 || level > 6) {
            return 0;
        }
        int after = spaces + level;
        if (after >= line.length() || line.charAt(after) != ' ') {
            return 0;
        }
        return level;
    }

    private static String stripHeading(String line) {
        String trimmed = line.trim();
        int start = 0;
        while (start < trimmed.length() && trimmed.charAt(start) == '#') {
            start++;
        }
        return trimmed.substring(start).trim();
    }
}
