package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.logs.adapter.HttpLogClient;
import com.bigdata.modules.logs.adapter.HttpTextResponse;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.RuntimeTestSupport;
import com.bigdata.modules.runtime.config.RuntimeProperties;
import com.bigdata.modules.runtime.model.MetricFamily;
import com.bigdata.modules.runtime.model.RuntimeMode;
import com.bigdata.modules.runtime.model.RuntimeSource;
import com.bigdata.modules.runtime.vo.RuntimeComponentVO;
import com.bigdata.modules.runtime.vo.RuntimeMetricVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Spark 运行时适配器：SHS/live、executors/allexecutors、stage 指标")
class SparkRuntimeAdapterTest {

    @Test
    @DisplayName("History Server：allexecutors 含已退出 executor，stage 只保留最新 attempt")
    void historyUsesAllExecutors() {
        FakeHttp http = new FakeHttp();
        http.on("/api/v1/applications/app-1/allexecutors", 200, executorsJson());
        http.on("/api/v1/applications/app-1/stages", 200, stagesJson());
        http.on("/api/v1/applications/app-1/jobs", 200, jobsJson());
        http.on("/api/v1/applications/app-1/environment", 200, environmentJson());
        http.on("/api/v1/applications/app-1", 200, appJson());

        SparkRuntimeAdapter adapter = RuntimeTestSupport.spark(http);
        RuntimeQueryContext context = RuntimeTestSupport.context(source(RuntimeMode.HISTORY), "app-1",
                MetricFamily.JVM_GC, MetricFamily.THROUGHPUT, MetricFamily.SHUFFLE,
                MetricFamily.FAILURES, MetricFamily.CHECKPOINT);
        RuntimeSnapshotVO snapshot = adapter.snapshot(context);

        assertEquals("FINISHED", snapshot.getState());
        assertEquals("spark-demo", snapshot.getJobName());

        List<RuntimeComponentVO> executors = snapshot.getComponents().stream()
                .filter(component -> "executor".equals(component.getType())).toList();
        assertEquals(2, executors.size());
        RuntimeComponentVO driver = executors.get(0);
        assertEquals("driver", driver.getId());
        assertEquals("ACTIVE", driver.getStatus());
        assertEquals(5000D, metric(driver.getMetrics(), "executor.gc.time").getValue());
        assertEquals("ms", metric(driver.getMetrics(), "executor.gc.time").getUnit());
        assertEquals(0.5D, metric(driver.getMetrics(), "executor.gc.ratio").getValue());
        assertEquals("ratio", metric(driver.getMetrics(), "executor.gc.ratio").getUnit());
        assertEquals(2048D, metric(driver.getMetrics(), "executor.input.bytes").getValue());
        assertEquals("bytes", metric(driver.getMetrics(), "executor.input.bytes").getUnit());

        RuntimeComponentVO dead = executors.get(1);
        assertEquals("REMOVED", dead.getStatus());
        assertEquals("Container released", dead.getNote());
        assertEquals(2D, metric(dead.getMetrics(), "executor.tasks.failed").getValue());

        List<RuntimeComponentVO> stages = snapshot.getComponents().stream()
                .filter(component -> "stage".equals(component.getType())).toList();
        assertEquals(2, stages.size(), "stage 0 的两个 attempt 应合并为一条");
        RuntimeComponentVO retried = stages.get(0);
        assertEquals("stage-0-1", retried.getId());
        assertEquals(2D, metric(retried.getMetrics(), "stage.attempt.count").getValue());
        assertEquals(300D, metric(retried.getMetrics(), "stage.shuffle.read.bytes").getValue());
        assertEquals("bytes", metric(retried.getMetrics(), "stage.shuffle.read.bytes").getUnit());
        assertEquals(1D, metric(retried.getMetrics(), "stage.tasks.failed").getValue());
        assertEquals("ms", metric(retried.getMetrics(), "stage.shuffle.fetch.wait").getUnit());

        assertEquals(2D, metric(snapshot.getJobMetrics(), "job.tasks.failed").getValue());
        assertEquals(1D, metric(snapshot.getJobMetrics(), "job.failedStages").getValue());
        assertEquals(1D, metric(snapshot.getJobMetrics(), "application.attempt.count").getValue());

        assertFalse(snapshot.getCheckpoint().isAvailable());
        assertTrue(snapshot.getCheckpoint().getReason().contains("不提供 checkpoint"));
        assertTrue(snapshot.getUnavailableFamilies().containsKey("checkpoint"));
    }

    @Test
    @DisplayName("live UI：/executors 为主，/allexecutors 404 不影响结果")
    void liveUsesExecutors() {
        FakeHttp http = new FakeHttp();
        http.on("/api/v1/applications/app-2/executors", 200, """
                [{"id":"1","hostPort":"h2:2","isActive":true,"maxMemory":1073741824,
                  "memoryUsed":1048576,"totalCores":4,"activeTasks":2,"failedTasks":0,
                  "totalDuration":20000,"totalGCTime":1000,"totalInputBytes":4096,
                  "addTime":1700000000000}]
                """);
        http.on("/api/v1/applications/app-2", 200, appJson());

        SparkRuntimeAdapter adapter = RuntimeTestSupport.spark(http);
        RuntimeQueryContext context = RuntimeTestSupport.context(source(RuntimeMode.LIVE), "app-2",
                MetricFamily.JVM_GC);
        context.setIncludeVertices(false);
        RuntimeSnapshotVO snapshot = adapter.snapshot(context);

        assertEquals(1, snapshot.getComponents().size());
        assertEquals("ACTIVE", snapshot.getComponents().get(0).getStatus());
        assertEquals(1000D, metric(snapshot.getComponents().get(0).getMetrics(), "executor.gc.time")
                .getValue());
    }

    @Test
    @DisplayName("History Server 不可达：UNAVAILABLE 而不是空结果")
    void shsUnreachable() {
        HttpLogClient failing = (url, headers, timeout) -> {
            throw new IOException("Connection timed out");
        };
        RuntimeAdapterException error = assertThrows(RuntimeAdapterException.class,
                () -> RuntimeTestSupport.spark(failing).snapshot(
                        RuntimeTestSupport.context(source(RuntimeMode.HISTORY), "app-1",
                                MetricFamily.JVM_GC)));
        assertEquals(RuntimeAdapterException.Code.UNAVAILABLE, error.getCode());
        assertTrue(error.getMessage().contains("不可达"));
    }

    @Test
    @DisplayName("应用不存在：404 转 NOT_FOUND")
    void appNotFound() {
        FakeHttp http = new FakeHttp();
        http.on("/api/v1/applications/missing", 404, "{}");

        RuntimeAdapterException error = assertThrows(RuntimeAdapterException.class,
                () -> RuntimeTestSupport.spark(http).snapshot(
                        RuntimeTestSupport.context(source(RuntimeMode.HISTORY), "missing",
                                MetricFamily.JVM_GC)));
        assertEquals(RuntimeAdapterException.Code.NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains("missing"));
    }

    private RuntimeMetricVO metric(List<RuntimeMetricVO> metrics, String name) {
        for (RuntimeMetricVO metric : metrics) {
            if (name.equals(metric.getName())) {
                return metric;
            }
        }
        throw new AssertionError("未找到指标 " + name + "，实际: "
                + metrics.stream().map(RuntimeMetricVO::getName).toList());
    }

    private RuntimeSource source(RuntimeMode mode) {
        RuntimeProperties.Source config = RuntimeTestSupport.sourceConfig(
                mode == RuntimeMode.HISTORY ? "spark-shs" : "spark-live",
                "spark", mode.wireName(), "http://spark-ui:18080");
        return RuntimeTestSupport.source(config, ServiceType.SPARK, mode);
    }

    private String appJson() {
        return """
                {"id":"app-1","name":"spark-demo",
                 "attempts":[{"attemptId":1,"startTime":1700000000000,"endTime":1700000060000,
                              "duration":60000,"sparkUser":"u","completed":true}]}
                """;
    }

    private String executorsJson() {
        return """
                [
                  {"id":"driver","hostPort":"h1:1","isActive":true,"maxMemory":1073741824,
                   "memoryUsed":524288000,"totalCores":2,"activeTasks":1,"failedTasks":0,
                   "completedTasks":10,"totalTasks":11,"totalDuration":10000,"totalGCTime":5000,
                   "totalInputBytes":2048,"addTime":1700000000000},
                  {"id":"2","hostPort":"h2:2","isActive":false,"maxMemory":1073741824,
                   "memoryUsed":0,"totalCores":4,"activeTasks":0,"failedTasks":2,
                   "completedTasks":8,"totalTasks":10,"totalDuration":8000,"totalGCTime":2000,
                   "totalInputBytes":4096,"addTime":1700000000000,"removeTime":1700000050000,
                   "removeReason":"Container released"}
                ]
                """;
    }

    private String stagesJson() {
        return """
                [
                  {"stageId":0,"attemptId":0,"name":"stage0","status":"COMPLETE","numTasks":2,
                   "numFailedTasks":0,"numKilledTasks":0,"inputRecords":100,"outputRecords":90,
                   "inputBytes":1000,"outputBytes":900,"shuffleReadRecords":10,"shuffleReadBytes":100,
                   "shuffleWriteRecords":20,"shuffleWriteBytes":200,"shuffleFetchWaitTime":5,
                   "shuffleWriteTime":6,"submissionTime":1700000000000,"completionTime":1700000010000},
                  {"stageId":0,"attemptId":1,"name":"stage0","status":"COMPLETE","numTasks":2,
                   "numFailedTasks":1,"numKilledTasks":0,"inputRecords":300,"outputRecords":290,
                   "inputBytes":3000,"outputBytes":2900,"shuffleReadRecords":30,"shuffleReadBytes":300,
                   "shuffleWriteRecords":40,"shuffleWriteBytes":400,"shuffleFetchWaitTime":7,
                   "shuffleWriteTime":8,"failureReason":"retry",
                   "submissionTime":1700000020000,"completionTime":1700000030000},
                  {"stageId":1,"attemptId":0,"name":"stage1","status":"FAILED","numTasks":1,
                   "numFailedTasks":1,"numKilledTasks":0,"inputRecords":5,"outputRecords":0,
                   "inputBytes":50,"outputBytes":0,"shuffleReadRecords":0,"shuffleReadBytes":0,
                   "shuffleWriteRecords":0,"shuffleWriteBytes":0,"failureReason":"boom",
                   "submissionTime":1700000040000,"completionTime":1700000045000}
                ]
                """;
    }

    private String jobsJson() {
        return """
                [{"jobId":0,"numFailedTasks":2,"numKilledTasks":1,"numFailedStages":1}]
                """;
    }

    private String environmentJson() {
        return """
                {"sparkProperties":{"spark.executor.memory":"2g",
                                    "spark.hadoop.fs.s3a.secret.key":"abc"},
                 "hadoopProperties":{},"systemProperties":{"user.name":"u"},
                 "classpathEntries":[["a.jar","b.jar"]]}
                """;
    }

    private static final class FakeHttp implements HttpLogClient {
        private final Map<String, HttpTextResponse> responses = new HashMap<>();

        private void on(String suffix, int status, String body) {
            responses.put(suffix, new HttpTextResponse(status, body, "application/json"));
        }

        @Override
        public HttpTextResponse get(String url, Map<String, String> headers, Duration timeout) {
            String matched = null;
            for (String suffix : responses.keySet()) {
                if (url.contains(suffix) && (matched == null || suffix.length() > matched.length())) {
                    matched = suffix;
                }
            }
            return matched == null ? new HttpTextResponse(404, "{}", "application/json")
                    : responses.get(matched);
        }
    }
}
