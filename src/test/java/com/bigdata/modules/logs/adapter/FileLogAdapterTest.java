package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("file 适配器：根目录约束与按字节游标读取")
class FileLogAdapterTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("读到文件末尾时保留最后一行（不因缺少换行被截掉）")
    void readsLastLineWithoutTrailingNewline() throws Exception {
        Files.writeString(root.resolve("app.log"), "l1\nl2", StandardCharsets.UTF_8);
        FileLogAdapter adapter = new FileLogAdapter();

        AdapterResult result = adapter.query(context("app.log", 2000, 262144, 0));

        assertEquals("l1\nl2", result.getContent());
        assertEquals(2, result.getLineCount());
        assertFalse(result.isHasMore());
        assertFalse(result.isTruncated());
    }

    @Test
    @DisplayName("按字节游标翻页：第二页从行边界继续，不重复上一页内容")
    void paginatesByByteOffset() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add("line-" + i + "-xxxxxxxxxx");
        }
        Files.writeString(root.resolve("big.log"), String.join("\n", lines), StandardCharsets.UTF_8);
        FileLogAdapter adapter = new FileLogAdapter();

        AdapterResult first = adapter.query(context("big.log", 2000, 120, 0));
        assertTrue(first.isHasMore());
        assertTrue(first.getNextOffset() > 0);

        AdapterResult second = adapter.query(context("big.log", 2000, 120, first.getNextOffset()));
        assertFalse(second.getContent().startsWith("line-0-"));
        assertFalse(first.getContent().lines().toList().equals(second.getContent().lines().toList()));
    }

    @Test
    @DisplayName("拒绝 root-dirs 之外的绝对路径")
    void rejectsPathOutsideRoot() throws Exception {
        Path outside = Files.createTempFile("outside", ".log");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        FileLogAdapter adapter = new FileLogAdapter();

        LogAdapterException error = assertThrows(LogAdapterException.class,
                () -> adapter.query(context(outside.toString(), 2000, 262144, 0)));
        assertEquals(LogAdapterException.Code.BAD_REQUEST, error.getCode());
        assertTrue(error.getMessage().contains("root-dirs"));
    }

    private LogQueryContext context(String path, int maxLines, int maxBytes, long offset) {
        LogAccessProperties.Source config = LogTestSupport.sourceConfig("local-file", "file", "");
        config.getOptions().getRootDirs().add(root.toString());
        LogSource source = LogTestSupport.source(config, ServiceType.FILE);
        LogQueryContext context = LogTestSupport.context(source, maxLines, maxBytes, Map.of("path", path));
        context.setOffset(offset);
        return context;
    }
}
