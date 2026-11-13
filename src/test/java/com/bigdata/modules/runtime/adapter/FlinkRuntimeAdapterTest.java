package com.bigdata.modules.runtime;

import com.bigdata.modules.logs.adapter.HttpLogClient;
import com.bigdata.modules.logs.adapter.HttpTextResponse;
import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.adapter.FlinkRuntimeAdapter;
import com.bigdata.modules.runtime.adapter.RuntimeAdapterException;
import com.bigdata.modules.runtime.adapter.RuntimeQueryContext;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Flink 运行时适配器：TM 指标合并、checkpoint、通配符、404")
class FlinkRuntimeAdapterTest {

    @Test
    @DisplayName("多 TaskManager：GC 按 collector 求和、单位毫秒、作业级吞吐与重启")
    void mergesTaskManagerMetrics() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1/checkpoints", 200, checkpointsJson());
        http.on("/jobs/j1/metrics", 200, """
                [{"id":"numRecordsIn","value":"1000"},
                 {"id":"numRecordsOut","value":"900"},
                 {"id":"numRecordsInPerSecond","value":"10.5"},
                 {"id":"numRestarts","value":"2"},
                 {"id":"lastCheckpointSize","value":"2048"},
                 {"id":"numberOfCompletedCheckpoints","value":"7"},
                 {"id":"numberOfFailedCheckpoints","value":"1"}]
                """);
        http.on("/jobs/j1", 200, jobJson());
        http.on("/taskmanagers/tm-1/metrics", 200, tmMetrics("104857600", "120", "3"));
        http.on("/taskmanagers/tm-2/metrics", 200, tmMetrics("209715200", "80", "2"));
        http.on("/taskmanagers", 200, taskManagersJson());

        FlinkRuntimeAdapter adapter = RuntimeTestSupport.flink(http);
        RuntimeQueryContext context = RuntimeTestSupport.context(source(), "j1",
                MetricFamily.JVM_GC, MetricFamily.THROUGHPUT, MetricFamily.CHECKPOINT,
                MetricFamily.FAILURES);
        context.setIncludeVertices(false);

        RuntimeSnapshotVO snapshot = adapter.snapshot(context);

        assertEquals("RUNNING", snapshot.getState());
        assertEquals(2, snapshot.getComponents().size());
        RuntimeComponentVO tm1 = snapshot.getComponents().get(0);
        assertEquals("tm-1", tm1.getId());
        assertEquals("host1", tm1.getHost());
        assertEquals(4, tm1.getSlots());
        assertEquals(4, tm1.getCores());
        assertEquals(120D, metric(tm1.getMetrics(), "jvm.gc.time").getValue());
        assertEquals("ms", metric(tm1.getMetrics(), "jvm.gc.time").getUnit());
        assertEquals(104857600D, metric(tm1.getMetrics(), "jvm.heap.used").getValue());
        assertEquals("bytes", metric(tm1.getMetrics(), "jvm.heap.used").getUnit());
        assertEquals(42D, metric(tm1.getMetrics(), "jvm.threads").getValue());
        assertTrue(metric(tm1.getMetrics(), "jvm.gc.time").getSourceMetric().contains("*"));

        assertEquals(1000D, metric(snapshot.getJobMetrics(), "records.in.total").getValue());
        assertEquals("records", metric(snapshot.getJobMetrics(), "records.in.total").getUnit());
        assertEquals(10.5D, metric(snapshot.getJobMetrics(), "records.in.rate").getValue());
        assertEquals(2D, metric(snapshot.getJobMetrics(), "restarts").getValue());
        assertEquals(2048D, metric(snapshot.getJobMetrics(), "checkpoint.last.size").getValue());

        assertTrue(snapshot.getCheckpoint().isAvailable());
        assertEquals(7, snapshot.getCheckpoint().getCompleted());
        assertEquals(1, snapshot.getCheckpoint().getFailed());
        assertEquals("timeout", snapshot.getCheckpoint().getLatestFailedReason());
        assertEquals(2, snapshot.getCheckpoint().getHistory().size());
        assertEquals(8L, snapshot.getCheckpoint().getHistory().get(0).getId());
        assertEquals("timeout", snapshot.getCheckpoint().getHistory().get(1).getFailureMessage());
        assertEquals(1D, metric(snapshot.getJobMetrics(), "tasks.failed").getValue());
    }

    @Test
    @DisplayName("值为 0 与取不到必须区分：0 标记 ZERO，缺失标记 UNAVAILABLE")
    void zeroVersusUnavailable() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1", 200, jobJson());
        http.on("/taskmanagers/tm-1/metrics", 200, """
                [{"id":"Status.JVM.Memory.Heap.Used","value":"0"}]
                """);
        http.on("/taskmanagers", 200, taskManagersJson());

        FlinkRuntimeAdapter adapter = RuntimeTestSupport.flink(http);
        RuntimeQueryContext context = RuntimeTestSupport.context(source(), "j1", MetricFamily.JVM_GC);
        context.setIncludeVertices(false);

        RuntimeSnapshotVO snapshot = adapter.snapshot(context);
        RuntimeComponentVO tm = snapshot.getComponents().get(0);

        assertEquals(RuntimeMetricVO.ZERO, metric(tm.getMetrics(), "jvm.heap.used").getAvailability());
        assertEquals(0D, metric(tm.getMetrics(), "jvm.heap.used").getValue());

        RuntimeMetricVO nonHeap = metric(tm.getMetrics(), "jvm.nonheap.used");
        assertEquals(RuntimeMetricVO.UNAVAILABLE, nonHeap.getAvailability());
        assertEquals(null, nonHeap.getValue());
        assertNotNull(nonHeap.getReason());

        // metrics 端点返回空数组：通配符不被该版本支持时，GC 指标要给出明确原因而不是 0
        FakeHttp empty = new FakeHttp();
        empty.on("/jobs/j1", 200, jobJson());
        empty.on("/taskmanagers/tm-1/metrics", 200, "[]");
        empty.on("/taskmanagers", 200, taskManagersJson());
        RuntimeSnapshotVO emptySnapshot = RuntimeTestSupport.flink(empty).snapshot(context);
        RuntimeMetricVO gc = metric(emptySnapshot.getComponents().get(0).getMetrics(), "jvm.gc.time");
        assertEquals(RuntimeMetricVO.UNAVAILABLE, gc.getAvailability());
        assertTrue(gc.getReason().contains("通配符"));
    }

    @Test
    @DisplayName("vertex 合并：多 subtask 记录数求和，背压取最大值")
    void aggregatesVertexMetrics() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/j1/vertices/v1/subtasks/metrics", 200, """
                [{"id":"0.numRecordsIn","value":"10"},
                 {"id":"1.numRecordsIn","value":"15"},
                 {"id":"0.numRecordsOut","value":"10"},
                 {"id":"0.backPressuredTimeMsPerSecond","value":"100"}]
                """);
        http.on("/jobs/j1/vertices/v2/subtasks/metrics", 200, """
                [{"id":"0.numRecordsIn","value":"5"},
                 {"id":"0.numRecordsOut","value":"5"},
                 {"id":"0.backPressuredTimeMsPerSecond","value":"300"}]
                """);
        http.on("/jobs/j1/metrics", 200, "[]");
        http.on("/jobs/j1", 200, jobJson());

        FlinkRuntimeAdapter adapter = RuntimeTestSupport.flink(http);
        RuntimeQueryContext context = RuntimeTestSupport.context(source(), "j1",
                MetricFamily.THROUGHPUT, MetricFamily.BACKPRESSURE, MetricFamily.FAILURES);
        context.setIncludeComponents(false);
        RuntimeSnapshotVO snapshot = adapter.snapshot(context);

        List<RuntimeComponentVO> vertices = snapshot.getComponents().stream()
                .filter(component -> "vertex".equals(component.getType()))
                .toList();
        assertEquals(2, vertices.size());
        assertEquals(25D, metric(vertices.get(0).getMetrics(), "records.in.total").getValue());
        assertEquals(5D, metric(vertices.get(1).getMetrics(), "records.in.total").getValue());
        assertEquals(100D, metric(vertices.get(0).getMetrics(), "backpressure.backpressured").getValue());
        assertEquals(300D, metric(vertices.get(1).getMetrics(), "backpressure.backpressured").getValue());
        assertEquals(1D, metric(vertices.get(1).getMetrics(), "vertex.tasks.failed").getValue());
    }

    @Test
    @DisplayName("作业不存在：404 转 NOT_FOUND，消息包含作业 id")
    void jobNotFound() {
        FakeHttp http = new FakeHttp();
        http.on("/jobs/missing", 404, "{\"errors\":[\"Job not found\"]}");

        RuntimeQueryContext context = RuntimeTestSupport.context(source(), "missing",
                MetricFamily.JVM_GC);
        RuntimeAdapterException error = assertThrows(RuntimeAdapterException.class,
                () -> RuntimeTestSupport.flink(http).snapshot(context));

        assertEquals(RuntimeAdapterException.Code.NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("JobManager 不可达：UNAVAILABLE")
    void unreachable() {
        HttpLogClient failing = (url, headers, timeout) -> {
            throw new IOException("Connection refused");
        };
        RuntimeAdapterException error = assertThrows(RuntimeAdapterException.class,
                () -> RuntimeTestSupport.flink(failing).snapshot(
                        RuntimeTestSupport.context(source(), "j1", MetricFamily.JVM_GC)));

        assertEquals(RuntimeAdapterException.Code.UNAVAILABLE, error.getCode());
        assertTrue(error.getMessage().contains("不可达"));
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

    private RuntimeSource source() {
        RuntimeProperties.Source config = RuntimeTestSupport.sourceConfig(
                "flink-default", "flink", "live", "http://jm:8081");
        return RuntimeTestSupport.source(config, ServiceType.FLINK, RuntimeMode.LIVE);
    }

    private String jobJson() {
        return """
                {"jid":"j1","name":"demo","state":"RUNNING","start-time":1700000000000,
                 "end-time":-1,"duration":60000,
                 "tasks":{"total":4,"failed":1},
                 "vertices":[
                   {"id":"v1","name":"Source","status":"RUNNING","tasks":{"failed":0}},
                   {"id":"v2","name":"Sink","status":"RUNNING","tasks":{"failed":1}}
                 ]}
                """;
    }

    private String taskManagersJson() {
        return """
                {"taskmanagers":[
                  {"id":"tm-1","path":"akka.tcp://flink@host1:1234/user/taskmanager",
                   "timeSinceLastHeartbeat":1000,"slotsNumber":4,"freeSlots":2,
                   "totalResource":{"cpuCores":4,"memory":1073741824,"managedMemory":536870912},
                   "hardware":{"physicalMemory":2147483648}},
                  {"id":"tm-2","path":"akka.tcp://flink@host2:1234/user/taskmanager",
                   "timeSinceLastHeartbeat":2000,"slotsNumber":4,"freeSlots":4,
                   "totalResource":{"cpuCores":4,"memory":1073741824,"managedMemory":536870912},
                   "hardware":{"physicalMemory":2147483648}}
                ]}
                """;
    }

    private String tmMetrics(String heapUsed, String gcTime, String gcCount) {
        return "["
                + "{\"id\":\"Status.JVM.Memory.Heap.Used\",\"value\":\"" + heapUsed + "\"},"
                + "{\"id\":\"Status.JVM.Memory.Heap.Max\",\"value\":\"536870912\"},"
                + "{\"id\":\"Status.JVM.GarbageCollector.G1 Young Generation.Time\",\"value\":\"" + gcTime + "\"},"
                + "{\"id\":\"Status.JVM.GarbageCollector.G1 Young Generation.Count\",\"value\":\"" + gcCount + "\"},"
                + "{\"id\":\"Status.JVM.Threads.Count\",\"value\":\"42\"}"
                + "]";
    }

    private String checkpointsJson() {
        return """
                {"counts":{"restored":1,"total":8,"in_progress":0,"completed":7,"failed":1},
                 "latest":{
                   "completed":{"id":8,"status":"COMPLETED","trigger_timestamp":1700000040000,
                                "latest_ack_timestamp":1700000040045,"state_size":2048,"end_to_end_duration":45},
                   "failed":{"id":7,"status":"FAILED","trigger_timestamp":1700000030000,
                             "latest_ack_timestamp":1700000030010,"end_to_end_duration":10,
                             "failure_message":"timeout"}},
                 "history":[
                   {"id":8,"status":"COMPLETED","trigger_timestamp":1700000040000,
                    "latest_ack_timestamp":1700000040045,"state_size":2048,"end_to_end_duration":45},
                   {"id":7,"status":"FAILED","trigger_timestamp":1700000030000,
                    "latest_ack_timestamp":1700000030010,"end_to_end_duration":10,
                    "failure_message":"timeout"}
                 ]}
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
