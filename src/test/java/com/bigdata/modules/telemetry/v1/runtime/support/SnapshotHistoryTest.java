package com.bigdata.modules.telemetry.v1.runtime.support;

import com.bigdata.modules.telemetry.v1.runtime.config.RuntimeV1Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotHistoryTest {

    @Test
    @DisplayName("容量按配置生效，超出后丢弃最旧样本")
    void keepsOnlyConfiguredCapacity() {
        RuntimeV1Properties properties = new RuntimeV1Properties();
        properties.setHistoryCapacity(3);
        SnapshotHistory history = new SnapshotHistory(properties);

        for (int i = 1; i <= 5; i++) {
            history.record("k", new SnapshotHistory.Entry("j1", i, Map.of()));
        }

        assertThat(history.capacity()).isEqualTo(3);
        assertThat(history.latest("k")).get()
                .extracting(SnapshotHistory.Entry::capturedAt).isEqualTo(5L);
        assertThat(history.entriesWithPrefix("k")).hasSize(3);
    }

    @Test
    @DisplayName("默认容量 10")
    void defaultCapacityIsTen() {
        SnapshotHistory history = new SnapshotHistory(new RuntimeV1Properties());
        assertThat(history.capacity()).isEqualTo(10);
    }
}
