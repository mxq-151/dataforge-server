package com.bigdata.modules.telemetry.util;

import com.bigdata.modules.telemetry.service.logdata.model.LogFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bigdata.modules.telemetry.service.logdata.support.LogFilterEngine;

class RegexGuardTest {

    @Test
    @DisplayName("拒绝 RE2 不支持的回溯引用")
    void rejectsBackreference() {
        assertThatThrownBy(() -> RegexGuard.validate("(a)\\1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RE2");
    }

    @Test
    @DisplayName("拒绝 lookahead / lookbehind")
    void rejectsLookaround() {
        assertThatThrownBy(() -> RegexGuard.validate("foo(?=bar)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegexGuard.validate("(?<=foo)bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("嵌套量词允许但保持线性：RE2 不会灾难性回溯")
    void nestedQuantifierIsSafe() {
        LogFilterEngine engine = LogFilterEngine.compile(new LogFilter(
                List.of("(a+)+$"), List.of(), LogFilter.MatchMode.ALL, true, true));

        assertThat(engine.matches("aaaa")).isTrue();
        assertThat(engine.matches("b")).isFalse();
    }

    @Test
    @DisplayName("超长正则被拒绝，避免构造超大自动机")
    void rejectsTooLongPattern() {
        assertThatThrownBy(() -> RegexGuard.validate("a".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
