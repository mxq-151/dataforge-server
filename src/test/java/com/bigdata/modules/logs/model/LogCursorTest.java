package com.bigdata.modules.logs.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("不透明游标编解码")
class LogCursorTest {

    @Test
    @DisplayName("编码后可原样解码，偏移与源保持一致")
    void roundTrip() {
        LogCursor cursor = new LogCursor("ds-default", 1234L, null);
        String encoded = cursor.encode();

        LogCursor decoded = LogCursor.decode(encoded);
        assertEquals("ds-default", decoded.getSourceId());
        assertEquals(1234L, decoded.getOffset());
        assertNull(decoded.getToken());
    }

    @Test
    @DisplayName("空游标返回 null（表示第一页）")
    void blankCursor() {
        assertNull(LogCursor.decode(null));
        assertNull(LogCursor.decode("  "));
    }

    @Test
    @DisplayName("非法游标抛出可读异常")
    void invalidCursor() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LogCursor.decode("not-a-cursor"));
        assertEquals("游标格式非法", error.getMessage());
    }
}
