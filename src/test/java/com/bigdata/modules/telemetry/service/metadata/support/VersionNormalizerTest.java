package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.service.metadata.model.NormalizedVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionNormalizerTest {

    private final VersionNormalizer normalizer = new VersionNormalizer();

    @Test
    @DisplayName("四段版本：前三段进 semver，第四段原样进 build")
    void parsesFourSegments() {
        NormalizedVersion version = normalizer.normalize("24.8.14.39");

        assertThat(version.semver()).isEqualTo("24.8.14");
        assertThat(version.major()).isEqualTo(24);
        assertThat(version.minor()).isEqualTo(8);
        assertThat(version.patch()).isEqualTo(14);
        assertThat(version.build()).isEqualTo("39");
        assertThat(version.prerelease()).isNull();
    }

    @Test
    @DisplayName("Doris 版本：distribution 前缀被忽略，release 进 prerelease，hash 进 build")
    void parsesDorisVersion() {
        NormalizedVersion version = normalizer.normalize("doris-4.1.5-release-917b20be838");

        assertThat(version.semver()).isEqualTo("4.1.5");
        assertThat(version.prerelease()).isEqualTo("release");
        assertThat(version.build()).isEqualTo("917b20be838");
    }

    @Test
    @DisplayName("三段版本没有 build / prerelease")
    void parsesPlainSemver() {
        NormalizedVersion version = normalizer.normalize("3.2.1");

        assertThat(version.semver()).isEqualTo("3.2.1");
        assertThat(version.build()).isNull();
        assertThat(version.prerelease()).isNull();
    }

    @Test
    @DisplayName("MySQL 兼容版本也能解析（但 provider 不得用它冒充 Doris 版本）")
    void parsesMysqlCompatVersion() {
        assertThat(normalizer.normalize("5.7.99").semver()).isEqualTo("5.7.99");
    }

    @Test
    @DisplayName("解析失败返回 null，不猜默认值")
    void returnsNullForUnparseable() {
        assertThat(normalizer.normalize("unknown")).isNull();
        assertThat(normalizer.normalize("v1.2")).isNull();
        assertThat(normalizer.normalize("")).isNull();
        assertThat(normalizer.normalize(null)).isNull();
    }

    @Test
    @DisplayName("带发行版前缀的版本仍按第一处三段数字解析")
    void parsesVersionWithPrefix() {
        assertThat(normalizer.normalize("ClickHouse 24.8.14.39").semver()).isEqualTo("24.8.14");
    }
}
