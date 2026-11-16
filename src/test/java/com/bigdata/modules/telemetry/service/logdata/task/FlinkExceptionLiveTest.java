package com.bigdata.modules.telemetry.service.logdata.task;

import com.bigdata.modules.telemetry.common.model.Target;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.ExceptionQuerySpec;

/**
 * Flink 异常摘要的真实联调（默认跳过）。
 *
 * <p>运行方式：{@code mvn test -Dtest=FlinkExceptionLiveTest -Dflink.live=true}。
 * 需要 127.0.0.1:8081 上存在 Flink 2.2 standalone JobManager，且保留以下作业：
 * <ul>
 *   <li>{@code a4f1a1a028310ef81f8f912eff920595}：运行中的 TopSpeedWindowing，异常历史为空；</li>
 *   <li>{@code ec5eeeb5a7eea421765989feb5cbcfff}：SocketWindowWordCount 失败作业，提供真实异常样本。</li>
 * </ul>
 *
 * <p>Flink 2.2 没有单条异常子资源，本测试只覆盖列表接口。失败样本被 JobManager 清理后，
 * 第二个用例会以 Assumption 跳过而不是伪造数据。</p>
 */
@EnabledIfSystemProperty(named = "flink.live", matches = "true")
class FlinkExceptionLiveTest {

    private static final String RUNNING_JOB = "a4f1a1a028310ef81f8f912eff920595";
    private static final String FAILED_JOB = "ec5eeeb5a7eea421765989feb5cbcfff";
    private static final Target TARGET = new Target("flink", "flink.jobmanager", "cluster");

    @Test
    @DisplayName("运行中作业：真实空异常历史")
    void runningJobHasNoExceptions() {
        ExceptionQueryResult result = backend().fetch(
                new ExceptionQuerySpec(TARGET, RUNNING_JOB, null, null, null, null));

        System.out.println("[flink-live] " + RUNNING_JOB + " empty=" + result.isEmpty()
                + " truncated=" + result.truncated() + " warnings=" + result.warnings());
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("失败作业：真实异常条目与根因")
    void failedJobExposesStructuredExceptions() {
        ExceptionQueryResult result;
        try {
            result = backend().fetch(
                    new ExceptionQuerySpec(TARGET, FAILED_JOB, null, null, null, null));
        } catch (com.bigdata.modules.telemetry.common.exception.BackendException e) {
            Assumptions.assumeTrue(false, "失败样本作业已不在 JobManager 中: " + e.getCode()
                    + " / " + e.getMessage());
            return;
        }
        result.exceptions().forEach(entry -> System.out.println("[flink-live] "
                + entry.exceptionName() + " rootCause=" + entry.rootCause()
                + " taskName=" + entry.taskName() + " timestamp=" + entry.timestamp()
                + " concurrent=" + entry.concurrentExceptions().size()));

        assertThat(result.isEmpty()).isFalse();
        ExceptionQueryResult.ExceptionRecord root = result.exceptions().get(0);
        assertThat(root.exceptionName()).isNotBlank();
        assertThat(root.timestamp()).isNotNull();
        assertThat(root.rootCause()).contains("ConnectException");
        assertThat(root.concurrentExceptions()).isNotEmpty();
        assertThat(root.concurrentExceptions().get(0).taskName()).isNotBlank();
    }

    private FlinkRestExceptionBackend backend() {
        FlinkRestConfig config = new FlinkRestConfig();
        config.setId("flink-rest");
        config.setBaseUrl("http://127.0.0.1:8081");
        return new FlinkRestExceptionBackend("flink-rest", config, null, new ObjectMapper());
    }
}
